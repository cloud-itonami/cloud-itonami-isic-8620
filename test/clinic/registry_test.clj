(ns clinic.registry-test
  (:require [clojure.test :refer [deftest is]]
            [clinic.registry :as r]))

;; ----------------------------- treatment-contraindicated? -----------------------------

(deftest not-contraindicated-when-treatment-absent-from-the-set
  (is (not (r/treatment-contraindicated? {:proposed-treatment :amoxicillin :contraindications #{}})))
  (is (not (r/treatment-contraindicated? {:proposed-treatment :amoxicillin :contraindications #{:penicillin}}))))

(deftest contraindicated-when-treatment-is-a-member-of-the-set
  (is (r/treatment-contraindicated? {:proposed-treatment :penicillin :contraindications #{:penicillin}}))
  (is (r/treatment-contraindicated? {:proposed-treatment :penicillin :contraindications #{:penicillin :sulfa}})))

;; ----------------------------- register-treatment -----------------------------

(deftest treatment-is-a-draft-not-a-real-administration
  (let [result (r/register-treatment "encounter-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest treatment-assigns-administration-number
  (let [result (r/register-treatment "encounter-1" "JPN" 7)]
    (is (= (get result "administration_number") "JPN-TX-000007"))
    (is (= (get-in result ["record" "encounter_id"]) "encounter-1"))
    (is (= (get-in result ["record" "kind"]) "treatment-administration-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest treatment-validation-rules
  (is (thrown? Exception (r/register-treatment "" "JPN" 0)))
  (is (thrown? Exception (r/register-treatment "encounter-1" "" 0)))
  (is (thrown? Exception (r/register-treatment "encounter-1" "JPN" -1))))

(deftest treatment-history-is-append-only
  (let [t1 (r/register-treatment "encounter-1" "JPN" 0)
        hist (r/append [] t1)
        t2 (r/register-treatment "encounter-2" "JPN" 1)
        hist2 (r/append hist t2)]
    (is (= 2 (count hist2)))
    (is (= "JPN-TX-000000" (get-in hist2 [0 "record_id"])))
    (is (= "JPN-TX-000001" (get-in hist2 [1 "record_id"])))))
