# Hosting

Decided 2026-10-06/07. The app runs on a Linux server at home, using the same Docker Compose setup as development, and deploys through GitHub Actions with a self-hosted runner.

## Decision

| Topic | Choice |
|---|---|
| Where | An existing Linux server at home. Hosting costs €0; the LLM is the only variable cost. |
| How | Docker Compose: the same `docker-compose.yml` as dev, plus a production override. No Kubernetes. |
| Deploy | GitHub Actions. The public app repo builds and pushes the image; a **private deploy repo** with a **self-hosted runner** on the server rolls it out. |
| Telegram | Long polling (spec §5.1), so it needs no public URL and no open port. |
| Mealie access | Tailscale for the household, or a domain plus a Cloudflare Tunnel for a public URL with HTTPS. |
| Secrets | Bitwarden Secrets Manager (see `docs/secrets.md`), injected at deploy time. |
| Batch work | The weekly plan with the first suggestion, the promotion scanner and the `last_made` timeline job run as job entrypoints in the core image, started by a scheduler. |

### Why

- **Local/prod parity.** The same images and the same compose file run everywhere. Only environment variables and secrets differ.
- **Cost.** Cloud options were €10 to €60 per month for an always-on setup. Scale-to-zero on Cloud Run could reach ~€0, but it differs from local in four places (webhook, Cloud Run Jobs, hosted Postgres, a storage mount for Mealie).
- **Safety with a public repo.** A self-hosted runner on a public repo lets pull requests from outsiders run code on the server. Registering the runner only on a private deploy repo avoids that and keeps the app repo public.

### Options considered

| Option | Est. €/month excl. LLM | Why not |
|---|---|---|
| Google Cloud: Cloud Run + Cloud SQL + Vertex AI | 10 to 20 | Fixed database cost |
| Google Cloud scale to zero + Neon | 0 to 3 | Drifts from local |
| AWS: Fargate + RDS + Bedrock | 40 to 60 | Cost, many moving parts |
| Azure Container Apps + Foundry | 20 to 40 | Claude only in US regions |
| VPS (Hetzner) + Compose | ~5 | Fine, but the home server is free |

## Architecture rules that follow from this

1. **Config through environment only.** URLs, tokens and model ids come from env vars, with no profile-specific code paths for prod.
2. **All state in Postgres.** Conversations, pending questions and workflow state survive a container restart. This also keeps a later move to scale-to-zero possible.
3. **Batch work as entrypoints**, for example `java -jar core.jar --job=weekly-plan` or `--job=promotion-scan`. A job does its work, sends any Telegram message, and exits. Locally you run it with `docker compose run --rm core --job=weekly-plan`; in prod a scheduler starts it (Spring `@Scheduled` in the bot process, or a cron entry).
4. **Health endpoint** via Spring Boot Actuator, used by the compose healthcheck and the deploy check.

## Implementation steps

### 1. Prepare the server

- Install Docker in **rootless mode** for a dedicated user (e.g. `groceries`). Being in the `docker` group is effectively root.
- `ufw` denies all incoming traffic by default. SSH uses keys only. Enable `unattended-upgrades`.
- Set Docker log rotation in `daemon.json` (`"log-opts": {"max-size": "10m", "max-file": "3"}`).
- Install Tailscale and join your tailnet.
- Install the Bitwarden Secrets Manager CLI (`bws`) for the `groceries` user.

### 2. Create the private deploy repo (`groceriesagent-deploy`)

```
compose.prod.yml          production override (below)
.github/workflows/deploy.yml
scripts/backup.sh
```

`compose.prod.yml` sketch:

```yaml
services:
  core:
    image: ghcr.io/steinmaerivoet/groceries-core:${TAG}
    restart: unless-stopped
    user: "10001"
    read_only: true
    tmpfs: [/tmp]
    cap_drop: [ALL]
    security_opt: [no-new-privileges:true]
    mem_limit: 768m
  mealie:
    ports: !override
      - "127.0.0.1:9000:9000"   # or the Tailscale IP; never 0.0.0.0
    mem_limit: 1g
  postgres:
    mem_limit: 512m
```

Use `!override` on `ports`: Compose merges port lists from override files instead of replacing them. Docker also bypasses `ufw` for ports bound to all interfaces. The deploy workflow checks out the app repo at the same commit, so the base `docker-compose.yml` comes from there.

### 3. Register the self-hosted runner

- In the deploy repo, go to Settings → Actions → Runners and add a Linux runner as the `groceries` user. Give it the label `home`.
- Install it as a systemd service (`./svc.sh install groceries`).
- Register it only on the deploy repo, never on the public app repo.

### 4. Build workflow in the app repo

On push to `main`:
1. Run `./gradlew test`.
2. Build the core image and push it to GHCR with tag `${{ github.sha }}`. If the package is private, the server needs a read-only token for GHCR.
3. Send `repository_dispatch` (`event_type: deploy`, payload `{ "tag": "<sha>" }`) to the deploy repo. Use a fine-grained token scoped to that repo only, stored as an Actions secret. Workflows triggered by fork PRs cannot read it.

### 5. Deploy workflow in the deploy repo

```yaml
on:
  repository_dispatch: { types: [deploy] }
  workflow_dispatch: { inputs: { tag: { required: true } } }   # manual deploy or rollback
jobs:
  deploy:
    runs-on: [self-hosted, home]
    environment: production
    env:
      TAG: ${{ github.event.client_payload.tag || inputs.tag }}
    steps:
      - uses: actions/checkout@v4                     # deploy repo
      - uses: actions/checkout@v4                     # app repo at the same commit, for docker-compose.yml
        with: { repository: steinmaerivoet/groceriesagent, ref: ${{ env.TAG }}, path: app }
      - run: |
          bws run -- docker compose -f app/docker-compose.yml -f compose.prod.yml pull
          bws run -- docker compose -f app/docker-compose.yml -f compose.prod.yml up -d --wait
```

Rolling back means running the workflow by hand with an older tag.

### 6. Reach Mealie by name

- **Household only:** Tailscale on every phone and laptop, with Mealie at `http://<server>:9000` (MagicDNS). Free, and nothing is exposed to the internet.
- **Public URL:** buy a domain (~€10/year) and run `cloudflared` as a container in the stack, giving `mealie.<domain>` with HTTPS. No port forwarding, and it works with a changing home IP. Keep `ALLOW_SIGNUP=false` and set `BASE_URL`.

### 7. Backups

`scripts/backup.sh` runs nightly from cron (or a systemd timer). It runs `pg_dump`, copies the `mealie-data` volume, and sends both to another disk or cloud storage. Keep 14 days. Test a restore once.

### 8. Core changes this needs (later, in the integration step)

- A `Dockerfile` for the core (Spring Boot layered jar, non-root user `10001`).
- A `core` service in `docker-compose.yml` that builds locally (`build: ./core`).
- The `--job=` entrypoints and Actuator health from the rules above.

## Later, if needed

- More isolation: run the whole stack inside one VM (KVM/Proxmox) or an LXD container.
- Cloud instead of home: the same compose file on a ~€5 VPS, or Cloud Run with a Telegram webhook. Rules 2 and 3 above make either a deployment change, not a code change.
