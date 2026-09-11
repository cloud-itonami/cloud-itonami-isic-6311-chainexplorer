(ns explorer.policy
  "ExplorerGovernor — the independent compliance layer that earns the
  ExplorerAdvisor-LLM the right to index, label or disclose. The advisor
  has no notion of finality, of what makes an attribution re-checkable, of
  the difference between a company and a person, or of what a reorg means
  for something already published — so this MUST be a separate system able
  to REJECT a proposal and fall back to HOLD (index nothing, label nothing,
  disclose nothing).

  Seven checks, in priority order. The first five are HARD: a human
  approver CANNOT override them. The last two are SOFT/always-escalate.

    1. rbac                 — does actor-role have permission for op?
    2. identity-gate        — does the proposal try to link an address to a
                              NATURAL PERSON? Refused outright, permanently,
                              with no approval path. This is the one gate
                              that exists to protect someone who is not the
                              customer and will never know this ran.
    3. attribution-gate     — does a label cite an allowed, re-checkable
                              attribution class with a real ref? Heuristic
                              clustering, vendor risk scores, social reports
                              and LLM inference have no class to cite.
    4. finality-gate        — is the disclosed finality claim no stronger
                              than the fact's ACTUAL finality? Serving a
                              1-confirmation tx as :final is the defining
                              explorer failure.
    5. corroboration-gate   — for a canonical-chain fact, did >= N
                              independent endpoints agree on the hash? One
                              node is one party's claim about the chain.
    6. confidence floor     — advisor confidence below threshold → escalate
    7. allegation-gate      — any negative characterization of an address
                              (sanctioned/scam/hack-proceeds/...) ALWAYS
                              goes to a human, at any phase, at any
                              confidence, even when perfectly sourced. A
                              sanctions listing is a real fact; publishing
                              it about a party at scale is still a decision.

  Reorg handling is not a gate but an op (`:reorg/resolve`) that is never
  auto-eligible — see `explorer.phase`."
  (:require [clojure.set :as set]
            [explorer.chain :as chain]
            [explorer.facts :as facts]
            [explorer.store :as store]))

(def confidence-floor 0.6)

(def permissions
  "actor-role → set of operations it may perform."
  {:indexer     #{:block/index :tx/index}
   :curator     #{:block/index :tx/index :label/assert :reorg/resolve}
   :subscriber  #{:disclosure/query}})

(def tier-columns
  "For `:disclosure/query` — what each subscriber tier may see. `:label`
  and `:attribution` are pro-tier and above deliberately: a basic-tier
  consumer gets objective chain data, and the interpretive layer (who this
  address supposedly is, and on what basis) only goes to someone whose
  contract says they receive it — and always WITH its citation, never the
  label alone."
  (let [base #{:hash :block-number :from :to :value :confirmations :finality}
        pro-extra #{:label :attribution}
        inst-extra #{:corroboration :raw}]
    {:tier/basic         base
     :tier/pro           (into base pro-extra)
     :tier/institutional (into base (into pro-extra inst-extra))}))

(def label-ops #{:label/assert})
(def chain-ops #{:block/index :tx/index})

;; ───────────────────────── checks ──────────────────────────────────────

(defn- rbac-violations [{:keys [op]} {:keys [actor-role]}]
  (when-not (contains? (get permissions actor-role #{}) op)
    [{:rule :rbac :detail (str actor-role " は " op " の権限を持たない")}]))

(defn- identity-violations
  "HARD, no override, checked BEFORE attribution: a proposal that carries
  identity fields is refused even if it also cites a perfect source,
  because there is no source that makes de-anonymizing a person in a public
  explorer index acceptable here."
  [{:keys [op]} proposal]
  (when (contains? label-ops op)
    (let [label (:value proposal)]
      (when (facts/identity-claim? label)
        [{:rule :identity-gate
          :detail (str "自然人への紐付けは恒久的にスコープ外（人的承認でも解除不可）: "
                       (pr-str (vec (filter facts/identity-fields (keys (or label {}))))))}]))))

(defn- attribution-violations
  "HARD: an address label must cite a class from the closed catalog with a
  non-blank ref. This is what keeps the explorer from labelling by
  heuristic — the classes a conventional explorer relies on are simply
  absent from `facts/allowed-classes` and therefore uncitable."
  [{:keys [op]} proposal]
  (when (contains? label-ops op)
    (let [attribution (get-in proposal [:value :attribution])]
      (when-not (facts/attribution-ok? attribution)
        [{:rule :attribution-gate
          :detail (str "帰属の出典が無いか再検証可能なクラスでない: " (pr-str attribution))}]))))

(defn- finality-violations
  "HARD: the claimed finality of a disclosure may not exceed the actual
  finality of the underlying block. `:claimed-finality` defaults to what
  the data itself computes (so an honest caller cannot trip this), and the
  gate exists for the caller who asserts something stronger."
  [{:keys [op] :as request} proposal st]
  (when (= :disclosure/query op)
    (let [claimed (or (:claimed-finality request) (get-in proposal [:value :finality]))
          tx*     (store/tx st (:subject request))
          {:keys [head-number finalized-number]} (:chain-context request)
          actual  (when tx*
                    (chain/finality-class (:block-number tx*) head-number finalized-number))]
      (cond
        (nil? tx*)
        [{:rule :finality-gate :detail (str "未知の tx は開示できない: " (:subject request))}]

        (and claimed (not (chain/at-least? actual claimed)))
        [{:rule :finality-gate
          :detail (str "実際の finality (" (name actual) ") より強い主張 ("
                       (name claimed) ") はできない")}]

        :else nil))))

(defn- corroboration-violations
  "HARD: a canonical-chain fact needs independent agreement. The
  observations travel on the request (the transport collects them); the
  governor re-runs `chain/corroboration` itself rather than trusting a
  caller-computed `:ok?` — the same discipline as the sibling actor
  re-checking a composite's constituents instead of its summary."
  [{:keys [op] :as request}]
  (when (contains? chain-ops op)
    (let [obs (:observations request)
          {:keys [ok? agree endpoints disagreement]}
          (chain/corroboration (or obs []) (or (:min-endpoints request)
                                               chain/default-min-endpoints))]
      (when-not ok?
        [{:rule :corroboration-gate
          :detail (if (seq disagreement)
                    (str "ノード間でブロックハッシュが不一致（reorg か誤報。自動解決しない）: "
                         (pr-str disagreement))
                    (str "独立ノードの裏取りが不足: agree=" agree " endpoints=" endpoints
                         " required=" (or (:min-endpoints request)
                                          chain/default-min-endpoints)))}]))))

(defn- disclosure-violations
  "`:disclosure/query` is served only against a registered active
  subscriber contract, and only within its tier's column set."
  [{:keys [op]} {:keys [tenant]} proposal st]
  (when (= :disclosure/query op)
    (let [c (when tenant (store/subscriber st tenant))]
      (if (or (nil? c) (not (:active? c)))
        [{:rule :licensed-disclosure :detail (str "有効な契約が無い: tenant=" tenant)}]
        (let [allowed (get tier-columns (:tier c) #{})
              extra (set/difference (set (:columns proposal)) allowed)]
          (when (seq extra)
            [{:rule :licensed-disclosure
              :detail (str "契約 tier " (:tier c) " に対し過剰な列: " (vec extra))}]))))))

(defn- allegation? [{:keys [op]} proposal]
  (and (contains? label-ops op) (facts/allegation? (:value proposal))))

(defn- reorg-op? [{:keys [op]}] (= :reorg/resolve op))

(defn check
  "-> {:ok? bool :violations [..] :confidence c :escalate? bool
       :hard? bool :allegation? bool :reorg? bool}

  - `:hard?`  — an unoverridable violation (rbac / identity / attribution /
                finality / corroboration / disclosure). Disposition HOLD.
  - `:escalate?` — soft: low confidence, an allegation label, or a reorg
                resolution. Routes to a human, who may approve."
  [request context proposal st]
  (let [violations (vec (concat (rbac-violations request context)
                                (identity-violations request proposal)
                                (attribution-violations request proposal)
                                (finality-violations request proposal st)
                                (corroboration-violations request)
                                (disclosure-violations request context proposal st)))
        hard? (boolean (seq violations))
        conf (:confidence proposal 0)
        low? (< conf confidence-floor)
        alleg? (allegation? request proposal)
        reorg? (reorg-op? request)]
    {:ok? (and (not hard?) (not low?) (not alleg?) (not reorg?))
     :violations violations
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? alleg? reorg?))
     :allegation? alleg?
     :reorg? reorg?}))

(defn hold-fact
  "The ledger fact written when the governor holds. Every hold is recorded
  with its violated rules — a refusal is as auditable as a publication."
  [request context verdict]
  {:t :policy-hold
   :op (:op request)
   :actor (:actor-id context)
   :subject (:subject request)
   :disposition :hold
   :basis (mapv :rule (:violations verdict))
   :detail (mapv :detail (:violations verdict))})
