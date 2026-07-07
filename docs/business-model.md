# Business Model: Medical and dental practice activities

## Classification

- Repository: `cloud-itonami-isic-8620`
- ISIC Rev.5: `8620`
- Activity: medical and dental practice activities -- outpatient consultation, diagnosis and treatment by licensed physicians/dentists
- Social impact: care quality, data sovereignty, transparent audit

## Customer

- independent medical/dental practices
- cooperative clinic networks
- community health-access programs

## Offer

- patient intake
- diagnostic/treatment-plan proposal
- prescription/procedure proposal
- immutable audit ledger

## Revenue

- self-host setup: one-time implementation fee
- managed hosting: monthly subscription per practice
- support: monthly retainer with SLA
- migration: import from an incumbent practice-management system
- per-visit fee

## Trust Controls

- no treatment, prescription or procedure is administered without human sign-off (licensed physician/dentist)
- a fabricated jurisdiction licensing citation, incomplete licensing
  evidence, a proposed treatment that appears on the patient's own
  recorded contraindication list, or a lapsed clinician license -- each
  forces a hold, not an override
- an encounter cannot be treated twice: a double-administration attempt
  is held off this actor's own encounter facts alone, with no upstream
  comparison needed
- every intake, assessment, screening and administration path is
  auditable
- patient health data stays outside Git
- emergency manual override paths remain outside LLM control
