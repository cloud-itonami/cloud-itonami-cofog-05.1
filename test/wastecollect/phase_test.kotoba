(ns wastecollect.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:schedule-route-dispatch` must NEVER be a member of any
  phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [wastecollect.phase :as phase]))

(deftest schedule-route-dispatch-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in future entries, auto-commits a real route dispatch"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :schedule-route-dispatch))
          (str "phase " n " must not auto-commit :schedule-route-dispatch")))))

(deftest escalate-contamination-never-auto-at-any-phase
  (doseq [[n {:keys [auto]}] phase/phases]
    (is (not (contains? auto :escalate-contamination))
        (str "phase " n " must not auto-commit :escalate-contamination"))))

(deftest log-sorting-result-never-auto-at-any-phase
  (doseq [[n {:keys [auto]}] phase/phases]
    (is (not (contains? auto :log-sorting-result))
        (str "phase " n " must not auto-commit :log-sorting-result"))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-no-risk-ops
  (testing ":log-route-pickup carries no physical/financial risk -- auto-eligible; it is the ONLY auto-eligible op in this domain"
    (is (= #{:log-route-pickup} (:auto (get phase/phases 3))))))

(deftest schedule-route-dispatch-enabled-from-phase-3-only
  (is (contains? (:writes (get phase/phases 3)) :schedule-route-dispatch))
  (is (not (contains? (:writes (get phase/phases 2)) :schedule-route-dispatch)))
  (is (not (contains? (:writes (get phase/phases 1)) :schedule-route-dispatch))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :log-route-pickup} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :schedule-route-dispatch} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :escalate-contamination} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :log-sorting-result} :commit)))))

(deftest gate-auto-commits-the-one-eligible-write-when-clean
  (is (= :commit (:disposition (phase/gate 3 {:op :log-route-pickup} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :log-route-pickup} :commit)))))

(deftest verdict->disposition-maps-hard-to-hold
  (is (= :hold (phase/verdict->disposition {:hard? true :escalate? false}))))

(deftest verdict->disposition-maps-escalate
  (is (= :escalate (phase/verdict->disposition {:hard? false :escalate? true}))))

(deftest verdict->disposition-maps-commit
  (is (= :commit (phase/verdict->disposition {:hard? false :escalate? false}))))
