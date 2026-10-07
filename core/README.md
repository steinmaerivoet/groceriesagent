# Grocery Core: Phase 1 POCs

The future Grocery Core (spec §8) as a Gradle multi-module build (Java 21). Each Phase 1 POC is
its own module with a small demo and its own tests. The modules hand data to each other through
the records in `contracts/`, written as JSON files in `../fixtures/` for now. The integration
step later replaces those files with direct calls.

| Module | POC | Question it answers | Needs |
| --- | --- | --- | --- |
| `contracts` | | The data shapes between modules (`PlanningSnapshot`, `ProposedPlan`, `ShoppingRequirements`, …) | |
| `mealie` | 1 | Does Mealie behave as the spec assumes, and do our candidate pools equal what the random button draws from? | Mealie |
| `planning` | 2 | Does a greedy + local-improvement planner produce acceptable weeks? | a snapshot file |
| `shoppinglist` | 3 | Can a week of recipes become a correct Mealie list that survives re-runs and manual edits? | Mealie |
| `chat` | 4 | Can a Telegram group handle the proposal, a shared checklist and safe button presses? | nothing (console) or a bot token |

## Running

Start Mealie first from the repository root (`make setup`). Then, from `core/`:

```bash
./gradlew build                      # compile + all tests (Mealie tests are skipped when it isn't running)

# POC 1: Mealie
./gradlew -q :mealie:run --args="pools --week 2026-W42"        # pools per slot, checked against the random button
./gradlew -q :mealie:run --args="snapshot --week 2026-W42"     # writes fixtures/snapshot-2026-W42.json
./gradlew -q :mealie:run --args="probe"                        # checks the open Mealie API questions (OQ-01)

# POC 2: planner (no Mealie needed)
./gradlew -q :planning:run --args="fixtures/snapshot-2026-W42.json"
./gradlew -q :planning:run --args="fixtures/snapshot-2026-W42.json --replace 2026-10-14 --out fixtures/plan-2026-W42.json"

# POC 3: shopping list
./gradlew -q :shoppinglist:run --args="--plan fixtures/plan-2026-W42.json"                       # what would be bought / asked
./gradlew -q :shoppinglist:run --args="--plan fixtures/plan-2026-W42.json --yes garlic,onion --write"

# POC 4: chat
./gradlew -q --console=plain :chat:run --args=console           # the Appendix A.1 conversation in your terminal
./gradlew -q :chat:run                                          # the same in your Telegram test group
```

Demos run from the repository root, so paths like `fixtures/…` are relative to it.

### Trying the bot in Telegram

1. Create a bot with @BotFather and copy its token. Turn off `/setjoingroups` after adding it to
   your test group, and turn off group privacy (`/setprivacy`) so it sees replies and mentions.
2. Add the bot to a test group and find the group's chat id (for example by sending a message and
   opening `https://api.telegram.org/bot<token>/getUpdates`).
3. Fill in `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID` and `TELEGRAM_ALLOWED_USER_IDS` in `.env`.
4. `./gradlew -q :chat:run`. Press buttons from two phones, double-tap, press an old message,
   send `/plan` to post a fresh proposal (the older buttons then become outdated).

## What the POCs found

**POC 1: Mealie (OQ-01).** Verified against Mealie v3.28.0:

- Our pools equal what `POST /mealplans/random` draws from, for every slot. The rule matching and
  the `AND` combination mirror Mealie's own code, and `GET /api/recipes?queryFilter=` uses the
  same user and group scope as the random button. `LiveMealieTest` checks this on every build.
- The random endpoint **creates a meal-plan entry** for every draw, so the pool check deletes them
  again. Grocery Core writes plans through the normal meal-plan API (spec §3.4.3).
- Meal-plan entries can be created, listed by date and deleted. Shopping-list items and foods keep
  `groceries` extras, which Mealie stores as a JSON *string*.
- **Mealie merges a new list item into an existing item with the same food.** The merged item
  keeps the first item's extras. So the reconciler never adds an item for a food the household
  already has on the list (that would change their item); it reports the overlap instead.
- Java's HTTP client must use HTTP/1.1 against Mealie; its HTTP/2 upgrade request is rejected.

**POC 2: planner.** The seeded week meets every target (Monday pasta from the rules, ≥1 fish,
≥2 vegetarian, no repeats, at most 2 per carbohydrate) with recently planned recipes penalized,
and the same run id always gives the same plan. A recipe may carry several planning tags
(*Vegetarian* + *Legumes*, *Potatoes* + *Bread*) and counts towards each of them; only a missing
protein tag or a contradiction such as *Vegetarian* + *Fish* is reported as
`RECIPE_CLASSIFICATION_REQUIRED`. The seeded data has none.

**POC 3: shopping list.** Scaling, unit conversion, policies and re-runs work against Mealie: a
second run changes nothing, a hand-edited item is left alone, and an item the household removed
is not added back. The first run asked **38 stock-check questions**, mostly salt, pepper, oil and
spices. Pantry staples are now `STOCKED` (assumed in stock, never asked), which brings the seeded
week to 22 questions: canned goods, pasta and rice, dairy (which Phase 2 will predict instead)
and a few non-staple pantry items such as nuts. Every food is now one line: a food's weight per
unit (`gramsPer` in the seed catalog) converts tablespoons, slices or pieces into grams, so
peanut butter 75 g + 1 tbsp becomes 91 g (21 questions). Without a weight the amounts stay side
by side on one line ("75 gram + 1 tablespoon"); `make check-data` warns about such foods.

**POC 4: chat.** Button handling, the shared checklist, the allowlist, outdated and simultaneous
presses work and are covered by tests. The demo was run in console mode only: this sandbox can't
reach Telegram, so the live bot still needs a first run with a real token.
