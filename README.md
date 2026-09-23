# Relay — AI Workflow Orchestrator

Relay runs operational workflows defined as graphs of **typed nodes**. Runs are triggered by
webhooks or API calls, execute **asynchronously on a durable queue + worker**, pause for **human
approvals**, call an **AI node whose output is validated against a JSON schema**, and **recover from
crashes without duplicating side effects**. Every run keeps a complete trace.

The design goal is a *reliable* engine: crash-resume with exactly-once side effects, approval gates
enforced by the engine (not the prompt), schema-checked AI output, idempotency keys, timeouts, and a
step cap — all observable through a simple console.

> Airtribe capstone. Problem statement and provided assets live in [`relay-capstone-pack/`](relay-capstone-pack).
> Implementation plan: [`docs/PLAN.md`](docs/PLAN.md) · verification: [`docs/VERIFICATION.md`](docs/VERIFICATION.md) · demo: [`docs/DEMO.md`](docs/DEMO.md).

## Stack
Java 21 · Spring Boot 3 · PostgreSQL 16 · Redis 7 · Flyway · Thymeleaf console. AI is behind a
mockable adapter (`AiProvider`) with a deterministic default (`MockAiProvider`) — no API key needed.

## Architecture

```
                  HTTP (REST + webhooks)
  client ───────────────────────────────► TriggerController ──┐
                                                               │ create Run (definition snapshot)
  console (Thymeleaf) ◄── ConsoleController                    │ + enqueue QueueJob (durable)
                                                               ▼
   Postgres  ◄──────────────────────────────────────  RunService ──► Redis list (fast dispatch)
   workflow / run / step / approval / queue_job                          │
        ▲                                                                 ▼
        │  persist-then-advance (each step committed          Worker loop (BRPOP) ──► RunEngine
        │  before the run pointer moves)                                            │
        └──────────────────────────  EngineStore  ◄──────────────────────  NodeExecutor registry
                                                                 http_request · condition · delay ·
   RecoveryService (startup + lease reclaim)                     notify · ai · approval · order_action
   QueuePoller (@Scheduled, re-dispatches due jobs)                                  │
                                                                        MockWorldClient (timeouts,
                                                                        Idempotency-Key) ──► mock world
```

- **Durable queue**: Redis is the low-latency dispatch list; the `queue_job` table is the durable
  ledger (survives restarts, carries `available_at` for delays and a lease for recovery). The API
  never runs a workflow inline.
- **Persist-then-advance**: `EngineStore` commits each step (output + idempotency key) *and* the
  run's next-node pointer in one transaction, so resume derives entirely from committed rows.
- **AI behind an adapter**: `AiProvider` → `MockAiProvider` (deterministic, schema-aware). A real
  provider can be added later as `@Primary` with no engine changes.

## Run state machine

```
                 enqueue                 dequeue / lease
   [*] ─────────► queued ─────────► running ──┬───────────────► succeeded ─► [*]
                    │                          │
                    │                          ├─ node fails (no retries left) ─► failed ─► [*]
                    │                          ├─ step cap exceeded ────────────► failed
                    │                          │
                    │                          ├─ delay node ─► (rescheduled) ─► running
                    │                          │
                    │  reject                  ▼   approve
              cancelled ◄──────────── waiting_approval ─────────► running
```

`delay` doesn't block a thread: it advances past itself and inserts a future-dated `queue_job`, so a
crash during a delay is just a pending job the poller picks up when due.

## Getting started

### Option A — Docker (everything in containers, recommended)

Prerequisite: Docker Desktop. One command builds the app image and starts Postgres, Redis, the mock
world, and Relay:

```bash
docker compose up --build            # Relay on http://localhost:8080  (console: /console)
```

`docker compose ps` shows `relay-app`, `relay-mockworld`, `relay-postgres`, `relay-redis`.
Stop with `docker compose down` (add `-v` to wipe the database volume).

To use a real AI model instead of the built-in mock, copy `.env.example` to `.env`, set
`RELAY_AI_PROVIDER=openrouter` + `RELAY_AI_API_KEY` + `RELAY_AI_MODEL`, and re-run
`docker compose up` — Compose reads `.env` automatically. `.env` is gitignored.

### Option B — Local dev (run the app from source)

Prerequisites: Java 21, Maven, Docker (for the datastores), Python 3.

```bash
docker compose up -d postgres redis                                  # just the datastores
python relay-capstone-pack/scripts/mock_world.py --port 9210         # separate terminal
mvn spring-boot:run                                                  # http://localhost:8080
```

### Configuration
Override via environment (see `.env.example`): `RELAY_AUTH_TOKEN` (default `relay-dev-token`, blank
disables auth), `RELAY_MOCK_WORLD_URL`, and the `RELAY_AI_*` provider settings. In Docker the app
reaches the datastores by service name (`postgres:5432`, `redis:6379`, `mockworld:9210`); seed
workflows that hardcode `http://localhost:9210` are rewritten to the configured mock-world URL on
load, so `http_request` nodes work inside containers too.

> **Ports**: Compose maps host **5433** (Postgres) and **6380** (Redis) to avoid clashing with a
> local install; the containers talk to each other on the standard internal ports.
> **Timezone**: the app pins the JVM to UTC (pgjdbc rejects the `Asia/Calcutta` legacy alias).

## API

Platform endpoints require `Authorization: Bearer <token>`; webhooks require `X-Relay-Secret`.
All errors are `{"error": {"message", "code"}}`.

| Method & path | Purpose |
|---|---|
| `GET /workflows` | list workflows |
| `POST /workflows` | create a draft (validated against the node catalog) |
| `GET /workflows/{id}` | fetch a workflow |
| `PUT /workflows/{id}` | update a draft (published are frozen → 409) |
| `POST /workflows/{id}/publish` | validate + freeze + publish |
| `POST /workflows/{id}/trigger` | manual trigger; body `{"input": {...}}` → `{{trigger.body}}` |
| `POST /hooks/{id}` | webhook trigger (secret-protected); body → `{{trigger.body}}` |
| `GET /runs/{id}` | run + full step trace |
| `GET /approvals?status=pending` | list approvals |
| `POST /approvals/{id}/approve` \| `/reject` | decide (approve resumes, reject cancels) |
| `POST /workflows/compile` | *(Good-To-Have)* natural-language → workflow (or refusal); `?save=true` stores a draft |
| `POST /workflows/compile/eval` | *(Good-To-Have)* run the labelled NL eval, report accuracy |

Triggers return `202 {"run_id": "..."}`. Console pages live under `/console`.

### Node types
`http_request` (timeout; non-GET carries an idempotency key), `condition`
(`equals/not_equals/greater_than/less_than/contains` → `on_true`/`on_false`), `delay` (durable,
non-blocking), `notify` (email/chat via mock world), `ai` (schema-validated, one repair retry),
`approval` (pause for a human), `order_action` (sensitive; **requires an approval earlier in the
run**). Templates are simple path lookups: `{{trigger.body.x}}`, `{{nodes.id.output.y}}`.

## Exactly-once recovery design

- **Stable idempotency keys** — every side effect uses `{run_id}:{node_id}`, constant across
  retries and resumes. The mock world replays a repeated key instead of re-executing.
- **Persist-then-advance** — a step's result and the run's next pointer commit together; the engine
  resumes purely from committed steps, re-running only the node that was in flight.
- **Crash window** — if a side effect fired but its step didn't commit, resume re-sends with the
  same key → the world absorbs it (replayed), so no duplicate.
- **Leases** — a claimed `queue_job` is leased; on restart `RecoveryService` reclaims orphaned
  leases and re-enqueues interrupted runs, and a scheduled task reclaims expired leases at runtime.
- **Guardrails independent of AI** — the approval gate (`order_action`) and the `max_steps` cap are
  enforced in the engine, so prompt injection can't skip an approval or loop forever.

Verified live: kill the worker mid-delay, restart, and `duplication_check.py` reports every side
effect executed exactly once. See [`docs/VERIFICATION.md`](docs/VERIFICATION.md).

## Testing

```bash
mvn test                                                  # unit tests (validation, templates, ai)
python relay-capstone-pack/scripts/smoke_test.py --url http://localhost:8080 --token relay-dev-token
python relay-capstone-pack/scripts/duplication_check.py --url http://localhost:9210   # after a kill-and-resume
```

## Known limitations
- **Testcontainers**: the persistence integration test is gated with `@EnabledIf(dockerAvailable)`.
  On this machine Docker 29 rejects the Testcontainers/docker-java handshake, so it **skips here**;
  it runs on a standard Docker setup. Mappings were verified against the compose Postgres via psql.
- **Single worker instance** by design (one `relay-worker` thread). The lease model supports scaling
  to multiple workers, but that isn't exercised here.
- **AI provider** defaults to a deterministic mock (offline); set `relay.ai.provider=openrouter`
  for a real OpenAI-compatible model. The `ai` node, schema validation, repair retry, and token
  accounting work with either.
- **NL compiler** (`POST /workflows/compile`) is implemented (Good-To-Have) and needs a real model;
  it scores 15/15 on the provided eval with gpt-4o-mini (see `docs/VERIFICATION.md`).
