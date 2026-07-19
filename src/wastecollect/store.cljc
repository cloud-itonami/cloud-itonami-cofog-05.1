(ns wastecollect.store
  "SSoT for the municipal waste-collection Route Advisor actor, behind
  a `Store` protocol so the backend is a swap, not a rewrite -- the
  same seam every `cloud-itonami` actor in this fleet uses.

  Scope note: like several siblings (e.g. `cloud-itonami-isic-3091`'s
  own `motomfg.store`), this build ships a single `MemStore` backend
  only (atom of EDN) -- the deterministic default for dev/tests/demo,
  no deps. A `langchain.db`-backed store can be added later behind the
  same protocol without changing any caller.

  Four kinds of entity live here:
    - `routes`               -- the central entity. A collection
                                 route's zone + latest pickup tonnage/
                                 bin-count record. `:verified?` marks
                                 whether the route has actually been
                                 surveyed/confirmed (never inferred
                                 from a routine pickup-log patch);
                                 `:registered?` marks whether it is on
                                 file in the hauler's route registry.
    - `vehicles`              -- a collection robot/vehicle's own
                                 record. `:verified?`/`:registered?`
                                 track whether it has actually been
                                 inspected/commissioned and is on file
                                 -- the same ground-truth discipline as
                                 `routes`.
    - `sorting-results`       -- a preliminary on-vehicle sort finding
                                 (diversion-category +
                                 contamination-percentage) filed against
                                 a route's latest pickup
                                 (`wastecollect.registry` validates the
                                 category/percentage; this store just
                                 holds the filed record, keyed by
                                 route-id, replaced on each new filing
                                 -- never merged, so a stale category
                                 can never linger alongside a fresh
                                 one).
    - `dispatch`              -- a scheduled collection-vehicle
                                 dispatch DRAFT against a route
                                 (`wastecollect.registry`'s
                                 `register-dispatch`). Dedicated
                                 `:dispatched?` double-dispatch guard
                                 (never a `:status` value -- the same
                                 discipline every prior governor's
                                 guards establish, informed by
                                 `cloud-itonami-isic-6492`'s
                                 status-lifecycle bug, ADR-2607071320).

  Plus a generic `records` map (id -> raw record) used only for
  direct, domain-agnostic `commit-record!` calls (a record with no
  `:effect` key) -- the store-level primitive every sibling actor's
  own MemStore exposes underneath its domain-specific commit dispatch.

  The ledger stays append-only: 'which route was logged, which
  sorting result was filed, which dispatch was scheduled against a
  verified/registered route and vehicle, approved by whom, which
  contamination concern was escalated' is always a query over an
  immutable log -- the audit trail a municipality or downstream
  auditor trusting this hauler needs."
  (:require [wastecollect.registry :as registry]))

(defprotocol Store
  (route [s id])
  (all-routes [s])
  (vehicle [s id])
  (all-vehicles [s])
  (dispatch [s id] "a scheduled route-dispatch record, or nil")
  (sorting-result-of [s route-id])
  (contamination-log [s] "the append-only contamination-escalation log")
  (ledger [s])
  (dispatch-history [s] "the append-only route-dispatch history (wastecollect.registry drafts)")
  (next-dispatch-sequence [s] "next dispatch-number sequence")
  (next-escalation-sequence [s] "next escalation-number sequence")
  (dispatch-already-scheduled? [s dispatch-id] "has this dispatch already been scheduled?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (get-records [s] "the generic id -> raw-record map (domain-agnostic commit-record! path)")
  (with-routes [s routes] "replace/seed the route directory (map id->route)")
  (with-vehicles [s vehicles] "replace/seed the vehicle directory (map id->vehicle)"))

;; ----------------------------- demo/sample data -----------------------------

(defn- sample-routes []
  {"route-001" {:id "route-001" :zone "north-district"
                :verified? true :registered? true
                :tonnage-kg 0.0 :bin-count 0 :last-pickup-date nil}
   "route-002" {:id "route-002" :zone "harbor-district"
                :verified? true :registered? true
                :tonnage-kg 0.0 :bin-count 0 :last-pickup-date nil}
   "route-003" {:id "route-003" :zone "east-annex"
                :verified? false :registered? false
                :tonnage-kg 0.0 :bin-count 0 :last-pickup-date nil}})

(defn- sample-vehicles []
  {"collector-001" {:id "collector-001" :kind :collection-robot
                     :verified? true :registered? true
                     :last-dispatched-route nil}
   "collector-002" {:id "collector-002" :kind :collection-robot
                     :verified? false :registered? false
                     :last-dispatched-route nil}})

;; ----------------------------- shared commit logic -----------------------------

(defn- schedule-dispatch!
  "Backend-agnostic `:dispatch/schedule` -- drafts the route-dispatch
  record via `wastecollect.registry` and returns {:result .. :patch ..}
  for the caller to persist."
  [s dispatch-id route-id vehicle-id]
  (let [seq-n (next-dispatch-sequence s)
        result (registry/register-dispatch dispatch-id route-id vehicle-id seq-n)]
    {:result result
     :patch {:dispatched? true
             :dispatch-number (get result "dispatch_number")}}))

(defn- file-escalation!
  "Backend-agnostic `:contamination/escalate` -- drafts the
  contamination-escalation record via `wastecollect.registry` and
  returns {:result .. :concern ..} for the caller to persist."
  [s concern-id value]
  (let [seq-n (next-escalation-sequence s)
        result (registry/register-escalation concern-id seq-n)]
    {:result result
     :concern (assoc value :id concern-id :escalation-number (get result "escalation_number"))}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (route [_ id] (get-in @a [:routes id]))
  (all-routes [_] (sort-by :id (vals (:routes @a))))
  (vehicle [_ id] (get-in @a [:vehicles id]))
  (all-vehicles [_] (sort-by :id (vals (:vehicles @a))))
  (dispatch [_ id] (get-in @a [:dispatch id]))
  (sorting-result-of [_ route-id] (get-in @a [:sorting-results route-id]))
  (contamination-log [_] (:contamination-log @a))
  (ledger [_] (:ledger @a))
  (dispatch-history [_] (:dispatch-history @a))
  (next-dispatch-sequence [_] (:dispatch-sequence @a 0))
  (next-escalation-sequence [_] (:escalation-sequence @a 0))
  (dispatch-already-scheduled? [_ dispatch-id]
    (boolean (get-in @a [:dispatch dispatch-id :dispatched?])))
  (get-records [_] (:records @a))
  (commit-record! [s {:keys [effect path value] :as record}]
    (cond
      (= effect :route/upsert)
      (swap! a update-in [:routes (first path)] merge (assoc value :id (first path)))

      (= effect :sorting/set)
      (swap! a assoc-in [:sorting-results (first path)] (assoc value :route-id (first path)))

      (= effect :dispatch/schedule)
      (let [dispatch-id (first path)
            route-id (:route-id value)
            vehicle-id (:vehicle-id value)
            {:keys [result patch]} (schedule-dispatch! s dispatch-id route-id vehicle-id)]
        (swap! a (fn [state]
                   (-> state
                       (update :dispatch-sequence (fnil inc 0))
                       (update-in [:dispatch dispatch-id] merge (assoc value :id dispatch-id) patch)
                       (update :dispatch-history registry/append result)
                       (update-in [:vehicles vehicle-id :last-dispatched-route]
                                  (fn [_prev] route-id)))))
        result)

      (= effect :contamination/flag)
      (let [concern-id (first path)
            {:keys [result concern]} (file-escalation! s concern-id value)]
        (swap! a (fn [state]
                   (-> state
                       (update :escalation-sequence (fnil inc 0))
                       (update :contamination-log conj concern))))
        result)

      ;; Domain-agnostic path: a raw record with an :id and no :effect
      ;; is written verbatim into the generic `records` map -- the
      ;; store-level primitive underneath the domain-specific dispatch
      ;; above (also what `logging`-style siblings expose as their own
      ;; low-level commit path).
      (and (nil? effect) (:id record))
      (swap! a assoc-in [:records (:id record)] record)

      :else nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-routes [s routes] (when (seq routes) (swap! a assoc :routes routes)) s)
  (with-vehicles [s vehicles] (when (seq vehicles) (swap! a assoc :vehicles vehicles)) s))

(defn mem-store
  "A fresh, empty MemStore."
  []
  (->MemStore (atom {:routes {} :vehicles {} :sorting-results {} :dispatch {}
                      :records {} :contamination-log []
                      :ledger [] :dispatch-sequence 0 :dispatch-history []
                      :escalation-sequence 0})))

(defn sample-data!
  "Seeds `s` (a MemStore) with a small, self-contained route + vehicle
  set -- two verified+registered routes (schedulable for dispatch),
  one UNVERIFIED/unregistered route (blocks any dispatch scheduled
  against it); one verified+registered collection-robot unit
  (schedulable for dispatch), one UNVERIFIED/unregistered
  collection-robot unit (blocks any dispatch scheduled against it) --
  so the actor + demo + tests run offline. Returns `s` (thread-friendly
  with `->`)."
  [s]
  (with-routes s (sample-routes))
  (with-vehicles s (sample-vehicles))
  s)

;; ----------------------------- back-compat aliases -----------------------------
;; `get-ledger` mirrors `ledger` under the name several sibling actors'
;; own demo/test harnesses already call.

(defn get-ledger [s] (ledger s))
