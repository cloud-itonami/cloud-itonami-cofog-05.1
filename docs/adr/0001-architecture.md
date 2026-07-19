# ADR-0001: Route Advisor ⊣ Waste Operations Governor architecture

## Status

Accepted. `cloud-itonami-cofog-05.1` promoted from `:blueprint` to
`:implemented`, following the verified fresh-scaffold protocol
established by prior actors in this fleet.

## Context

`cloud-itonami-cofog-05.1` publishes an OSS blueprint for an
independent municipal waste-collection contractor: a waste-collection
robot performs route pickup, bin handling and preliminary on-vehicle
sorting under an actor that proposes route/dispatch actions, gated by
an independent governor. Like every actor in this fleet, the blueprint
alone is not an implementation: this ADR records the governed-actor
architecture that promotes it to real, tested code, following the same
langgraph StateGraph + independent Governor + Phase 0->3 rollout
pattern established across the cloud-itonami fleet.

The closest structural template is `cloud-itonami-isic-3091`
(motorcycle plant operations): both are back-office coordination
actors with a central "record being logged" entity (a route here, a
production batch there) and a separate physical "unit that can be
scheduled" entity (a collection vehicle here, plant equipment there),
each independently verified/registered before any scheduling action.
This build borrows that dual-gate shape directly (`route-ready?`/
`vehicle-ready?` mirroring `motomfg.registry`'s
`batch-ready?`/`equipment-ready?`) and the "one administrative logging
op is phase-3 auto-eligible, everything else always escalates" phase
posture. It also borrows `cloud-itonami-isic-3830`'s (materials
recovery) three-op intake/verify/screen shape for the two logging ops
that both act on the SAME central entity via different `:effect`
values (`:route/upsert` vs `:sorting/set`, mirroring 3830's
`:batch/upsert` vs `:verification/set`/`:contamination-screen/set`).

This vertical has NO pre-existing `kotoba-lang/wastecollect`-style
capability library to wrap (verified: no such repo exists). The domain
logic therefore lives as pure functions in `wastecollect.registry`,
re-verified independently by `wastecollect.governor` -- the same
"ground truth, not self-report" discipline established across prior
actors.

## Decision

### Decision 1: Self-contained domain logic (no external waste-collection capability library to wrap)

The route/vehicle-verification, diversion-category-validity,
tonnage-plausibility and contamination-percentage-plausibility
functions live as pure functions in `wastecollect.registry` and are
re-verified independently by `wastecollect.governor`.

### Decision 2: Coordination, not control -- scope boundary at the back-office

This actor is **strictly back-office coordination** of a municipal
waste-collection contractor's route dispatch and record-keeping. It
does NOT:
- Actuate the collection robot/vehicle directly (the physical pickup act stays with the vehicle's own control system, under human dispatch authority)
- Make municipal diversion-rate compliance determinations (that is the municipality's/regulator's own authority; this actor logs measured tonnage/diversion data, it never self-certifies a compliance filing)
- Clear, dispose of, or reclassify suspected hazardous material on its own judgment

All proposals are `:effect :propose` only. The advisor proposes; the
governor validates; escalation paths funnel to human route-coordinator
approval.

### Decision 3: Contamination escalation -- always human sign-off, informed by (not claiming) the RCRA manifest discipline

`:escalate-contamination` ALWAYS escalates, never auto-commits. Many
jurisdictions govern suspected hazardous material discovered in a
municipal waste stream through a manifest/chain-of-custody framework
rather than a collector's own unilateral disposal decision -- for
example, the U.S. RCRA hazardous-waste-generator standards (40 CFR
Part 262, verified via eCFR/EPA during this ADR's research) require a
signed manifest naming a permitted receiving facility, not a
generator's self-clearance. This governor's unconditional
never-auto-resolved posture on a contamination concern is the
software-side analog of that same discipline -- **not** a claim that
this software itself is a RCRA-regulated generator, a licensed hauler,
or a compliance system. Whoever deploys a live instance bears their own
jurisdiction's actual hazardous-waste obligations.

### Decision 4: Two independent verified/registered gates (route AND vehicle), not one

`:schedule-route-dispatch` independently verifies BOTH the referenced
**route**'s own `:verified?`/`:registered?` fields AND the referenced
**vehicle**'s own `:verified?`/`:registered?` fields before any
dispatch may be scheduled -- the same "record must be independently
verified/registered before any action" HARD invariant applied to both
entity kinds this domain has, evaluated together for the single op
that touches both.

### Decision 5: HARD invariants (no override)

Elaborated into ten concrete checks in `wastecollect.governor`:
1. Route AND vehicle records must be independently verified/registered before a dispatch is scheduled against them
2. Proposals must be `:effect :propose` only (never direct vehicle control)
3. Direct collection-robot/vehicle control, or vehicle actuation, is permanently blocked
4. The op allowlist is closed -- `:log-route-pickup`/`:log-sorting-result`/`:schedule-route-dispatch`/`:escalate-contamination` only
5. No fabricated `:diversion-category`, no physically implausible `:tonnage-kg` or `:contamination-percentage`
6. No double-scheduling the same dispatch record

## Consequences

(+) Municipal waste-collection route dispatch now has a documented,
governed, auditable coordination layer that funnels all decisions
through independent validation before human approval.

(+) The "coordination, not control" boundary is explicit in code: all
`:effect :propose`, all real-world vehicle actuation requires human
route-coordinator sign-off.

(+) Scope is bounded and verifiable: HARD invariants (elaborated into
ten concrete governor checks) protect against scope creep into
unauthorized vehicle actuation or a fabricated diversion-rate/
compliance record. Contamination escalation is a circuit-breaker, not
a threshold.

(-) Still a simulation/proposal layer, not a real fleet-management
control system. Vehicle actuation and municipal compliance filing
remain human-/institution-controlled via external channels.

(-) No integration with real fleet-telemetry or municipal-reporting
databases -- this is a standalone coordinator blueprint.

## Verification

- `cloud-itonami-cofog-05.1`: `clojure -M:test` green -- 64 tests, 185
  assertions, 0 failures, 0 errors (verified from a fresh checkout).
  Demo narrative (`clojure -M:dev:run`) exercises proposal submission,
  escalation, and every HARD-hold scenario directly (not-propose-
  effect, unknown-op, route-not-verified, vehicle-not-verified,
  vehicle-actuate-blocked, already-dispatched, invalid-diversion-
  category, invalid-contamination-percentage, invalid-tonnage).
- `clojure -M:lint` (clj-kondo): 0 errors, 0 warnings.
- All source is `.cljc` (portable ClojureScript / JVM / nbb) -- no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain` via `:local/root` directly in the
  top-level `:deps`, so a bare `clojure -M:test` resolves offline
  inside the monorepo checkout.
