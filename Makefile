.DEFAULT_GOAL := help
COMPOSE := docker compose

.env:
	cp .env.example .env

.PHONY: help
help: ## Show this help
	@grep -E '^[a-z-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-10s\033[0m %s\n", $$1, $$2}'

.PHONY: setup
setup: up seed ## First-time setup: start Mealie and load the test dataset

.PHONY: up
up: .env ## Start Mealie + Postgres and wait until healthy
	$(COMPOSE) up -d --wait
	@echo "Mealie is running at http://localhost:$$(grep ^MEALIE_PORT .env | cut -d= -f2)"

.PHONY: down
down: ## Stop containers (data is kept)
	$(COMPOSE) down

.PHONY: seed
seed: .env ## Load labels, units, foods, categories, tags and 100 recipes (idempotent)
	$(COMPOSE) run --rm seed

.PHONY: seed-update
seed-update: .env ## Re-apply the dataset, overwriting seeded recipes that already exist
	$(COMPOSE) run --rm seed --update

.PHONY: check-data
check-data: .env ## Validate the dataset without touching Mealie
	$(COMPOSE) run --rm --no-deps seed --check

.PHONY: reset
reset: .env ## Wipe all Mealie data and seed again from scratch
	$(COMPOSE) down -v
	$(MAKE) setup

.PHONY: logs
logs: ## Follow Mealie logs
	$(COMPOSE) logs -f mealie
