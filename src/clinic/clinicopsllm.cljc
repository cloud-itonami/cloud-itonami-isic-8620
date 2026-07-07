(ns clinic.clinicopsllm
  "ClinicOps-LLM client -- the *contained intelligence node* for the
  medical/dental-practice actor.

  It normalizes encounter intake, drafts a per-jurisdiction medical/
  dental-licensing evidence checklist, screens encounters for a lapsed
  clinician license, and drafts the treatment-administration action.
  CRITICAL: it is a smart-but-untrusted advisor. It returns a
  *proposal* (with a rationale + the fields it cited), never a
  committed record or a real treatment administration. Every output is
  censored downstream by `clinic.governor` before anything touches the
  SSoT, and `:treatment/administer` proposals NEVER auto-commit at any
  phase -- see README `Actuation`.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis gate
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     kw             ; how a commit would mutate the SSoT
     :stake      kw|nil         ; :actuation/administer-treatment | nil
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [clinic.facts :as facts]
            [clinic.registry :as registry]
            [clinic.store :as store]
            [langchain.model :as model]))

(defn- normalize-intake
  "Directory upsert -- the LLM only normalizes/validates the patch; it
  does not invent the patient, chief complaint/proposed treatment or
  jurisdiction. High confidence, low stakes."
  [_db {:keys [patch]}]
  {:summary    (str "診療記録更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :encounter/upsert
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- assess-jurisdiction
  "Per-jurisdiction medical/dental-licensing evidence checklist draft.
  `:no-spec?` injects the failure mode we must defend against:
  proposing a checklist for a jurisdiction with NO official spec-basis
  in `clinic.facts` -- the Clinical Practice Governor must reject this
  (never invent a jurisdiction's requirements)."
  [db {:keys [subject no-spec?]}]
  (let [e (store/encounter db subject)
        iso3 (if no-spec? "ATL" (:jurisdiction e))
        sb (facts/spec-basis iso3)]
    (if (nil? sb)
      {:summary    (str iso3 " の公式spec-basisが見つかりません")
       :rationale  "clinic.facts に未登録の法域。要件を推測で作らない。"
       :cites      []
       :effect     :assessment/set
       :value      {:jurisdiction iso3 :checklist [] :spec-basis nil}
       :stake      nil
       :confidence 0.9}
      {:summary    (str iso3 " (" (:owner-authority sb) ") 向け必要書類 "
                        (count (:required-evidence sb)) " 件を提案")
       :rationale  (str "公式ソース: " (:provenance sb) " / 法的根拠: " (:legal-basis sb))
       :cites      [(:legal-basis sb) (:provenance sb)]
       :effect     :assessment/set
       :value      {:jurisdiction iso3
                    :checklist (:required-evidence sb)
                    :spec-basis (:provenance sb)
                    :legal-basis (:legal-basis sb)}
       :stake      nil
       :confidence 0.9})))

(defn- screen-credential
  "Clinician-license screening draft. `:clinician-license-current?` on
  the encounter record injects the failure mode: the Clinical Practice
  Governor must HOLD, un-overridably, on any lapsed license."
  [db {:keys [subject]}]
  (let [e (store/encounter db subject)]
    (cond
      (nil? e)
      {:summary "対象encounterが見つかりません" :rationale "no encounter record"
       :cites [] :effect :credential/set :value {:encounter-id subject :verdict :unknown}
       :stake nil :confidence 0.0}

      (false? (:clinician-license-current? e))
      {:summary    (str (:patient e) ": 臨床医の免許失効を検出")
       :rationale  "スクリーニングが免許失効を検出。人手確認とホールドが必須。"
       :cites      [:credential-check]
       :effect     :credential/set
       :value      {:encounter-id subject :verdict :not-current}
       :stake      nil
       :confidence 0.95}

      :else
      {:summary    (str (:patient e) ": 免許は有効")
       :rationale  "免許スクリーニング非該当。"
       :cites      [:credential-check]
       :effect     :credential/set
       :value      {:encounter-id subject :verdict :current}
       :stake      nil
       :confidence 0.9})))

(defn- propose-treatment
  "Draft the actual TREATMENT-ADMINISTRATION action -- administering a
  real treatment, prescription or procedure. ALWAYS `:stake :actuation/
  administer-treatment` -- this is a REAL-WORLD act (a patient's health
  outcome depends on it), never a draft the actor may auto-run. See
  README `Actuation`: no phase ever adds this op to a phase's `:auto`
  set (`clinic.phase`); the governor also always escalates on
  `:actuation/administer-treatment`. Two independent layers agree,
  deliberately."
  [db {:keys [subject]}]
  (let [e (store/encounter db subject)
        safe? (and e (not (registry/treatment-contraindicated? e)))]
    {:summary    (str subject " 向け治療実施提案"
                      (when e (str " (patient=" (:patient e) ")")))
     :rationale  (if e
                   (str "proposed-treatment=" (:proposed-treatment e)
                        " contraindications=" (:contraindications e))
                   "encounterが見つかりません")
     :cites      (if e [subject] [])
     :effect     :encounter/mark-treated
     :value      {:encounter-id subject}
     :stake      :actuation/administer-treatment
     :confidence (if safe? 0.9 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :encounter/intake       (normalize-intake db request)
    :jurisdiction/assess       (assess-jurisdiction db request)
    :credential/screen           (screen-credential db request)
    :treatment/administer            (propose-treatment db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは診療所の治療実施エージェントの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。説明や前置きは"
       "一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:encounter/upsert|:assessment/set|:credential/set|"
       ":encounter/mark-treated) "
       ":stake(:actuation/administer-treatment か nil) :confidence(0..1)。\n"
       "重要: 登録されていない法域の要件を絶対に創作してはいけません。"
       "spec-basisが無い場合は :cites を空にし confidence を上げないこと。"))

(defn- facts-for [st {:keys [op subject]}]
  (case op
    :jurisdiction/assess  {:encounter (store/encounter st subject)}
    :credential/screen    {:encounter (store/encounter st subject)}
    :treatment/administer {:encounter (store/encounter st subject)}
    {:encounter (store/encounter st subject)}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the Clinical Practice Governor
  escalates/holds -- an LLM hiccup can never auto-administer a
  treatment."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :clinicopsllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
