(ns clinic.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a configuration
  change, not a rewrite -- see `cloud-itonami-isic-6511`'s
  `underwriting.store-contract-test` for the same pattern on the sibling
  actor."
  (:require [clojure.test :refer [deftest is testing]]
            [clinic.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "Sakura Tanaka" (:patient (store/encounter s "encounter-1"))))
      (is (= "JPN" (:jurisdiction (store/encounter s "encounter-1"))))
      (is (= :amoxicillin (:proposed-treatment (store/encounter s "encounter-1"))))
      (is (= #{} (:contraindications (store/encounter s "encounter-1"))))
      (is (= #{:penicillin} (:contraindications (store/encounter s "encounter-3"))))
      (is (true? (:clinician-license-current? (store/encounter s "encounter-1"))))
      (is (false? (:clinician-license-current? (store/encounter s "encounter-4"))))
      (is (false? (:treated? (store/encounter s "encounter-1"))))
      (is (= ["encounter-1" "encounter-2" "encounter-3" "encounter-4"]
             (mapv :id (store/all-encounters s))))
      (is (nil? (store/credential-of s "encounter-1")))
      (is (nil? (store/assessment-of s "encounter-1")))
      (is (= [] (store/ledger s)))
      (is (= [] (store/treatment-history s)))
      (is (zero? (store/next-sequence s "JPN")))
      (is (false? (store/encounter-already-treated? s "encounter-1"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :encounter/upsert
                                 :value {:id "encounter-1" :patient "Sakura Tanaka"}})
        (is (= "Sakura Tanaka" (:patient (store/encounter s "encounter-1"))))
        (is (= :amoxicillin (:proposed-treatment (store/encounter s "encounter-1"))) "proposed-treatment preserved"))
      (testing "assessment / credential payloads commit and read back"
        (store/commit-record! s {:effect :assessment/set :path ["encounter-1"]
                                 :payload {:jurisdiction "JPN" :checklist ["a" "b"]}})
        (is (= {:jurisdiction "JPN" :checklist ["a" "b"]} (store/assessment-of s "encounter-1")))
        (store/commit-record! s {:effect :credential/set :path ["encounter-1"]
                                 :payload {:encounter-id "encounter-1" :verdict :current}})
        (is (= {:encounter-id "encounter-1" :verdict :current} (store/credential-of s "encounter-1"))))
      (testing "treatment administration drafts a treatment record and advances the sequence"
        (store/commit-record! s {:effect :encounter/mark-treated :path ["encounter-1"]})
        (is (= "JPN-TX-000000" (get (first (store/treatment-history s)) "record_id")))
        (is (= "treatment-administration-draft" (get (first (store/treatment-history s)) "kind")))
        (is (true? (:treated? (store/encounter s "encounter-1"))))
        (is (= 1 (count (store/treatment-history s))))
        (is (= 1 (store/next-sequence s "JPN")))
        (is (true? (store/encounter-already-treated? s "encounter-1")))
        (is (false? (store/encounter-already-treated? s "encounter-2"))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/encounter s "nope")))
    (is (= [] (store/all-encounters s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/treatment-history s)))
    (is (zero? (store/next-sequence s "JPN")))
    (store/with-encounters s {"x" {:id "x" :patient "p" :chief-complaint "c"
                                   :proposed-treatment :amoxicillin :contraindications #{}
                                   :clinician-license-current? true :treated? false
                                   :jurisdiction "JPN" :status :intake}})
    (is (= "p" (:patient (store/encounter s "x"))))))
