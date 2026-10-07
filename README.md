# Grocery Orchestrator

A self-hosted household assistant that plans the week's dinners from **your own recipe
collection**, turns them into one shopping list, and (later) orders the groceries at the
cheapest sensible supermarket. The household talks to it through a Telegram bot,
[@boodschappen_buddy_bot](https://t.me/boodschappen_buddy_bot), and sees recipes, the meal plan
and the shopping list in [Mealie](https://mealie.io).

The full product specification is [spec.md](spec.md). This README explains the idea, how the
repository is set up and how to run what exists today.

## The idea

Think of a meal-kit service like HelloFresh, but built on your own recipes and your own
supermarket:

- **Your own recipe collection** in Mealie: family favourites, Belgian classics, anything you import.
- **Better-varied meals**: weekly targets for fish and vegetarian dinners, at most two meals per
  carbohydrate (pasta, rice, potatoes…), no repeats, recently eaten recipes pushed back and
  seasonal ingredients preferred (Phase 2). Household rules such as *pasta on Monday* or
  *something quick on Friday* live in Mealie.
- **One shopping list for everything**: recipe ingredients plus anything you add by hand or by
  chat, and recurring products (milk, coffee, dishwasher tablets) suggested when they are probably
  running out (Phase 2). It only asks about doubtful items (*still have rice?*) and assumes staples
  like salt and oil are in stock.
- **A lower total price**: meals whose ingredients are on promotion score higher, packs are chosen
  for the best effective price, and the basket can be split across Albert Heijn and Colruyt when
  that clearly saves money (Phases 3–4).
- **Nothing is bought without explicit approval.**

## How a week works

The target experience is three decisions a week; everything else is automatic.

```text
Planning day                                  Telegram household group
───────────────────────────────────────────   ──────────────────────────────────────────
1. Read recipes, rules and recent meals
   from Mealie; plan next week's dinners  ──▶ "Here is next week's menu"  [Looks good] [Swap…]
2. Scale and combine the ingredients,
   apply purchase policies                ──▶ "Do you still have…?"       ☐ rice ☑ passata [Confirm]
3. Write the shopping list to Mealie       ──▶ "The list is ready" (household shops from Mealie)
4. Phase 3+: find products, prices and
   promotions, pick retailer and slot     ──▶ "AH, Thursday 18–20h, €84.20" [Order]
5. Place the order after approval
```

Free-text messages (*swap Wednesday for something with fish*, *put coffee on the list*) go to an
AI agent that uses the same operations as the buttons. A week driven only by buttons needs no AI
at all.

## Architecture

```text
┌─────────── Household ───────────┐
│  Mealie               Telegram  │   Mealie: recipes, foods, planner rules,
│  (visual app)         group     │   meal plan and shopping list (source of truth)
└──────┬──────────────────┬───────┘
       │ REST             │ Bot API (long polling)
       ▼                  ▼
┌──────────────────────────────────┐
│ Grocery Core (Java, Spring Boot) │   planning, shopping list, chat, agent,
│ modular monolith                 │   later prices, promotions and ordering
└──────┬───────────────┬───────────┘
       ▼               ▼
   PostgreSQL     Albert Heijn [P3], Colruyt [P4]
```

- **Mealie owns the food domain.** Recipes, ingredients (Foods), the meal plan and the shopping
  list live in Mealie; Grocery Core never keeps its own copy. Edits you make in Mealie always win.
- **Rules restrict, scores prefer.** What is *not allowed* on a day is a Mealie planner rule.
  What is *more attractive* (rating, variety, season, promotions) is a score in the planner.
  Promotions never decide which recipes are allowed, only which ones are preferred.
- **Deterministic first.** Planning, quantities, unit conversion, prices and promotion arithmetic
  are plain code. AI only handles free text.
- **Purchase policies** decide per ingredient what happens: `AUTO` (just buy it), `CHECK` (ask
  first), `STOCKED` (assume it is in the cupboard) and, from Phase 2, `PREDICT` (suggest based on
  how often you buy it).
- **One Telegram group, one bot.** Only allowlisted members can press buttons; a button press is
  handled without AI and can never be applied twice.

## Roadmap

| Phase | Outcome | Status |
| --- | --- | --- |
| **1. MVP** | A weekly meal plan and a Mealie shopping list after a short check-in in Telegram | POCs 1–4 done in [core/](core/README.md); the agent POC and wiring into one app are next |
| 2. Routines | Recurring products suggested; learned habits and the season shape the plan | Not started |
| 3. Albert Heijn | Prices and promotions in the plan; the list becomes an AH order after approval | Not started |
| 4. Multi-retailer | Split the basket over AH and Colruyt when it is clearly worth it | Not started |

Each phase is usable on its own. Details and acceptance criteria are in [spec.md §9](spec.md).

## Repository layout

| Path | What it is |
| --- | --- |
| [spec.md](spec.md) | Product specification: requirements, domain model, architecture, phases |
| [core/](core/README.md) | Grocery Core: Gradle multi-module build (Java 21), one module per Phase 1 POC |
| `core/contracts` | Data shapes passed between modules (snapshot, proposed plan, shopping requirements) |
| `core/mealie` | POC 1: Mealie client, planner rules and candidate pools |
| `core/planning` | POC 2: deterministic meal planner and scoring |
| `core/shoppinglist` | POC 3: scaling, aggregation, purchase policies and list reconciliation |
| `core/chat` | POC 4: Telegram bot, interactions and button handling |
| [fixtures/](fixtures/) | Example snapshot, plan and requirements for week 2026-W42, used to run POCs on their own |
| [dev/seed/](dev/seed/) | Test dataset and seeder (Python, data loading only) |
| [docker-compose.yml](docker-compose.yml), [Makefile](Makefile) | Local Mealie + Postgres and the setup commands |
| [.env.example](.env.example) | Settings template; `make setup` copies it to `.env` (never committed) |

## Getting started

Requirements: Docker (with Compose v2), `make`, and Java 21 for the Grocery Core.

```bash
make setup     # start Mealie + Postgres, then load the test dataset
```

Then open <http://localhost:9925> and log in with `changeme@example.com` / `MyPassword`.

`make setup` also creates a long-lived Mealie API token and writes it to `.env` as
`MEALIE_API_TOKEN`. That's the token the Grocery Core uses:

```bash
source .env
curl -H "Authorization: Bearer $MEALIE_API_TOKEN" "http://localhost:9925/api/recipes?perPage=5"
```

API docs: <http://localhost:9925/docs>. To build and run the POCs, see [core/README.md](core/README.md):

```bash
cd core && ./gradlew build
```

| Command            | What it does                                                        |
| ------------------ | ------------------------------------------------------------------- |
| `make up`          | Start Mealie + Postgres (data persists in Docker volumes)           |
| `make down`        | Stop the containers and keep the data                               |
| `make seed`        | Load the dataset. Safe to rerun because existing items are skipped  |
| `make seed-update` | Re-apply the dataset and overwrite recipes that already exist       |
| `make reset`       | Wipe everything and start from a fresh, seeded Mealie               |
| `make check-data`  | Validate the dataset files without touching Mealie                  |

The seeder is plain Python (standard library only). It runs in a `python:3.12-alpine`
container, so you don't need to install anything on the host. You can also run it directly:
`MEALIE_URL=http://localhost:9925 python3 dev/seed/seed.py`.

### What's running

| Service    | Image                                   | Port   |
| ---------- | --------------------------------------- | ------ |
| `mealie`   | `ghcr.io/mealie-recipes/mealie:v3.28.0` | `9925` |
| `postgres` | `postgres:17-alpine` (Mealie database)  | none   |

### The test dataset

All data lives in [dev/seed/data/](dev/seed/data/) and follows the Mealie conventions in spec §3.3.

- **100 recipes** in [dev/seed/data/recipes/](dev/seed/data/recipes/):
  - 25 Belgian classics (stoofvlees, waterzooi, vol-au-vent, stoemp…)
  - 46 everyday dinners
  - 18 breakfast and lunch recipes
  - 11 sides and snacks
- **Every ingredient is structured** as quantity + unit + Food + note, with no free-text
  ingredients. Shopping-list aggregation per Food UUID (spec §4.3.1) therefore works out of the box.
- **Categories** (meal purpose): Breakfast, Lunch, Dinner, Snack, Side.
- **Tags** (planning characteristics): carbohydrate, protein and preparation groups as in spec §3.3.
- **250 Foods**, each with a shopping label. Foods that deviate from their label's default purchase
  policy carry an override in extras; the label defaults live in Grocery Core
  (`ShoppingSettings`), so policy resolution happens in one place.
  The Foods include non-recipe items such as Coca-Cola, dishwasher tablets and toilet paper, so
  you can test PREDICT behaviour.
- **16 metric units** (g, kg, ml, l, tsp, tbsp, clove, can, bunch…).
- **Planner rules** from spec §3.4.4 in [planner-rules.json](dev/seed/data/planner-rules.json)
  (Monday pasta, no weekend recipes on weekdays, a quick Friday).
- **Three weeks of past dinners** in [meal-plan-history.json](dev/seed/data/meal-plan-history.json),
  dated relative to the week you seed in, for recency scoring.
- **Ratings** for most recipes (1 to 5; rating 5 also marks the recipe as a favourite), plus an
  empty `Groceries` shopping list.
- **Seasonal variety**: white asparagus, pumpkin, Brussels sprouts, endive, berries and so on,
  for seasonality scoring.

Recipe file format (ingredients use `[quantity] [unit] food[, note]`; the seeder rejects unknown
foods and units, so the catalog and recipes stay consistent):

```json
{
  "name": "Liège Salad",
  "description": "…",
  "servings": 4, "prepMinutes": 15, "cookMinutes": 25, "rating": 4,
  "categories": ["Dinner"],
  "tags": ["Red meat", "Potatoes", "Quick"],
  "ingredients": ["800 g waxy potatoes", "2 shallots, finely chopped", "salt"],
  "steps": ["…", "…"]
}
```

To add a recipe, drop it into one of the JSON files, add any new food to
[catalog.json](dev/seed/data/catalog.json), then run `make check-data && make seed`.

#### Mealie extras are strings

Mealie stores extras as flat **string** key/value pairs. Sending a nested object returns a 500.
The seeder therefore stores the spec §3.3 namespace as a JSON-encoded string:

```json
"extras": { "groceries": "{\"purchasePolicy\": \"CHECK\"}" }
```

Foods can also carry `gramsPer` (grams per unit, `piece` for "no unit", e.g.
`{"tablespoon": 16}` for peanut butter) so the shopping list can merge "75 g" and "1 tbsp" into
one quantity. Set it in `catalog.json` for foods the recipes use in units that don't convert
into each other; `make check-data` lists the ones that are missing.

The Grocery Core needs to `JSON.parse` / serialize the `groceries` value. Seeded recipes carry
`"groceries": "{\"seed\": true}"`.

#### Deviations from the spec's suggested labels

- **Pantry**: an extra label for oils, spices, flour, sugar and stock cubes. None of the
  suggested labels fit these.
- **Purchase policies**: the label sets a default (in Grocery Core), which individual foods
  override in `catalog.json`.
  - Onions, garlic and potatoes are `CHECK`.
  - Lemons, berries and cream are `AUTO`.
  - Beer for stoofvlees is `AUTO`; wine is `CHECK`.
