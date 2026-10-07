# Secrets: keuze en implementatie

_Beslist op 2026-10-07._

## Keuze

We bewaren alle wachtwoorden en tokens in **Bitwarden Secrets Manager** (gratis plan) en halen ze op met de `bws` CLI of de officiële GitHub Action. Bitwarden is de enige bron van waarheid: een geheim wijzigen doe je daar, nooit in een bestand dat je rondstuurt.

Het gratis plan ([bitwarden.com/help/secrets-manager-plans](https://bitwarden.com/help/secrets-manager-plans), nagekeken op 2026-10-07) geeft:

| | Gratis plan |
|---|---|
| Gebruikers | 2 |
| Projecten | 3 |
| Machine-accounts | 3 |
| Secrets | onbeperkt |

Let op: de prijspagina toont enkel de betaalde plannen (Teams, Enterprise); het gratis plan staat op de helppagina hierboven. Kijk die na voor je begint, want limieten veranderen.

**Waarom Bitwarden**
- Eén plek voor lokaal, de Claude-cloudsessies en de deploy naar de eigen server.
- `bws run -- <commando>` zet de secrets als omgevingsvariabelen, dus er staat geen platte `.env` met echte tokens op schijf.
- Er is een officiële GitHub Action (`bitwarden/sm-action`).
- Stein kent en gebruikt Bitwarden al.

**Alternatieven die we niet kozen:** Infisical en Doppler (ook gratis plan, maar een extra dienst om te leren), SOPS + age (gratis, secrets versleuteld in de repo, maar je beheert zelf de sleutel en de repo is publiek), alleen GitHub Secrets (werkt niet lokaal of in Claude-sessies).

## Indeling: 3 projecten, 3 machine-accounts

Het gratis plan heeft precies genoeg ruimte als we per omgeving één project en één machine-account maken.

| Project | Wie leest het (machine-account) | Inhoud |
|---|---|---|
| `groceries-dev` | `dev-laptop` (Stein lokaal) | test-bot token, LLM-key met lage limiet |
| `groceries-ci` | `claude-cloud` (Claude Code op het web) | aparte test-tokens, LLM-key met heel lage limiet |
| `groceries-prod` | `prod-deploy` (deploy-job op de server) | echte bot-token, Mealie-token van prod, prod LLM-key, Postgres-wachtwoord |

Regels:
- **Elke omgeving krijgt eigen tokens.** Lekt een test-token uit een cloudsessie, dan trek je enkel dat in, en prod draait door.
- **LLM-keys met een uitgavenlimiet** per key (in de console van de provider). Dat is meteen de kostenbewaking uit `plans/hosting-options.md`.
- **Een machine-account heeft enkel leesrechten op zijn eigen project.**
- Zelf log je in de Bitwarden-webapp in met je gewone account; de machine-accounts zijn enkel voor scripts en servers.
- We gebruiken de EU-cloud (`vault.bitwarden.eu`), zodat de data in de EU blijft. Dat moet je kiezen bij het aanmaken van het account.

### Welke secrets

Namen van secrets zijn ook de namen van de omgevingsvariabelen (`bws run` gebruikt de naam als variabele), dus hou ze POSIX-geldig: hoofdletters, cijfers en `_`.

| Secret | dev | ci | prod | Opmerking |
|---|---|---|---|---|
| `TELEGRAM_BOT_TOKEN` | ✓ | ✓ | ✓ | Liefst een aparte testbot voor dev/ci; prod is @boodschappen_buddy_bot |
| `TELEGRAM_CHAT_ID`, `TELEGRAM_ALLOWED_USER_IDS` | ✓ | ✓ | ✓ | Niet echt geheim, maar wel per omgeving |
| `ANTHROPIC_API_KEY` (of de key van het gekozen model) | ✓ | ✓ | ✓ | Eigen key per omgeving, met limiet |
| `MEALIE_API_TOKEN` | | | ✓ | Lokaal maakt `make seed` er zelf een aan in `.env`, dat blijft zo |
| `POSTGRES_PASSWORD`, `MEALIE_ADMIN_PASSWORD` | | | ✓ | Lokaal volstaan de defaults uit `.env.example` |

De niet-geheime instellingen (`MEALIE_PORT`, `TZ`, `MEALIE_VERSION`, …) blijven in `.env.example`/`.env`.

## Hoe het in de code past

De code hoeft niet te veranderen. `MealieConfig` en `chat/Demo.java` lezen eerst `.env` en laten **omgevingsvariabelen voorgaan**. `docker-compose.yml` vult `${VAR}` ook in vanuit de shell. Alles wat `bws run` zet, wint dus van wat in `.env` staat. Hou nieuwe code bij die regel: lees configuratie uit de omgeving, `.env` is enkel een fallback.

## Implementatie, stap voor stap

### 1. Bitwarden opzetten (eenmalig, in de webapp)

1. Maak op [vault.bitwarden.eu](https://vault.bitwarden.eu) een organisatie aan en activeer Secrets Manager met het gratis plan.
2. Maak de projecten `groceries-dev`, `groceries-ci` en `groceries-prod`.
3. Maak de machine-accounts `dev-laptop`, `claude-cloud` en `prod-deploy`, geef elk **leesrecht** op zijn project en maak voor elk een access token. Je ziet een token maar één keer: bewaar het meteen in je gewone Bitwarden-kluis.
4. Zet de secrets uit de tabel hierboven in de juiste projecten.
5. Trek de huidige Telegram-token in via @BotFather (`/revoke`), want die stond eerder in de chat, en zet de nieuwe enkel in `groceries-prod`.

### 2. Lokaal (Stein)

1. Installeer `bws` (zie [de CLI-docs](https://bitwarden.com/help/secrets-manager-cli/)), en richt hem op de EU-cloud:
   ```sh
   bws config server-base https://vault.bitwarden.eu
   ```
2. Zet de token van `dev-laptop` in je shell, bijvoorbeeld in `~/.zshrc` of via je OS-sleutelhanger, nooit in de repo:
   ```sh
   export BWS_ACCESS_TOKEN=...
   ```
3. Draai commando's via `bws run`:
   ```sh
   bws run -- make up
   cd core && bws run -- ./gradlew -q :chat:run   # zoals in core/README.md
   ```
4. Haal de echte tokens uit je lokale `.env`. Daar blijven enkel de dev-defaults en de `MEALIE_API_TOKEN` die `make seed` schrijft.

Later handig (niet nodig om te starten): `make` targets die automatisch `bws run` gebruiken als `BWS_ACCESS_TOKEN` gezet is, en een korte sectie in de README.

### 3. Claude Code-cloudsessies

Claude-sessies op het web krijgen hun secrets via een **cloud environment** (Project settings → Cloud environment → Add cloud environment).

1. Zet daar als omgevingsvariabele `BWS_ACCESS_TOKEN` met de token van `claude-cloud`.
2. Zorg dat de netwerkinstelling `vault.bitwarden.eu`, `api.bitwarden.eu` en `identity.bitwarden.eu` toelaat, plus de API van het LLM en `api.telegram.org`.
3. Laat het setup-script van die omgeving `bws` installeren (een Python-script of een paar regels shell, geen domeinlogica).
4. Claude draait dan tests met `bws run -- ...`, net zoals lokaal.

Eenvoudiger alternatief: zet de test-tokens rechtstreeks als omgevingsvariabelen in die cloud environment. Dat werkt meteen, maar dan moet je bij rotatie op twee plaatsen aanpassen.

Plak nooit een token in de chat; een nieuwe sessie pikt de omgevingsvariabelen vanzelf op.

### 4. Deploy naar de eigen server

Volgens `plans/hosting-options.md` bouwt GitHub Actions de image naar GHCR en draait een deploy-job (self-hosted runner in de private deploy-repo, of Tailscale + SSH) `docker compose pull && up -d`.

1. Zet in GitHub (in het `production` environment van de repo die deployt) één secret: `BWS_ACCESS_TOKEN` met de token van `prod-deploy`. Dat is de enige secret die in GitHub hoeft te staan, naast die voor Tailscale/SSH als je die route neemt.
2. Haal in de deploy-job de secrets op en start compose met die omgeving:
   ```yaml
   - uses: bitwarden/sm-action@v3
     with:
       access_token: ${{ secrets.BWS_ACCESS_TOKEN }}
       cloud_region: eu
       secrets: |
         <secret-id> > TELEGRAM_BOT_TOKEN
         <secret-id> > ANTHROPIC_API_KEY
         <secret-id> > MEALIE_API_TOKEN
         <secret-id> > POSTGRES_PASSWORD
   - run: docker compose -f docker-compose.yml -f compose.prod.yml up -d
   ```
   De action maskeert de waarden in de logs. De secret-id's zijn geen geheimen en mogen in de workflow staan.
3. Of, als `bws` op de server staat: `bws run --project-id <prod-project-id> -- docker compose -f docker-compose.yml -f compose.prod.yml up -d`.
4. `compose.prod.yml` geeft de variabelen door aan de containers via `environment:` (zoals `docker-compose.yml` nu al doet voor Postgres). Er komt dan geen `.env` met echte tokens op de server.

Let op: docker houdt omgevingsvariabelen bij in de containerconfig (`docker inspect`). Op een eigen server waar enkel Stein op kan is dat aanvaardbaar.

## Wat er niet mag

- Echte tokens in de repo, in `.env.example`, in issues, PR's of in de chat.
- Prod-tokens in de `claude-cloud`- of `dev-laptop`-machine-accounts.
- Eén LLM-key delen over omgevingen.

## Rotatie

Een token vervangen: maak de nieuwe aan bij de provider, pas de waarde aan in Bitwarden, herstart wat hem gebruikt (lokaal opnieuw `bws run`, prod een nieuwe deploy), en trek dan de oude in. Verdacht gelekt? Eerst intrekken, dan pas vervangen.
