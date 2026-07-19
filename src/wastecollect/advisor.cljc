(ns wastecollect.advisor
  "Route Advisor -- the *contained intelligence node* for the municipal
  waste-collection actor.

  It normalizes route-pickup patches (tonnage/bin-count/pickup-date),
  drafts a preliminary on-vehicle sorting-result finding
  (diversion-category/contamination-percentage), drafts a
  collection-vehicle route-dispatch scheduling proposal against a
  route and vehicle, and drafts a contamination-escalation flag.
  CRITICAL: it is a smart-but-untrusted advisor. It returns a
  *proposal* (with a rationale + the fields it cited), never a
  committed record and NEVER a real collection-robot/vehicle
  actuation or a municipal diversion-rate compliance filing -- see
  README `What this actor does NOT do`. Every output is censored
  downstream by `wastecollect.governor` before anything touches the
  SSoT.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- informational only, NOT trusted
                                 ; by the governor for any ground-truth
                                 ; check (see `wastecollect.governor`)
     :cites      [kw|str ..]    ; fields the advisor used
     :effect     kw             ; how a commit would mutate the SSoT --
                                 ; ALWAYS one of the closed
                                 ; #{:route/upsert :sorting/set
                                 ; :dispatch/schedule
                                 ; :contamination/flag} propose-shaped
                                 ; effects, NEVER a direct collection-
                                 ; robot/vehicle-control effect
     :stake      kw|nil         ; :collection/contamination-concern | nil
     :confidence 0..1}

  CRITICAL invariant this advisor upholds: every request it is asked to
  route MUST itself carry `:effect :propose` (the request-level
  contract every caller of this actor agrees to) -- `wastecollect.
  governor` HARD-holds any request that doesn't, so a mis-wired caller
  can never reach a commit path even if this advisor were compromised."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [wastecollect.registry :as registry]
            [wastecollect.store :as store]
            [langchain.model :as model]))

(defn- log-route-pickup
  "Route-pickup intake upsert -- the advisor only normalizes/validates
  the patch; it does not invent the route's tonnage, bin-count or
  verification status. High confidence, low stakes -- administrative
  logging, not an operational decision."
  [_db {:keys [patch]}]
  {:summary    (str "収集ルート記録更新: " (pr-str (keys patch)))
   :rationale  "入力patchの正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :route/upsert
   :value      patch
   :stake      nil
   :confidence 0.95})

(defn- log-sorting-result
  "Draft a preliminary on-vehicle sorting-result finding (diversion-
  category + contamination-percentage) for a route's latest pickup.
  The advisor reports the category/percentage it was handed; it does
  NOT independently validate that the category is a real known value
  or the percentage is physically plausible -- `wastecollect.governor`
  never trusts this report and independently re-checks both against
  `wastecollect.registry`'s closed set / plausibility bounds before any
  commit is possible."
  [db {:keys [subject value]}]
  (let [r (store/route db subject)]
    {:summary    (str subject " 向け選別結果提案 ("
                      (name (or (:diversion-category value) :unknown)) ")")
     :rationale  (if r
                   (str "route-verified?=" (registry/route-verified? r)
                        " diversion-category=" (:diversion-category value)
                        " contamination-percentage=" (:contamination-percentage value))
                   (str subject " が見つかりません"))
     :cites      (if r [subject] [])
     :effect     :sorting/set
     :value      value
     :stake      nil
     :confidence (if r 0.85 0.2)}))

(defn- schedule-route-dispatch
  "Draft a collection-vehicle route-dispatch scheduling proposal
  against a route and vehicle. The advisor reports what it can see
  (route/vehicle verified?/registered?) in its rationale, but
  `wastecollect.governor` NEVER trusts this report -- it independently
  re-derives verified?/registered? from the route's and vehicle's own
  stored fields before any commit is possible."
  [db {:keys [subject value]}]
  (let [route-id (:route-id value)
        vehicle-id (:vehicle-id value)
        r (store/route db route-id)
        v (store/vehicle db vehicle-id)
        ready? (and r v (registry/route-ready? r) (registry/vehicle-ready? v))]
    {:summary    (str subject " 向け収集車両配車提案"
                      (when r (str " route=" route-id))
                      (when v (str " vehicle=" vehicle-id)))
     :rationale  (str "route-verified?=" (some-> r registry/route-verified?)
                      " route-registered?=" (some-> r registry/route-registered?)
                      " vehicle-verified?=" (some-> v registry/vehicle-verified?)
                      " vehicle-registered?=" (some-> v registry/vehicle-registered?)
                      " actuate-vehicle?=" (boolean (:actuate-vehicle? value)))
     :cites      (cond-> [] r (conj route-id) v (conj vehicle-id))
     :effect     :dispatch/schedule
     :value      value
     :stake      nil
     :confidence (if (and ready? (not (:actuate-vehicle? value))) 0.9 0.3)}))

(defn- escalate-contamination
  "Draft a suspected-hazardous-material/contamination concern. ALWAYS
  `:stake :collection/contamination-concern` -- a contamination concern
  is NEVER a proposal the advisor may quietly downgrade to low-stakes,
  and it is never gated on the referenced route being verified (a
  concern can be raised about ANY route, verified or not -- see README
  `What this actor does NOT do` re: never blocking safety-relevant
  reporting on an administrative technicality). See `wastecollect.
  phase`: no phase ever adds this op to a phase's `:auto` set;
  `wastecollect.governor` also always escalates on `:collection/
  contamination-concern`. Two independent layers agree, deliberately."
  [db {:keys [subject value]}]
  (let [route-id (:route-id value)
        r (and route-id (store/route db route-id))]
    {:summary    (str subject " 向け汚染懸念報告 (" (:severity value) ")"
                      (when r (str " route=" route-id)))
     :rationale  (str "severity=" (:severity value) " description=" (:description value))
     :cites      (if r [route-id] [])
     :effect     :contamination/flag
     :value      value
     :stake      :collection/contamination-concern
     :confidence 0.9}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :effect :propose :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :log-route-pickup          (log-route-pickup db request)
    :log-sorting-result        (log-sorting-result db request)
    :schedule-route-dispatch   (schedule-route-dispatch db request)
    :escalate-contamination    (escalate-contamination db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは自治体廃棄物収集ルートアドバイザーの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。"
       "説明や前置きは一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:route/upsert|:sorting/set|"
       ":dispatch/schedule|:contamination/flag) "
       ":stake(:collection/contamination-concern か nil) :confidence(0..1)。\n"
       "重要: 未検証または未登録のルート・車両に対する配車を提案してはいけません。"
       "収集ロボット・車両の直接操作(actuate)を絶対に提案してはいけません"
       "(この actor は提案のみを行い、実行は一切行いません)。"
       "自治体の分別率(diversion-rate)コンプライアンス申告を自己発行する提案をしてはいけません。"
       "疑わしい有害物質を自己判断で処理済みと報告してはいけません。"))

(defn- facts-for [st {:keys [op subject value]}]
  (case op
    :log-route-pickup           {:route (store/route st subject)}
    :log-sorting-result         {:route (store/route st subject)}
    :schedule-route-dispatch    {:route (store/route st (:route-id value))
                                 :vehicle (store/vehicle st (:vehicle-id value))}
    :escalate-contamination     {:route (and (:route-id value) (store/route st (:route-id value)))}
    {}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so `wastecollect.governor`
  escalates/holds -- an LLM hiccup can never auto-schedule a dispatch,
  auto-file a sorting result, or auto-escalate/downgrade a
  contamination concern."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :route-advisor-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
