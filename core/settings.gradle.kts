rootProject.name = "grocery-core"

// One subproject per module from spec §8.2. Each Phase 1 POC is its own module so it builds,
// tests and runs on its own; the `app` module (integration step) will wire them together.
include("contracts", "mealie", "planning", "shoppinglist", "chat", "agent")
