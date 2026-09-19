# Relay — Build Prompt Sequence

These are the self-contained prompts to drive each phase, in order. Run one per working session.
Each assumes the repo, [`PLAN.md`](./PLAN.md), and the vendored `relay-capstone-pack/` are present.
After each phase: run its tests, then update [`../TASKS.md`](../TASKS.md) and the task board.

**Global rules (apply to every prompt):**
- Java 21, Spring Boot 3, Maven. Follow the package layout in PLAN.md §8.
- Every external call (mock world, AI) has a timeout. Side effects use idempotency key `{run_id}:{node_id}`.
- Approval gate + step cap are enforced in the engine, never via prompt/AI output.
- Error responses use `{"error":{"message","code"}}`. Add/extend tests with each phase. Don't break earlier phases.

---

## Prompt 0 — Scaffolding
> Convert this repo from a plain JDK 21 IntelliJ module into a Spring Boot 3 Maven project (groupId `com.relay`, Java 21). Add dependencies: web, data-jpa, validation, thymeleaf, redis (spring-data-redis/lettuce), flyway, postgresql driver, actuator, and test (junit5, testcontainers for postgres+redis, mockwebserver). Create `docker-compose.yml` with Postgres 16 and Redis 7. Add `application.yml` (datasource, redis, flyway, a `relay.auth.token` and per-workflow-secret config). Implement a Bearer-token auth filter for `/workflows`, `/runs`, `/approvals` and an `X-Relay-Secret` check hook (stub for now). Implement a global `@RestControllerAdvice` returning `{"error":{"message","code"}}`. Add a `MockWorldClient` config pointing at `http://localhost:9210` with a timeout. Vendor the starter pack into `relay-capstone-pack/`. App must boot and expose `/actuator/health`. Delete `src/Main.java`.

## Prompt 1 — Data & persistence
> Implement the data model from PLAN.md §4 as JPA entities: Workflow, Run, Step, Approval, QueueJob (and an optional Schedule). Use JSONB columns for `definition`, `definition_snapshot`, `input`, `resolved_input`, `output`, `error`. Write Flyway migrations `V1..` creating all tables with the index `(run_id, sequence)` on steps and sensible FKs/indexes. Add Spring Data repositories. Implement a `SeedLoader` (ApplicationRunner) that on startup reads `relay-capstone-pack/data/node_catalog.json` into an in-memory catalog and upserts + publishes the workflows from `seed_workflows.json` (idempotent — safe to run repeatedly). Add repository/integration tests using Testcontainers Postgres.

## Prompt 2 — Workflow CRUD & validation
> Implement `GET /workflows`, `POST /workflows` (creates a draft), `GET /workflows/{id}`, and `POST /workflows/{id}/publish`. Build a `WorkflowValidator` that validates a definition against the node catalog: unknown node types, missing required params, invalid node references, non-existent entry point → 400/422 with the error shape. Publish freezes the workflow (published = immutable; further edits rejected 409). When a run is later triggered, copy the definition into the Run as `definition_snapshot`. Unit-test the validator against each seed workflow (valid) and crafted invalid definitions.

## Prompt 3 — Triggers
> Implement `POST /workflows/{id}/trigger` (Bearer, body `{"input":{...}}`) and `POST /hooks/{id}` (webhook, `X-Relay-Secret`; raw body becomes `{{trigger.body}}`). Both: reject if workflow not published (409), create a Run in `queued` with the definition snapshot + input + trigger_type, enqueue a job on the durable queue, and return `{"run_id":"run_..."}` (202). Wrong/missing secret → 401/403. The API must NOT execute the workflow inline. Tests: trigger enqueues, unpublished→409, bad secret→401/403.

## Prompt 4 — Engine v1 (happy path)
> Build the durable execution core. A Redis-backed queue with a polling worker loop (`@Scheduled` or dedicated thread) that leases a job, loads the Run + snapshot, and walks nodes from the entry point. Implement the run state machine (queued→running→succeeded/failed) with atomic status transitions. Define a `NodeExecutor` interface (`ExecResult execute(NodeContext ctx)`) and a registry keyed by node type. Implement a `TemplateResolver` for `{{trigger.body.x.y}}` and `{{nodes.id.output.z}}` path lookups (unresolvable → step fails with a clear message). Persist a Step row for every node execution (sequence, resolved_input, output, timing) and increment `steps_executed`. Prove it by running `wf_expense_approval` end-to-end with a stub executor for unimplemented node types. Integration test the full manual-trigger→completion path.

## Prompt 5 — Deterministic nodes
> Implement the four deterministic `NodeExecutor`s. `http_request`: template URL/method/headers/body, timeout, capture status+body, fail on non-2xx. `condition`: evaluate a templated boolean and route via `on_true`/`on_false` edges. `delay`: persist `resume_at` BEFORE sleeping and make it crash-safe (on resume, wait only the remainder). `notify`: call the mock world with an idempotency key `{run_id}:{node_id}` and a timeout. Route all side-effect calls through `MockWorldClient`. Tests with MockWebServer: non-2xx fails, condition branches both ways, delay persists resume_at, notify sends the idempotency key.

## Prompt 6 — Durability & crash recovery
> Make execution crash-safe. Enforce persist-then-advance: a step (output + idempotency_key) is committed before the next node starts. Add a lease/heartbeat (or visibility timeout) so a crashed worker's job becomes reclaimable, plus a startup `RecoveryService` that finds `running` runs / in-flight jobs and resumes from the last completed step. Confirm idempotency keys are stable across retries and resumes. Wire everything so re-executing a completed step is skipped (replay from stored steps). Verify: start `wf_slow_fulfillment` with `pay_201`, kill the worker mid-delay, restart, and make `scripts/duplication_check.py` pass (zero duplicate side effects). Add an integration test that simulates a mid-run crash and asserts no duplicate mock-world calls.

## Prompt 7 — Approvals, retries, caps
> Implement the `approval` node: create a pending Approval row, move the run to `waiting_approval`, and stop the worker from advancing. Implement `GET /approvals?status=pending`, `POST /approvals/{id}/approve` (resume the run), `POST /approvals/{id}/reject` (end run `cancelled`); 409 if already decided. Enforce at the engine level: before any node with `requires_approval`, require an `approved` Approval row — AI output can never create one. Add per-node retry with exponential backoff (configurable max attempts). Enforce `limits.max_steps`: check `steps_executed` before each execution; exceeding fails the run with a reason naming the cap. Verify `wf_runaway` stops at step 12. Tests: approve resumes, reject cancels, requires_approval blocked without approval, cap trips.

## Prompt 8 — AI node
> Implement the `ai` node behind an `AiProvider` adapter. Provide a `MockAiProvider` compatible with `scripts/mock_provider.py` behavior, wired by default (real Anthropic provider is a future drop-in — do not add the key now). Flow: resolve the templated prompt, invoke the provider (with timeout + token usage capture), parse JSON, and validate against the node's `output_schema` (JSON Schema). On validation/parse failure, do ONE repair retry appending the validation error to the prompt; fail the step if the second attempt is still invalid. Record `tokens_prompt`/`tokens_completion` on the step and add to `ai_tokens_used`. Ensure prompt-injection in inputs cannot bypass the approval gate or step cap (they're engine-enforced). Verify: run `wf_support_triage` with `pay_001` (→ classified branch → notify) and `pay_inject_001` (→ parks in `waiting_approval`; reject leaves the order untouched in the mock world). Tests: schema failure triggers one repair then fails; token usage recorded; injection stays gated.

## Prompt 9 — Console (Thymeleaf)
> Build a simple server-rendered console. Pages: (1) workflows list with status; (2) run history with status + timing; (3) run detail showing the full trace — each step's resolved input, output, attempt, status, duration, and token usage; (4) pending approvals with Approve/Reject buttons that call the approval APIs. Keep styling minimal (one small CSS file). Reuse the trace-assembly service used by `GET /runs/{id}`. No auth complexity — a simple shared token or local-only is fine; document it.

## Prompt 10 — Testing & verification
> Fill test gaps so we have automated coverage for: publish validation, template resolution, schema enforcement, idempotency, guardrails (approval gate + step cap), and crash recovery. Make `scripts/smoke_test.py` pass against the running app, and run the live kill-and-resume drill so `scripts/duplication_check.py` passes. Produce `docs/VERIFICATION.md` capturing the smoke-test output and the duplication-check result. Fix any bugs surfaced.

## Prompt 11 — Docs & demo
> Write the top-level `README.md`: overview, architecture diagram, setup (docker compose + mock world + run app), API documentation, the run state machine diagram, the exactly-once/recovery design, and known limitations. Add `docs/DEMO.md` with step-by-step instructions to reproduce all demo scenarios (AI triage + injection defense, kill-and-resume, caps & chaos) using the mock world and seed payloads. Draft `docs/VIDEO_SCRIPT.md` for the explainer video covering: workflow trigger, AI node branching, approval gate, worker crash + resume, prompt-injection handling, and step-cap protection.

---

## Optional (Good-To-Have, after Phase 11)
> Implement `POST /workflows/compile`: accept `{"description":"..."}`, prompt the model with the full node catalog, generate a candidate definition, validate it via the Phase 2 validator, and return a publishable draft or `{"refusal":{"reason","missing_capabilities":[...]}}`. Add an eval command over `relay-capstone-pack/data/nl_eval.jsonl` and report accuracy.
