# cloud-itonami-cofog-05.1

Open COFOG Blueprint (implemented actor) for **COFOG 05.1**: Waste
management.

This repository publishes a forkable OSS business for an independent
municipal waste collection contractor: a waste-collection robot performs
route pickup and sorting under a governor-gated actor, so a municipality's
contracted hauler keeps auditable collection and diversion records instead
of renting a closed fleet-management SaaS. Complements
[`cloud-itonami-3830`](https://github.com/cloud-itonami/cloud-itonami-3830)
(Local Materials Recovery) at the upstream collection stage.

**Maturity: `:implemented`** — Route Advisor ⊣ Waste Operations
Governor as a langgraph StateGraph (`intake → advise → govern → decide
→ commit/hold`, human-approval interrupt), modeled on
`cloud-itonami-isic-3091`'s (motorcycle plant operations) dual
verified/registered-gate shape and `cloud-itonami-isic-3830`'s
(materials recovery) intake/verify/screen op shape. 64 tests / 185
assertions green, `clj-kondo` 0 errors / 0 warnings. See
[`cloud-itonami-isco-1324`](https://github.com/cloud-itonami/cloud-itonami-isco-1324)
for this fleet's minimal implemented reference (`actor`/`advisor`/
`governor`/`store`).

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here a waste-collection robot performs
route pickup, bin handling and preliminary sort under an actor that
proposes route/dispatch actions and an independent **Waste Operations
Governor** that gates them. The governor never dispatches hardware itself;
`:high`/`:safety-critical` actions (e.g. suspected hazardous-material
pickup) require human sign-off.

## What this actor does

Proposes **route/dispatch coordination**, not vehicle operation:
- `:log-route-pickup` — route pickup tonnage/bin-count data logging (administrative, not an operational decision)
- `:log-sorting-result` — preliminary on-vehicle sort finding: diversion-category + contamination-percentage
- `:schedule-route-dispatch` — collection-vehicle route-dispatch scheduling proposal
- `:escalate-contamination` — surface a suspected hazardous-material/contamination concern (always escalates)

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY**:

- Does NOT actuate the collection robot/vehicle directly (the physical pickup act, and any live-vehicle operation, stays under human dispatch authority) — a PERMANENT, unconditional block
- Does NOT self-issue a municipal diversion-rate compliance filing (that is the municipality's/regulator's own authority; this actor logs measured tonnage/diversion data only)
- Does NOT clear, dispose of, or reclassify suspected hazardous material on its own judgment — contamination escalation ALWAYS routes to a human route coordinator, never auto-decided
- ONLY proposes/coordinates route dispatch back-office; all vehicle actuation requires explicit human authority

## Core Contract

```text
collection route + bin/site registry
        |
        v
Route Advisor -> Waste Operations Governor -> dispatch, or human sign-off
        |
        v
robot collection actions (gated) + diversion/tonnage record + audit ledger
```

No automated route plan can dispatch a robot action the governor
refuses, suppress a collection record, or misreport diversion tonnage
without governor approval and audit evidence.

## Architecture

Classic governed-actor pattern (`wastecollect.operation/build`, a langgraph-clj StateGraph):
1. **`wastecollect.advisor`** (sealed intelligence node, `Route Advisor`): proposes decisions only, never commits
2. **`wastecollect.governor`** (independent, `Waste Operations Governor`): validates against domain rules, re-derived from `wastecollect.registry`'s pure functions and `wastecollect.store`'s SSoT -- never trusts the advisor's own self-report
   - HARD invariants (always `:hold`, no override):
     - Route AND vehicle records must be independently verified/registered (`:verified?` AND `:registered?`) before any dispatch is scheduled against them
     - The request's own `:effect` must be `:propose` (never a direct-write bypass)
     - `:op` must be in the closed four-op allowlist
     - The proposal's own `:effect` must be one of the four propose-shaped effects (no direct collection-robot/vehicle control)
     - Directly actuating the collection vehicle (`:actuate-vehicle? true`) is a PERMANENT, unconditional block
     - No double-scheduling the same dispatch record
     - No fabricated `:diversion-category` value on a sorting-result finding
     - No physically implausible `:contamination-percentage` value on a sorting-result finding
     - No physically implausible `:tonnage-kg` value on a route-pickup patch
   - ESCALATE (always human sign-off, overridable by a human):
     - `:escalate-contamination` always escalates, regardless of confidence
     - Low-confidence proposals
3. **`wastecollect.phase`** (Phase 0->3 rollout): `:log-sorting-result`/`:schedule-route-dispatch`/`:escalate-contamination` are NEVER in any phase's `:auto` set (permanent, matching the governor's own posture); only `:log-route-pickup` may auto-commit at phase 3 when clean
4. **`wastecollect.store`** (append-only audit ledger + SSoT): a single `MemStore` backend behind a `Store` protocol

## Development

```bash
# Run tests (top-level deps.edn already pins langgraph+langchain local/root)
clojure -M:test

# Run tests via the workspace :dev override alias (equivalent, kept for sibling-repo parity)
clojure -M:dev:test

# Run the demo
clojure -M:dev:run

# Lint
clojure -M:lint
```

## Capability layer

Resolves via [`kotoba-lang/cofog`](https://github.com/kotoba-lang/cofog)
(COFOG `05.1`). Required capabilities:

- :robotics
- :telemetry
- :optimization
- :bpmn
- :audit-ledger

See [`docs/business-model.md`](docs/business-model.md),
[`docs/operator-guide.md`](docs/operator-guide.md) and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
full architecture and decision record.

## License

AGPL-3.0-or-later.
