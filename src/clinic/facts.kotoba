(ns clinic.facts
  "Per-jurisdiction medical/dental practice-licensing regulatory
  catalog -- the G2-style spec-basis table the Clinical Practice
  Governor checks every jurisdiction/assess proposal against ('did the
  advisor cite an OFFICIAL public source for this jurisdiction's
  physician/dentist licensing requirements, or did it invent one?').

  Coverage is reported HONESTLY (see `coverage`), the same discipline
  every sibling actor's `facts` namespace uses: a jurisdiction not in
  this table has NO spec-basis, full stop -- the advisor must not
  fabricate one, and the governor holds if it tries.

  Seed values are drawn from each jurisdiction's official medical-
  licensing authority (see `:provenance`); they are a STARTING catalog,
  not a from-scratch survey of all ~194 jurisdictions. Extending
  coverage is additive: add one map to `catalog`, cite a real source,
  done -- never invent a jurisdiction's requirements to make coverage
  look bigger.

  Like every sibling catalog, the USA entry cites a single national
  aggregating body (the Federation of State Medical Boards) rather
  than all 50 individual state medical boards -- an honest single
  representative citation, not a state-by-state survey, the same
  simplification every prior catalog makes when a jurisdiction's real
  regulatory structure is itself federated.")

(def catalog
  "iso3 -> requirement map. `:required-evidence` mirrors the generic
  patient-consent/diagnostic-evidence/clinician-license-verification/
  treatment-plan-documentation evidence set submitted in some form;
  `:legal-basis` / `:owner-authority` / `:provenance` are the G2
  citation the governor requires before any :jurisdiction/assess
  proposal can commit."
  {"JPN" {:name "Japan"
          :owner-authority "厚生労働省 (Ministry of Health, Labour and Welfare)"
          :legal-basis "医師法 (Medical Practitioners Act) + 歯科医師法 (Dentists Act)"
          :national-spec "医師免許・歯科医師免許制度"
          :provenance "https://www.mhlw.go.jp/"
          :required-evidence ["患者同意記録 (patient consent record)"
                              "診断根拠引用書 (diagnostic-evidence citation)"
                              "医師/歯科医師免許確認記録 (clinician license verification)"
                              "治療計画書 (treatment-plan documentation)"]}
   "USA" {:name "United States"
          :owner-authority "Federation of State Medical Boards (FSMB)"
          :legal-basis "State Medical Practice Acts (aggregated via FSMB)"
          :national-spec "FSMB Uniform Application / license-verification standards"
          :provenance "https://www.fsmb.org/"
          :required-evidence ["Patient consent record"
                              "Diagnostic-evidence citation"
                              "Clinician license verification"
                              "Treatment-plan documentation"]}
   "GBR" {:name "United Kingdom"
          :owner-authority "General Medical Council (GMC) / General Dental Council (GDC)"
          :legal-basis "Medical Act 1983 / Dentists Act 1984"
          :national-spec "GMC/GDC registration and licensing requirements"
          :provenance "https://www.gmc-uk.org/ https://www.gdc-uk.org/"
          :required-evidence ["Patient consent record"
                              "Diagnostic-evidence citation"
                              "Clinician license verification"
                              "Treatment-plan documentation"]}
   "DEU" {:name "Germany"
          :owner-authority "Bundesärztekammer (German Medical Association)"
          :legal-basis "Bundesärzteordnung (Federal Medical Practitioners Act)"
          :national-spec "Bundesärztekammer Approbation/licensing requirements"
          :provenance "https://www.bundesaerztekammer.de/"
          :required-evidence ["Patienteneinwilligung (patient consent record)"
                              "Diagnosebegründung (diagnostic-evidence citation)"
                              "Approbationsnachweis (clinician license verification)"
                              "Behandlungsplan (treatment-plan documentation)"]}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any proposal that tries to administer a
  treatment on it."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions actually
  have a spec-basis entry. Never report a missing jurisdiction as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-8620 R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `clinic.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements.")})))

(defn required-evidence-satisfied?
  "Does `submitted` (a set/coll of evidence keywords or strings) satisfy
  every evidence item listed for `iso3`? Missing spec-basis -> never
  satisfied."
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))

(defn evidence-checklist [iso3]
  (:required-evidence (spec-basis iso3) []))
