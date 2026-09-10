(ns wastecollect.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean hauler through
  intake -> sorting-result filing (escalate/approve) -> dispatch
  scheduling (escalate/approve) -> contamination escalation
  (escalate/approve), then shows HARD-hold scenarios: a mis-wired
  request whose own `:effect` is not `:propose`, an unrecognized op, a
  dispatch scheduled against an UNVERIFIED/unregistered route, a
  dispatch scheduled against an UNVERIFIED/unregistered vehicle, a
  proposal that tries to ACTUATE the collection vehicle directly
  (permanently blocked, no override), a double-dispatch of the same
  dispatch record, a sorting-result patch with a fabricated
  diversion-category, a sorting-result patch with an implausible
  contamination-percentage reading, and a route-pickup patch with an
  implausible tonnage reading.

  Like every sibling actor's own demo, each check is exercised directly
  and independently below, one request per HARD-hold scenario, the SAME
  'exercise the failure mode directly, never only via a happy-path
  actuation' discipline `parksafety`'s ADR-2607071922 Decision 5 and
  every sibling since establish."
  (:require [langgraph.graph :as g]
            [wastecollect.store :as store]
            [wastecollect.operation :as op]))

(def coordinator {:actor-id "coord-1" :actor-role :route-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}} {:thread-id tid :resume? true}))

(defn -main [& _args]
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]

    (println "== log-route-pickup route-001 (clean patch -> phase-3 auto-commit) ==")
    (println (exec-op actor "t1"
                       {:op :log-route-pickup :effect :propose :subject "route-001"
                        :patch {:tonnage-kg 850.0 :bin-count 42 :last-pickup-date "2026-07-14"}}
                       coordinator))

    (println "== log-sorting-result on route-001 (verified route -- escalates, approve) ==")
    (let [r (exec-op actor "t2"
                      {:op :log-sorting-result :effect :propose :subject "route-001"
                       :value {:diversion-category :recyclable-mixed
                               :contamination-percentage 3.5}}
                      coordinator)]
      (println r)
      (println "-- human route coordinator approves --")
      (println (approve! actor "t2")))

    (println "== schedule-route-dispatch dsp-1 on route-001+collector-001 (verified, registered -- escalates, approve) ==")
    (let [r (exec-op actor "t3"
                      {:op :schedule-route-dispatch :effect :propose :subject "dsp-1"
                       :value {:route-id "route-001" :vehicle-id "collector-001"
                               :scheduled-date "2026-08-01" :actuate-vehicle? false}}
                      coordinator)]
      (println r)
      (println "-- human route coordinator approves --")
      (println (approve! actor "t3")))

    (println "== escalate-contamination concern-1 on route-001 (always escalates -- approve) ==")
    (let [r (exec-op actor "t4"
                      {:op :escalate-contamination :effect :propose :subject "concern-1"
                       :value {:route-id "route-001" :severity :moderate
                               :description "収集ビン内に疑わしい容器を検知、成分不明"}}
                      coordinator)]
      (println r)
      (println "-- human route coordinator approves --")
      (println (approve! actor "t4")))

    (println "\n-- HARD-hold scenarios --\n")

    (println "== log-route-pickup with :effect other than :propose -> HARD hold (structural) ==")
    (println (exec-op actor "t5"
                       {:op :log-route-pickup :effect :direct-write :subject "route-001"
                        :patch {:tonnage-kg 100.0}}
                       coordinator))

    (println "== unrecognized op -> HARD hold ==")
    (println (exec-op actor "t6"
                       {:op :drive-collection-robot :effect :propose :subject "route-001"}
                       coordinator))

    (println "== schedule-route-dispatch dsp-2 on route-003 (UNVERIFIED/unregistered route -> HARD hold) ==")
    (println (exec-op actor "t7"
                       {:op :schedule-route-dispatch :effect :propose :subject "dsp-2"
                        :value {:route-id "route-003" :vehicle-id "collector-001"
                                :scheduled-date "2026-08-01" :actuate-vehicle? false}}
                       coordinator))

    (println "== schedule-route-dispatch dsp-3 on collector-002 (UNVERIFIED/unregistered vehicle -> HARD hold) ==")
    (println (exec-op actor "t8"
                       {:op :schedule-route-dispatch :effect :propose :subject "dsp-3"
                        :value {:route-id "route-002" :vehicle-id "collector-002"
                                :scheduled-date "2026-08-01" :actuate-vehicle? false}}
                       coordinator))

    (println "== schedule-route-dispatch dsp-4 on route-002+collector-001 with :actuate-vehicle? true -> HARD hold, PERMANENT, never reaches a human ==")
    (println (exec-op actor "t9"
                       {:op :schedule-route-dispatch :effect :propose :subject "dsp-4"
                        :value {:route-id "route-002" :vehicle-id "collector-001"
                                :scheduled-date "2026-09-01" :actuate-vehicle? true}}
                       coordinator))

    (println "== schedule-route-dispatch dsp-1 AGAIN (double-dispatch -> HARD hold) ==")
    (println (exec-op actor "t10"
                       {:op :schedule-route-dispatch :effect :propose :subject "dsp-1"
                        :value {:route-id "route-001" :vehicle-id "collector-001"
                                :scheduled-date "2026-08-01" :actuate-vehicle? false}}
                       coordinator))

    (println "== log-sorting-result on route-001 with a fabricated diversion-category -> HARD hold ==")
    (println (exec-op actor "t11"
                       {:op :log-sorting-result :effect :propose :subject "route-001"
                        :value {:diversion-category :unobtanium-scrap :contamination-percentage 1.0}}
                       coordinator))

    (println "== log-sorting-result on route-001 with an implausible contamination-percentage -> HARD hold ==")
    (println (exec-op actor "t12"
                       {:op :log-sorting-result :effect :propose :subject "route-001"
                        :value {:diversion-category :recyclable-mixed :contamination-percentage 250.0}}
                       coordinator))

    (println "== log-route-pickup on route-001 with an implausible tonnage reading -> HARD hold ==")
    (println (exec-op actor "t13"
                       {:op :log-route-pickup :effect :propose :subject "route-001"
                        :patch {:tonnage-kg 999999.0}}
                       coordinator))

    (println "\n== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "\n== draft dispatch records ==")
    (doseq [r (store/dispatch-history db)] (println r))

    (println "\n== contamination log ==")
    (doseq [c (store/contamination-log db)] (println c))))
