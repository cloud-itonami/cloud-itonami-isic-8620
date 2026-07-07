(ns clinic.store
  "SSoT for the medical/dental-practice actor, behind a `Store`
  protocol so the backend is a swap, not a rewrite -- the same seam
  every prior `cloud-itonami-isic-*` actor in this fleet uses:

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/clinic/store_contract_test.clj), which is the whole point: the
  actor, the Clinical Practice Governor and the audit ledger never
  know which SSoT they run on.

  Like `credit.store`'s/`accounting.store`'s/`marketadmin.store`'s/
  `testlab.store`'s simpler entities, an ENCOUNTER is acted on directly
  by the ONE actuation op -- no dynamically-filed sub-record, and the
  double-administration guard checks a dedicated `:treated?` boolean
  rather than a `:status` value, the same discipline `accounting.
  governor`'s/`marketadmin.governor`'s/`testlab.governor`'s guards
  establish.

  The ledger stays append-only on every backend: 'which encounter was
  screened for a current clinician license, which treatment was
  administered, on what jurisdictional basis, approved by whom' is
  always a query over an immutable log -- the audit trail a patient
  trusting a practice needs, and the evidence an operator needs if a
  treatment is later disputed."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clinic.registry :as registry]
            [langchain.db :as d]))

(defprotocol Store
  (encounter [s id])
  (all-encounters [s])
  (credential-of [s encounter-id] "committed credential screening verdict for an encounter, or nil")
  (assessment-of [s encounter-id] "committed jurisdiction licensing assessment, or nil")
  (ledger [s])
  (treatment-history [s] "the append-only treatment-administration history (clinic.registry drafts)")
  (next-sequence [s jurisdiction] "next treatment-administration-number sequence for a jurisdiction")
  (encounter-already-treated? [s encounter-id] "has this encounter already been treated?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-encounters [s encounters] "replace/seed the encounter directory (map id->encounter)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained encounter set so the actor + tests run
  offline."
  []
  {:encounters
   {"encounter-1" {:id "encounter-1" :patient "Sakura Tanaka" :chief-complaint "bacterial sinus infection"
                    :proposed-treatment :amoxicillin :contraindications #{}
                    :clinician-license-current? true :treated? false :jurisdiction "JPN" :status :intake}
    "encounter-2" {:id "encounter-2" :patient "Atlantis Doe" :chief-complaint "bacterial sinus infection"
                    :proposed-treatment :amoxicillin :contraindications #{}
                    :clinician-license-current? true :treated? false :jurisdiction "ATL" :status :intake}
    "encounter-3" {:id "encounter-3" :patient "鈴木一郎" :chief-complaint "streptococcal pharyngitis"
                    :proposed-treatment :penicillin :contraindications #{:penicillin}
                    :clinician-license-current? true :treated? false :jurisdiction "JPN" :status :intake}
    "encounter-4" {:id "encounter-4" :patient "田中花子" :chief-complaint "bacterial sinus infection"
                    :proposed-treatment :amoxicillin :contraindications #{}
                    :clinician-license-current? false :treated? false :jurisdiction "JPN" :status :intake}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- administer-treatment!
  "Backend-agnostic `:encounter/mark-treated` -- looks up the encounter
  via the protocol and drafts the treatment-administration record, and
  returns {:result .. :encounter-patch ..} for the caller to persist."
  [s encounter-id]
  (let [e (encounter s encounter-id)
        seq-n (next-sequence s (:jurisdiction e))
        result (registry/register-treatment encounter-id (:jurisdiction e) seq-n)]
    {:result result
     :encounter-patch {:treated? true
                       :administration-number (get result "administration_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (encounter [_ id] (get-in @a [:encounters id]))
  (all-encounters [_] (sort-by :id (vals (:encounters @a))))
  (credential-of [_ id] (get-in @a [:credential id]))
  (assessment-of [_ encounter-id] (get-in @a [:assessments encounter-id]))
  (ledger [_] (:ledger @a))
  (treatment-history [_] (:treatments @a))
  (next-sequence [_ jurisdiction] (get-in @a [:sequences jurisdiction] 0))
  (encounter-already-treated? [_ encounter-id] (boolean (get-in @a [:encounters encounter-id :treated?])))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :encounter/upsert
      (swap! a update-in [:encounters (:id value)] merge value)

      :assessment/set
      (swap! a assoc-in [:assessments (first path)] payload)

      :credential/set
      (swap! a assoc-in [:credential (first path)] payload)

      :encounter/mark-treated
      (let [encounter-id (first path)
            {:keys [result encounter-patch]} (administer-treatment! s encounter-id)
            jurisdiction (:jurisdiction (encounter s encounter-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:sequences jurisdiction] (fnil inc 0))
                       (update-in [:encounters encounter-id] merge encounter-patch)
                       (update :treatments registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-encounters [s encounters] (when (seq encounters) (swap! a assoc :encounters encounters)) s))

(defn seed-db
  "A MemStore seeded with the demo encounter set. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :assessments {} :credential {} :ledger [] :sequences {}
                           :treatments []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (assessment/credential payloads, ledger facts,
  treatment records) are stored as EDN strings so `langchain.db`
  doesn't expand them into sub-entities -- the same convention every
  sibling actor's store uses."
  {:encounter/id                {:db/unique :db.unique/identity}
   :assessment/encounter-id     {:db/unique :db.unique/identity}
   :credential/encounter-id     {:db/unique :db.unique/identity}
   :ledger/seq                  {:db/unique :db.unique/identity}
   :treatment/seq                {:db/unique :db.unique/identity}
   :sequence/jurisdiction       {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- encounter->tx [{:keys [id patient chief-complaint proposed-treatment contraindications
                              clinician-license-current? treated? jurisdiction status administration-number]}]
  (cond-> {:encounter/id id}
    patient                        (assoc :encounter/patient patient)
    chief-complaint                 (assoc :encounter/chief-complaint chief-complaint)
    proposed-treatment                (assoc :encounter/proposed-treatment proposed-treatment)
    contraindications                  (assoc :encounter/contraindications (enc contraindications))
    (some? clinician-license-current?)  (assoc :encounter/clinician-license-current? clinician-license-current?)
    (some? treated?)                     (assoc :encounter/treated? treated?)
    jurisdiction                          (assoc :encounter/jurisdiction jurisdiction)
    status                                 (assoc :encounter/status status)
    administration-number                   (assoc :encounter/administration-number administration-number)))

(def ^:private encounter-pull
  [:encounter/id :encounter/patient :encounter/chief-complaint :encounter/proposed-treatment
   :encounter/contraindications :encounter/clinician-license-current? :encounter/treated?
   :encounter/jurisdiction :encounter/status :encounter/administration-number])

(defn- pull->encounter [m]
  (when (:encounter/id m)
    {:id (:encounter/id m) :patient (:encounter/patient m) :chief-complaint (:encounter/chief-complaint m)
     :proposed-treatment (:encounter/proposed-treatment m)
     :contraindications (or (dec* (:encounter/contraindications m)) #{})
     :clinician-license-current? (boolean (:encounter/clinician-license-current? m))
     :treated? (boolean (:encounter/treated? m))
     :jurisdiction (:encounter/jurisdiction m) :status (:encounter/status m)
     :administration-number (:encounter/administration-number m)}))

(defrecord DatomicStore [conn]
  Store
  (encounter [_ id]
    (pull->encounter (d/pull (d/db conn) encounter-pull [:encounter/id id])))
  (all-encounters [_]
    (->> (d/q '[:find [?id ...] :where [?e :encounter/id ?id]] (d/db conn))
         (map #(pull->encounter (d/pull (d/db conn) encounter-pull [:encounter/id %])))
         (sort-by :id)))
  (credential-of [_ id]
    (dec* (d/q '[:find ?p . :in $ ?eid
                :where [?k :credential/encounter-id ?eid] [?k :credential/payload ?p]]
              (d/db conn) id)))
  (assessment-of [_ encounter-id]
    (dec* (d/q '[:find ?p . :in $ ?eid
                :where [?a :assessment/encounter-id ?eid] [?a :assessment/payload ?p]]
              (d/db conn) encounter-id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (treatment-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :treatment/seq ?s] [?e :treatment/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (next-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :sequence/jurisdiction ?j] [?e :sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (encounter-already-treated? [s encounter-id]
    (boolean (:treated? (encounter s encounter-id))))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :encounter/upsert
      (d/transact! conn [(encounter->tx value)])

      :assessment/set
      (d/transact! conn [{:assessment/encounter-id (first path) :assessment/payload (enc payload)}])

      :credential/set
      (d/transact! conn [{:credential/encounter-id (first path) :credential/payload (enc payload)}])

      :encounter/mark-treated
      (let [encounter-id (first path)
            {:keys [result encounter-patch]} (administer-treatment! s encounter-id)
            jurisdiction (:jurisdiction (encounter s encounter-id))
            next-n (inc (next-sequence s jurisdiction))]
        (d/transact! conn
                     [(encounter->tx (assoc encounter-patch :id encounter-id))
                      {:sequence/jurisdiction jurisdiction :sequence/next next-n}
                      {:treatment/seq (count (treatment-history s)) :treatment/record (enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-encounters [s encounters]
    (when (seq encounters) (d/transact! conn (mapv encounter->tx (vals encounters)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:encounters ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [encounters]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-encounters s encounters))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo encounter set -- the Datomic-
  backed analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
