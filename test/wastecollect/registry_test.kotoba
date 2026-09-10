(ns wastecollect.registry-test
  (:require [clojure.test :refer [deftest is]]
            [wastecollect.registry :as r]))

;; ----------------------------- route-verified? / route-registered? / route-ready? -----------------------------

(deftest route-is-verified-when-flagged
  (is (true? (r/route-verified? {:id "r1" :verified? true}))))

(deftest route-is-not-verified-when-false-or-missing
  (is (false? (r/route-verified? {:id "r1" :verified? false})))
  (is (false? (r/route-verified? {:id "r1"}))))

(deftest route-is-registered-when-flagged
  (is (true? (r/route-registered? {:registered? true}))))

(deftest route-is-not-registered-when-false-or-missing
  (is (false? (r/route-registered? {:registered? false})))
  (is (false? (r/route-registered? {}))))

(deftest route-ready-requires-both
  (is (true? (r/route-ready? {:verified? true :registered? true})))
  (is (false? (r/route-ready? {:verified? true :registered? false})))
  (is (false? (r/route-ready? {:verified? false :registered? true})))
  (is (false? (r/route-ready? {}))))

;; ----------------------------- vehicle-verified? / vehicle-registered? / vehicle-ready? -----------------------------

(deftest vehicle-is-verified-when-flagged
  (is (true? (r/vehicle-verified? {:id "v1" :verified? true}))))

(deftest vehicle-is-not-verified-when-false-or-missing
  (is (false? (r/vehicle-verified? {:id "v1" :verified? false})))
  (is (false? (r/vehicle-verified? {:id "v1"}))))

(deftest vehicle-is-registered-when-flagged
  (is (true? (r/vehicle-registered? {:registered? true}))))

(deftest vehicle-is-not-registered-when-false-or-missing
  (is (false? (r/vehicle-registered? {:registered? false})))
  (is (false? (r/vehicle-registered? {}))))

(deftest vehicle-ready-requires-both
  (is (true? (r/vehicle-ready? {:verified? true :registered? true})))
  (is (false? (r/vehicle-ready? {:verified? true :registered? false})))
  (is (false? (r/vehicle-ready? {:verified? false :registered? true})))
  (is (false? (r/vehicle-ready? {}))))

;; ----------------------------- diversion-category-valid? -----------------------------

(deftest known-diversion-categories-are-valid
  (doseq [c [:recyclable-mixed :recyclable-organics :recyclable-metal
             :landfill-residual :bulky-item :yard-waste :hazardous-suspect]]
    (is (r/diversion-category-valid? c))))

(deftest fabricated-diversion-category-is-invalid
  (is (not (r/diversion-category-valid? :unobtanium-scrap)))
  (is (not (r/diversion-category-valid? nil))))

;; ----------------------------- tonnage-valid? -----------------------------

(deftest typical-tonnage-is-valid
  (is (r/tonnage-valid? 0))
  (is (r/tonnage-valid? 850.0))
  (is (r/tonnage-valid? 40000.0)))

(deftest negative-tonnage-is-invalid
  (is (not (r/tonnage-valid? -1))))

(deftest excessive-tonnage-is-invalid
  (is (not (r/tonnage-valid? 999999.0)))
  (is (not (r/tonnage-valid? 40001))))

(deftest non-numeric-or-missing-tonnage-is-invalid
  (is (not (r/tonnage-valid? nil)))
  (is (not (r/tonnage-valid? "850"))))

;; ----------------------------- contamination-percentage-valid? -----------------------------

(deftest typical-contamination-percentage-is-valid
  (is (r/contamination-percentage-valid? 0.0))
  (is (r/contamination-percentage-valid? 3.5))
  (is (r/contamination-percentage-valid? 100.0)))

(deftest negative-contamination-percentage-is-invalid
  (is (not (r/contamination-percentage-valid? -1.0))))

(deftest excessive-contamination-percentage-is-invalid
  (is (not (r/contamination-percentage-valid? 250.0)))
  (is (not (r/contamination-percentage-valid? 100.01))))

(deftest non-numeric-or-missing-contamination-percentage-is-invalid
  (is (not (r/contamination-percentage-valid? nil)))
  (is (not (r/contamination-percentage-valid? "3.5"))))

;; ----------------------------- register-dispatch -----------------------------

(deftest dispatch-is-a-draft-not-a-real-actuation
  (let [result (r/register-dispatch "dsp-1" "route-001" "collector-001" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest dispatch-assigns-dispatch-number
  (let [result (r/register-dispatch "dsp-1" "route-001" "collector-001" 7)]
    (is (= (get result "dispatch_number") "DSP-000007"))
    (is (= (get-in result ["record" "dispatch_id"]) "dsp-1"))
    (is (= (get-in result ["record" "route_id"]) "route-001"))
    (is (= (get-in result ["record" "vehicle_id"]) "collector-001"))
    (is (= (get-in result ["record" "kind"]) "route-dispatch-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest dispatch-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-dispatch "" "route-001" "collector-001" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-dispatch "dsp-1" "" "collector-001" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-dispatch "dsp-1" "route-001" "" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-dispatch "dsp-1" "route-001" "collector-001" -1))))

;; ----------------------------- register-escalation -----------------------------

(deftest escalation-is-a-draft-not-a-real-clearance
  (let [result (r/register-escalation "concern-1" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest escalation-assigns-escalation-number
  (let [result (r/register-escalation "concern-1" 7)]
    (is (= (get result "escalation_number") "ESC-000007"))
    (is (= (get-in result ["record" "concern_id"]) "concern-1"))
    (is (= (get-in result ["record" "kind"]) "contamination-escalation-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest escalation-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-escalation "" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-escalation "concern-1" -1))))

(deftest history-is-append-only
  (let [c1 (r/register-dispatch "dsp-1" "route-001" "collector-001" 0)
        hist (r/append [] c1)
        c2 (r/register-dispatch "dsp-2" "route-002" "collector-001" 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "DSP-000000" (get-in hist2 [0 "record_id"])))
    (is (= "DSP-000001" (get-in hist2 [1 "record_id"])))))
