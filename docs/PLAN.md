# Relay — AI Workflow Orchestrator · Implementation Plan

> Airtribe capstone. This document is the architecture + phased build plan.
> Companion files: [`PROMPTS.md`](./PROMPTS.md) (the build prompts) and [`../TASKS.md`](../TASKS.md) (living status).

---

## 1. What we're building

Relay is a durable AI workflow orchestration engine. Workflows are graphs of **typed nodes**
that run **asynchronously on a worker loop** fed by a **durable queue**. Runs are triggered by
webhooks or manual API calls, execute step-by-step with every step persisted before advancing,
pause for human approvals, validate AI outputs against JSON schema, and recover from crashes
**without duplicating side effects**.

What is actually graded is the **reliability engine**, not the feature count:
crash-resume with zero duplication, approval gates enforced by the engine (not the AI),
schema-validated AI output, idempotency, step caps, and complete run traces.

## 2. Chosen stack (decided with the user)

| Concern | Choice |
|---|---|
| Language / runtime | Java 21 |
| Framework / build | **Spring Boot 3 + Maven** |
| Persistent store | **PostgreSQL** (workflows, runs, snapshots, steps, approvals) |
| Durable queue | **Redis** (list/stream) + polling worker, via Docker Compose |
| AI provider | **Adapter + deterministic mock first** (real Anthropic key drops in later, no code change) |
| Console | **Server-rendered** (Thymeleaf + vanilla JS) |
| Migrations | Flyway |
| Tests | JUnit 5 + Testcontainers (Postgres/Redis) + MockWebServer |

## 3. Starter pack (upstream reference — do NOT reinvent)

Repo: `https://github.com/airtribe-projects/relay-capstone`. We consume its assets:

- `data/node_catalog.json` — node & trigger type registry (validate definitions against this)
- `data/seed_workflows.json` — 4 workflows: `wf_support_triage`, `wf_expense_approval`,
  `wf_slow_fulfillment`, `wf_runaway` (load + publish on startup)
- `data/sample_payloads.jsonl` — trigger payloads incl. injection tests (`pay_001`, `pay_inject_001`, `pay_201`)
- `scripts/mock_world.py` — external world sim on **port 9210**: `/email/send`, `/orders/{id}/refund`,
  `/admin/ledger`; supports failure injection (503/latency/partial). Idempotency ledger built in.
- `scripts/mock_provider.py` — OpenAI-compatible mock LLM (our mock adapter should match its behavior)
- `scripts/smoke_test.py`, `scripts/duplication_check.py` — the acceptance gates we must pass

**Action:** vendor these into `relay-capstone-pack/` (git submodule or copy) so the build and demo are reproducible.

## 4. Data model (from starter `DATA_MODEL.md`)

- **Workflow**: `id, name, description, status(draft|published), definition(JSON), created_at, updated_at`
- **Run**: `run_id, workflow_id, definition_snapshot(JSON), status(queued|running|waiting_approval|succeeded|failed|cancelled), trigger_type, input(JSON), current_node_id, steps_executed, ai_tokens_used, error(JSON), started_at, finished_at`
- **Step** (trace): `run_id, node_id, node_type, sequence, status, attempt, resolved_input(JSON), output(JSON), tokens_prompt, tokens_completion, idempotency_key, started_at, duration_ms` — index `(run_id, sequence)`
- **Approval**: `id, run_id, node_id, message, status(pending|approved|rejected), decided_by, decided_at`
- **QueueJob**: durable job rows (Redis is primary; a DB mirror gives lease/heartbeat + recovery)
- **Schedule** (optional/GTH): `workflow_id, cron, next_fire_at, enabled`

### Correctness invariants (the whole point)
1. **Persist-then-acknowledge** — a step's completion (output + idempotency key) is persisted *before* moving to the next node. Resume derives entirely from stored steps.
2. **Stable idempotency keys** — `{run_id}:{node_id}`, constant across retries/crashes (never include attempt #).
3. **Crash window** — if a side effect fired but persistence didn't, resume re-sends with the same key; the mock world's replay detection absorbs the duplicate.
4. **Loop accounting** — `steps_executed` increments on every execution incl. repeats; enforce `limits.max_steps` before each step (`wf_runaway` stops at 12).
5. **Approval as data** — engine checks Approval rows before `requires_approval` nodes; nothing the AI outputs can create/modify an Approval.

## 5. Run state machine

```
                 enqueue                dequeue
   [*] ──────▶ queued ──────▶ running ──┬────────────▶ succeeded ──▶ [*]
                  │                       │
                  │ cancel                ├─ node fails (no retries left) ─▶ failed ──▶ [*]
                  ▼                       │
              cancelled ◀────────────────┤ step cap exceeded ─▶ failed
                  ▲                       │
                  │  reject               ▼
                  └────────────── waiting_approval ── approve ──▶ running
```

## 6. API surface (from starter `API_CONTRACT.md`)

Platform APIs use `Authorization: Bearer <token>`; webhooks use `X-Relay-Secret`.
Error shape everywhere: `{"error": {"message": "...", "code": "..."}}`.

- `GET /workflows` · `POST /workflows` · `POST /workflows/{id}/publish` · `POST /workflows/{id}/trigger`
- `POST /hooks/{id}` (secret-protected webhook)
- `GET /runs/{id}` (incl. `steps[]`) · `POST /runs/{id}/cancel`
- `GET /approvals?status=pending` · `POST /approvals/{id}/approve` · `POST /approvals/{id}/reject`
- `POST /workflows/compile` (Good-To-Have: NL → definition or refusal)

## 7. Node types

| Node | Behavior | Notes |
|---|---|---|
| `http_request` | templated URL/method/headers/body; capture status+body; fail on non-2xx | timeout |
| `condition` | evaluate templated boolean; route via `on_true`/`on_false` | no arithmetic, path lookups only |
| `delay` | persist `resume_at` **before** sleeping; safe across crash | |
| `notify` | send email/chat via mock world | side-effect → idempotency key |
| `ai` | templated prompt → model → parse → validate vs `output_schema`; **one repair retry** on failure; fail after 2 bad JSON | record tokens; behind adapter |
| `approval` | pause → `waiting_approval`; engine gates `requires_approval` downstream | approve/reject/resume |

**Template resolution:** simple path lookups only — `{{trigger.body.x.y}}`, `{{nodes.id.output.z}}`.
Unresolvable path → step fails with a clear error.

## 8. Package layout (target)

```
src/main/java/com/relay/
├── RelayApplication.java
├── config/        # security (bearer filter), redis, jackson, async, mockworld client
├── web/           # REST controllers + Thymeleaf console controllers + error handler
├── workflow/      # Workflow entity, repo, service, validator (catalog-based), seed loader
├── run/           # Run/Step/Approval entities, repos, run service, snapshotting
├── engine/        # queue, worker loop, state machine, template resolver, step cap
│   └── nodes/     # NodeExecutor interface + http/condition/delay/notify/ai/approval
├── ai/            # AiProvider adapter, MockAiProvider, (AnthropicAiProvider later), schema validator
├── mockworld/     # typed client with idempotency-key + timeout
└── trace/         # trace assembly for API + console
src/main/resources/
├── db/migration/  # Flyway V1__*.sql ...
├── templates/     # Thymeleaf
└── application.yml
```

## 9. Phased build order

Mirrors the starter `IMPLEMENTATION_GUIDE.md` (Steps 1–7 must-have, 8–9 optional). See TASKS.md for status.

- **Phase 0** — Scaffolding: Maven+Spring Boot, Docker Compose (PG+Redis), Flyway, auth filter, error handler, mock-world wiring, boots + `/health`.
- **Phase 1** — Persistence: entities, migrations w/ indexes, repos, seed loader (catalog + publish seed workflows).
- **Phase 2** — Workflow CRUD + validation + draft→publish + per-run snapshot.
- **Phase 3** — Triggers: manual + webhook (secret), enqueue (never inline).
- **Phase 4** — Engine v1 happy path: Redis queue, worker loop, state machine, template resolver, step persistence; `wf_expense_approval` end-to-end.
- **Phase 5** — Deterministic nodes: http_request, condition, delay, notify + idempotency + timeouts.
- **Phase 6** — Durability: persist-then-advance, startup recovery/resume, lease/heartbeat; pass `duplication_check.py` on `wf_slow_fulfillment` kill-and-resume.
- **Phase 7** — Approvals + retries(backoff) + max_steps cap (`wf_runaway`).
- **Phase 8** — AI node: adapter + mock, schema validation + one repair retry, token accounting; `wf_support_triage` + injection stays gated.
- **Phase 9** — Console: workflows, run history, trace detail, pending approvals w/ actions.
- **Phase 10** — Testing & verification: unit/integration tests + `smoke_test.py` + `duplication_check.py`; verification report.
- **Phase 11** — Docs & demo: README (arch, state machine, recovery), seeded demo steps, explainer video script.

## 10. Definition of done (success metrics)

- [ ] Deterministic + AI nodes execute with schema validation
- [ ] Kill-and-resume shows **zero** duplicate side effects (`duplication_check.py` passes)
- [ ] Approval gates enforce human approval before sensitive actions (injection can't bypass)
- [ ] Step cap stops runaway workflows; injection attempts fail safely
- [ ] Complete audit trail: traces, timing, token usage
- [ ] `smoke_test.py` passes; README + demo + verification report delivered

## 11. Open items / risks

- Redis-as-queue + DB mirror for recovery: decide exact lease mechanism (visibility timeout vs heartbeat) in Phase 6.
- Real Anthropic provider deferred; keep `AiProvider` seam clean so it's a drop-in.
- `mock_provider.py` is OpenAI-compatible — our mock adapter mirrors it for parity with grader scripts.
- NL compiler (Phase 8 upstream / Good-To-Have) intentionally out of the must-have path; revisit after Phase 11.
