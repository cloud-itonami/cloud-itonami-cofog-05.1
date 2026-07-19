(ns wastecollect.store-contract-test
  "The Store contract as executable tests. Single MemStore backend --
  see `wastecollect.store` ns docstring for why a second (Datomic-backed)
  backend is out of scope for this build."
  (:require [clojure.test :refer [deftest is testing]]
            [wastecollect.store :as store]))

(defn- seeded [] (-> (store/mem-store) (store/sample-data!)))

(deftest sample-data-read-basics
  (let [s (seeded)]
    (is (true? (:verified? (store/route s "route-001"))))
    (is (true? (:registered? (store/route s "route-001"))))
    (is (true? (:verified? (store/route s "route-002"))))
    (is (true? (:registered? (store/route s "route-002"))))
    (is (false? (:verified? (store/route s "route-003"))))
    (is (false? (:registered? (store/route s "route-003"))))
    (is (= ["route-001" "route-002" "route-003"] (mapv :id (store/all-routes s))))
    (is (true? (:verified? (store/vehicle s "collector-001"))))
    (is (true? (:registered? (store/vehicle s "collector-001"))))
    (is (false? (:verified? (store/vehicle s "collector-002"))))
    (is (false? (:registered? (store/vehicle s "collector-002"))))
    (is (= ["collector-001" "collector-002"] (mapv :id (store/all-vehicles s))))
    (is (= [] (store/ledger s)))
    (is (= [] (store/dispatch-history s)))
    (is (= [] (store/contamination-log s)))
    (is (zero? (store/next-dispatch-sequence s)))
    (is (zero? (store/next-escalation-sequence s)))
    (is (false? (store/dispatch-already-scheduled? s "dsp-1")))
    (is (nil? (store/sorting-result-of s "route-001")))))

(deftest fresh-store-has-no-routes-or-vehicles
  (let [s (store/mem-store)]
    (is (= [] (store/all-routes s)))
    (is (nil? (store/route s "route-001")))
    (is (= [] (store/all-vehicles s)))
    (is (nil? (store/vehicle s "collector-001")))))

(deftest route-upsert-merges-preserving-untouched-fields
  (let [s (seeded)]
    (store/commit-record! s {:effect :route/upsert :path ["route-001"]
                             :value {:tonnage-kg 850.0}})
    (is (= 850.0 (:tonnage-kg (store/route s "route-001"))))
    (is (true? (:verified? (store/route s "route-001"))) "unrelated field preserved")
    (is (true? (:registered? (store/route s "route-001"))) "unrelated field preserved")))

(deftest sorting-set-replaces-not-merges
  (let [s (seeded)]
    (store/commit-record! s {:effect :sorting/set :path ["route-001"]
                             :value {:diversion-category :recyclable-mixed
                                     :contamination-percentage 3.5}})
    (is (= :recyclable-mixed (:diversion-category (store/sorting-result-of s "route-001"))))
    (store/commit-record! s {:effect :sorting/set :path ["route-001"]
                             :value {:diversion-category :landfill-residual
                                     :contamination-percentage 12.0}})
    (is (= :landfill-residual (:diversion-category (store/sorting-result-of s "route-001")))
        "the new filing fully replaces the stale one")
    (is (= 12.0 (:contamination-percentage (store/sorting-result-of s "route-001"))))))

(deftest dispatch-schedule-commits-and-advances-sequence
  (testing "commit-record! (like every sibling actor's own MemStore) returns the store `s`, not the domain result -- inspect the store directly, matching the discipline the actor's own :commit node relies on"
    (let [s (seeded)]
      (store/commit-record! s {:effect :dispatch/schedule :path ["dsp-1"]
                               :value {:route-id "route-001" :vehicle-id "collector-001"
                                       :scheduled-date "2026-08-01"}})
      (is (= "DSP-000000" (get (first (store/dispatch-history s)) "record_id")))
      (is (= "route-dispatch-draft" (get (first (store/dispatch-history s)) "kind")))
      (is (true? (:dispatched? (store/dispatch s "dsp-1"))))
      (is (= "route-001" (:route-id (store/dispatch s "dsp-1"))))
      (is (= 1 (count (store/dispatch-history s))))
      (is (= 1 (store/next-dispatch-sequence s)))
      (is (true? (store/dispatch-already-scheduled? s "dsp-1")))
      (is (= "route-001" (:last-dispatched-route (store/vehicle s "collector-001")))))))

(deftest contamination-flag-appends
  (let [s (seeded)]
    (store/commit-record! s {:effect :contamination/flag :path ["concern-1"]
                             :value {:route-id "route-001" :severity :moderate}})
    (is (= 1 (count (store/contamination-log s))))
    (is (= :moderate (:severity (first (store/contamination-log s)))))
    (store/commit-record! s {:effect :contamination/flag :path ["concern-2"]
                             :value {:route-id "route-002" :severity :high}})
    (is (= 2 (count (store/contamination-log s))) "append-only")))

(deftest ledger-is-append-only-and-order-preserving
  (let [s (store/mem-store)]
    (store/append-ledger! s {:op :a :disposition :commit})
    (store/append-ledger! s {:op :b :disposition :hold})
    (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))

(deftest generic-commit-record-path-writes-a-raw-record-by-id
  (testing "a record with no :effect key is written verbatim into the generic records map -- the store-level primitive underneath the domain-specific dispatch"
    (let [s (store/mem-store)
          record {:id "test-001" :data "test"}]
      (store/commit-record! s record)
      (is (= record (get (store/get-records s) "test-001"))))))

(deftest get-ledger-alias-matches-ledger
  (let [s (store/mem-store)]
    (store/append-ledger! s {:t :x})
    (is (= (store/ledger s) (store/get-ledger s)))))
