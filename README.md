# cloud-itonami-isic-8620

Open Business Blueprint for **ISIC Rev.5 8620**: Medical and dental practice activities.

This repository designs a forkable OSS business for medical and dental practice activities -- outpatient consultation, diagnosis and treatment by licensed physicians/dentists -- run by a qualified, licensed operator so a community or
independent provider never surrenders patient/resident data and ledgers to a
closed SaaS.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot performs
the physical domain work**. Here a diagnostic-imaging robot assists physical patient examination,
under an actor that proposes actions and an independent **Clinical Practice Governor**
that gates them. The governor never dispatches hardware itself;
`:high`/`:safety-critical` actions require human sign-off.

## Core Contract

```text
intake + identity + care records
        |
        v
ClinicOps-LLM -> Clinical Practice Governor -> hold, proceed, or human approval
        |
        v
care ledger + evidence record + audit
```

No automated proposal, by itself, can complete the following without governor
approval and audit evidence: administering a treatment, prescription or procedure.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`8620`). This vertical's care/case records are practice-specific rather
than a shared cross-operator data contract, so it runs on the generic
identity/forms/dmn/bpmn/audit-ledger stack -- no bespoke domain capability lib.

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## Maturity

`:blueprint` -- this repository is the published business/operator design.
The governed actor implementation (`ClinicOps-LLM` + `Clinical Practice Governor` as
running code) is a follow-up, same as any other `:blueprint`-tier
`cloud-itonami-*` entry in `kotoba-lang/industry`'s registry.

## License

Code and implementation templates are AGPL-3.0-or-later.
