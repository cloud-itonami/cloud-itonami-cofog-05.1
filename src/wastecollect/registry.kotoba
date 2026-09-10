(ns wastecollect.registry
  "Pure-function domain logic for the municipal waste-collection Route
  Advisor actor -- route/vehicle verification, diversion-category
  validation, tonnage plausibility validation, contamination-percentage
  plausibility validation, and draft route-dispatch/escalation record
  construction.

  Per docs/adr/0001-architecture.md Decision 1: this vertical has no
  pre-existing `kotoba-lang/wastecollect`-style capability library to
  wrap (verified: no such repo exists). The domain logic therefore
  lives here as pure functions, re-verified INDEPENDENTLY by
  `wastecollect.governor` -- the same 'ground truth, not self-report'
  discipline every sibling actor's own registry establishes (e.g.
  `motomfg.registry/shipment-quantity-exceeded?` from
  `cloud-itonami-isic-3091`, `recovery.registry/contamination-
  percentage-exceeds-maximum?` from `cloud-itonami-isic-3830`): never
  trust a proposal's own self-reported tonnage/category/percentage when
  the inputs needed to independently validate it are already on
  record, or are simple physical-plausibility bounds.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real fleet-management or municipal-reporting system. It
  builds the DRAFT record a route coordinator would keep (a scheduled
  route dispatch, a filed contamination escalation), not the act of
  actuating a collection robot/vehicle directly, and never a municipal
  diversion-rate compliance filing (this actor NEVER does either -- see
  README `What this actor does NOT do`).

  SCOPE: COFOG 05.1 covers municipal waste-collection service delivery
  -- route pickup, preliminary on-vehicle sorting, and dispatch
  coordination for a contracted hauler's collection fleet. This actor
  coordinates the back-office record-keeping around that fleet (route
  pickup logging, sorting-result logging, dispatch scheduling,
  contamination escalation) -- it never actuates the collection
  robot/vehicle directly, and it never stands in for a municipality's
  own diversion-rate compliance authority.")

;; ----------------------------- constants -----------------------------

(def valid-diversion-categories
  "The closed set of diversion-category values a sorting-result record
  may declare -- spans the outcomes a preliminary on-vehicle sort of a
  collected load can actually produce. Anything else is a
  fabricated/unrecognized category -- the governor HARD-holds rather
  than let an invented category pass through into a diversion-rate
  record."
  #{:recyclable-mixed :recyclable-organics :recyclable-metal
    :landfill-residual :bulky-item :yard-waste :hazardous-suspect})

(def tonnage-min-kg
  "Physical floor for a route pickup's own reported load weight (an
  empty/no-pickup run legitimately reports 0)."
  0.0)

(def tonnage-max-kg
  "Physical ceiling for a single route pickup's own reported load
  weight -- generous enough to cover a large municipal collection
  vehicle or transfer-trailer load, but bounded so an implausible/
  sensor-error tonnage reading is rejected rather than silently
  accepted into a diversion-rate record."
  40000.0)

(def contamination-percentage-min
  "Physical floor for a load's own measured contamination percentage
  (zero contamination is the best possible outcome, never negative)."
  0.0)

(def contamination-percentage-max
  "Physical ceiling for a load's own measured contamination percentage
  -- a load cannot be more than 100% contaminated. A reading above this
  is implausible sensor/inspection data, not a real load."
  100.0)

;; ----------------------------- route checks -----------------------------

(defn route-verified?
  "Ground-truth check: has `route`'s own record been marked verified
  (i.e. it has actually been surveyed/confirmed by the municipality or
  hauler, not merely referenced from an unverified dispatch request)?
  A pure predicate over the route's own permanent field -- no proposal
  inspection needed."
  [route]
  (true? (:verified? route)))

(defn route-registered?
  "Ground-truth check: does `route`'s own record carry a
  `:registered?` true flag (i.e. it is on file in the hauler's route
  registry)? Dispatching a vehicle against a route that is not on file
  and registered is the exact scope violation this actor's HARD
  invariant ('route/vehicle record must be independently verified/
  registered before any action') exists to block."
  [route]
  (true? (:registered? route)))

(defn route-ready?
  "Combined ground-truth gate: the route must be both `verified?` AND
  `registered?` before ANY vehicle dispatch may be scheduled against
  it. Two independent facts on the route's own permanent record,
  neither inferred from the advisor's own rationale."
  [route]
  (and (route-verified? route) (route-registered? route)))

;; ----------------------------- vehicle checks -----------------------------

(defn vehicle-verified?
  "Ground-truth check: has `vehicle`'s own record been marked verified
  (i.e. the collection robot/vehicle has actually been inspected/
  commissioned and registered in the SSoT, not merely referenced from
  an unverified dispatch request)?"
  [vehicle]
  (true? (:verified? vehicle)))

(defn vehicle-registered?
  "Ground-truth check: does `vehicle`'s own record carry a
  `:registered?` true flag (i.e. it is on file in the hauler's fleet
  registry)?"
  [vehicle]
  (true? (:registered? vehicle)))

(defn vehicle-ready?
  "Combined ground-truth gate: the collection vehicle/robot must be
  both `verified?` AND `registered?` before ANY route dispatch may be
  scheduled against it."
  [vehicle]
  (and (vehicle-verified? vehicle) (vehicle-registered? vehicle)))

;; ----------------------------- record-field validation -----------------------------

(defn diversion-category-valid?
  "Is `category` one of the closed, known diversion-category values a
  preliminary on-vehicle sort can actually produce? nil/blank is
  treated as invalid (a sorting-result patch must declare a real
  category, not omit it silently)."
  [category]
  (contains? valid-diversion-categories category))

(defn tonnage-valid?
  "Is `kg` a physically plausible reported route-pickup load weight?
  Rejects nil, non-numbers, negative values, and values beyond
  `tonnage-max-kg` -- a fabricated or sensor-error reading, never let
  through as a real pickup fact."
  [kg]
  (and (number? kg)
       (>= (double kg) tonnage-min-kg)
       (<= (double kg) tonnage-max-kg)))

(defn contamination-percentage-valid?
  "Is `percent` a physically plausible load contamination-percentage
  reading? Rejects nil, non-numbers, negative values, and values beyond
  `contamination-percentage-max` -- a fabricated or sensor-error
  reading, never let through as a real sorting-result fact."
  [percent]
  (and (number? percent)
       (>= (double percent) contamination-percentage-min)
       (<= (double percent) contamination-percentage-max)))

;; ----------------------------- draft record construction -----------------------------

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the human route coordinator's/dispatcher's act, not this actor's.
  And NEVER a municipal diversion-rate compliance filing -- this actor
  is never the municipality's own compliance-reporting authority (see
  README `What this actor does NOT do`)."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn register-dispatch
  "Validate + construct the ROUTE-DISPATCH DRAFT -- a proposed
  collection-vehicle dispatch against a verified, registered route and
  a verified, registered vehicle. Pure function -- does not actuate
  the collection robot/vehicle or execute any pickup; it builds the
  RECORD a route coordinator would keep. `wastecollect.governor`
  independently re-verifies the route's and vehicle's own
  verified/registered ground truth, and permanently blocks any attempt
  to directly actuate the collection vehicle (see README `Actuation`),
  before this is ever allowed to commit."
  [dispatch-id route-id vehicle-id sequence]
  (when-not (and dispatch-id (not= dispatch-id ""))
    (throw (ex-info "dispatch: dispatch_id required" {})))
  (when-not (and route-id (not= route-id ""))
    (throw (ex-info "dispatch: route_id required" {})))
  (when-not (and vehicle-id (not= vehicle-id ""))
    (throw (ex-info "dispatch: vehicle_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "dispatch: sequence must be >= 0" {})))
  (let [dispatch-number (str "DSP-" (zero-pad sequence 6))
        record {"record_id" dispatch-number
                "kind" "route-dispatch-draft"
                "dispatch_id" dispatch-id
                "route_id" route-id
                "vehicle_id" vehicle-id
                "immutable" true}]
    {"record" record "dispatch_number" dispatch-number
     "certificate" (unsigned-certificate "RouteDispatch" dispatch-number dispatch-number)}))

(defn register-escalation
  "Validate + construct the CONTAMINATION-ESCALATION DRAFT -- a filed
  suspected-hazardous-material/contamination concern, routed to a
  human for sign-off. Pure function -- does not itself clear, dispose
  of, or reclassify the material; it builds the RECORD a route
  coordinator would keep pending human review. Many jurisdictions
  govern suspected hazardous material discovered in a municipal waste
  stream through a manifest/chain-of-custody framework rather than a
  collector's own unilateral disposal decision (e.g. the U.S. RCRA
  hazardous-waste-generator standards, 40 CFR Part 262, require a
  manifest rather than self-clearance) -- this record's own
  never-auto-resolved posture is the software-side analog of that same
  discipline, not a claim that this software itself is a RCRA-regulated
  generator or compliance system (see `wastecollect.governor` docstring
  and README)."
  [concern-id sequence]
  (when-not (and concern-id (not= concern-id ""))
    (throw (ex-info "escalation: concern_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "escalation: sequence must be >= 0" {})))
  (let [escalation-number (str "ESC-" (zero-pad sequence 6))
        record {"record_id" escalation-number
                "kind" "contamination-escalation-draft"
                "concern_id" concern-id
                "immutable" true}]
    {"record" record "escalation_number" escalation-number
     "certificate" (unsigned-certificate "ContaminationEscalation" escalation-number escalation-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
