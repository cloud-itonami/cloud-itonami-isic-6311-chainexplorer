(ns explorer.operation
  "OperationActor — one index/label/disclosure/reorg operation = one
  supervised actor run, expressed as a langgraph-clj StateGraph. The
  advisor (ExplorerAdvisor-LLM) is sealed into a single node (:advise); its
  proposal ALWAYS goes through the ExplorerGovernor (:govern) and the
  rollout phase gate (:decide) before anything is written or disclosed.

  Everything is injected, so each is a swap rather than a rewrite:
    - the Store   (MemStore | Datomic | kotobase)  — `store` arg
    - the Advisor (mock | real LLM)                — :advisor opt
    - the Phase   (0→3 rollout)                    — :phase in context

  One graph run = one operation (intake → advise → govern → decide →
  commit | hold | approval). `interrupt-before #{:request-approval}` pauses
  the actor and hands the decision to a human curator, who resumes with
  `{:approval {:status :approved}}` (or `:rejected`).

  Same skeleton as `cloud-itonami-isic-6311`'s `marketdata.operation`
  deliberately: the sibling actors' governor contract is well-tested, and a
  new domain should differ in its gates, not in the shape of its
  supervision."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [explorer.llm :as llm]
            [explorer.phase :as phase]
            [explorer.policy :as policy]
            [explorer.store :as store]))

(defn- commit-fact [request context proposal]
  {:t :committed
   :op (:op request)
   :actor (:actor-id context)
   :subject (:subject request)
   :disposition :commit
   :basis (:cites proposal)
   :summary (:summary proposal)})

(defn- commit-record [request _context proposal]
  {:effect (:effect proposal)
   :value (:value proposal)
   :path [(:subject request)]})

(defn build
  "Compiles an OperationActor graph bound to `store` (any
  `explorer.store/Store`).
  opts:
    :advisor      — an `explorer.llm/Advisor` (default: mock-advisor)
    :checkpointer — langgraph checkpointer (default: in-mem)"
  [store & [{:keys [advisor checkpointer]
             :or {advisor (llm/mock-advisor)
                  checkpointer (cp/mem-checkpointer)}}]]
  (-> (g/state-graph
       {:channels
        {:request {:default nil}
         :context {:default nil}
         :proposal {:default nil}
         :verdict {:default nil}
         :disposition {:default nil}
         :record {:default nil}
         :approval {:default nil}
         :audit {:reducer into :default []}}})

      (g/add-node :intake (fn [s] s))

      (g/add-node :advise
        (fn [{:keys [request]}]
          (let [p (llm/-advise advisor store request)]
            {:proposal p :audit [(llm/trace request p)]})))

      (g/add-node :govern
        (fn [{:keys [request context proposal]}]
          {:verdict (policy/check request context proposal store)}))

      (g/add-node :decide
        (fn [{:keys [request context proposal verdict]}]
          (let [base (phase/verdict->disposition verdict)
                ph (:phase context phase/default-phase)
                {:keys [disposition reason]} (phase/gate ph request base)]
            (case disposition
              :hold
              {:disposition :hold
               :audit [(cond-> (policy/hold-fact request context verdict)
                         reason (assoc :phase-reason reason :phase ph))]}

              :escalate
              {:disposition :escalate
               :audit [{:t :approval-requested
                        :op (:op request) :subject (:subject request)
                        :reason (or reason
                                    (cond (:reorg? verdict) :reorg-resolution
                                          (:allegation? verdict) :allegation-label
                                          :else :low-confidence))
                        :phase ph
                        :confidence (:confidence verdict)}]}

              :commit
              {:disposition :commit
               :record (commit-record request context proposal)}))))

      (g/add-node :request-approval
        (fn [{:keys [request context proposal approval verdict]}]
          (if (= :approved (:status approval))
            {:disposition :commit
             :record (assoc (commit-record request context proposal)
                            :approved-by (:by approval))
             :audit [{:t :approval-granted :op (:op request)
                      :subject (:subject request) :by (:by approval)}]}
            {:disposition :hold
             :audit [(merge (policy/hold-fact request context
                                              (assoc verdict :violations
                                                     [{:rule :approver-rejected}]))
                            {:t :approval-rejected})]})))

      ;; Commit — the ONLY node that writes the SSoT + audit ledger. For
      ;; `:disclosure/query` and `:reorg/resolve`, `commit-record!` has no
      ;; matching `:effect` case (no SSoT mutation) — the ledger fact is
      ;; what makes the event auditable.
      (g/add-node :commit
        (fn [{:keys [request context proposal record]}]
          (store/commit-record! store record)
          (let [f (commit-fact request context proposal)]
            (store/append-ledger! store f)
            {:audit [f]})))

      (g/add-node :hold
        (fn [{:keys [audit]}]
          (when-let [hf (last (filter #(#{:policy-hold :approval-rejected} (:t %)) audit))]
            (store/append-ledger! store (assoc hf :disposition :hold)))
          {}))

      (g/set-entry-point :intake)
      (g/add-edge :intake :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)

      (g/add-conditional-edges :decide
        (fn [{:keys [disposition]}]
          (case disposition
            :commit :commit
            :escalate :request-approval
            :hold)))

      (g/add-conditional-edges :request-approval
        (fn [{:keys [disposition]}]
          (if (= :commit disposition) :commit :hold)))

      (g/set-finish-point :commit)
      (g/set-finish-point :hold)

      (g/compile-graph
       {:checkpointer checkpointer
        :interrupt-before #{:request-approval}})))
