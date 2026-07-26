(ns explorer.llm
  "ExplorerAdvisor-LLM — the *contained intelligence node*.

  It drafts human-readable transaction summaries, proposes address labels
  from evidence it was handed, and proposes subscriber disclosure column
  sets. CRITICAL: it is a smart-but-untrusted advisor. It returns a
  *proposal* (with rationale and the citation it used), never a committed
  or published record. Everything it produces is censored downstream by
  `explorer.policy` before anything is indexed, labelled or disclosed.

  Same shape as the sibling actors' advisors, with one addition specific to
  this domain: the mock advisor deliberately includes failure modes this
  actor must survive, because they are the ones a REAL LLM produces
  constantly when pointed at chain data —

    `:guess?`    an address label with no evidence at all, stated
                 confidently ('this looks like an exchange hot wallet').
                 The attribution-gate must reject it regardless of the
                 stated confidence.
    `:identity?` a label that names a natural person behind an address.
                 The identity-gate must reject it with no approval path.
    `:overclaim?` a disclosure asserting stronger finality than the block
                 actually has.

  These are not hypothetical adversary inputs — they are the default
  behaviour of a language model asked 'who owns this address?'. The mock
  states them at HIGH confidence on purpose, to prove the hard gates do not
  consult confidence at all."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [explorer.chain :as chain]
            [explorer.store :as store]
            [langchain.model :as model]))

(defn- propose-block-index
  "Index an observed block header into the canonical chain. The advisor
  adds no facts here — the block came from the transport, and the
  corroboration evidence travels on the request for the governor to
  re-check."
  [st {:keys [block]}]
  (let [{:keys [chain effect]} (chain/apply-block (store/chain-view st) block)]
    {:summary (str "block index: #" (:number block) " " (:hash block))
     :rationale (str "ノード観測ヘッダの取込のみ。連鎖判定=" (name (:kind effect)) "。")
     :cites [:number :hash :parent-hash]
     :effect :chain-extend
     :value {:chain chain :block block :chain-effect effect}
     :confidence (if (= :reorg (:kind effect)) 0.4 0.95)}))

(defn- propose-tx-index
  [_st {:keys [tx]}]
  {:summary (str "tx index: " (:hash tx))
   :rationale "観測済みトランザクションの正規化のみ。新規事実の生成なし。"
   :cites [:hash :block-number :from :to :value]
   :effect :tx-index
   :value tx
   :confidence 0.95})

(defn- propose-label
  "Address attribution draft. `:guess?` / `:identity?` inject the two
  failure modes described in this namespace's docstring."
  [_st {:keys [address label guess? identity?]}]
  (let [base (or label {})]
    (cond
      identity?
      {:summary (str "address label: " address " (個人特定を含む提案)")
       :rationale "取引パターンから実在人物に紐付けられると判断した。"
       :cites [:from :to]
       :effect :label-assert
       :value (assoc base :address address :subject-type :natural-person
                     :person-name "（LLM が推定した実名）")
       :confidence 0.92}

      guess?
      {:summary (str "address label: " address " (出典なしの推定)")
       :rationale "入出金の規模とパターンから取引所ホットウォレットと推定。"
       :cites [:value]
       :effect :label-assert
       :value (-> base (assoc :address address :kind :exchange-hot-wallet)
                  (dissoc :attribution))
       :confidence 0.93}

      :else
      {:summary (str "address label: " address " (" (some-> (:kind base) name) ")")
       :rationale (str "提示された出典に基づく帰属のみ: "
                       (pr-str (:attribution base)))
       :cites [:attribution]
       :effect :label-assert
       :value (assoc base :address address)
       :confidence 0.9})))

(defn- propose-disclosure
  "Disclosure column-set + view proposal. `:greedy?` pulls columns beyond a
  basic tier; `:overclaim?` asserts a stronger finality than the data has."
  [st {:keys [subject greedy? overclaim? chain-context]}]
  (let [tx* (store/tx st subject)
        view (chain/tx-view tx* (or chain-context {}))
        base [:hash :block-number :from :to :value :confirmations :finality]]
    {:summary (str "開示: " subject)
     :rationale (if overclaim?
                  "確定済みとして提示してよいと判断した。"
                  "契約 tier に必要な最小列のみ、finality はデータから計算した値。")
     :cites base
     :effect :disclosure-serve
     :columns (if greedy? (into base [:label :attribution :raw]) base)
     :value (cond-> view overclaim? (assoc :finality :final))
     :confidence 0.9}))

(defn- propose-reorg-resolve
  "Reorg resolution draft. NEVER auto-applies: `explorer.policy` always
  escalates `:reorg/resolve`, and `explorer.phase` keeps it out of every
  phase's `:auto` set. A reorg means facts already disclosed may now be
  false, and choosing what to do about that is not an inference."
  [_st {:keys [at ours theirs]}]
  {:summary (str "reorg 解決案: height " at)
   :rationale (str "ローカル " ours " / ノード " theirs
                   "。公開済み事実の取り扱いは人間判断。")
   :cites [:number :hash]
   :effect :noop
   :value {:at at :ours ours :theirs theirs}
   :confidence 0.5})

(defn infer
  [st {:keys [op] :as request}]
  (case op
    :block/index      (propose-block-index st request)
    :tx/index         (propose-tx-index st request)
    :label/assert     (propose-label st request)
    :disclosure/query (propose-disclosure st request)
    :reorg/resolve    (propose-reorg-resolve st request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :confidence 0.0}))

;; ───────────────────────── Advisor protocol ────────────────────────────

(defprotocol Advisor
  (-advise [advisor store request] "store + request → proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたはブロックチェーン・エクスプローラの解説アドバイザーです。"
       "与えられた事実のみに基づき、提案を1つだけ EDN マップで返します。"
       "説明や前置きは書かず EDN だけを出力します。\n"
       "キー: :summary :rationale :cites :effect"
       "(:chain-extend|:tx-index|:label-assert|:disclosure-serve|:noop) "
       ":value :columns :confidence(0..1)。\n"
       "重要: (1) アドレスへのラベル付けは、提示された再検証可能な出典 "
       "(:attribution {:class .. :ref ..}) がある場合のみ提案してよい。"
       "取引パターンからの推測でラベルを付けてはならない。"
       "(2) アドレスを実在の個人に紐付ける提案は絶対にしてはならない。"
       "(3) finality は与えられた計算値をそのまま使う——強めてはならない。"
       "これらは governor が hard reject するため、提案しても通らない。"))

(defn- facts-for [st {:keys [op subject]}]
  (case op
    :disclosure/query {:tx (store/tx st subject) :label (store/label st subject)}
    :label/assert     {:existing-label (store/label st subject)}
    {:head (chain/head-block (store/chain-view st))}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the governor escalates or holds — an
  LLM hiccup can never auto-commit."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :confidence 0.0})))

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
  "Decision-grounded audit record — persisted to the :audit channel."
  [request proposal]
  {:t :exploreradvisor-proposal
   :op (:op request)
   :subject (:subject request)
   :summary (:summary proposal)
   :rationale (:rationale proposal)
   :cites (:cites proposal)
   :confidence (:confidence proposal)})
