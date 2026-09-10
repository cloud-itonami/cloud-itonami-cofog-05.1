(ns wastecollect.governor-contract-test
  "The governor contract as executable tests -- this vertical's own
  scope boundary ('does NOT actuate a collection robot/vehicle
  directly... does NOT authorize or execute a real pickup run... does
  NOT self-issue a municipal diversion-rate compliance filing')
  implemented faithfully. The single invariant under test:

    Route Advisor never schedules a dispatch, files a sorting result,
    or escalates a contamination concern the Waste Operations Governor
    would reject; `:schedule-route-dispatch`/`:log-sorting-result`/
    `:escalate-contamination` NEVER auto-commit at any phase;
    `:log-route-pickup` (no physical/financial risk) MAY auto-commit
    when clean; and every decision (commit OR hold) leaves exactly one
    ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [wastecollect.store :as store]
            [wastecollect.operation :as op]))

(defn- fresh []
  (let [db (-> (store/mem-store) (store/sample-data!))]
    [db (op/build db)]))

(def coordinator {:actor-id "coord-1" :actor-role :route-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}} {:thread-id tid :resume? true}))

(defn- reject! [actor tid]
  (g/run* actor {:approval {:status :rejected :by "coord-1"}} {:thread-id tid :resume? true}))

(deftest clean-log-route-pickup-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :log-route-pickup :effect :propose :subject "route-001"
                   :patch {:tonnage-kg 850.0}} coordinator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= 850.0 (:tonnage-kg (store/route db "route-001"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest schedule-route-dispatch-always-needs-approval
  (testing "dispatch scheduling is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2"
                    {:op :schedule-route-dispatch :effect :propose :subject "dsp-1"
                     :value {:route-id "route-001" :vehicle-id "collector-001"
                             :scheduled-date "2026-08-01" :actuate-vehicle? false}}
                    coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (true? (:dispatched? (store/dispatch db "dsp-1"))))
        (is (= 1 (count (store/dispatch-history db))))))))

(deftest effect-not-propose-is-held
  (testing "a request whose own :effect is not :propose -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :log-route-pickup :effect :direct-write :subject "route-001"
                     :patch {:tonnage-kg 100.0}} coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:not-propose-effect} (-> (store/ledger db) first :basis))))))

(deftest unknown-op-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t4" {:op :drive-collection-robot :effect :propose :subject "x"} coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:unknown-op} (-> (store/ledger db) first :basis)))))

(deftest route-not-verified-is-held-and-unoverridable
  (testing "dispatching against an unverified/unregistered route -> HOLD, settles immediately, no interrupt"
    (let [[db actor] (fresh)
          res (exec-op actor "t5"
                    {:op :schedule-route-dispatch :effect :propose :subject "dsp-2"
                     :value {:route-id "route-003" :vehicle-id "collector-001"
                             :scheduled-date "2026-08-01" :actuate-vehicle? false}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:route-not-verified} (-> (store/ledger db) last :basis)))
      (is (empty? (store/dispatch-history db))))))

(deftest vehicle-not-verified-is-held-and-unoverridable
  (testing "dispatching an unverified/unregistered vehicle -> HOLD, settles immediately, no interrupt"
    (let [[db actor] (fresh)
          res (exec-op actor "t6"
                    {:op :schedule-route-dispatch :effect :propose :subject "dsp-3"
                     :value {:route-id "route-002" :vehicle-id "collector-002"
                             :scheduled-date "2026-08-01" :actuate-vehicle? false}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:vehicle-not-verified} (-> (store/ledger db) last :basis)))
      (is (empty? (store/dispatch-history db))))))

(deftest vehicle-actuate-is-held-and-permanently-blocked
  (testing "a proposal that sets :actuate-vehicle? true -> HOLD, PERMANENT, never reaches request-approval even though the route and vehicle are verified and registered"
    (let [[db actor] (fresh)
          res (exec-op actor "t7"
                    {:op :schedule-route-dispatch :effect :propose :subject "dsp-4"
                     :value {:route-id "route-002" :vehicle-id "collector-001"
                             :scheduled-date "2026-09-01" :actuate-vehicle? true}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:vehicle-actuate-blocked} (-> (store/ledger db) last :basis)))
      (is (empty? (store/dispatch-history db))))))

(deftest schedule-route-dispatch-double-dispatch-is-held
  (testing "scheduling the SAME dispatch record twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (exec-op actor "t8a" {:op :schedule-route-dispatch :effect :propose :subject "dsp-1"
                                  :value {:route-id "route-001" :vehicle-id "collector-001"
                                          :scheduled-date "2026-08-01" :actuate-vehicle? false}} coordinator)
          _ (approve! actor "t8a")
          res (exec-op actor "t8" {:op :schedule-route-dispatch :effect :propose :subject "dsp-1"
                                   :value {:route-id "route-001" :vehicle-id "collector-001"
                                           :scheduled-date "2026-08-01" :actuate-vehicle? false}} coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-dispatched} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/dispatch-history db))) "still only the one earlier dispatch"))))

(deftest invalid-diversion-category-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t9" {:op :log-sorting-result :effect :propose :subject "route-001"
                                 :value {:diversion-category :unobtanium-scrap
                                         :contamination-percentage 1.0}} coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:invalid-diversion-category} (-> (store/ledger db) last :basis)))
    (is (nil? (store/sorting-result-of db "route-001")) "fabricated diversion-category never lands in the SSoT")))

(deftest invalid-contamination-percentage-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t10" {:op :log-sorting-result :effect :propose :subject "route-001"
                                  :value {:diversion-category :recyclable-mixed
                                          :contamination-percentage 250.0}} coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:invalid-contamination-percentage} (-> (store/ledger db) last :basis)))
    (is (nil? (store/sorting-result-of db "route-001")) "fabricated contamination-percentage never lands in the SSoT")))

(deftest invalid-tonnage-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t11" {:op :log-route-pickup :effect :propose :subject "route-001"
                                  :patch {:tonnage-kg 999999.0}} coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:invalid-tonnage} (-> (store/ledger db) last :basis)))
    (is (not= 999999.0 (:tonnage-kg (store/route db "route-001"))) "fabricated tonnage never lands in the SSoT")))

(deftest contamination-always-escalates-even-high-confidence
  (testing "escalate-contamination always escalates -- never auto-committed, regardless of confidence"
    (let [[db actor] (fresh)
          res (exec-op actor "t12" {:op :escalate-contamination :effect :propose :subject "concern-1"
                                    :value {:route-id "route-001" :severity :moderate
                                            :description "suspicious container in bin, unknown contents"}}
                       coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t12")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= 1 (count (store/contamination-log db))))))))

(deftest contamination-approval-rejected-leaves-no-record-only-a-hold-fact
  (let [[db actor] (fresh)
        _ (exec-op actor "t13" {:op :escalate-contamination :effect :propose :subject "concern-2"
                                :value {:route-id "route-001" :severity :low :description "y"}}
                   coordinator)
        r (reject! actor "t13")]
    (is (= :hold (get-in r [:state :disposition])))
    (is (= 0 (count (store/contamination-log db))) "rejected approval never reaches the commit node")
    (is (= 1 (count (store/ledger db))))))

(deftest log-sorting-result-always-needs-approval
  (testing "a CLEAN sorting-result filing is never auto-eligible -- always escalates"
    (let [[db actor] (fresh)
          res (exec-op actor "t14" {:op :log-sorting-result :effect :propose :subject "route-001"
                                    :value {:diversion-category :recyclable-mixed
                                            :contamination-percentage 3.5}}
                       coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t14")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= :recyclable-mixed (:diversion-category (store/sorting-result-of db "route-001"))))))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N settled operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :log-route-pickup :effect :propose :subject "route-001"
                          :patch {:tonnage-kg 100.0}} coordinator)
      (exec-op actor "b" {:op :log-route-pickup :effect :propose :subject "route-001"
                          :patch {:tonnage-kg 999999.0}} coordinator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
