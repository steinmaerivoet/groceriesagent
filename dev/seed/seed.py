#!/usr/bin/env python3
"""Seed a local Mealie instance with the Grocery Orchestrator dev dataset.

Standard library only, so it runs in a bare python:3.12 container or on the host.

    python3 dev/seed/seed.py            # seed (idempotent: existing items are skipped)
    python3 dev/seed/seed.py --check    # validate the dataset offline, no Mealie needed
    python3 dev/seed/seed.py --update   # also overwrite recipes that already exist

Environment: MEALIE_URL (default http://localhost:9925), MEALIE_ADMIN_EMAIL, MEALIE_ADMIN_PASSWORD.
"""

from __future__ import annotations

import argparse
import datetime
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass
from fractions import Fraction
from pathlib import Path

HERE = Path(__file__).resolve().parent
DATA = HERE / "data"
EXTRAS_NAMESPACE = "groceries"  # spec §9: all integration metadata lives under one key
SHOPPING_LIST_NAME = "Groceries"
API_TOKEN_NAME = "grocery-core-dev"


# --------------------------------------------------------------------------- dataset


@dataclass
class Ingredient:
    quantity: float | None
    unit: str | None
    food: str
    note: str
    original: str


class Catalog:
    def __init__(self, raw: dict):
        self.labels: dict[str, dict] = raw["labels"]
        self.units: list[dict] = raw["units"]
        self.categories: list[str] = raw["categories"]
        self.tags: list[str] = raw["tags"]
        self.foods: list[dict] = []
        for label, group in raw["foods"].items():
            if label not in self.labels:
                raise ValueError(f"foods group '{label}' is not a declared label")
            for entry in group:
                food = {"name": entry} if isinstance(entry, str) else dict(entry)
                food["label"] = label
                self.foods.append(food)

        self.unit_lookup: dict[str, str] = {}
        for u in self.units:
            for alias in [u["name"], u["abbreviation"], u.get("pluralName"), *u.get("aliases", [])]:
                if alias:
                    self.unit_lookup[alias.lower()] = u["name"]
        self.food_lookup: dict[str, str] = {}
        for f in self.foods:
            for alias in [f["name"], f.get("plural")]:
                if alias:
                    key = alias.lower()
                    if key in self.food_lookup and self.food_lookup[key] != f["name"]:
                        raise ValueError(f"food alias '{alias}' is ambiguous")
                    self.food_lookup[key] = f["name"]

    _QTY = re.compile(r"^(\d+\s+\d+/\d+|\d+/\d+|\d+(?:\.\d+)?)\s+(.*)$")

    def parse_ingredient(self, text: str) -> Ingredient:
        """Grammar: `[quantity] [unit] food[, note]`, e.g. `2 cloves garlic, crushed`."""
        body, _, note = text.partition(",")
        body = body.strip()
        quantity = None
        m = self._QTY.match(body)
        if m:
            quantity = float(sum(Fraction(p) for p in m.group(1).split()))
            body = m.group(2)
        unit = None
        first, _, rest = body.partition(" ")
        if rest and first.lower() in self.unit_lookup:
            unit, body = self.unit_lookup[first.lower()], rest
        food = self.food_lookup.get(body.lower())
        if food is None:
            raise ValueError(f"unknown food '{body}' in ingredient '{text}'")
        return Ingredient(quantity, unit, food, note.strip(), text)


def load_dataset() -> tuple[Catalog, list[dict]]:
    catalog = Catalog(json.loads((DATA / "catalog.json").read_text()))
    recipes = []
    for path in sorted((DATA / "recipes").glob("*.json")):
        recipes.extend(json.loads(path.read_text()))
    return catalog, recipes


def load_rules() -> list[dict]:
    return json.loads((DATA / "planner-rules.json").read_text())["rules"]


def load_history() -> list[dict]:
    return json.loads((DATA / "meal-plan-history.json").read_text())["entries"]


_PLACEHOLDER = re.compile(r"\{(tag|category):([^}]+)\}")
DAYS = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]


def validate(catalog: Catalog, recipes: list[dict], rules: list[dict], history: list[dict]) -> list[str]:
    errors = []
    for i, rule in enumerate(rules, 1):
        for kind, name in _PLACEHOLDER.findall(rule["filter"]):
            known = catalog.tags if kind == "tag" else catalog.categories
            if name not in known:
                errors.append(f"planner rule {i}: unknown {kind} '{name}'")
        if rule["day"] not in [*DAYS, "unset"]:
            errors.append(f"planner rule {i}: unknown day '{rule['day']}'")
    recipe_names = {r.get("name") for r in recipes}
    for entry in history:
        if entry["recipe"] not in recipe_names:
            errors.append(f"meal-plan history: unknown recipe '{entry['recipe']}'")
        if entry["day"] not in DAYS:
            errors.append(f"meal-plan history: unknown day '{entry['day']}'")
    names = set()
    for r in recipes:
        where = r.get("name", "<unnamed>")
        if where in names:
            errors.append(f"{where}: duplicate recipe name")
        names.add(where)
        for key in ("name", "description", "servings", "prepMinutes", "cookMinutes", "categories", "tags", "ingredients", "steps"):
            if key not in r:
                errors.append(f"{where}: missing '{key}'")
        for c in r.get("categories", []):
            if c not in catalog.categories:
                errors.append(f"{where}: unknown category '{c}'")
        for t in r.get("tags", []):
            if t not in catalog.tags:
                errors.append(f"{where}: unknown tag '{t}'")
        for line in r.get("ingredients", []):
            try:
                catalog.parse_ingredient(line)
            except ValueError as e:
                errors.append(f"{where}: {e}")
    return errors


# --------------------------------------------------------------------------- Mealie client


class Mealie:
    def __init__(self, base_url: str):
        self.base = base_url.rstrip("/")
        self.token: str | None = None

    def request(self, method: str, path: str, body=None, form: dict | None = None):
        headers = {"Accept": "application/json"}
        data = None
        if form is not None:
            data = urllib.parse.urlencode(form).encode()
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        elif body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        req = urllib.request.Request(self.base + path, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                raw = resp.read()
        except urllib.error.HTTPError as e:
            raise RuntimeError(f"{method} {path} -> {e.code}: {e.read().decode(errors='replace')[:500]}") from None
        return json.loads(raw) if raw else None

    def get(self, path):
        return self.request("GET", path)

    def post(self, path, body=None):
        return self.request("POST", path, body)

    def put(self, path, body):
        return self.request("PUT", path, body)

    def wait_until_up(self, timeout_s: int = 180):
        deadline = time.time() + timeout_s
        while True:
            try:
                return self.get("/api/app/about")
            except Exception:
                if time.time() > deadline:
                    raise SystemExit(f"Mealie not reachable at {self.base} after {timeout_s}s")
                time.sleep(2)

    def login(self, email: str, password: str):
        self.token = self.request("POST", "/api/auth/token", form={"username": email, "password": password})["access_token"]

    def all(self, path: str) -> list[dict]:
        sep = "&" if "?" in path else "?"
        return self.get(f"{path}{sep}perPage=-1")["items"]


def ensure_by_name(api: Mealie, path: str, wanted: list[dict], kind: str) -> dict[str, dict]:
    """Create every item in `wanted` whose name doesn't exist yet. Returns name -> Mealie object."""
    existing = {item["name"]: item for item in api.all(path)}
    created = 0
    for body in wanted:
        if body["name"] not in existing:
            existing[body["name"]] = api.post(path, body)
            created += 1
    print(f"  {kind:<12} {created:>3} created, {len(wanted) - created:>3} already present")
    return existing


# --------------------------------------------------------------------------- seeding


def food_extras(food: dict) -> dict:
    """Only a food's own overrides go into Mealie. Label defaults and policy resolution live in
    Grocery Core (spec §4.2.2), so they are not copied onto every food here."""
    overrides = {}
    if "policy" in food:
        overrides["purchasePolicy"] = food["policy"]
    if "stockUpAllowed" in food:
        overrides["stockUpAllowed"] = food["stockUpAllowed"]
    return {EXTRAS_NAMESPACE: json.dumps(overrides)} if overrides else {}


def seed_catalog(api: Mealie, catalog: Catalog):
    labels = ensure_by_name(
        api, "/api/groups/labels",
        [{"name": name, "color": spec["color"]} for name, spec in catalog.labels.items()],
        "labels",
    )
    units = ensure_by_name(
        api, "/api/units",
        [
            {
                "name": u["name"],
                "pluralName": u.get("pluralName"),
                "abbreviation": u["abbreviation"],
                "useAbbreviation": u.get("useAbbreviation", True),
                "fraction": u.get("fraction", False),
                "aliases": [{"name": a} for a in u.get("aliases", [])],
            }
            for u in catalog.units
        ],
        "units",
    )
    foods = ensure_by_name(
        api, "/api/foods",
        [
            {
                "name": f["name"],
                "pluralName": f.get("plural"),
                "labelId": labels[f["label"]]["id"],
                "extras": food_extras(f),
            }
            for f in catalog.foods
        ],
        "foods",
    )
    categories = ensure_by_name(api, "/api/organizers/categories", [{"name": c} for c in catalog.categories], "categories")
    tags = ensure_by_name(api, "/api/organizers/tags", [{"name": t} for t in catalog.tags], "tags")
    return units, foods, categories, tags


def iso_minutes(minutes: int) -> str:
    h, m = divmod(minutes, 60)
    return f"{h} hour{'s' if h > 1 else ''} {m} minutes".replace(" 0 minutes", "") if h else f"{m} minutes"


def build_recipe(current: dict, r: dict, catalog: Catalog, units, foods, categories, tags) -> dict:
    def ref(obj):
        return {"id": obj["id"], "name": obj["name"], "slug": obj["slug"]}

    ingredients = []
    for line in r["ingredients"]:
        ing = catalog.parse_ingredient(line)
        ingredients.append({
            "quantity": ing.quantity or 0,
            "unit": units[ing.unit] if ing.unit else None,
            "food": foods[ing.food],
            "note": ing.note,
            "originalText": ing.original,
            "referenceId": str(uuid.uuid4()),
        })
    body = dict(current)
    body.update({
        "description": r["description"],
        "recipeServings": r["servings"],
        "recipeYieldQuantity": 0,
        "recipeYield": "",
        "prepTime": iso_minutes(r["prepMinutes"]),
        "cookTime": iso_minutes(r["cookMinutes"]),
        "performTime": None,
        "totalTime": iso_minutes(r["prepMinutes"] + r["cookMinutes"]),
        "recipeCategory": [ref(categories[c]) for c in r["categories"]],
        "tags": [ref(tags[t]) for t in r["tags"]],
        "recipeIngredient": ingredients,
        "recipeInstructions": [{"id": str(uuid.uuid4()), "title": "", "summary": "", "text": s, "ingredientReferences": []} for s in r["steps"]],
        "extras": {EXTRAS_NAMESPACE: json.dumps({"seed": True})},
    })
    return body


def seed_recipes(api: Mealie, catalog: Catalog, recipes: list[dict], refs, update: bool, user_id: str):
    units, foods, categories, tags = refs
    existing = {r["name"]: r["slug"] for r in api.all("/api/recipes")}
    created = updated = skipped = 0
    for i, r in enumerate(recipes, 1):
        slug = existing.get(r["name"])
        if slug and not update:
            skipped += 1
            continue
        if not slug:
            slug = api.post("/api/recipes", {"name": r["name"]})
            created += 1
        else:
            updated += 1
        current = api.get(f"/api/recipes/{slug}")
        api.put(f"/api/recipes/{slug}", build_recipe(current, r, catalog, units, foods, categories, tags))
        if r.get("rating"):
            api.post(f"/api/users/{user_id}/ratings/{slug}", {"rating": r["rating"], "isFavorite": r["rating"] >= 5})
        print(f"\r  recipes      {i:>3}/{len(recipes)}", end="", flush=True)
    print(f"\r  recipes      {created:>3} created, {updated:>3} updated, {skipped:>3} already present")


def seed_planner_rules(api: Mealie, rules: list[dict], categories, tags):
    def to_id(m: re.Match) -> str:
        kind, name = m.groups()
        return (tags if kind == "tag" else categories)[name]["id"]

    existing = {(r["day"], r["entryType"], r["queryFilterString"]) for r in api.all("/api/households/mealplans/rules")}
    created = 0
    for rule in rules:
        body = {"day": rule["day"], "entryType": rule["entryType"], "queryFilterString": _PLACEHOLDER.sub(to_id, rule["filter"])}
        if (body["day"], body["entryType"], body["queryFilterString"]) not in existing:
            api.post("/api/households/mealplans/rules", body)
            created += 1
    print(f"  {'rules':<12} {created:>3} created, {len(rules) - created:>3} already present")


def seed_meal_plan_history(api: Mealie, history: list[dict]):
    today = datetime.date.today()
    this_monday = today - datetime.timedelta(days=today.weekday())
    entries = [
        (this_monday - datetime.timedelta(weeks=e["weeksAgo"]) + datetime.timedelta(days=DAYS.index(e["day"])), e["recipe"])
        for e in history
    ]
    start, end = min(d for d, _ in entries), max(d for d, _ in entries)
    taken = {
        p["date"]
        for p in api.all(f"/api/households/mealplans?start_date={start}&end_date={end}")
        if p["entryType"] == "dinner"
    }
    recipe_ids = {r["name"]: r["id"] for r in api.all("/api/recipes")}
    created = 0
    for date, recipe in entries:
        if date.isoformat() not in taken:
            api.post("/api/households/mealplans", {"date": date.isoformat(), "entryType": "dinner", "recipeId": recipe_ids[recipe]})
            created += 1
    print(f"  {'meal plans':<12} {created:>3} created, {len(entries) - created:>3} already present")


def ensure_shopping_list(api: Mealie):
    lists = api.all("/api/households/shopping/lists")
    if not any(l["name"] == SHOPPING_LIST_NAME for l in lists):
        api.post("/api/households/shopping/lists", {"name": SHOPPING_LIST_NAME})
        print(f"  shopping list '{SHOPPING_LIST_NAME}' created")


def ensure_api_token(api: Mealie, env_path: Path):
    """Create a long-lived API token and store it in .env, unless one is already configured."""
    if not env_path.exists():
        print(f"  (no {env_path.name} found, skipping API token)")
        return
    text = env_path.read_text()
    m = re.search(r"^MEALIE_API_TOKEN=(.*)$", text, re.M)
    if m and m.group(1).strip():
        probe = Mealie(api.base)
        probe.token = m.group(1).strip()
        try:
            probe.get("/api/users/self")
            print("  API token    already present in .env and valid")
            return
        except RuntimeError:
            print("  API token    in .env is no longer valid (database reset?), replacing it")
    token = api.post("/api/users/api-tokens", {"name": f"{API_TOKEN_NAME}-{int(time.time())}"})["token"]
    line = f"MEALIE_API_TOKEN={token}"
    text = re.sub(r"^MEALIE_API_TOKEN=.*$", line, text, flags=re.M) if m else text.rstrip("\n") + f"\n{line}\n"
    env_path.write_text(text)
    print("  API token    created and written to .env (MEALIE_API_TOKEN)")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--check", action="store_true", help="validate the dataset and exit")
    ap.add_argument("--update", action="store_true", help="overwrite recipes that already exist")
    ap.add_argument("--env-file", default=os.environ.get("ENV_FILE", ".env"), help="where to store the API token")
    args = ap.parse_args()

    catalog, recipes = load_dataset()
    rules, history = load_rules(), load_history()
    errors = validate(catalog, recipes, rules, history)
    if errors:
        print("Dataset is invalid:", *errors, sep="\n  ")
        sys.exit(1)
    print(f"Dataset OK: {len(recipes)} recipes, {len(catalog.foods)} foods, {len(catalog.units)} units")
    if args.check:
        return

    api = Mealie(os.environ.get("MEALIE_URL", "http://localhost:9925"))
    about = api.wait_until_up()
    print(f"Seeding Mealie {about['version']} at {api.base}")
    api.login(os.environ.get("MEALIE_ADMIN_EMAIL", "changeme@example.com"), os.environ.get("MEALIE_ADMIN_PASSWORD", "MyPassword"))
    user = api.get("/api/users/self")

    refs = seed_catalog(api, catalog)
    seed_recipes(api, catalog, recipes, refs, args.update, user["id"])
    seed_planner_rules(api, rules, categories=refs[2], tags=refs[3])
    seed_meal_plan_history(api, history)
    ensure_shopping_list(api)
    ensure_api_token(api, Path(args.env_file))
    print("Done.")


if __name__ == "__main__":
    main()
