(ns clinic.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean encounter through
  intake -> jurisdiction licensing assessment -> credential screening
  -> treatment-administration proposal (always escalates) -> human
  approval -> commit, then shows four HARD holds (a jurisdiction with
  no spec-basis, a proposed treatment that appears on the patient's own
  contraindication list, a lapsed clinician license, and a double
  administration of an already-treated encounter) that never reach a
  human at all, and prints the audit ledger + the draft treatment-
  administration records."
  (:require [langgraph.graph :as g]
            [clinic.store :as store]
            [clinic.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :licensed-physician :phase 3})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== encounter/intake encounter-1 (JPN, clean; amoxicillin, no contraindications) ==")
    (println (exec! actor "t1" {:op :encounter/intake :subject "encounter-1"
                                :patch {:id "encounter-1" :patient "Sakura Tanaka"}} operator))

    (println "== jurisdiction/assess encounter-1 (escalates -- human approves) ==")
    (println (exec! actor "t2" {:op :jurisdiction/assess :subject "encounter-1"} operator))
    (println (approve! actor "t2"))

    (println "== credential/screen encounter-1 (clean; escalates -- human approves) ==")
    (println (exec! actor "t3" {:op :credential/screen :subject "encounter-1"} operator))
    (println (approve! actor "t3"))

    (println "== treatment/administer encounter-1 (always escalates -- actuation/administer-treatment) ==")
    (let [r (exec! actor "t4" {:op :treatment/administer :subject "encounter-1"} operator)]
      (println r)
      (println "-- human licensed physician approves --")
      (println (approve! actor "t4")))

    (println "== jurisdiction/assess encounter-2 (no spec-basis -> HARD hold) ==")
    (println (exec! actor "t5" {:op :jurisdiction/assess :subject "encounter-2" :no-spec? true} operator))

    (println "== jurisdiction/assess encounter-3 (escalates -- human approves; sets up the contraindication test) ==")
    (println (exec! actor "t6" {:op :jurisdiction/assess :subject "encounter-3"} operator))
    (println (approve! actor "t6"))

    (println "== treatment/administer encounter-3 (proposed treatment :penicillin is in the patient's own contraindication set -> HARD hold) ==")
    (println (exec! actor "t7" {:op :treatment/administer :subject "encounter-3"} operator))

    (println "== credential/screen encounter-4 (lapsed clinician license -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t8" {:op :credential/screen :subject "encounter-4"} operator))

    (println "== treatment/administer encounter-1 AGAIN (double-administration of an already-treated encounter -> HARD hold) ==")
    (println (exec! actor "t9" {:op :treatment/administer :subject "encounter-1"} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft treatment-administration records ==")
    (doseq [r (store/treatment-history db)] (println r))))
