# Grocery Orchestrator: development workspace

See [spec.md](spec.md) for the product specification.

## Quick start

Requirements: Docker (with Compose v2) and `make`.

```bash
make setup     # start Mealie + Postgres, then load the test dataset
```

Then open <http://localhost:9925> and log in with `changeme@example.com` / `MyPassword`.

`make setup` also creates a long-lived Mealie API token and writes it to `.env` as
`MEALIE_API_TOKEN`. That's the token the Grocery Core should use:

```bash
source .env
curl -H "Authorization: Bearer $MEALIE_API_TOKEN" "http://localhost:9925/api/recipes?perPage=5"
```

API docs: <http://localhost:9925/docs>

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

## What's running

| Service    | Image                                   | Port   |
| ---------- | --------------------------------------- | ------ |
| `mealie`   | `ghcr.io/mealie-recipes/mealie:v3.28.0` | `9925` |
| `postgres` | `postgres:17-alpine` (Mealie database)  | none   |

## The test dataset

All data lives in [dev/seed/data/](dev/seed/data/) and follows the Mealie conventions in the spec (§7–§10).

- **100 recipes** in [dev/seed/data/recipes/](dev/seed/data/recipes/):
  - 25 Belgian classics (stoofvlees, waterzooi, vol-au-vent, stoemp…)
  - 46 everyday dinners
  - 18 breakfast and lunch recipes
  - 11 sides and snacks
- **Every ingredient is structured** as quantity + unit + Food + note, with no free-text
  ingredients. Shopping-list aggregation per Food UUID (spec §21) therefore works out of the box.
- **Categories** (meal purpose): Breakfast, Lunch, Dinner, Snack, Side.
- **Tags** (planning characteristics): carbohydrate, protein and preparation groups as in spec §7.2.
- **250 Foods**, each with a shopping label (spec §8) and a purchase policy in extras (spec §10).
  The Foods include non-recipe items such as Coca-Cola, dishwasher tablets and toilet paper, so
  you can test PREDICT behaviour.
- **16 metric units** (g, kg, ml, l, tsp, tbsp, clove, can, bunch…).
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

### Mealie extras are strings

Mealie stores extras as flat **string** key/value pairs. Sending a nested object returns a 500.
The seeder therefore stores the spec §9 namespace as a JSON-encoded string:

```json
"extras": { "groceries": "{\"purchasePolicy\": \"AUTO\", \"stockUpAllowed\": false}" }
```

The Grocery Core needs to `JSON.parse` / serialize the `groceries` value. Seeded recipes carry
`"groceries": "{\"seed\": true}"`.

### Deviations from the spec's suggested labels

- **Pantry**: an extra label for oils, spices, flour, sugar and stock cubes. None of the
  suggested labels fit these.
- **Purchase policies**: the label sets a default, which individual foods override.
  - Onions, garlic and potatoes are `CHECK`.
  - Lemons, berries and cream are `AUTO`.
  - Beer for stoofvlees is `AUTO`; wine is `CHECK`.
