# Operator Quickstart

Get the Medical and dental practice activities actor (`cloud-itonami-isic-8620`) running locally in 5 minutes.

## Who This Is For

- **Licensed physicians and dentists** in any jurisdiction who need a governed, spec-cited compliance scaffold for patient intake, licensing assessment, and treatment administration
- **Practice operators** who want to fork and deploy this blueprint as their own open business, with an independent Governor verifying every high-stakes decision
- **Developers** exploring the LLM+Governor pattern and how an autonomous actor layer enforces trust controls without license itself

## Prerequisites

- **Clojure** — `clojure` CLI tool (e.g. `brew install clojure/tools/clojure` on macOS, or [download](https://clojure.org/guides/install_clojure))
- **Java** — JDK 11+ (check with `java -version`)
- **Monorepo context** (optional) — if running inside the full `cloud-itonami` workspace:
  - `langgraph-clj` and `langchain-clj` dependencies resolve via `:local/root` paths in `deps.edn`
  - See `deps.edn` for the exact paths — the `:dev` alias pins them to the workspace checkout
  - Standalone fork: override those dependencies with git coordinates instead

## Run Tests

```bash
clojure -M:dev:test
```

This runs the full test suite covering:
- **Governor contract** — no treatment administers without hard-gating on spec-basis, evidence, contraindication, and credential checks
- **Phase invariants** — phase progression rules (read-only → assisted intake → assisted assess → supervised)
- **Store parity** — both in-memory and Datomic stores honor the treatment-administration audit trail
- **Registry conformance** — treatment proposals and contraindication checks
- **Facts coverage** — honest reporting of jurisdiction medical/dental-licensing catalog (JPN, USA, GBR, DEU only in R0)

## Run the Demo

```bash
clojure -M:dev:run
```

This drives one clean encounter intake + treatment administration through the OperationActor (happy path), plus four hard-hold cases:
1. Fabricated jurisdiction requirement (spec-basis gate fails)
2. Incomplete licensing evidence (evidence-incomplete gate fails)
3. Treatment on patient contraindication list (contraindication gate fails)
4. Lapsed clinician license (credential-not-current gate fails)

Demo output appears in stdout. The actor never administers a treatment without human sign-off; all holds are immutably logged.

## Open the Documentation

```bash
# View the README (architecture, scope, contract)
open README.md

# View the business model
open docs/business-model.md

# View the operator guide
open docs/operator-guide.md
```

## The Governor

The **Clinical Practice Governor** (`src/clinic/governor.cljc`) is the independent verification layer that seals the ClinicOps-LLM and enforces trust controls. It gates every treatment administration decision with four hard checks:

- **`spec-basis?`** — Is the jurisdiction requirement cited from an official spec? (Fabricated requirements are rejected outright.)
- **`evidence-complete?`** — Is the licensing proof sufficient for the jurisdiction?
- **`treatment-contraindicated?`** — Does the proposed treatment appear on the patient's own contraindication set? (Set-membership check, not inference.)
- **`credential-current?`** — Is the treating clinician's license up to date? (Unconditional evaluation; no workarounds.)

The governor is tested independently (`test/clinic/governor_test.clj`) and integrated into the OperationActor (`src/clinic/operation.cljc`), which routes all high-stakes decisions through it. No treatment administers past a governor hold; holds cannot be overridden.

## Linting

```bash
clojure -M:lint
```

Runs `clj-kondo` (no external binary required). Errors fail CI.

## Deploy

For managed hosting or a self-hosted deployment on your own infrastructure:

1. **Register the operator's license, jurisdiction and responsible clinicians/staff**
2. **Import historical patient/resident records and counterparties**
3. **Run read-only validation** against this blueprint's contracts
4. **Configure the Clinical Practice Governor's hold/escalation policy**
5. **Publish a dry-run operation and audit export**

See [`docs/operator-guide.md`](operator-guide.md) and [`https://itonami.cloud/docs/go-live.md`](https://itonami.cloud/docs/go-live.md) for the full path.

## What Next?

- **Browse the source** — `src/clinic/*.cljc` for the actor logic, governor, and ledger
- **Read the architecture** — `docs/adr/0001-architecture.md`
- **Check the business model** — `docs/business-model.md` lists the offer (intake, diagnostic/treatment-plan proposals, immutable ledger) and revenue streams
- **Fork the repo** — visit [`https://itonami.cloud/isco-1212/`](https://itonami.cloud/isco-1212/) to claim your fork
