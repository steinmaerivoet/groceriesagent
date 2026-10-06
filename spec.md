# Personal Grocery Orchestrator — Software Requirements Specification

| Attribute    | Value                                                                       |
| ------------ | --------------------------------------------------------------------------- |
| Version      | 0.4                                                                         |
| Status       | Draft — MVP baseline                                                        |
| Product      | Personal, self-hosted household application                                 |
| User UI      | Mealie (visual) + conversational bot in a Telegram household group          |
| Technology   | Java / Spring Boot modular monolith, PostgreSQL, Playwright (fallback only) |
| Architecture | API-first, deterministic-first, agent-assisted                              |

**Revision history**

| Version | Changes                                                                                                                                                                            |
| ------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 0.1     | Initial draft.                                                                                                                                                                     |
| 0.2     | Mealie planner rules adopted as the eligibility layer. Timefold replaced by a deterministic planner (Timefold moved to nice-to-haves). Phases redefined around an MVP.              |
| 0.3     | Telegram household group chosen as the chat channel: interaction primitives, deterministic button handling, access control and channel adapter.                                    |
| 0.4     | Restructured into a conventional SRS layout. Data model, workflows and phase tables consolidated; duplicated requirements merged. No functional changes.                            |

---

## Table of contents

1. [Introduction](#1-introduction) — purpose, scope, definitions, conventions
2. [Overall description](#2-overall-description) — context, phases, principles, invariants, non-goals
3. [Domain model](#3-domain-model) — data ownership, food identity, Mealie conventions, planner rules, Grocery Core entities
4. [Functional requirements](#4-functional-requirements) — planning, purchase policies, shopping list, retailers, procurement, ordering, problems
5. [Conversational interface](#5-conversational-interface) — Telegram channel, interactions, agent, UX
6. [Weekly workflow](#6-weekly-workflow)
7. [Non-functional requirements](#7-non-functional-requirements) — configuration, security, reliability, observability, efficiency
8. [Architecture](#8-architecture) — deployment, modules, persistence
9. [Delivery plan](#9-delivery-plan) — phases with acceptance criteria, nice-to-have backlog
10. [Open issues](#10-open-issues)

[Appendix A — Example conversations](#appendix-a--example-conversations)

---

## 1. Introduction

### 1.1 Purpose

This document specifies the functional and technical requirements of the Personal Grocery Orchestrator: the custom application *Grocery Core* and the way it uses Mealie and a Telegram bot. It is the reference for development, testing and phase acceptance.

### 1.2 Product scope

The system automates the recurring household grocery process: selecting meals and keeping a weekly meal schedule; determining the required groceries, including recurring household products and uncertain pantry items; comparing products, prices and promotions across supermarkets; choosing a retailer and a delivery or pickup moment; and preparing and placing orders after explicit approval.

**Objectives**

- The system **SHALL** minimize active household administration; the weekly interaction **SHOULD** take only a few minutes.
- The system **SHALL** prefer predictable household routines over marginal theoretical optimizations.
- A mature system **SHOULD** reduce the week to three decisions, with everything else automatic:
  1. Is this meal plan acceptable?
  2. Which uncertain or predicted products should be added?
  3. May these supermarket orders be placed?

**Definition of success**

| Today                                                                                                                                         | Target                                                                                                                         |
| --------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| choose meals → make list → check cupboards → remember recurring products → search promotions → compare stores → build carts → find delivery slots → place orders | open notification → review sensible meal plan → answer a few preselected questions → approve retailer plan → approve final order → done |

The MVP is successful when the first three target steps work, the household shops from the Mealie list, and the Phase 1 success measures (§9.1) are met. Supermarket procurement is added only after that workflow is genuinely useful.

### 1.3 Definitions

| Term                 | Definition                                                                                |
| -------------------- | ----------------------------------------------------------------------------------------- |
| **Grocery Core**     | The Spring Boot application specified in this document.                                   |
| **Mealie**           | The self-hosted recipe manager that owns the household food domain.                       |
| **Food**             | A Mealie Food entity; the canonical household identity of a purchasable concept.          |
| **Planner rule**     | A Mealie meal-planner rule: a day, a meal type and a recipe query filter.                 |
| **Slot**             | One date × meal type to be filled with a recipe, e.g. *Tuesday dinner*.                   |
| **Candidate pool**   | The recipes eligible for a slot, as defined by the matching planner rules.                |
| **Retailer product** | A retailer-specific SKU. Never a canonical household identity.                            |
| **Managed item**     | A Mealie shopping-list item created by Grocery Core and marked as such in its extras.     |
| **Planning run**     | One weekly execution of the workflow (§6).                                                |
| **Procurement run**  | One retailer optimization over a snapshot of the finalized shopping list.                 |
| **Fulfilment**       | Delivery or pickup of an order.                                                           |
| **Household group**  | The Telegram group chat in which the household and the bot interact.                      |
| **Interaction**      | A persisted bot message that awaits an answer, linked to a workflow state.                |
| **MVP**              | Phase 1: Mealie-first meal planning and shopping-list generation, without retailers.      |
| **Target design**    | Everything required for Phases 1–4.                                                       |
| **Nice-to-have**     | A candidate improvement that is explicitly not part of the target design (§9.5).          |

### 1.4 Conventions

- **Requirement keywords.** **SHALL** / **SHALL NOT** are absolute; **SHOULD** / **SHOULD NOT** are recommended, deviation needs a deliberate reason; **MAY** is optional ([RFC 2119](https://www.rfc-editor.org/rfc/rfc2119)).
- **Phase tags.** A requirement applies from the phase it, or its section, is tagged with, e.g. **[P3]**. Untagged requirements apply from Phase 1. **[NTH-nn]** refers to the nice-to-have backlog (§9.5).
- **Identifiers.** `INV-nn` architecture invariants (§2.4), `OQ-nn` open questions (§10), `NTH-nn` nice-to-haves (§9.5).
- **Conceptual models** describe the information a concept carries; they are not final schemas, classes or API contracts.

---

## 2. Overall description

### 2.1 System context

```text
┌───────────────────────────────────────────────────────┐
│                    Household                          │
│      ┌───────────────┐       ┌─────────────────┐      │
│      │    Mealie     │       │ Telegram group  │      │
│      │ recipes, foods│       │ proposals       │      │
│      │ planner rules │       │ questions       │      │
│      │ meal plan     │       │ approvals       │      │
│      │ shopping list │       │ free-text agent │      │
│      └───────┬───────┘       └────────┬────────┘      │
└──────────────┼────────────────────────┼───────────────┘
               │ REST                   │ Bot API / tools
               ▼                        ▼
        ┌──────────────────────────────────────┐
        │            Grocery Core              │
        │ planning                    [P1]     │
        │ prediction / routines       [P2]     │
        │ market intelligence         [P3]     │
        │ procurement / fulfilment    [P3]     │
        │ ordering                    [P3]     │
        └──────┬────────────┬──────────────────┘
               ▼            ▼
          PostgreSQL    Retailers: AH [P3], Colruyt [P4]
```

Mealie is the household's visual application and the source of truth for the food domain. The Telegram household group is the conversational interface. Grocery Core holds all planning, prediction, procurement and workflow logic and persists only its own state in PostgreSQL.

### 2.2 Delivery phases

| Phase | Name                          | Outcome                                                                                        | Retailers    | Depends on | End state                 |
| ----- | ----------------------------- | ---------------------------------------------------------------------------------------------- | ------------ | ---------- | ------------------------- |
| **1** | **MVP: Mealie-first planning**| A sensible weekly meal plan and a mostly complete Mealie shopping list after a short check-in. | —            | —          | `SHOPPING_LIST_FINALIZED` |
| 2     | Recurring needs and routines  | Recurring products are suggested; learned routines and seasonality shape the plan.             | —            | 1          | `SHOPPING_LIST_FINALIZED` |
| 3     | Albert Heijn ordering         | The finalized shopping list becomes a prepared and, after approval, confirmed AH order.        | AH           | 1          | `ORDERED`                 |
| 4     | Multi-retailer optimization   | Requirements are split across AH and Colruyt when that is clearly worthwhile.                  | AH + Colruyt | 3          | `ORDERED`                 |

- Each phase **SHALL** be usable on its own: it is complete only when the household can rely on it weekly without the next phase.
- Phases 2 and 3 are independent and **MAY** be swapped.
- Detailed scope and acceptance criteria are in §9.

### 2.3 Design principles

1. **Reuse before building.** Existing applications and libraries **SHALL** be used where they provide the domain: Mealie owns the food domain and recipe eligibility; existing retailer libraries **SHOULD** be reused where reliable; Playwright **SHALL** be preferred over AI browser interaction.
2. **Mealie-first.** Mealie **SHALL** be the primary visual application. A separate grocery UI **SHALL NOT** be built unless a requirement cannot reasonably be met through Mealie or the chat. Preferences Mealie can express (recipe eligibility per day and meal type) **SHALL** be configured in Mealie.
3. **Prediction instead of inventory.** Users **SHALL NOT** have to register consumption or track pantry stock. Uncertain products are bought, asked about or predicted according to their purchase policy (§4.2).
4. **Deterministic before AI.** Prices, promotions, quantities, unit conversions, scheduling, statistics, need prediction, meal constraints, retailer allocation and basket calculations **SHALL** be deterministic. AI is reserved for free-text conversation, ambiguity and recovery.
5. **Minimum surprise.** Established behaviour (meal categories, recipes, supermarkets, products, delivery days and windows, order frequency) **SHOULD** be preserved unless a change provides meaningful benefit.
6. **Filters restrict, scores prefer.** What makes a recipe *unacceptable* belongs in Mealie planner rules. What makes it *more or less attractive* (price, promotions, seasonality, variety, familiarity) **SHALL** be a score in Grocery Core and **SHALL NOT** restrict eligibility.

### 2.4 Architecture invariants

The following **SHALL** hold in every phase and are the first check in design reviews.

| ID     | Invariant                                                                                 |
| ------ | ----------------------------------------------------------------------------------------- |
| INV-01 | Mealie is authoritative for recipes, Foods, planner rules, meal plans and shopping lists. |
| INV-02 | The Mealie Food UUID is the canonical household product identity.                         |
| INV-03 | Retailer SKUs never become canonical household identities.                                |
| INV-04 | Grocery Core does not maintain exact pantry inventory.                                    |
| INV-05 | Manual Mealie changes always win.                                                         |
| INV-06 | Shopping intent and retailer allocation remain separate.                                  |
| INV-07 | AI is not required for a normal successful weekly run.                                    |
| INV-08 | Resolved problems become deterministic state.                                             |
| INV-09 | Convenience and routine are legitimate optimization objectives.                           |
| INV-10 | Final spending requires explicit user approval.                                           |
| INV-11 | Retailer integrations are replaceable behind capabilities.                                |
| INV-12 | Credentials never enter LLM context.                                                      |
| INV-13 | Failure to order never invalidates the household shopping list.                           |
| INV-14 | Mealie planner rules define eligibility; Grocery Core never plans outside a slot's pool.  |
| INV-15 | Market state (prices, promotions, seasonality) influences scores only, never eligibility. |
| INV-16 | Grocery Core never changes Mealie planner rules without explicit user approval.           |

### 2.5 Non-goals

The following will not be built in any phase:

| Area                     | Not in scope                                                                                                                       |
| ------------------------ | ---------------------------------------------------------------------------------------------------------------------------------- |
| Household administration | Exact pantry inventory; consumption logging; ERP functionality; barcode-driven administration                                      |
| Data duplication         | Duplicate recipe management or Food ontology; one Mealie shopping list per supermarket; a Grocery Core recipe-rule engine          |
| Infrastructure           | Microservices; Kafka; RabbitMQ; dedicated vector database; Kubernetes                                                              |
| AI usage                 | LLM-driven price or promotion arithmetic; autonomous LLM browsing as the normal retailer interface                                 |
| Purchasing               | Unapproved purchasing                                                                                                              |

---

## 3. Domain model

### 3.1 Data ownership

Mealie **SHALL** be authoritative for recipes (ingredients, servings, nutrition, ratings, categories, tags), cookbooks, Foods, Units, Food labels, ingredient substitutions, meal-planner rules, meal-plan entries, shopping lists and shopping-list items.

Grocery Core **SHALL NOT** create an alternative source of truth for, or persist copies of, these entities. It persists only the state listed in §3.5.

### 3.2 Canonical food identity

- A Mealie Food UUID **SHALL** be the canonical household identifier of a purchasable concept. No separate `FoodConcept` entity **SHALL** exist.
- Retailer products are mapped to Foods (§4.4.4), e.g. Food `983c…` *Greek yoghurt* → AH product `123456`, Colruyt product `882991`.
- Non-food household products (Coca-Cola Zero, dishwasher tablets, detergent, toilet paper, kitchen roll, shampoo) **MAY** be Mealie Foods. A shared, stable identity outweighs the semantic mismatch (see OQ-03).

### 3.3 Mealie metadata conventions

| Metadata       | Convention                                                    | Values                                                                                                                                                                                                         |
| -------------- | ------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Categories     | Meal purpose.                                                 | `Breakfast`, `Lunch`, `Dinner`, `Snack`, `Side`                                                                                                                                                                |
| Tags           | Stable planning characteristics, understandable to a human.   | *Carbohydrate:* Pasta, Rice, Potatoes, Other grain, Bread · *Protein / meal type:* Fish, Poultry, Red meat, Vegetarian, Legumes · *Preparation:* Quick, Weekend, Freezer-friendly, Leftovers-friendly, Comfort food, Light meal |
| Food labels    | Shopping-oriented; every Food **SHALL** have one.             | Vegetables, Fruit, Fish, Meat, Dairy & eggs, Bread, Pasta & grains, Canned & jars, Frozen, Drinks, Snacks, Spices & condiments, Household, Personal care                                                       |

- Tags and labels **SHALL NOT** encode market state or purchasing behaviour (e.g. `On promotion`, `In season`, `Fresh`, `Weekly`, `Cheap`, `Always-buy`). Labels **MAY** define default purchase policies (§4.2.2).
- **Completeness.** Every plannable recipe **SHOULD** have exactly one carbohydrate tag (or none, if not applicable) and exactly one protein / meal-type tag. Missing or conflicting planning tags **SHALL** be reported as `RECIPE_CLASSIFICATION_REQUIRED`, never guessed. Ingredients **SHOULD** be parsed (linked to a Food and Unit) with Mealie's ingredient parser.
- **Extras.** Integration metadata **SHOULD** be stored in Mealie extras under the single namespace `groceries`; no other root-level keys **SHALL** be written.

| Extras key (under `groceries`)            | On                 | Purpose                    |
| ----------------------------------------- | ------------------ | -------------------------- |
| `purchasePolicy`                          | Food               | Policy override (§4.2.2)   |
| `managed`, `planningRunId`, `origin`      | Shopping-list item | Managed items (§4.3.3)     |
| `stockUpAllowed`                          | Food               | NTH-05 only                |

### 3.4 Mealie planner rules

#### 3.4.1 Mealie behaviour (verified against Mealie source)

| Aspect            | Behaviour                                                                                                                                         |
| ----------------- | ------------------------------------------------------------------------------------------------------------------------------------------------- |
| Rule structure    | `day` (Monday–Sunday or unset), `entryType` (breakfast, lunch, dinner, side, snack, drink, dessert or unset), `queryFilterString`.                |
| Filterable fields | Categories, tags, tools, ingredient Foods and Food labels, household, user, rating, total time, last made, created/updated date.                  |
| Operators         | `=`, `<>`, `>`, `>=`, `<`, `<=`, `IS`, `IS NOT`, `IN`, `NOT IN`, `CONTAINS ALL`, `LIKE`, `NOT LIKE`, `AND`, `OR`, parentheses, relative dates (`$NOW-14d`). |
| Matching          | Every rule whose day and entry type match the slot (or are unset) applies; their filters are combined with **AND**.                               |
| Selection         | `POST /api/households/mealplans/random` picks uniformly at random from the combined result; HTTP 404 if empty.                                    |
| Scope             | Slots are resolved independently: no variety, weekly targets or repeat prevention.                                                                |
| `last_made`       | Updated by a timeline job only after the planned day has passed, so it does not prevent repeats within the week being planned.                     |

#### 3.4.2 Role in the system

- Planner rules **SHALL** be the single place where the household defines recipe eligibility per day and meal type, explicit preferences (*Monday dinner → pasta*) and hard exclusions (diet, disliked ingredients).
- A slot's combined filter is a **hard constraint**: Grocery Core **SHALL NOT** plan a recipe outside the candidate pool.
- Volatile market state **SHALL NOT** be expressed as rules (§2.3, principle 6). Week-level conditions (e.g. *at least two fish meals*) cannot be expressed in Mealie and **SHALL** be configured in Grocery Core (§4.1.2).
- Because Grocery Core honours the same rules, Mealie's random button remains a valid manual fallback when Grocery Core is unavailable.

#### 3.4.3 Candidate-pool construction

For each slot, Grocery Core **SHALL**:

1. read all planner rules (`GET /api/households/mealplans/rules`);
2. select those matching the slot's day and meal type with Mealie's semantics (§3.4.1) and combine their filters with `AND`;
3. query all pages of `GET /api/recipes?queryFilter=…` with the same user context as the Mealie UI, so the pool equals what the random button would draw from;
4. pass the pool to the planner.

Planned entries **SHALL** be written through the normal meal-plan API, not the random endpoint.

#### 3.4.4 Recommended starter rules

Illustrative only; the Mealie rule editor stores IDs rather than names.

| Day     | Meal type | Filter                                                | Intent                         |
| ------- | --------- | ----------------------------------------------------- | ------------------------------ |
| any     | dinner    | `recipe_category.name IN ["Dinner"]`                  | Only dinner recipes for dinner |
| any     | any       | `tags.name NOT IN ["Excluded"]`                       | Household exclusions           |
| Monday  | dinner    | `tags.name IN ["Pasta"]`                              | Explicit pasta preference      |
| Mon–Fri | dinner    | `tags.name NOT IN ["Weekend"]` (one rule per weekday) | No elaborate meals on weekdays |
| Friday  | dinner    | `tags.name IN ["Quick"]`                              | Quick Friday meal              |

### 3.5 Grocery Core entities

Conceptual models of the state Grocery Core owns, with their persistence tables.

| Entity                | Key information                                                                                                                                                         | Table(s)                         | Phase |
| --------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------- | ----- |
| `PlanningRun`         | Run ID (e.g. `2026-W42`, also the planner seed), planning week, workflow state (§6), per-slot score explanations                                                         | `planning_run`                   | P1    |
| `ManagedListItem`     | Mealie item ID and the values Grocery Core last wrote (§4.3.3)                                                                                                           | `managed_list_item`              | P1    |
| `Setting`             | Configuration values (§7.1)                                                                                                                                             | `setting`                        | P1    |
| `UnresolvedProblem`   | Type, context, status, resolution (§4.7)                                                                                                                                | `unresolved_problem`             | P1    |
| `ChatInteraction`     | Run, workflow state, options, message reference, outcome (§5.4)                                                                                                         | `chat_interaction`               | P1    |
| `NeedModel`           | `foodId`, `lastPurchasedAt`, `usualQuantity`, `typicalInterval`, `intervalVariance`, `acceptanceRate`, `nextExpectedNeed`, `confidence`; plus its observations          | `need_model`, `need_observation` | P2    |
| `LearnedRoutine`      | Characteristic (tag) per day × meal type with probability and confidence, e.g. `P(Pasta \| Monday dinner) = 0.78`                                                         | `learned_routine`                | P2    |
| `Seasonality`         | `foodId`, `region`, `month`, `score` (e.g. Pumpkin, Belgium, October, 1.0)                                                                                              | `seasonality`                    | P2    |
| `RetailerProduct`     | `id`, `retailer`, `externalId`, `name`, `brand`, `ean`, `packageQuantity`, `packageUnit`, `availability`, `metadata`                                                    | `retailer_product`               | P3    |
| `FoodRetailerMapping` | `foodId` (Mealie Food UUID), `retailerProductId`, `compatibility`, `confidence`, `preferred`, `lastVerifiedAt`                                                          | `food_retailer_mapping`          | P3    |
| `PriceObservation`    | `retailerProductId`, `observedAt`, `price`, `unitPrice`, `promotionId`, `fulfillmentContext`                                                                            | `price_observation`              | P3    |
| `Promotion`           | `retailerProductId`, `type` (§4.4.5), `validFrom`, `validUntil`, `requiredQuantity`, `discountQuantity`, `discountPercent`, `fixedPrice`, `loyaltyRequired`, `personalized`, `fulfillmentConstraints` | `promotion`                      | P3    |
| `ProcurementRun`      | Snapshot of `PurchaseRequirement`s: `foodId`, `quantity`, `unit`, `requiredBy`, `source` (`MEAL_PLAN`, `RECURRING_NEED`, `MANUAL`), `optional`                           | `procurement_run`                | P3    |
| `BasketPlan`          | Chosen strategy and its `PurchaseCandidate`s per retailer (§4.5.2)                                                                                                       | `basket_plan`, `basket_item`     | P3    |
| `FulfillmentOption`   | `retailer`, `method` (`DELIVERY`/`PICKUP`), `location`, `start`, `end`, `fee`, `availability`, `promotionContext`; chosen options feed preferences                       | `fulfillment_preference`         | P3    |
| `OrderRecord`         | Retailer, fulfilment, final prices, purchased SKUs                                                                                                                      | `order_record`, `order_item`     | P3    |

---

## 4. Functional requirements

### 4.1 Meal planning

#### 4.1.1 Planning inputs

- Candidate recipes **SHALL** come only from the Mealie candidate pools (§3.4.3); Grocery Core **SHALL NOT** keep its own recipe catalog.
- The horizon **SHALL** be the configured planning week (default: the seven days after the planning day). Slots **SHALL** be configurable per weekday and meal type (default: dinner every day).
- Existing manual meal-plan entries **SHALL** be fixed: never replaced unless explicitly requested, and counted towards week-level constraints.
- Recipe recency **SHALL** be derived from Mealie meal-plan entries over a configurable lookback (default 4 weeks), not from `last_made`.
- If a pool is empty, the slot **SHALL** be left empty and an `EMPTY_CANDIDATE_POOL` problem raised naming the slot and rules; the other slots are still planned. Rules **SHALL NOT** be relaxed silently.

#### 4.1.2 Planning constraints

**HARD**: a violating plan is invalid. **MEDIUM**: large penalty, allowed only if unavoidable. **SOFT**: preference among acceptable plans (§4.1.3).

| Constraint                                       | Tier   | Phase |
| ------------------------------------------------ | ------ | ----- |
| Recipe within the slot's candidate pool          | HARD   | P1    |
| Fixed (manual) entries are not overwritten       | HARD   | P1    |
| Every configured slot is filled (if pool not empty) | HARD | P1    |
| No recipe twice in the same week                 | MEDIUM | P1    |
| Weekly fish target (default ≥ 1)                 | MEDIUM | P1    |
| Weekly vegetarian target (default ≥ 2)           | MEDIUM | P1    |
| Maximum repeats per carbohydrate tag (default 2) | MEDIUM | P1    |

Weekly targets and limits are expressed in terms of the tags in §3.3. Dietary diversity **SHALL** be pursued through these targets and the variety score; Mealie nutrition data **MAY** be displayed but is not scored (NTH-10), and missing nutrition **SHALL NOT** block planning.

#### 4.1.3 Scoring

The planner **SHALL** score stability explicitly. Conceptually:

```text
score = qualityBenefit + seasonalBenefit + financialBenefit
      - routineDeviationCost - recencyPenalty - mediumConstraintPenalty
```

| Term                      | Based on                                                                    | Phase |
| ------------------------- | --------------------------------------------------------------------------- | ----- |
| `qualityBenefit`          | Recipe rating, variety of protein and carbohydrate, ingredient reuse        | P1    |
| `recencyPenalty`          | Recipes planned within the lookback                                         | P1    |
| `mediumConstraintPenalty` | Violated MEDIUM constraints                                                 | P1    |
| `routineDeviationCost`    | Distance from learned routines                                              | P2    |
| `seasonalBenefit`         | Seasonality scores of the recipe's ingredient Foods                         | P2    |
| `financialBenefit`        | Expected promotion savings of the ingredients at the usual retailer         | P3    |

- All weights **SHALL** be configurable. A deviation from routine **SHOULD** occur only when its combined benefit exceeds the routine-deviation cost.
- Seasonality and promotions are scores only (INV-15). A promotions cookbook in Mealie is NTH-02.
- **Routines.** *Explicit preferences* are planner rules, hence hard, and always override *learned routines*, which **SHALL** remain soft scores.
- **Routine learning [P2].** Learned routines **SHOULD** use simple frequency statistics (no ML framework), with confidence based on observation count, recency, consistency and acceptance history. Phase 2 learns meal characteristics (tags) per day and meal type; from Phase 3, order history adds retailer combinations, delivery days and windows and preferred SKUs.

#### 4.1.4 Planner algorithm

The planner **SHALL** be a deterministic, slot-based heuristic; pools are small after rule filtering (typically 5–40 recipes per slot, 7–14 slots), so no solver framework is needed.

1. **Build pools** per slot; drop recipes already fixed elsewhere in the week.
2. **Order slots** by ascending pool size (most constrained first).
3. **Greedy fill** each slot with the highest-scoring candidate given the partial plan.
4. **Local improvement:** try replacing or swapping recipes of non-fixed slots, keeping only changes that improve the total score, for a bounded number of iterations.
5. **Explain:** record each slot's main score contributors so deviations from routine can be explained.

- Ties and optional jitter **SHALL** use a random generator seeded with the planning-run ID, so the same inputs produce the same plan. Week-to-week variety **SHALL** come from the recency penalty, not unseeded randomness.
- Replacing one meal (`replace_meal`) **SHALL** re-run steps 3–4 for that slot only, excluding the previous proposal and keeping all other slots fixed.
- The planner **SHALL** sit behind a `MealPlanner` interface so it can be replaced (e.g. Timefold, NTH-01) without changing pool construction, scoring inputs or Mealie integration.

### 4.2 Purchase policies and need prediction

#### 4.2.1 Purchase policies

Every relevant Food **SHALL** have an effective `PurchasePolicy`:

| Policy    | Behaviour                                                         | Typical examples                                                          | Phase |
| --------- | ----------------------------------------------------------------- | ------------------------------------------------------------------------- | ----- |
| `AUTO`    | Add the required quantity automatically.                          | Fresh vegetables, meat and fish; recipe-specific fresh ingredients        | P1    |
| `CHECK`   | Ask whether it needs to be bought; all questions in one interaction. | Pasta, rice, flour, olive oil, passata, spices                          | P1    |
| `PREDICT` | Decide from historical behaviour whether it is likely needed.     | Yoghurt, milk, fruit, Coca-Cola, dishwasher tablets, detergent, toilet paper | P2 |

#### 4.2.2 Policy resolution

1. The Food's own `groceries.purchasePolicy` extra **SHALL** take precedence;
2. otherwise the configured default for the Food's label (e.g. `Pasta & grains`, `Canned & jars`, `Spices & condiments` → `CHECK`);
3. otherwise `AUTO`.

Before Phase 2, a `PREDICT` Food occurring in a recipe **SHALL** be treated as `CHECK`. Where policies are stored is OQ-02.

#### 4.2.3 Need prediction [P2]

A `NeedModel` (§3.5) **SHALL** be maintained per `PREDICT` Food to approximate whether it probably needs replenishment, without stock tracking or consumption events.

Observations: suggestion accepted/rejected, manual shopping-list additions, items checked off in Mealie, time since last purchase (P2); retailer purchase history and quantities (P3). Without retailer integration, a checked-off Mealie item **SHALL** count as a purchase.

| Confidence  | Meaning                  | Behaviour                                         |
| ----------- | ------------------------ | ------------------------------------------------- |
| `UNKNOWN`   | Insufficient evidence.   | **SHOULD NOT** be suggested.                      |
| `SUGGEST`   | Some evidence of need.   | **MAY** be shown as an unchecked suggestion.      |
| `PRESELECT` | Strong evidence of need. | **SHOULD** be presented selected by default.      |

Adding products without asking (`AUTO_ADD`) is NTH-06; promotion-driven stock-up is NTH-05.

### 4.3 Shopping list

#### 4.3.1 Generation

After meal-plan confirmation: recipe ingredients → scale → aggregate → apply purchase policy → `AUTO` items, `CHECK` questions and `PREDICT` suggestions [P2].

- **Scaling.** Quantities **SHALL** be scaled by `householdServings / recipeServings`. Without a servings value, quantities are used unscaled and the recipe is reported as `RECIPE_CLASSIFICATION_REQUIRED`.
- **Aggregation.** Requirements for the same Food **SHALL** be summed when units are identical or convertible within a dimension (mass, volume, count), e.g. broccoli 200 g + 350 g → 550 g. Incompatible units remain separate lines.
- **Unparsed ingredients.** An ingredient without a Food **SHALL** be added as a managed note item with its original text and reported once as `UNPARSED_INGREDIENT`.

#### 4.3.2 List semantics

- The configured Mealie shopping list **SHALL** represent household purchase *intent*, never retailer allocation; the retailer split exists only in Grocery Core (INV-06).
- The finalized list is the boundary between *what to buy* (Phases 1–2) and *where and how to buy* (Phases 3–4). In Phases 1–2 it is the end product and the household shops manually from Mealie. From Phase 3 a `ProcurementRun` **SHALL** snapshot it, and retailer optimization operates on that snapshot.

#### 4.3.3 Managed items

Items created by Grocery Core **SHALL** carry `{"groceries": {"managed": true, "planningRunId": "2026-W42", "origin": "meal-plan"}}`, with origin `meal-plan`, `check-answer`, `prediction` [P2] or `manual-agent`. Grocery Core **SHALL** record what it last wrote for each managed item and reconcile on re-generation:

| Situation                                                  | Action                                      |
| ---------------------------------------------------------- | ------------------------------------------- |
| Managed, unchanged by the user, still required             | Update the quantity if needed.              |
| Managed, unchanged by the user, no longer required         | Remove it.                                  |
| Managed, changed by the user (quantity, note, checked)     | Leave it; the user's version wins.          |
| Created by the user                                        | Never modify or delete it.                  |
| Required Food already present as a user-created item       | Do not add a duplicate; report the overlap. Mealie would otherwise merge the new item into the user's. |

#### 4.3.4 Manual changes

Human modifications **SHALL** take precedence (INV-05). Grocery Core **SHALL** re-read the meal plan and shopping list before list finalization, procurement optimization [P3], order preparation [P3] and order confirmation [P3], and recalculate where needed.

### 4.4 Retailer integration and market data [P3]

#### 4.4.1 Retailer provider SPI

Grocery Core **SHALL** define its own retailer abstraction. Conceptually:

```java
interface RetailerProvider {
    Retailer retailer();
    Set<RetailerCapability> capabilities();
    ProductSearchResult searchProducts(ProductQuery query);
    List<Offer> getOffers(...);
    Optional<Basket> getBasket();
    Basket updateBasket(...);
    List<FulfillmentOption> getFulfillmentOptions(...);
    PreparedOrder prepareOrder(...);
    OrderResult confirmOrder(...);
    List<Purchase> getPurchaseHistory(...);
}
```

Capabilities: `CATALOG`, `PRODUCT_SEARCH`, `PUBLIC_PROMOTIONS`, `PERSONAL_PROMOTIONS`, `PURCHASE_HISTORY`, `BASKET_READ`, `BASKET_WRITE`, `FULFILLMENT_OPTIONS`, `ORDER_PREPARE`, `ORDER_CONFIRM`. A provider **SHALL** declare its capabilities; the Core **SHALL NOT** assume all are implemented.

**Implementation preference** per capability: (1) supported/licensed API, (2) stable private HTTP/GraphQL endpoint, (3) existing open-source integration, (4) deterministic browser automation with Playwright, (5) manual fallback. AI-driven browser navigation **SHALL NOT** be an integration mechanism (AI-assisted recovery is NTH-09).

#### 4.4.2 Albert Heijn provider [P3]

- **SHOULD** reuse `appie-go` and its protocol knowledge. The Go integration **MAY** run as a small local adapter process behind a narrow, stable interface.
- Required: `PRODUCT_SEARCH`, `PUBLIC_PROMOTIONS`, `PURCHASE_HISTORY` (where AH supports it), `BASKET_READ`, `BASKET_WRITE`, `FULFILLMENT_OPTIONS`, `ORDER_PREPARE`, `ORDER_CONFIRM`. `PERSONAL_PROMOTIONS` is NTH-13.

#### 4.4.3 Colruyt provider [P4]

- Catalog data **SHOULD** be imported from the BelgianNoise Colruyt dataset where sufficient, producing `RetailerProduct`, `PriceObservation` and `Promotion` records.
- Collect&Go transactions **SHALL** remain a separate concern (OQ-05). The provider **MAY** combine several technical sources behind one logical provider.

#### 4.4.4 Product mapping and resolution

Retailer products **SHALL** remain separate from Foods and are linked through `FoodRetailerMapping`. An unknown mapping **SHALL** be resolved by escalation: (1) existing mapping, (2) EAN match, (3) exact normalized match, (4) aliases, (5) deterministic fuzzy matching, (6) user choice from the top deterministic candidates, otherwise `UNKNOWN_PRODUCT_MAPPING`. A resolution **SHALL** be persisted so a Food is never resolved twice for the same retailer. Semantic/AI matching before step 6 is NTH-07.

#### 4.4.5 Prices and promotions

- Every observed price **SHOULD** be persisted as a `PriceObservation`; history **SHALL** support price trends and preferred retailer/product decisions (promotion-quality judgement is NTH-12).
- Retailer promotions **SHALL** be normalized to the types `PERCENT_DISCOUNT`, `FIXED_DISCOUNT`, `FIXED_BUNDLE_PRICE`, `BUY_X_GET_Y`, `NTH_ITEM_DISCOUNT`, `FIXED_UNIT_PRICE`. Promotion arithmetic **SHALL NOT** require AI; a promotion that cannot be normalized **SHALL** be ignored for pricing and reported as `UNKNOWN_PROMOTION`.
- Because a price **MAY** depend on retailer, fulfilment date and method, loyalty context and basket quantity, procurement **SHALL** use an *effective purchase cost*, not a catalog price.

### 4.5 Procurement and fulfilment [P3]

#### 4.5.1 Purchase requirements

Each finalized shopping-list item **SHALL** become a `PurchaseRequirement` in the procurement-run snapshot. Note items without a Food **SHALL** be excluded and listed as *buy manually* in the final summary.

#### 4.5.2 Candidate generation

For each requirement the Core **SHALL** derive `PurchaseCandidate`s via mapping → retailer product → price, offer and availability. A candidate **SHOULD** contain required packs, total and excess quantity, effective and unit price, promotion, retailer, availability and mapping confidence.

#### 4.5.3 Basket optimization

- **[P3]** With one retailer, choose the best candidate per requirement (pack size, price, promotion, preferred SKU).
- **[P4]** Evaluate the strategies `AH`, `Colruyt` and `AH + Colruyt` by deterministic enumeration and scoring; no second solver **SHALL** be introduced. Three-retailer plans are NTH-14.

Conceptually (lower is better):

```text
basketScore = productCost + fulfillmentCost + wasteCost + inconvenienceCost - promotionBenefit
```

Scoring **SHALL** consider item prices, promotion mechanics, pack sizes and excess quantity, minimum basket, delivery and service fees, availability, number of retailers [P4] and historical retailer preference.

**Convenience [P4].** Inconvenience (an additional retailer, an unusual pickup or delivery day, a large deviation from the preferred retailer, manual action) **SHALL** be a configurable monetary-equivalent penalty, e.g. additional retailer = €5. It represents household effort, not money.

#### 4.5.4 Fulfilment selection

After basket optimization, fulfilment options **SHALL** be requested and **SHOULD** be ranked by preferred weekdays and windows, historical selection, fee, promotion validity, required-by date (first planned use) and routine stability. The user **SHOULD** normally see one recommendation and at most a few alternatives. Calendar-aware ranking is NTH-04.

### 4.6 Ordering [P3]

- Ordering **SHALL** be split into `prepareOrder()` and `confirmOrder()`. Preparation **MAY** create/update the basket, reserve a slot, validate availability, calculate the final price and detect substitutions, but **SHALL NOT** perform the irreversible purchase where avoidable.
- Before `confirmOrder()`, the user **SHALL** receive a summary with at least: retailers, item count, basket totals, service/delivery fees, final total, fulfilment moments, significant substitutions and deviations, and items to buy manually. Explicit confirmation by an order approver (§5.4) **SHALL** be required (INV-10).
- After a successful order the system **SHALL** create an `OrderRecord` with final prices and purchased SKUs, record `NeedModel` observations, reinforce preferred mappings, update retailer and fulfilment history and check off the corresponding Mealie items. No consumption registration **SHALL** be required afterwards.

### 4.7 Unresolved problems

Situations the deterministic system cannot resolve **SHALL** be recorded as an `UnresolvedProblem` and surfaced in the chat.

| Type                             | Raised by                                           | Phase |
| -------------------------------- | --------------------------------------------------- | ----- |
| `EMPTY_CANDIDATE_POOL`           | Planner (§4.1.1)                                    | P1    |
| `RECIPE_CLASSIFICATION_REQUIRED` | Metadata check (§3.3), scaling (§4.3.1)             | P1    |
| `UNPARSED_INGREDIENT`            | Shopping-list generation (§4.3.1)                   | P1    |
| `UNKNOWN_PRODUCT_MAPPING`        | Product resolution (§4.4.4)                         | P3    |
| `UNKNOWN_PROMOTION`              | Promotion normalization (§4.4.5)                    | P3    |
| `RETAILER_INTEGRATION_FAILURE`   | Retailer providers                                  | P3    |
| `AMBIGUOUS_SUBSTITUTION`         | Order preparation (§4.6)                            | P3    |

Problems are resolved by the user (AI assistance is NTH-07, NTH-08, NTH-09). A resolution **SHALL** become durable deterministic state (a mapping, a tag, a parsed ingredient) so the problem does not recur (INV-08).

---

## 5. Conversational interface

### 5.1 Chat channel

The household **SHALL** interact with the system through a **Telegram bot** in one household group chat.

- The bot is created with @BotFather; its identity is the bot token (no phone number).
- Grocery Core **SHALL** use the official Bot API through an established client library, never unofficial user-account automation, and **SHALL** receive updates by **long polling** (no inbound port, public URL or TLS endpoint).
- Telegram is the only chat channel in the target design; Mealie remains the visual application.

**Rationale.** Telegram is free, needs no business verification, lets the bot start a conversation at any time and offers inline buttons that can be edited in place, which covers every interaction in Appendix A. Alternatives rejected:

| Alternative                  | Reason                                                                                                      |
| ---------------------------- | ----------------------------------------------------------------------------------------------------------- |
| WhatsApp Business Cloud API  | Meta Business setup; 24-hour window needs pre-approved templates for the weekly message; max. 3 buttons; no multi-select. |
| WhatsApp unofficial libraries| Violates terms; ban risk; unstable sessions; no buttons.                                                    |
| Facebook Messenger           | Requires a Facebook Page; same 24-hour restriction.                                                         |
| Home Assistant notifications | Not a conversation; no multi-select. **MAY** be added later as a notification-only adapter.                 |

### 5.2 Household group and access control

- The bot **SHALL** belong to exactly one household group, identified by the configured chat ID. Updates from any other chat **SHALL** be ignored; joining other groups **SHOULD** be disabled in @BotFather (`/setjoingroups`).
- Only allowlisted Telegram user IDs **SHALL** be able to press buttons or issue requests; others get a short rejection notice.
- The bot **SHALL** respond only to button presses, `/` commands, replies to its own messages and mentions. Other group conversation **SHALL** be ignored and **SHALL NOT** be sent to an LLM. Group privacy mode **MAY** be disabled to recognize mentions and replies reliably.
- All members see the same messages; any allowlisted member **MAY** answer and the first accepted answer wins. The bot **SHALL** then edit the message to show the outcome and who gave it (e.g. *Confirmed by Stein*) and remove the buttons.

### 5.3 Interaction primitives

The workflow **SHALL** express every interaction as a channel-independent primitive, rendered by the Telegram adapter:

| Primitive   | Purpose                                     | Telegram rendering                                                              |
| ----------- | ------------------------------------------- | ------------------------------------------------------------------------------- |
| `Notice`    | Information, no answer required.            | Text message, optionally silent.                                                |
| `Choice`    | Pick one option (e.g. `[Looks good]`).      | Inline keyboard buttons.                                                        |
| `Checklist` | Toggle several items, then confirm.         | One toggle button per item (☐/☑) plus `[Confirm]`; the message is edited per toggle. |
| `Summary`   | Amounts and tables.                         | Aligned monospace block (Telegram has no tables).                               |
| `Link`      | Open the matching Mealie page.              | URL button.                                                                     |
| `FreeText`  | Ask an open question.                       | Message with a *force reply* prompt.                                            |

- Checklist preselections **SHALL** be shown ticked; a checklist is shared state, so every member sees the same ticks.
- Messages **SHALL** stay within Telegram's 4,096-character limit; longer content is summarized with a `Link` to Mealie.
- Messages that need an answer **SHALL** notify the group; purely informative messages **SHOULD** be silent.

### 5.4 Interaction handling

- Button presses and commands **SHALL** invoke the corresponding Grocery Core operation (§5.5) directly, **without** an LLM call, so a button-only weekly run needs no LLM (INV-07). Free-text messages addressed to the bot (e.g. *swap Wednesday for something with fish*) **SHALL** go to the agent.
- Every message with buttons **SHALL** be linked to a persisted `ChatInteraction`. Because callback data is limited to 64 bytes, it **SHALL** be an opaque reference to that interaction, never the payload.
- Presses on an outdated interaction (older run, or a state that has moved on) **SHALL** be rejected with a notice and the stale buttons removed. Duplicate or concurrent presses **SHALL** be idempotent and never cause a double transition.
- Order confirmation **SHALL** only be accepted from configured order approvers [P3].
- If Telegram is unreachable, outgoing messages **SHALL** be retried while the workflow waits in its persisted state.
- Telegram-specific code **SHALL** stay behind a `ChatChannel` port in the `chat` module; workflow and agent use only the primitives in §5.3, so another channel **MAY** be added as an adapter later.

### 5.5 Agent

The agent handles free-text requests. It **SHALL** operate only through high-level Grocery Core operations and **SHALL NOT** touch database tables, retailer APIs, raw catalogs or credentials. Workflow state lives in Grocery Core, so the agent can be restarted or replaced at any time. The agent runtime is OQ-07.

| Tool                        | Purpose                                                   | Phase |
| --------------------------- | --------------------------------------------------------- | ----- |
| `plan_week`                 | Create or refresh the proposed plan.                      | P1    |
| `get_week_summary`          | Summarize the proposal, including deviations and reasons. | P1    |
| `replace_meal`              | Re-plan one slot (§4.1.4).                                | P1    |
| `finalize_meal_plan`        | Confirm the plan.                                         | P1    |
| `get_stock_questions`       | Return the batched `CHECK` questions.                     | P1    |
| `submit_stock_answers`      | Submit all `CHECK` answers at once.                       | P1    |
| `finalize_shopping_list`    | Write and finalize the Mealie shopping list.              | P1    |
| `get_unresolved_problems`   | List open problems.                                       | P1    |
| `resolve_problem`           | Record the user's resolution of a problem.                | P1    |
| `set_preference`            | Change Grocery Core settings (targets, weights).          | P1    |
| `get_recurring_suggestions` | Return `PREDICT` suggestions with preselection.           | P2    |
| `submit_recurring_answers`  | Accept or reject suggestions.                             | P2    |
| `get_procurement_plan`      | Return the recommended basket(s).                         | P3    |
| `get_fulfillment_options`   | Return the recommended slot and alternatives.             | P3    |
| `select_fulfillment`        | Choose a fulfilment option.                               | P3    |
| `prepare_orders`            | Prepare the order(s).                                     | P3    |
| `confirm_orders`            | Confirm prepared order(s) after explicit approval.        | P3    |
| `optimize_orders`           | Re-run multi-retailer optimization with changed inputs.   | P4    |

Tools **SHOULD** be meaningful domain operations, not low-level CRUD. Explicit recipe preferences are edited in Mealie planner rules, not via `set_preference`.

### 5.6 UX requirements

The conversation **SHALL** present recommendations rather than raw configuration, batch questions, preselect answers from learned behaviour [P2], explain deviations from routine but not routine decisions, offer detailed alternatives only on request, and preserve manual Mealie edits. Users **SHOULD NOT** need to understand the planner or optimizer. Example conversations are in Appendix A.

---

## 6. Weekly workflow

Each planning run moves through the following persisted states:

| State                         | Phase | Activities                                                                                                                                                                                                              |
| ----------------------------- | ----- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `PREPARING`                   | P1    | Scheduled trigger on the planning day. Refresh prices and promotions [P3]; load planner rules, recipes and the meal plan (week + lookback); build pools; compute routines and seasonality [P2]; run the planner; write the proposal to Mealie (OQ-04). |
| `MEAL_PLAN_PROPOSED`          | P1    | Proposal posted to the group; members approve or replace meals.                                                                                                                                                        |
| `MEAL_PLAN_CONFIRMED`         | P1    | Ingredients read, scaled, aggregated and run through purchase policies (§4.3.1).                                                                                                                                        |
| `STOCK_CONFIRMATION_REQUIRED` | P1    | `CHECK` questions and `PREDICT` suggestions [P2] sent as one checklist.                                                                                                                                                  |
| `SHOPPING_LIST_FINALIZED`     | P1    | Answers applied and reconciled with the Mealie list (§4.3.3). **End state in Phases 1–2.**                                                                                                                              |
| `PROCUREMENT_OPTIMIZED`       | P3    | Requirements snapshotted; products resolved; prices, promotions and availability fetched; candidates generated; strategies enumerated [P4] and ranked; recommendation presented.                                        |
| `FULFILLMENT_SELECTED`        | P3    | Fulfilment options retrieved, ranked and chosen.                                                                                                                                                                         |
| `ORDER_PREPARED`              | P3    | Retailer baskets updated, final prices validated, `prepareOrder()` executed.                                                                                                                                            |
| `ORDER_CONFIRMATION_REQUIRED` | P3    | Final summary shown; waiting for an approver.                                                                                                                                                                           |
| `ORDERED`                     | P3    | `confirmOrder()` executed; Mealie and history reconciled (§4.6).                                                                                                                                                        |

The workflow **MAY** pause for hours or days between states. State **SHALL** therefore be persisted, and the workflow **SHALL NOT** depend on an in-memory agent conversation.

---

## 7. Non-functional requirements

### 7.1 Configuration

Settings **SHALL** be persisted and editable through `set_preference` or configuration files.

| Setting                          | Default                       | Phase |
| -------------------------------- | ----------------------------- | ----- |
| Planning day and time            | Saturday 09:00                | P1    |
| Planning week                    | 7 days after the planning day | P1    |
| Slots per weekday                | Dinner every day              | P1    |
| Household servings               | 2                             | P1    |
| Mealie shopping list             | (required)                    | P1    |
| Mealie base URL for links        | (required)                    | P1    |
| Telegram group chat ID           | (required)                    | P1    |
| Allowed Telegram user IDs        | (required)                    | P1    |
| Recency lookback                 | 4 weeks                       | P1    |
| Weekly targets                   | Fish ≥ 1, Vegetarian ≥ 2      | P1    |
| Max repeats per carbohydrate tag | 2                             | P1    |
| Default policy per Food label    | See §4.2.2                    | P1    |
| Scoring weights                  | Implementation defaults       | P1    |
| Prediction thresholds            | Implementation defaults       | P2    |
| Preferred fulfilment windows     | (none)                        | P3    |
| Order approvers                  | All allowed Telegram users    | P3    |
| Convenience penalties            | Additional retailer = €5      | P4    |

### 7.2 Security

- Mealie and retailer credentials and the Telegram bot token **SHALL** be stored outside source code (environment-injected secrets, encrypted configuration or a local secret store) and **SHALL NOT** enter LLM prompts (INV-12).
- Authenticated retailer sessions **SHOULD** be persisted securely where supported.
- Chat access control is specified in §5.2.

### 7.3 Reliability

Failure of one subsystem **SHALL** degrade gracefully; meal planning **SHALL NOT** depend on retailer checkout (INV-13).

| Failure                  | Degraded behaviour                                                                     | Phase |
| ------------------------ | -------------------------------------------------------------------------------------- | ----- |
| Grocery Core unavailable | Household plans with Mealie's random button; rules still apply.                        | P1    |
| Empty candidate pool     | Slot left empty and reported; other slots planned.                                     | P1    |
| Agent / LLM unavailable  | Buttons keep working; only free text is unavailable; plan and list editable in Mealie. | P1    |
| Telegram unreachable     | Messages retried; workflow waits in its persisted state; Mealie remains usable.        | P1    |
| Retailer ordering broken | The Mealie shopping list remains valid for manual shopping.                            | P3    |
| One retailer unavailable | Optimize with the remaining retailer.                                                  | P4    |

Operations **SHALL** be idempotent where practical: re-planning a week **SHALL NOT** duplicate meal-plan entries; re-generating the list **SHALL NOT** duplicate managed items; re-running procurement **SHALL NOT** place an order; order confirmation **SHALL** use explicit order state to prevent duplicate purchases.

### 7.4 Observability

The application **SHOULD** expose structured logs, health endpoints, job status, last successful Mealie synchronization, and retailer integration status and last market refresh [P3]. Spring Boot Actuator **SHOULD** suffice; no separate observability platform is required.

### 7.5 LLM efficiency

Routine weekly execution **SHOULD** use minimal LLM tokens. Prompts **SHALL NOT** contain complete recipe or retailer catalogs, price histories or order histories; tools **SHALL** return precomputed summaries and candidate sets.

---

## 8. Architecture

### 8.1 Deployment

```text
Docker Compose
├── mealie
├── grocery-core     (includes Telegram long-polling bot and, optionally, Playwright)
├── postgres
└── ah-adapter       [P3] optional separate process
```

The Telegram bot **SHALL** run inside Grocery Core and needs no separate container or inbound network access. Kubernetes **SHALL NOT** be required.

### 8.2 Module structure

Grocery Core **SHALL** be a modular monolith. Module dependencies **SHOULD** follow the domain flow rather than arbitrary cross-references.

```text
grocery
├── mealie          [P1]  API client, rules, pools, sync
├── preferences     [P1]  configuration and weights
├── planning        [P1]  MealPlanner, scoring
├── shoppinglist    [P1]  aggregation, policies, reconciliation
├── agent           [P1]  tool endpoints, free-text handling
├── chat            [P1]  ChatChannel port, interactions, Telegram adapter
├── jobs            [P1]  scheduling, workflow state
├── needs           [P2]  NeedModel, suggestions
├── routines        [P2]  learned routines
├── seasonality     [P2]
├── market          [P3]  prices, promotions
├── retailer        [P3]  ah [P3], colruyt [P4]
├── procurement     [P3]
├── fulfillment     [P3]
└── ordering        [P3]
```

### 8.3 Persistence

PostgreSQL **SHALL** hold only Grocery Core-owned state; the tables per entity and phase are listed in §3.5. Mealie entities (`recipe`, `food`, `unit`, `planner_rule`, `meal_plan`, `shopping_list`, `shopping_list_item`) **SHALL NOT** be duplicated.

---

## 9. Delivery plan

### 9.1 Phase 1 — MVP: Mealie-first planning

**Goal.** On the planning day, turn the household's existing Mealie library into a sensible next-week meal plan and a mostly complete Mealie shopping list, with only a short confirmation.

**Scope.** §3; §4.1 (P1 terms); §4.2.1–4.2.2 (`AUTO`, `CHECK`); §4.3; §4.7; §5; §6 up to `SHOPPING_LIST_FINALIZED`; §7; §8. **Out of scope:** need prediction, learned routines, seasonality, retailers.

**Acceptance criteria**

- [ ] Grocery Core authenticates against Mealie and reads recipes, Foods, Units, tags, categories and planner rules.
- [ ] For every slot, the candidate pool equals the set Mealie's random endpoint would draw from (verified by test).
- [ ] Existing meal-plan entries are preserved and count towards weekly targets.
- [ ] A seven-day plan meets the configured weekly targets when feasible, without repeats within the week and with recent recipes penalized.
- [ ] The same inputs and run ID produce the same plan.
- [ ] Empty pools and unclassified recipes are reported, not guessed.
- [ ] Generated meals appear in Mealie.
- [ ] Ingredients are scaled to household servings and aggregated by Food.
- [ ] `AUTO` and `CHECK` work, including label defaults; unparsed ingredients appear as reported note items.
- [ ] Re-generating the shopping list creates no duplicates and never touches user-created or user-modified items.
- [ ] The run starts on the configured planning day and its state survives restarts.
- [ ] The bot posts the proposal to the household group; members approve or change the plan and answer stock checks with buttons.
- [ ] Other chats and non-allowlisted users are ignored; outdated and duplicate presses cause no state change.
- [ ] A button-only weekly run makes no LLM call; LLM calls occur only for free-text requests.

**Success measures** (**SHOULD**, over four consecutive weeks): weekly interaction under 5 minutes; at least 70 % of proposed meals accepted unchanged; at most 3 items per week added manually to the list.

### 9.2 Phase 2 — Recurring needs and routines

**Goal.** The list also covers recurring non-recipe products, and the plan reflects learned habits and the season.

**Scope.** §4.1.3 (routine and seasonal terms, routine learning); §4.2 (`PREDICT`, need prediction).

**Acceptance criteria**

- [ ] Recurring products can be Mealie Foods with policy `PREDICT`.
- [ ] `NeedModel` is persisted and updated from suggestion answers, manual additions and checked-off items; reorder intervals are calculated.
- [ ] `SUGGEST` and `PRESELECT` behaviour works.
- [ ] Learned meal routines are calculated per day and meal type, contribute to scoring, and deviations are explained.
- [ ] Seasonality contributes to planning.

### 9.3 Phase 3 — Albert Heijn ordering

**Goal.** The first complete vertical slice: a finalized Mealie shopping list becomes an AH order after explicit approval.

**Scope.** §4.1.3 (promotion term); §4.4 (AH); §4.5 (single retailer); §4.6; §6 to `ORDERED`.

**Acceptance criteria**

- [ ] Foods can be mapped to AH products, with user choice for ambiguous mappings.
- [ ] AH product search works; effective prices are retrieved and stored as observations.
- [ ] Bonus promotions are normalized and contribute to meal planning.
- [ ] Basket contents can be manipulated.
- [ ] Purchase history is imported where supported and feeds `NeedModel`.
- [ ] Fulfilment options are retrieved (API or Playwright fallback) and ranked.
- [ ] A complete Mealie shopping list becomes a prepared AH order; explicit confirmation is required to execute it.
- [ ] After ordering, Mealie items are checked off and history is recorded.

### 9.4 Phase 4 — Multi-retailer optimization

**Goal.** Split requirements across AH and Colruyt only when the benefit clearly exceeds the inconvenience.

**Scope.** §4.4.3; §4.5 (P4 parts).

**Acceptance criteria**

- [ ] Colruyt catalog data is imported and mappings can be created.
- [ ] Prices compare across AH and Colruyt; Colruyt promotions normalize to the shared model.
- [ ] The strategies AH, Colruyt and AH + Colruyt are evaluated, including delivery/service costs and convenience penalties.
- [ ] Collect&Go fulfilment can be proposed.
- [ ] The agent can explain why a one- or two-retailer plan was chosen.

### 9.5 Nice-to-have backlog

Not part of the target design. Each item **SHOULD** be justified by an observed limitation before it is built.

| ID     | Item                                    | Description                                                                                                                                                                                  | Earliest after |
| ------ | --------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------- |
| NTH-01 | Timefold planner                        | Replace the heuristic behind `MealPlanner` if the constraint set outgrows greedy + local search.                                                                                              | P1             |
| NTH-02 | Promotions cookbook                     | A Grocery Core-maintained Mealie cookbook (e.g. *Good value this week*) of recipes using promoted Foods. Browsing only, never a rule.                                                         | P3             |
| NTH-03 | Delhaize provider                       | Private API with Pepesto as fallback and Playwright for gaps, invisible to the Core. Needs an integration spike (OQ-06).                                                                     | P4             |
| NTH-04 | Calendar integration                    | Filter and rank fulfilment options by household availability through a narrow `CalendarProvider` (busy intervals, create event). Only availability is exposed; if unavailable, no filtering. | P3             |
| NTH-05 | Promotion-driven stock-up               | For Foods with `stockUpAllowed`, suggest buying early when the price is historically low, weighing time to predicted need, shelf life, price history, pack size, consumption and mechanics. E.g. *"Coca-Cola isn't needed until next week, but the price is among the lowest in six months. Add two packs now?"* | P3 |
| NTH-06 | `AUTO_ADD` confidence level             | Add highly predictable recurring products without asking; requires explicit permission per product.                                                                                          | P2             |
| NTH-07 | Semantic / AI product resolution        | Semantic and AI matching before asking the user (§4.4.4). AI **SHALL NOT** resolve the same mapping repeatedly.                                                                              | P3             |
| NTH-08 | AI recipe metadata completion           | Propose missing tags, servings or parsed ingredients for approval.                                                                                                                           | P1             |
| NTH-09 | AI retailer integration recovery        | Use AI to recover from integration failures; never the default mechanism.                                                                                                                    | P3             |
| NTH-10 | Numeric nutrition scoring               | Use Mealie nutrition values as soft scores.                                                                                                                                                  | P1             |
| NTH-11 | SKU and fulfilment habit learning       | Learn preferred SKUs, delivery days and windows statistically instead of from configuration.                                                                                                 | P3             |
| NTH-12 | Historical promotion quality            | Judge whether a promotion is genuinely good against price history.                                                                                                                           | P3             |
| NTH-13 | AH personal promotions                  | Use `PERSONAL_PROMOTIONS` in pricing.                                                                                                                                                        | P3             |
| NTH-14 | Three-retailer plans                    | Consider three-retailer strategies with a significant convenience penalty.                                                                                                                   | NTH-03         |
| NTH-15 | Promote learned routines to rules       | Offer to turn a stable learned routine into a planner rule, written only after approval.                                                                                                     | P2             |
| NTH-16 | Recipe-extras rule filters              | Use recipe extras (e.g. an effort score) in planner rules, if supported (OQ-08).                                                                                                             | P1             |
| NTH-17 | Leftovers planning                      | Plan a cook-once-eat-twice meal across two slots for `Leftovers-friendly` recipes.                                                                                                           | P1             |

---

## 10. Open issues

| ID    | Question                                                                                                                                                                                                                    | Blocks  | Status   |
| ----- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- | -------- |
| OQ-01 | **Mealie API surface.** Planner-rule behaviour is verified (§3.4.1). POC 1 verified against v3.28.0: Food extras, shopping-list item extras (stored as JSON strings), meal-plan entry CRUD, paginated `GET /api/recipes` query filters (pools equal the random button's). Mealie merges a new list item into an existing one with the same Food (§4.3.3). Still open: nutrition, substitutions. | Phase 1 | Partial  |
| OQ-02 | **Purchase-policy storage.** Per-Food overrides in Mealie extras, label defaults in Grocery Core settings (POC 3). The seeder writes only overrides.                                                                             | Phase 1 | Resolved |
| OQ-04 | **Proposed versus confirmed meals.** Write unconfirmed proposals to Mealie, or keep them in the `PlanningRun` until accepted? *Preferred:* write to Mealie if they can be clearly identified as proposed and safely reconciled. | Phase 1 | Open     |
| OQ-07 | **Agent runtime.** Select the agent host once the Phase 1 tool API exists; the framework **SHALL NOT** drive Grocery Core architecture. Independent of the chat channel: the agent only receives free text from the `chat` module. | Phase 1 | Open     |
| OQ-03 | **Household products in Mealie.** Does representing detergent, toilet paper etc. as Foods cause undesirable UI behaviour? If so, add a minimal `HouseholdProduct` concept for non-food items only — not pre-emptively.          | Phase 2 | Open     |
| OQ-05 | **Colruyt transactions.** Investigate authenticated Collect&Go operations separately from catalog acquisition.                                                                                                              | Phase 4 | Open     |
| OQ-06 | **Delhaize integration.** When NTH-03 is picked up, spike a private API, Pepesto and Playwright for search, promotions, baskets and fulfilment.                                                                              | NTH-03  | Deferred |
| OQ-08 | **Recipe extras in query filters.** Do Mealie query filters support `extras.key_name` / `extras.value`? If so, recipe metadata such as an effort score could be used in rules.                                              | NTH-16  | Open     |

---

## Appendix A — Example conversations

Messages in the household group. `[…]` are inline buttons, checkbox lists are `Checklist` interactions and tables are `Summary` blocks (§5.3).

### A.1 MVP week (Phase 1)

> **Next week is planned.** Monday pasta as usual, two fish meals and three vegetarian. Friday is a quick meal. Tuesday's slot is empty: no recipe matches your Tuesday rules. The full week is available in Mealie.
>
> `[Looks good]` `[Change something]`

> **A few stock checks**
> - [ ] Passata ×2
> - [ ] Rice
> - [ ] Olive oil
> - [ ] Spaghetti
>
> `[Confirm]`

> **Your shopping list is ready in Mealie** — 23 items. One ingredient in *Thai green curry* isn't linked to a food yet; I added it as a note.

### A.2 Mature week (after Phase 4)

> **Next week is planned.** I kept Monday pasta, Thursday fish and your usual quick Friday meal. Wednesday uses pumpkin because it is seasonal and currently well priced.
>
> `[Looks good]` `[Change something]`

> **A few stock checks**
> - [x] Passata ×2
> - [ ] Rice
> - [ ] Olive oil
> - [x] Spaghetti
>
> **Likely regulars**
> - [x] Greek yoghurt ×2
> - [x] Milk ×3
> - [ ] Coca-Cola Zero
>
> `[Confirm]`

> **Recommended shopping plan**
>
> | Retailer     | Total  |
> | ------------ | ------ |
> | Albert Heijn | €68.10 |
> | Colruyt      | €43.20 |
>
> Buying everything at AH would cost €6.40 more; the Colruyt pickup is on your usual Saturday morning. AH has your usual Thursday 18:00–20:00 delivery slot.
>
> `[Prepare orders]`

> **Ready to order**
>
> | Item      | Amount      |
> | --------- | ----------- |
> | Products  | €106.10     |
> | Fees      | €5.20       |
> | **Total** | **€111.30** |
>
> `[Place orders]`
