# ADR-0001: cloud-itonami-isic-8620 -- ClinicOps-LLM as a contained intelligence node

- Status: Accepted (2026-07-07)
- Related: `cloud-itonami-isic-6511`/`6512`/`6621`/`6622`/`6629`/`6520`/
  `6530`/`6820`/`6612`/`6492`/`6920`/`6611`/`7120` ADR-0001s (the
  pattern this ADR ports); ADR-2607071250/ADR-2607071320/
  ADR-2607071351/ADR-2607071618/ADR-2607071640 (`6612`/`6492`/`6920`/
  `6611`/`7120`, the five verticals built outside ADR-2607032000's
  original insurance/real-estate batch -- this is the sixth)
- Context: Continuing the standing "pick a new ISIC blueprint vertical"
  direction past `7120`, this ADR deepens `cloud-itonami-isic-8620`
  (medical and dental practice activities) from `:blueprint` to
  `:implemented`, the fourteenth actor in this fleet -- the FIRST
  healthcare vertical (ISIC division 86), diversifying this fleet
  further beyond finance/insurance and professional/technical services.

## Problem

A medical/dental practice's treatment-administration workflow bundles
several distinct concerns under one governed workflow:

1. **Jurisdiction medical/dental-licensing correctness** -- is the
   required evidence for administering a treatment based on an
   official licensing authority (MHLW/FSMB/GMC-GDC/Bundesärztekammer),
   or invented?
2. **Contraindication safety** -- does a proposed treatment appear on
   the patient's own recorded contraindication/allergy list? Unlike
   every prior pure-ground-truth-recompute check in this fleet
   (`credit.governor`'s/`accounting.governor`'s/`marketadmin.
   governor`'s/`testlab.governor`'s arithmetic threshold/range/equality
   comparisons), this is a SET-MEMBERSHIP/conflict test -- does the
   proposed action itself appear in a forbidden set already on file,
   not whether some number crosses some line.
3. **Clinician credential currency** -- does the treating clinician
   carry a current license, or a lapsed one? The healthcare-specific
   reuse of the unconditional-evaluation screening discipline this
   fleet's `casualty.governor/sanctions-violations` originally
   established.
4. **Real actuation, once** -- administering a real treatment,
   prescription or procedure is an irreversible act a patient's health
   outcome depends on.

An LLM has no authority or grounding for any of these. The design
problem is therefore not "run a medical/dental practice with an LLM"
but "seal the LLM inside a trust boundary and layer evidence-
sufficiency, contraindication-safety verification, credential-currency
screening, audit and human-approval on top of it, while structurally
fixing the one real actuation event as human-only."

## Decision

### 1. ClinicOps-LLM is sealed into the bottom node; it never treats directly

`clinic.clinicopsllm` returns exactly four kinds of proposal: intake
normalization, jurisdiction licensing checklist, credential screening,
and treatment-administration draft. No proposal writes the SSoT or
commits a real treatment administration directly.

### 2. OperationActor = langgraph-clj StateGraph, 1 run = 1 medical/dental-practice operation

`clinic.operation/build` is the SAME StateGraph shape as every sibling
actor's operation namespace, copied verbatim.

### 3. `treatment-contraindicated?` is the FIRST set-membership check in this fleet

`clinic.registry/treatment-contraindicated?` requires
`(contains? contraindications proposed-treatment)` -- a boolean set-
conflict test, not an arithmetic comparison. Every prior pure-ground-
truth-recompute check in this fleet has been an inequality or equality
over numbers (`credit.governor/affordability-exceeded-violations`'s
ratio-vs-ceiling, `accounting.governor/trial-balance-out-of-balance-
violations`'s equality-of-sums, `marketadmin.governor/listing-
standard-not-met-violations`'s value-vs-floor, `testlab.governor/out-
of-tolerance-violations`'s two-sided range). `contraindicated-
violations` reuses the SAME pure-ground-truth-recompute shape (no
proposal inspection or stored-verdict lookup needed at all, since its
inputs -- `:proposed-treatment`/`:contraindications` -- are permanent
facts already on the encounter), but generalizes the family's
"arithmetic comparison" pattern to a "set-membership" pattern for the
first time.

### 4. Credential screening reuses the unconditional-evaluation discipline for a further domain

`credential-not-current-violations` reuses `casualty.governor/
sanctions-violations`'s fix (evaluated unconditionally, not scoped to a
specific op, so the screening op itself can HARD-hold on its own
finding) for a further application in this fleet, for BOTH
`:credential/screen` and `:treatment/administer` -- the SAME shape
`marketadmin.governor/surveillance-flag-unresolved-violations`/
`testlab.governor/calibration-not-current-violations` establish for
their own domains (a party-screening-verdict grounding, a market-
surveillance-flag grounding, an instrument-calibration grounding, and
now a clinician-license grounding -- the fourth distinct application
of this exact discipline).

### 5. Single actuation event

`clinic.governor`'s `high-stakes` set has exactly one member
(`:actuation/administer-treatment`), matching `6511`'s/`6621`'s/
`6629`'s/`6612`'s/`6492`'s/`7120`'s single-actuation shape -- this
domain has one distinct real-world clinical act (administering a
treatment), not several independently-gated acts.

### 6. Double-administration guard checks a dedicated boolean fact, not `:status` -- deliberately sidestepping `6492`'s lifecycle trap

`already-treated-violations` checks `:treated?`, a dedicated boolean
set once and never cleared, rather than a `:status` value that could
legitimately advance past a checked state (the exact trap `cloud-
itonami-isic-6492`'s ADR-0001 documents in detail, rediscovered there
despite four prior safe reuses, and explicitly avoided BY DESIGN in
`6920`'s, `6611`'s and `7120`'s equivalent guards). This actor's
`:status` never needs to encode "has this actuation already happened"
at all, so there is no analogous status-lifecycle risk to fall into
here -- a deliberate architectural choice informed directly by the
lesson from three prior builds, applied here for a fourth consecutive
time.

### 7. No fabricated international treatment-administration-number standard

Same discipline as every sibling's registry: there is no single
international check-digit standard for a treatment-administration
reference number. `clinic.registry` therefore does not invent one; it
validates required fields and assigns a jurisdiction-scoped sequence
number only.

### 8. No bespoke capability lib

Like `6920`/`7120`, and unlike most other actors in this fleet (each
referencing its own `kotoba-lang/*` capability lib), this vertical's
encounter/care records are practice-specific rather than a shared
cross-operator data contract -- `clinic.*` runs on the generic
identity/forms/dmn/bpmn/audit-ledger stack only, per the blueprint's
own explicit statement.

### 9. No bug this time

Like `7120` (and unlike `6492`'s status-lifecycle bug or `6920`'s
NullPointerException), this build's test suite, lint, and demo-ledger
verification all passed clean on the first run -- the `already-
treated-violations` design (Decision 6) and the `contraindicated-
violations` pure-recompute shape (Decision 3) were both DELIBERATELY
informed by prior builds' lessons before writing any code. The demo
(`clojure -M:dev:run`) was still independently verified against the
printed audit ledger -- basis tags `:no-spec-basis` ·
`:contraindicated` · `:credential-not-current` · `:already-treated`
all appear exactly where the sim script intends, and the treatment
history contains exactly one drafted record after the double-
administration attempt is held -- the same discipline that caught
every real bug in this fleet so far, applied here and finding nothing
to fix.

## Consequences

- (+) Medical/dental practice gets the same governed, auditable-actor
  treatment as the thirteen prior actors, extending the pattern to a
  genuinely different domain (healthcare, ISIC division 86) for the
  first time.
- (+) The actuation invariant (governor + phase, two layers) is
  regression-tested by `test/clinic/phase_test.clj`'s `treatment-
  administer-never-auto-at-any-phase`.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/clinic/
  store_contract_test.clj`, the same `:db-api`-driven swap pattern
  every sibling actor uses.
- (+) `treatment-contraindicated?`/`contraindicated-violations` is a
  genuine new check shape for this fleet (set-membership/conflict, not
  an arithmetic comparison), regression-tested by `test/clinic/
  governor_contract_test.clj`'s `contraindicated-treatment-is-held`.
- (+) The dedicated-boolean double-actuation-guard lesson (from
  `6492`'s bug) has now been applied correctly BY DESIGN across a
  FOURTH consecutive build (`6920`, `6611`, `7120`, `8620`), each
  explicitly informed by the prior lesson rather than re-derived by
  shape-analogy.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `clinic.facts/coverage`
  reports this honestly rather than claiming broader coverage.
- (-) `treatment-contraindicated?` models only whether the proposed
  treatment itself is a member of the patient's own recorded
  contraindication set, not a full drug-interaction/allergy cross-
  reference database (interaction severity, dosage-dependent
  contraindications, cross-drug interactions are out of scope -- see
  that fn's own docstring); real EHR/practice-management-system
  integration and ongoing patient-outcome/clinical-quality monitoring
  are all out of scope for this OSS actor -- each operator's
  responsibility (see README's coverage table).
- 29 tests / 127 assertions, lint clean.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Keep `cloud-itonami-isic-8620` at `:blueprint` only | ❌ | The standing direction continues past `7120`; medical/dental practice is a natural, well-precedented next domain, and a deliberate diversification into healthcare (ISIC division 86), a division this fleet had not yet touched |
| Model `contraindicated-violations` against the LLM's own proposal rather than a pure ground-truth recompute | ❌ | Every prior actuation-adjacent numeric check in this fleet independently recomputes from the entity's own permanent fields rather than trusting the proposal's self-report -- the same discipline applies here: the encounter's own `:contraindications` set is the ground truth, not whatever the advisor claims to have checked |
| Model a full drug-interaction/allergy cross-reference database for conformance-test rigor | ❌ | Genuinely more complex real-world clinical pharmacology that this R0 does not claim to model correctly -- honestly scoped to a set-membership check against the patient's own recorded contraindications instead, same as every sibling's "starting catalog, not exhaustive" posture |
| Reference a capability lib (e.g. a hypothetical `kotoba-lang/health`) for consistency with most prior actors | ❌ | The blueprint itself explicitly states this vertical's records are practice-specific, not a shared cross-operator contract -- inventing a capability lib reference where the blueprint says none exists would misrepresent the domain, the same reasoning `6920`'s and `7120`'s ADRs already established |
