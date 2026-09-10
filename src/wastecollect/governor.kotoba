(ns wastecollect.governor
  "Waste Operations Governor -- the independent compliance layer that
  earns the Route Advisor the right to commit. The advisor has no
  notion of whether a route it wants to dispatch a vehicle against has
  actually been surveyed/registered, whether a vehicle it wants to
  dispatch has actually been inspected/registered, whether a dispatch
  proposal secretly tries to ACTUATE (rather than merely schedule) the
  collection robot/vehicle, whether a sorting-result patch declares a
  fabricated diversion-category or an implausible contamination
  percentage, whether a route-pickup patch declares an implausible
  tonnage reading, or when an act stops being a dispatch-coordination
  proposal and becomes direct collection-robot/vehicle control, so
  this MUST be a separate system able to *reject* a proposal and fall
  back to HOLD.

  `:itonami.blueprint/governor` is `:waste-operations-governor` (see
  docs/adr/0001-architecture.md).

  Checks below, ALL HARD violations except the confidence/high-stakes
  gate (SOFT -- asks a human to look, and the human may approve):

    1. Request-level propose-only  -- did the CALLER's own request
                                       actually declare `:effect
                                       :propose`? Any other value is a
                                       mis-wired/compromised caller
                                       trying to bypass proposal-only
                                       mode -- HARD, unconditional,
                                       evaluated BEFORE anything else.
    2. Closed op allowlist         -- is `:op` one of the four ops this
                                       actor is authorized to
                                       coordinate? Anything else --
                                       HARD hold.
    3. Closed effect allowlist     -- is the PROPOSAL's own `:effect`
                                       (what would actually commit) one
                                       of the four propose-shaped
                                       effects? A proposal effect
                                       outside this set (e.g. a
                                       hallucinated `:vehicle/actuate`
                                       or `:robot/drive`) is the
                                       'direct collection-robot/
                                       vehicle control' scope violation
                                       this actor must NEVER perform --
                                       HARD, PERMANENT, unconditional.
    4. Vehicle-actuate blocked     -- for `:schedule-route-dispatch`,
                                       does the proposal's own `:value`
                                       declare `:actuate-vehicle?
                                       true`? Directly actuating the
                                       collection robot/vehicle is this
                                       actor's other permanent scope
                                       boundary (see README `What this
                                       actor does NOT do`) -- HARD,
                                       PERMANENT, unconditional. NO
                                       phase and NO human approval can
                                       ever override this (see
                                       `wastecollect.phase`: this op is
                                       never a member of any phase's
                                       `:auto` set either -- two
                                       independent layers agree).
    5. Route not verified/
       registered                  -- for `:schedule-route-dispatch`,
                                       INDEPENDENTLY verify the
                                       referenced route's own
                                       `:verified?` AND `:registered?`
                                       are both true
                                       (`wastecollect.registry/route-
                                       ready?`) -- never trust the
                                       advisor's own rationale about
                                       verification/registration
                                       status. Grounded in this
                                       blueprint's own HARD invariant
                                       ('route/vehicle record must be
                                       independently verified/
                                       registered before any action'):
                                       a vehicle must never be
                                       dispatched against a route whose
                                       own survey has not actually been
                                       confirmed or whose registration
                                       is not actually on file.
    6. Vehicle not verified/
       registered                  -- for `:schedule-route-dispatch`,
                                       INDEPENDENTLY verify the
                                       referenced vehicle's own
                                       `:verified?` AND `:registered?`
                                       are both true
                                       (`wastecollect.registry/vehicle-
                                       ready?`) -- never trust the
                                       advisor's own rationale. Also
                                       part of the 'route/vehicle
                                       record' HARD invariant: a
                                       vehicle's own verified/
                                       registered status is as much a
                                       ground-truth fact as a route's
                                       own.
    7. Already dispatched          -- for `:schedule-route-dispatch`,
                                       refuses to schedule the SAME
                                       dispatch record twice, off a
                                       dedicated `:dispatched?` fact
                                       (never a `:status` value).
    8. Invalid diversion-category  -- for `:log-sorting-result`, if the
                                       value declares a
                                       `:diversion-category` outside
                                       the closed known set
                                       (`wastecollect.registry/
                                       diversion-category-valid?`), the
                                       sorting record is rejected
                                       rather than let a fabricated
                                       category through.
    9. Invalid contamination-
       percentage                   -- for `:log-sorting-result`, if
                                       the value declares a
                                       `:contamination-percentage` that
                                       is not a physically plausible
                                       reading
                                       (`wastecollect.registry/
                                       contamination-percentage-
                                       valid?`), the sorting record is
                                       rejected rather than let
                                       fabricated/sensor-error data
                                       through.
   10. Invalid tonnage             -- for `:log-route-pickup`, if the
                                       patch declares a `:tonnage-kg`
                                       that is not a physically
                                       plausible reading
                                       (`wastecollect.registry/
                                       tonnage-valid?`), the route
                                       record is rejected rather than
                                       let fabricated/sensor-error data
                                       through.
   11. Confidence floor / high-
       stakes gate                  -- LLM confidence below threshold,
                                       OR the proposal's own `:stake`
                                       is in `high-stakes`
                                       (`:collection/contamination-
                                       concern`, ALWAYS set for
                                       `:escalate-contamination`) --
                                       escalate to a human route
                                       coordinator. SOFT: the human may
                                       approve."
  (:require [wastecollect.registry :as registry]
            [wastecollect.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed allowlist of coordination proposals this actor may ever
  route -- see README `What this actor does`."
  #{:log-route-pickup :log-sorting-result
    :schedule-route-dispatch :escalate-contamination})

(def allowed-proposal-effects
  "The closed allowlist of SSoT-mutation effects a proposal may declare
  -- all four are propose-shaped drafts, NEVER a direct
  collection-robot/vehicle-control effect."
  #{:route/upsert :sorting/set
    :dispatch/schedule :contamination/flag})

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Contamination concerns are the one op in this domain that always
  demands human eyes regardless of confidence."
  #{:collection/contamination-concern})

;; ----------------------------- checks -----------------------------

(defn- no-propose-effect-violations
  "HARD, unconditional, evaluated first: the caller's own request MUST
  declare `:effect :propose` -- any other value is a mis-wired or
  compromised caller trying to bypass proposal-only mode."
  [{:keys [effect]}]
  (when (not= effect :propose)
    [{:rule :not-propose-effect
      :detail (str "request :effect は :propose のみ許可 (受信値: " (pr-str effect) ")")}]))

(defn- unknown-op-violations
  "HARD: `:op` must be one of the closed allowlist this actor
  coordinates -- never route an unrecognized operation."
  [{:keys [op]}]
  (when-not (contains? allowed-ops op)
    [{:rule :unknown-op
      :detail (str op " はこの actor が扱う操作の許可リストに無い")}]))

(defn- vehicle-control-blocked-violations
  "HARD, PERMANENT: the proposal's own `:effect` -- what would actually
  commit -- must be within the closed propose-shaped effect allowlist.
  Anything else (direct collection-robot/vehicle control, a fabricated
  actuation effect) is this actor's central scope boundary."
  [proposal]
  (when-not (contains? allowed-proposal-effects (:effect proposal))
    [{:rule :vehicle-control-blocked
      :detail (str "proposal :effect (" (pr-str (:effect proposal))
                   ") は収集ロボット・車両の直接操作に該当する可能性があり、恒久的に禁止")}]))

(defn- vehicle-actuate-blocked-violations
  "HARD, PERMANENT, unconditional: a `:schedule-route-dispatch`
  proposal whose own `:value` declares `:actuate-vehicle? true` is
  attempting to directly actuate the collection robot/vehicle -- this
  actor may only ever propose/schedule a DRAFT dispatch, never actuate
  the vehicle directly. No override, ever."
  [{:keys [op]} proposal]
  (when (and (= op :schedule-route-dispatch)
             (true? (:actuate-vehicle? (:value proposal))))
    [{:rule :vehicle-actuate-blocked
      :detail "収集ロボット・車両の直接操作(actuate)提案は恒久的に禁止 -- 提案(draft)のみ許可"}]))

(defn- route-not-verified-violations
  "For `:schedule-route-dispatch`, INDEPENDENTLY verify the referenced
  route exists and is both `:verified?` AND `:registered?` -- never
  trust the advisor's own report. This is the HARD invariant
  ('route/vehicle record must be independently verified/registered
  before any action')."
  [{:keys [op]} proposal st]
  (when (= op :schedule-route-dispatch)
    (let [route-id (:route-id (:value proposal))
          r (and route-id (store/route st route-id))]
      (when-not (and r (registry/route-ready? r))
        [{:rule :route-not-verified
          :detail (str route-id " は未検証または未登録、もしくは存在しない -- 検証済み・登録済みルート記録が無い状態での配車提案")}]))))

(defn- vehicle-not-verified-violations
  "For `:schedule-route-dispatch`, INDEPENDENTLY verify the referenced
  vehicle exists and is both `:verified?` AND `:registered?` -- never
  trust the advisor's own report. Also part of the 'route/vehicle
  record must be independently verified/registered before any action'
  HARD invariant."
  [{:keys [op]} proposal st]
  (when (= op :schedule-route-dispatch)
    (let [vehicle-id (:vehicle-id (:value proposal))
          v (and vehicle-id (store/vehicle st vehicle-id))]
      (when-not (and v (registry/vehicle-ready? v))
        [{:rule :vehicle-not-verified
          :detail (str vehicle-id " は未検証または未登録、もしくは存在しない -- 検証済み・登録済み車両記録が無い状態での配車提案")}]))))

(defn- already-dispatched-violations
  "For `:schedule-route-dispatch`, refuses to schedule the SAME
  dispatch record twice, off a dedicated `:dispatched?` fact (never a
  `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :schedule-route-dispatch)
    (when (store/dispatch-already-scheduled? st subject)
      [{:rule :already-dispatched
        :detail (str subject " は既に配車済み")}])))

(defn- invalid-diversion-category-violations
  "For `:log-sorting-result`, if the value declares a
  `:diversion-category` outside the closed known set, reject rather
  than let a fabricated category through."
  [{:keys [op]} proposal]
  (when (= op :log-sorting-result)
    (let [category (:diversion-category (:value proposal))]
      (when (and (some? category) (not (registry/diversion-category-valid? category)))
        [{:rule :invalid-diversion-category
          :detail (str category " は既知の diversion-category 値ではない")}]))))

(defn- invalid-contamination-percentage-violations
  "For `:log-sorting-result`, if the value declares a
  `:contamination-percentage` that is not a physically plausible
  reading, reject rather than let fabricated/sensor-error data
  through."
  [{:keys [op]} proposal]
  (when (= op :log-sorting-result)
    (let [percent (:contamination-percentage (:value proposal))]
      (when (and (some? percent) (not (registry/contamination-percentage-valid? percent)))
        [{:rule :invalid-contamination-percentage
          :detail (str percent "% は物理的に妥当な汚染率の範囲外")}]))))

(defn- invalid-tonnage-violations
  "For `:log-route-pickup`, if the patch declares a `:tonnage-kg` that
  is not a physically plausible reading, reject rather than let
  fabricated/sensor-error data through."
  [{:keys [op]} proposal]
  (when (= op :log-route-pickup)
    (let [kg (:tonnage-kg (:value proposal))]
      (when (and (some? kg) (not (registry/tonnage-valid? kg)))
        [{:rule :invalid-tonnage
          :detail (str kg "kg は物理的に妥当な積載量の範囲外")}]))))

(defn check
  "Censors a Route Advisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (no-propose-effect-violations request)
                           (unknown-op-violations request)
                           (vehicle-control-blocked-violations proposal)
                           (vehicle-actuate-blocked-violations request proposal)
                           (route-not-verified-violations request proposal st)
                           (vehicle-not-verified-violations request proposal st)
                           (already-dispatched-violations request st)
                           (invalid-diversion-category-violations request proposal)
                           (invalid-contamination-percentage-violations request proposal)
                           (invalid-tonnage-violations request proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
