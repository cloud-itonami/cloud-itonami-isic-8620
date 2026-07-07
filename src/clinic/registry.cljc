(ns clinic.registry
  "Pure-function treatment-administration record construction -- an
  append-only clinical book-of-record draft.

  Like every sibling actor's registry, there is no single international
  check-digit standard for a treatment-administration reference number
  -- every practice/jurisdiction assigns its own reference format. This
  namespace does NOT invent one; it builds a jurisdiction-scoped
  sequence number and validates the record's required fields, the same
  honest, non-fabricating discipline `clinic.facts` uses.

  `treatment-contraindicated?` is a REAL, foundational clinical-safety
  concept (a proposed treatment must not appear in a patient's own
  known contraindication/allergy list) -- see its own docstring for the
  honest simplification it makes vs. a full drug-interaction/allergy
  cross-reference database. Unlike every prior pure-ground-truth-
  recompute check in this fleet (`credit.governor`'s/`accounting.
  governor`'s/`marketadmin.governor`'s/`testlab.governor`'s threshold/
  range/equality comparisons), this is the FIRST check in this fleet
  to be a SET-MEMBERSHIP/conflict test rather than an arithmetic
  comparison -- does the proposed action itself appear in a forbidden
  set already on file for this entity, not whether some number crosses
  some line.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real practice-management/EHR system. It builds the
  RECORD a practice would keep, not the act of administering the
  treatment itself (that is `clinic.operation`'s `:treatment/
  administer`, always human-gated -- see README `Actuation`)."
  (:require [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is the
  treating clinician's act, not this actor's. See README `Actuation`."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn treatment-contraindicated?
  "Does `encounter`'s own `:proposed-treatment` appear in its own
  `:contraindications` set? A pure ground-truth check against the
  encounter's own permanent fields -- see ns docstring for the honest
  simplification this makes vs. a full drug-interaction/allergy cross-
  reference database (this R0 does not model interaction severity,
  dosage-dependent contraindications, or cross-drug interactions --
  only whether the proposed treatment itself is a member of the
  patient's own recorded contraindication set)."
  [{:keys [proposed-treatment contraindications]}]
  (contains? (set contraindications) proposed-treatment))

(defn register-treatment
  "Validate + construct the TREATMENT-ADMINISTRATION registration DRAFT
  -- the practice's own legal act of administering a real treatment,
  prescription or procedure. Pure function -- does not touch any real
  practice-management/EHR system; it builds the RECORD a practice
  would keep. `clinic.governor` independently re-verifies the
  encounter's own proposed treatment against its own contraindication
  list, and blocks a double-administration of the same encounter,
  before this is ever allowed to commit."
  [encounter-id jurisdiction sequence]
  (when-not (and encounter-id (not= encounter-id ""))
    (throw (ex-info "treatment: encounter_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "treatment: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "treatment: sequence must be >= 0" {})))
  (let [administration-number (str (str/upper-case jurisdiction) "-TX-" (zero-pad sequence 6))
        record {"record_id" administration-number
                "kind" "treatment-administration-draft"
                "encounter_id" encounter-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "administration_number" administration-number
     "certificate" (unsigned-certificate "TreatmentAdministration" administration-number administration-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
