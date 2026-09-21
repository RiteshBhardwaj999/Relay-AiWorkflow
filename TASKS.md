# Relay — Task List

Living status board. Legend: ✅ done · 🚧 in progress · ⬜ not started.
Plan: [`docs/PLAN.md`](docs/PLAN.md) · Prompts: [`docs/PROMPTS.md`](docs/PROMPTS.md)

_Last updated: 2026-09-19 (ALL phases 0–11 complete ✅)_

## Setup / meta
- ✅ Understand requirements (both PDFs) + inspect repo
- ✅ Confirm stack decisions with user (Spring Boot+Maven, PG+Redis, mock AI, Thymeleaf)
- ✅ Review Airtribe starter pack (API contract, data model, node specs, seed data, mock world)
- ✅ Write plan, prompt sequence, and this task list
- ✅ Vendor starter pack into `relay-capstone-pack/`

## Environment notes
- Local Postgres (5432) and Redis (6379) already run on this machine, so our containers use
  **host ports 5433 (Postgres) and 6380 (Redis)** — see `docker-compose.yml` / `application.yml`.
- JVM forced to UTC in `RelayApplication.main` (pgjdbc rejected the `Asia/Calcutta` legacy alias).
- Bearer token: `relay-dev-token` (override with `RELAY_AUTH_TOKEN`). Blank disables auth.
- Run: `docker compose up -d` → `mvn spring-boot:run` → http://localhost:8080/actuator/health
- **Testcontainers caveat**: the local Docker 29 daemon returns HTTP 400 to Testcontainers'
  ping (too-new-daemon vs bundled docker-java), so `PersistenceIntegrationTest` is gated with
  `@EnabledIf(dockerAvailable)` and **skips here** — it runs normally on standard Docker. Phase 1
  was instead verified by booting against the compose Postgres and inspecting schema + seeds via psql.
- Flyway owns the schema (`ddl-auto: none`); migrations in `src/main/resources/db/migration`.

## Build phases
| # | Phase | Status | Notes |
|---|-------|--------|-------|
| 0 | Project scaffolding (Boot+Maven, compose, auth, error handler) | ✅ | boots; health UP; 401/404 verified |
| 1 | Data & persistence (entities, migrations, seed loader) | ✅ | 6 tables, indexes; 4 seeds published; catalog 7 nodes/3 triggers |
| 2 | Workflow CRUD & validation (draft→publish, snapshot) | ✅ | list/create/get/publish/update; 422/409/404 verified; snapshot in P3 |
| 3 | Triggers (manual + webhook secret, enqueue) | ✅ | 202+run_id; 401/403/409 verified; durable QueueJob + Redis push |
| 4 | Engine v1 happy path (Redis queue, worker, templates) | ✅ | wf_expense_approval → succeeded; condition+templates+trace verified; notify still stub |
| 5 | Deterministic nodes (http/condition/delay/notify) | ✅ | slow_fulfillment E2E; durable non-blocking delay; idem keys once in ledger |
| 6 | Durability & crash recovery | ✅ | kill-mid-delay → resume; duplication_check.py PASS (each effect once) |
| 7 | Approvals, retries, caps | ✅ | approve/reject/409; cap stops wf_runaway @12; retry×3 backoff on 503; order_action gate wired |
| 8 | AI node (adapter+mock, schema, repair retry) | ✅ | triage complaint/refund/inject; schema+repair; tokens; injection stays gated, order untouched |
| 9 | Web console (Thymeleaf) | ✅ | workflows/runs/trace/approvals; approve button resumes run |
| 10 | Testing & verification | ✅ | 12 unit pass (2 skip); smoke 28/0; duplication_check PASS; docs/VERIFICATION.md |
| 11 | Docs & demo | ✅ | README, docs/DEMO.md, docs/VIDEO_SCRIPT.md, docs/VERIFICATION.md |

## Good-To-Have (post-11)
- ⬜ NL compiler `POST /workflows/compile` + eval over `nl_eval.jsonl`
- ✅ Real AI provider drop-in — `OpenAiCompatibleAiProvider` (OpenRouter/OpenAI-compatible),
     selected via `relay.ai.provider=openrouter` + `RELAY_AI_API_KEY` + `RELAY_AI_MODEL`; mock stays default
- ⬜ Schedules (cron-fired runs)

## Definition of done
- ✅ Deterministic + AI nodes execute with schema validation
- ✅ Kill-and-resume: zero duplicate side effects (duplication_check PASS)
- ✅ Approval gates enforce human approval; injection can't bypass (order stays untouched)
- ✅ Step cap stops runaway (wf_runaway fails @12); injection fails safely
- ✅ Complete audit trail (traces, timing, tokens)
- ✅ smoke_test (28/0) + duplication_check pass; README + demo + verification report delivered

## Remaining (owner: user)
- ⬜ Commit + push to GitHub (user handles commits)
- ⬜ Record explainer video (script in docs/VIDEO_SCRIPT.md)
- ⬜ Optional/Good-To-Have: NL compiler, real Anthropic provider, schedules
