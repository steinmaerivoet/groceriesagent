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
| `agent` | 5 | Can a Spring AI agent on Amazon Bedrock answer free-text questions in the group from Mealie, e.g. *geef mij een recept met vis*? | Mealie, AWS Bedrock, optionally a bot token |

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

# POC 5: free-text agent (Spring AI + Bedrock + Mealie)
./gradlew -q --console=plain :agent:run --args=console          # ask questions in your terminal
./gradlew -q :agent:run                                         # the bot answers in your Telegram group
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

### Trying the agent (POC 5)

The agent is a Spring Boot app (`agent/`). Spring AI runs the LLM loop on Amazon Bedrock's
Converse API and calls three read-only tools over Mealie: `listRecipeTags`, `searchRecipes`
(free text and tags) and `getRecipe`. In Telegram it answers messages that mention the bot or
reply to it, from allowlisted members only; `/help` is answered without an LLM call. Each chat
keeps its last 20 messages in memory, so follow-up questions work until the app restarts.

It reads these settings from the environment, falling back to `.env` (the environment wins):

| Variable | Needed | Meaning |
| --- | --- | --- |
| `MEALIE_API_TOKEN`, `MEALIE_URL` | yes | as for POC 1 (`make setup` fills in the token) |
| `AWS_REGION` | yes | region with Bedrock model access; default `eu-central-1` |
| `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` (`AWS_SESSION_TOKEN`) or `AWS_PROFILE` | yes | credentials allowed to call `bedrock:InvokeModel` / `bedrock:Converse`. Leave all empty to use the AWS default chain (`~/.aws`, SSO, …). |
| `BEDROCK_MODEL_ID` | no | model or inference profile id; default `eu.anthropic.claude-opus-5-5`. Copy the exact id from the Bedrock console (Model catalog or Cross-region inference) for your region. |
| `BEDROCK_MAX_TOKENS` | no | answer length limit; default 4096 |
| `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`, `TELEGRAM_ALLOWED_USER_IDS` | for Telegram | as for POC 4; without a token the agent starts in console mode |

In the AWS account, enable access to the model in the Bedrock console first; the IAM user or
role needs `bedrock:InvokeModel` on that model or inference profile. Then:

1. `make setup` (Mealie with the seeded recipes), fill in the variables above.
2. `./gradlew -q --console=plain :agent:run --args=console` and ask *geef mij een recept met vis*.
3. `./gradlew -q :agent:run` and ask the same in the group: `@boodschappen_buddy_bot geef mij een recept met vis`.

The tests run the real Spring AI tool loop against a scripted stand-in for Bedrock and a fake
Mealie, so `./gradlew build` needs neither AWS nor Mealie.

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
and a few non-staple pantry items such as nuts.

**POC 4: chat.** Button handling, the shared checklist, the allowlist, outdated and simultaneous
presses work and are covered by tests. The demo was run in console mode only: this sandbox can't
reach Telegram, so the live bot still needs a first run with a real token.
