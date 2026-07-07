# cloud-itonami-isic-8620

Open Business Blueprint for **ISIC Rev.5 8620**: Medical and dental
practice activities. This repository publishes a medical/dental-
practice actor -- encounter intake, jurisdiction licensing assessment,
clinician-credential screening and treatment administration -- as an
OSS business that any qualified, licensed physician/dentist can fork,
deploy, run, improve and sell.

Built on this workspace's
[`langgraph-clj`](https://github.com/com-junkawasaki/langgraph-clj)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, Datomic/in-mem checkpoints) -- the same actor pattern as
every prior actor in this fleet
([`cloud-itonami-isic-6511`](https://github.com/cloud-itonami/cloud-itonami-isic-6511),
[`6512`](https://github.com/cloud-itonami/cloud-itonami-isic-6512),
[`6621`](https://github.com/cloud-itonami/cloud-itonami-isic-6621),
[`6622`](https://github.com/cloud-itonami/cloud-itonami-isic-6622),
[`6629`](https://github.com/cloud-itonami/cloud-itonami-isic-6629),
[`6520`](https://github.com/cloud-itonami/cloud-itonami-isic-6520),
[`6530`](https://github.com/cloud-itonami/cloud-itonami-isic-6530),
[`6820`](https://github.com/cloud-itonami/cloud-itonami-isic-6820),
[`6612`](https://github.com/cloud-itonami/cloud-itonami-isic-6612),
[`6492`](https://github.com/cloud-itonami/cloud-itonami-isic-6492),
[`6920`](https://github.com/cloud-itonami/cloud-itonami-isic-6920),
[`6611`](https://github.com/cloud-itonami/cloud-itonami-isic-6611),
[`7120`](https://github.com/cloud-itonami/cloud-itonami-isic-7120)) --
the first healthcare vertical (ISIC division 86) in this fleet. Here
it is **ClinicOps-LLM ⊣ Clinical Practice Governor**.

> **Why an actor layer at all?** An LLM is great at drafting an
> encounter summary, normalizing intake, and checking whether a
> proposed treatment appears on a patient's own recorded
> contraindication list -- but it has **no notion of which
> jurisdiction's medical/dental-licensing requirements are official,
> no license to administer a real treatment, prescription or
> procedure, and no way to know on its own whether the treating
> clinician's own license is actually current**. Letting it administer
> a treatment directly invites fabricated jurisdiction citations, a
> treatment administered against a patient's own known
> contraindication, and a lapsed clinician license being quietly waved
> through -- and liability for whoever runs it. This project seals the
> ClinicOps-LLM into a single node and wraps it with an independent
> **Clinical Practice Governor**, a human **approval workflow**, and an
> immutable **audit ledger**.

## Scope: what this actor does and does not do

This actor covers encounter intake through jurisdiction licensing
assessment, clinician-credential screening and treatment
administration. It does **not**, by itself, hold a license to practice
medicine or dentistry in any jurisdiction, and it does not claim to. It
also does **not** model a full drug-interaction/allergy cross-reference
database -- no interaction severity, no dosage-dependent
contraindications, no cross-drug interaction analysis (see
`clinic.registry/treatment-contraindicated?`'s own docstring for the
honest simplification this makes: does the proposed treatment itself
appear in the patient's own recorded contraindication set, not a full
clinical-pharmacology cross-reference). Whoever deploys and operates a
live instance (a licensed physician/dentist) supplies the
jurisdiction-specific license, the real clinical judgment and the real
EHR/practice-management-system integrations, and bears that
jurisdiction's liability -- the software supplies the governed,
spec-cited, audited execution scaffold so that operator does not have
to build the compliance layer from scratch for every new market.

### Actuation

**Administering a real treatment, prescription or procedure is never
autonomous, at any phase, by construction.** Two independent layers
enforce this (`clinic.governor`'s `:actuation/administer-treatment`
high-stakes gate and `clinic.phase`'s phase table, which never puts
`:treatment/administer` in any phase's `:auto` set) -- see
`clinic.phase`'s docstring and `test/clinic/phase_test.clj`'s
`treatment-administer-never-auto-at-any-phase`. The actor may draft,
check and recommend; a human licensed physician/dentist is always the
one who actually administers a treatment. Like `6511`/`6621`/`6629`/
`6612`/`6492`/`7120`, this actor has ONE actuation event.

## The core contract

```
encounter intake + jurisdiction facts (clinic.facts, spec-cited)
        |
        v
   ┌──────────────┐   proposal      ┌───────────────────────┐
   │ ClinicOps-   │ ─────────────▶ │ Clinical Practice           │  (independent system)
   │ LLM (sealed) │  + citations    │ Governor: spec-basis ·      │
   └──────────────┘                 │ evidence-incomplete ·        │
                             commit ◀────┼──────────▶ hold │ contraindicated
                                 │             │           │ (set-membership,
                           record + ledger  escalate ─▶ human   not arithmetic) ·
                                             (ALWAYS for         credential-not-current ·
                                              :treatment/           already-treated
                                              administer)
```

**The ClinicOps-LLM never administers a treatment the Clinical
Practice Governor would reject, and never does so without a human
sign-off.** Hard violations (fabricated jurisdiction requirements;
unsupported licensing evidence; a proposed treatment that appears on
the patient's own contraindication list; a lapsed clinician license; a
double administration) force **hold** and *cannot* be approved past; a
clean treatment proposal still always routes to a human.

## Run

```bash
clojure -M:dev:run     # walk one clean lifecycle (treatment administration) + four HARD-hold cases through the actor
clojure -M:dev:test    # governor contract · phase invariants · store parity · registry conformance · facts coverage
clojure -M:lint        # clj-kondo (errors fail; CI mirrors this)
```

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here a diagnostic-imaging robot
assists physical patient examination, under the actor, gated by the
independent **Clinical Practice Governor**. The governor never
dispatches hardware itself; `:high`/`:safety-critical` actions require
human sign-off.

## Open business

This repository is not only source code. It is a public, forkable
business model:

| Layer | What is open |
|---|---|
| OSS core | Actor runtime, Clinical Practice Governor, treatment-administration draft records, audit ledger |
| Business blueprint | Customer, offer, pricing, unit economics, sales motion |
| Operator playbook | How to fork, license, deploy and support the service in a jurisdiction |
| Trust controls | Governance, security reporting, actuation invariant, audit requirements |

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md) to start this as an
open business on itonami.cloud, and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
full architecture and decision record.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`8620`). Like `6920`/`7120`, this vertical's encounter/care records are
practice-specific rather than a shared cross-operator data contract, so
`clinic.*` runs on the generic identity/forms/dmn/bpmn/audit-ledger
stack only -- no bespoke domain capability lib to reference at all.

## Layout

| File | Role |
|---|---|
| `src/clinic/store.cljc` | **Store** protocol -- `MemStore` ‖ `DatomicStore` (`langchain.db`) + append-only audit ledger + treatment-administration history. No dynamically-filed sub-record -- the actuation op acts directly on a pre-seeded encounter, and the double-administration guard checks a dedicated `:treated?` boolean rather than a `:status` value |
| `src/clinic/registry.cljc` | Treatment-administration draft records, plus `treatment-contraindicated?` -- the FIRST check in this fleet to be a SET-MEMBERSHIP/conflict test rather than an arithmetic comparison (does the proposed treatment appear in the patient's own recorded contraindication set) |
| `src/clinic/facts.cljc` | Per-jurisdiction medical/dental-licensing catalog with an official spec-basis citation per entry, honest coverage reporting |
| `src/clinic/clinicopsllm.cljc` | **ClinicOps-LLM Advisor** -- `mock-advisor` ‖ `llm-advisor`; intake/assessment/credential-screening/treatment-administration proposals |
| `src/clinic/governor.cljc` | **Clinical Practice Governor** -- 4 HARD checks (spec-basis · evidence-incomplete · contraindicated, pure ground-truth set-membership recompute · credential-not-current, unconditional evaluation) + already-treated guard + 1 soft (confidence/actuation gate) |
| `src/clinic/phase.cljc` | **Phase 0→3** -- read-only → assisted intake → assisted assess → supervised (treatment always human; encounter intake is the ONLY auto-eligible op, no direct clinical risk) |
| `src/clinic/operation.cljc` | **OperationActor** -- langgraph-clj StateGraph |
| `src/clinic/sim.cljc` | demo driver |
| `test/clinic/*_test.clj` | governor contract · phase invariants · store parity · registry conformance · facts coverage |

## Business-process coverage (honest)

This actor covers encounter intake through jurisdiction licensing
assessment, clinician-credential screening and treatment
administration -- the core governed lifecycle this blueprint's own
`docs/business-model.md` names as its Offer:

| Covered | Not covered (out of scope for this R0) |
|---|---|
| Encounter intake + per-jurisdiction medical/dental-licensing checklisting, HARD-gated on an official spec-basis citation (`:encounter/intake`/`:jurisdiction/assess`) | A full drug-interaction/allergy cross-reference database (interaction severity, dosage-dependent contraindications, cross-drug interactions -- see `treatment-contraindicated?`'s docstring) |
| Clinician-credential screening, evaluated unconditionally so the screening op itself can HARD-hold on its own finding (`:credential/screen`) | Real EHR/practice-management-system integration, insurance/billing reporting |
| Treatment administration, HARD-gated on the proposed treatment not appearing on the patient's own contraindication list and a double-administration guard (`:treatment/administer`) | Ongoing patient-outcome/clinical-quality monitoring itself |
| Immutable audit ledger for every intake/assessment/screening/administration decision | |

Extending coverage is additive: add the next gate (e.g. a drug-
interaction check) as its own governed op with its own HARD checks and
tests, following the SAME "an independent governor re-verifies against
the actor's own records before any real-world act" pattern this repo's
flagship op already establishes.

## Jurisdiction coverage (honest)

`clinic.facts/coverage` reports how many requested jurisdictions
actually have an official spec-basis in `clinic.facts/catalog` --
currently 4 seeded (JPN, USA, GBR, DEU) out of ~194 jurisdictions
worldwide. This is a starting catalog to prove the governor contract
end-to-end, not a claim of global coverage. Adding a jurisdiction is
additive: one map entry in `clinic.facts/catalog`, citing a real
official source -- never fabricate a jurisdiction's requirements to make
coverage look bigger.

## Maturity

`:implemented` -- `ClinicOps-LLM` + `Clinical Practice Governor` run as
real, tested code (see `Run` above), promoted from the originally-
published `:blueprint`-tier scaffold, modeled closely on the thirteen
prior actors' architecture. See `docs/adr/0001-architecture.md` for
the history and design.

## License

Code and implementation templates are AGPL-3.0-or-later.
