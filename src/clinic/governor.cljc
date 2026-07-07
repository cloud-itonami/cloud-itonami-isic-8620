(ns clinic.governor
  "Clinical Practice Governor -- the independent compliance layer that
  earns the ClinicOps-LLM the right to commit. The LLM has no notion of
  jurisdictional medical/dental-licensing law, whether a proposed
  treatment actually appears on a patient's own recorded
  contraindication list, whether the treating clinician's own license
  is actually current, or when an act stops being a draft and becomes
  a real-world treatment administration, so this MUST be a separate
  system able to *reject* a proposal and fall back to HOLD -- the
  medical/dental-practice analog of `cloud-itonami-isic-6512`'s
  CasualtyGovernor.

  Four checks, in priority order, ALL HARD violations: a human approver
  CANNOT override them (you don't get to approve your way past a
  fabricated jurisdiction spec-basis, incomplete licensing evidence, a
  treatment that appears on the patient's own contraindication list, a
  not-current clinician license, or administering a treatment to the
  same encounter twice). The confidence/actuation gate is SOFT: it asks
  a human to look (low confidence / actuation), and the human may
  approve -- but see `clinic.phase`: for `:stake :actuation/administer-
  treatment` (a real treatment administration) NO phase ever allows
  auto-commit either. Two independent layers agree that actuation is
  always a human call.

    1. Spec-basis                  -- did the jurisdiction proposal cite
                                       an OFFICIAL source (`clinic.
                                       facts`), or invent one? Like
                                       `credit.governor`'s/`marketadmin.
                                       governor`'s/`testlab.governor`'s
                                       actuation ops, `:treatment/
                                       administer` acts directly on a
                                       pre-seeded encounter (see
                                       `clinic.store`'s own docstring)
                                       -- there is no 'encounter is
                                       missing' failure mode to guard
                                       against here.
    2. Evidence incomplete         -- for `:treatment/administer`, has
                                       the jurisdiction actually been
                                       assessed with a full licensing
                                       evidence checklist on file?
    3. Contraindicated             -- for `:treatment/administer`,
                                       INDEPENDENTLY recompute whether
                                       the encounter's own `:proposed-
                                       treatment` appears in its own
                                       `:contraindications` set
                                       (`clinic.registry/treatment-
                                       contraindicated?`) -- needs no
                                       proposal inspection or stored-
                                       verdict lookup at all, the SAME
                                       pure-ground-truth-recompute
                                       shape `credit.governor/
                                       affordability-exceeded-
                                       violations`/`accounting.
                                       governor/trial-balance-out-of-
                                       balance-violations`/`marketadmin.
                                       governor/listing-standard-not-met-
                                       violations`/`testlab.governor/
                                       out-of-tolerance-violations`
                                       establish -- but the FIRST check
                                       in this fleet to be a SET-
                                       MEMBERSHIP/conflict test rather
                                       than an arithmetic comparison.
    4. Credential not current      -- reported by THIS proposal itself
                                       (a `:credential/screen` that just
                                       found a lapsed license), or
                                       already on file for the
                                       encounter (`:credential/screen`/
                                       `:treatment/administer`).
                                       Evaluated UNCONDITIONALLY (not
                                       scoped to a specific op), the
                                       SAME discipline `casualty.
                                       governor/sanctions-violations`/
                                       `marketadmin.governor/
                                       surveillance-flag-unresolved-
                                       violations`/`testlab.governor/
                                       calibration-not-current-
                                       violations` established -- a
                                       lapsed license blocks
                                       administration even if the
                                       screening op itself never
                                       (re)ran in this session.
    5. Confidence floor / actuation
       gate                          -- LLM confidence below threshold,
                                       OR the op is `:treatment/
                                       administer` (a REAL clinical act)
                                       -> escalate.

  One more guard, double-administration prevention, is enforced but NOT
  listed as a numbered HARD check above because it needs no upstream
  comparison at all -- `already-treated-violations` refuses to
  administer a treatment to the SAME encounter twice, off a dedicated
  `:treated?` fact (never a `:status` value) -- the SAME 'check a
  dedicated boolean, not status' discipline `accounting.governor`'s/
  `marketadmin.governor`'s/`testlab.governor`'s guards establish,
  informed by `cloud-itonami-isic-6492`'s status-lifecycle bug
  (ADR-2607071320)."
  (:require [clinic.facts :as facts]
            [clinic.registry :as registry]
            [clinic.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Administering a real treatment, prescription or procedure is the ONE
  real-world actuation event this actor performs -- a single-member
  set, matching `cloud-itonami-isic-6511`'s/`6621`'s/`6629`'s/`6612`'s/
  `6492`'s/`7120`'s single-actuation shape."
  #{:actuation/administer-treatment})

;; ----------------------------- checks -----------------------------

(defn- spec-basis-violations
  "A `:jurisdiction/assess` (or `:treatment/administer`) proposal with
  no spec-basis citation is a HARD violation -- never invent a
  jurisdiction's medical/dental-licensing requirements."
  [{:keys [op]} proposal]
  (when (contains? #{:jurisdiction/assess :treatment/administer} op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basisの引用が無い提案は法域要件として扱えない"}]))))

(defn- evidence-incomplete-violations
  "For `:treatment/administer`, the jurisdiction's required patient-
  consent/diagnostic-evidence/clinician-license-verification/
  treatment-plan evidence must actually be satisfied -- do not trust
  the advisor's self-reported confidence alone."
  [{:keys [op subject]} st]
  (when (= op :treatment/administer)
    (let [e (store/encounter st subject)
          assessment (store/assessment-of st subject)]
      (when-not (and assessment
                     (facts/required-evidence-satisfied?
                      (:jurisdiction e) (:checklist assessment)))
        [{:rule :evidence-incomplete
          :detail "法域の必要書類(患者同意記録/診断根拠引用書/免許確認記録等)が充足していない状態での治療実施提案"}]))))

(defn- contraindicated-violations
  "For `:treatment/administer`, INDEPENDENTLY recompute whether the
  encounter's own proposed treatment appears in its own recorded
  contraindication set via `clinic.registry/treatment-contraindicated?`
  -- needs no proposal inspection or stored-verdict lookup at all,
  since its inputs are permanent ground-truth fields already on the
  encounter. The FIRST check in this fleet to be a set-membership/
  conflict test rather than an arithmetic comparison."
  [{:keys [op subject]} st]
  (when (= op :treatment/administer)
    (let [e (store/encounter st subject)]
      (when (registry/treatment-contraindicated? e)
        [{:rule :contraindicated
          :detail (str subject " の提案治療(" (:proposed-treatment e)
                      ")が患者自身の禁忌リスト" (:contraindications e) "に含まれている")}]))))

(defn- credential-not-current-violations
  "A not-current clinician license -- reported by THIS proposal (e.g. a
  `:credential/screen` that itself just found a lapsed license), or
  already on file in the store for the encounter (`:credential/
  screen`/`:treatment/administer`) -- is a HARD, un-overridable hold.
  Evaluated UNCONDITIONALLY (not scoped to a specific op) so the
  screening op itself can HARD-hold on its own finding."
  [{:keys [op subject]} proposal st]
  (let [hit-in-proposal? (= :not-current (get-in proposal [:value :verdict]))
        encounter-id (when (contains? #{:credential/screen :treatment/administer} op) subject)
        hit-on-file? (and encounter-id (= :not-current (:verdict (store/credential-of st encounter-id))))]
    (when (or hit-in-proposal? hit-on-file?)
      [{:rule :credential-not-current
        :detail "臨床医の免許が最新でない状態での治療実施提案は進められない"}])))

(defn- already-treated-violations
  "For `:treatment/administer`, refuses to administer a treatment to
  the SAME encounter twice, off a dedicated `:treated?` fact (never a
  `:status` value) -- see ns docstring for why this sidesteps the
  status-lifecycle risk `cloud-itonami-isic-6492`'s ADR-0001
  documents."
  [{:keys [op subject]} st]
  (when (= op :treatment/administer)
    (when (store/encounter-already-treated? st subject)
      [{:rule :already-treated
        :detail (str subject " は既に治療実施済み")}])))

(defn check
  "Censors a ClinicOps-LLM proposal against the governor rules. Returns
   {:ok? bool :violations [..] :confidence c :escalate? bool :high-stakes? bool
    :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (contraindicated-violations request st)
                           (credential-not-current-violations request proposal st)
                           (already-treated-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
