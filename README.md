# cloud-itonami-cofog-05.1

Open COFOG Blueprint for **COFOG 05.1**: Waste management.

This repository designs a forkable OSS business for an independent
municipal waste collection contractor: a waste-collection robot performs
route pickup and sorting under a governor-gated actor, so a municipality's
contracted hauler keeps auditable collection and diversion records instead
of renting a closed fleet-management SaaS. Complements
[`cloud-itonami-3830`](https://github.com/cloud-itonami/cloud-itonami-3830)
(Local Materials Recovery) at the upstream collection stage.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here a waste-collection robot performs
route pickup, bin handling and preliminary sort under an actor that
proposes route/dispatch actions and an independent **Waste Operations
Governor** that gates them. The governor never dispatches hardware itself;
`:high`/`:safety-critical` actions (e.g. suspected hazardous-material
pickup) require human sign-off.

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

No automated route plan can dispatch a robot action the governor refuses,
suppress a collection record, or misreport diversion tonnage without
governor approval and audit evidence.

## Capability layer

Resolves via [`kotoba-lang/cofog`](https://github.com/kotoba-lang/cofog)
(COFOG `05.1`). Required capabilities:

- :robotics
- :telemetry
- :optimization
- :bpmn
- :audit-ledger

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## License

AGPL-3.0-or-later.
