;; The public explorer, running in the browser (scittle = in-browser
;; ClojureScript, no build step) — same zero-build pattern as
;; cloud-itonami.github.io's catalog.cljs.
;;
;; THE POINT OF THIS FILE: it calls `explorer.chain` — the SAME namespace
;; the ExplorerGovernor uses server-side — which the generator copies next
;; to this one. Corroboration and finality classification in this page are
;; not a re-implementation that could drift from the actor's rules; they
;; are the actor's rules, executing in the reader's browser against the
;; reader's own endpoints. Nothing here trusts a backend, because there
;; isn't one.
;;
;; html.core / explorer.chain are loaded before this file by script order,
;; and are called FULLY QUALIFIED (not via ns :require) so the headless
;; harness can `load-string` this file — same convention, and same reason,
;; as catalog.cljs.

(ns explorer.ui
  (:require [kotoba.lang.text]))

;; ─────────────────────────── endpoints ───────────────────────────────

(defn- endpoints []
  (->> (.split (.-value (js/document.getElementById "endpoints")) #"[\n,]")
       (map #(.trim %))
       (remove #(= "" %))
       distinct
       vec))

(defn- rpc
  "One read-only JSON-RPC call. Returns a promise of the raw result, or
  nil on any failure — a dead endpoint must shrink the quorum, never
  abort the page."
  [url method params]
  (-> (js/fetch url
                #js {:method "POST"
                     :headers #js {"content-type" "application/json"}
                     :body (js/JSON.stringify
                            #js {:jsonrpc "2.0" :id 1 :method method
                                 :params (clj->js params)})})
      (.then #(.json %))
      (.then #(some-> (aget % "result") ))
      (.catch (fn [_] nil))))

(defn- hex->num [h]
  (when (string? h) (js/parseInt (subs h 2) 16)))

(defn- get-block [url tag]
  (-> (rpc url "eth_getBlockByNumber"
           [(if (number? tag) (str "0x" (.toString tag 16)) tag) false])
      (.then (fn [b]
               (when b
                 {:number (hex->num (aget b "number"))
                  :hash (aget b "hash")
                  :parent-hash (aget b "parentHash")
                  :timestamp (hex->num (aget b "timestamp"))})))))

;; ─────────────────────────── rendering ───────────────────────────────

(defn- set-html! [id hiccup]
  (set! (.-innerHTML (js/document.getElementById id))
        (html.core/->html hiccup)))

(defn- finality-chip [f]
  (let [color (case f
                :final "green"
                :confirmed "blue"
                :probabilistic "yellow"
                "red")]
    [:span {:class "dads-chip-label" :data-style "filled-1" :data-color color}
     (name (or f :unknown))]))

(defn- kv-row [k v] [:tr [:th {:class "dads-table__col-header" :scope "row"} k] [:td v]])

(defn- kv-table [rows]
  [:div {:class "dads-table"}
   [:table {:class "dads-table__table"} (into [:tbody] rows)]])

(defn- status! [msg] (set-html! "status" [:p {:class "x-status"} msg]))

(defn- clear! [& ids]
  (doseq [id ids] (set! (.-innerHTML (js/document.getElementById id)) "")))

;; ─────────────────────────── head + finality ─────────────────────────

(defn- render-head! [heads finalized]
  (let [head (:number (first (remove nil? heads)))
        fin (:number (first (remove nil? finalized)))]
    (set-html!
     "head-out"
     (if (nil? head)
       [:p {:class "x-refusal"} "どのエンドポイントも応答しませんでした。表示できる chain 状態はありません。"]
       [:<>
        (kv-table
         [(kv-row "head" [:code (str head)])
          (kv-row "finalized head"
                  (if fin [:code (str fin)]
                      [:span {:class "x-refusal-inline"}
                       "このエンドポイントは finalized タグを返しません → :final には到達できません"]))
          (kv-row "head の finality" (finality-chip (explorer.chain/finality-class head head fin)))
          (kv-row "finalized head の finality"
                  (if fin (finality-chip (explorer.chain/finality-class fin head fin)) "—"))])
        [:p {:class "x-note"}
         "finality はチェーン自身の finalized タグから決まります。タグが取れないときは "
         [:code ":final"] " に到達できません — 深さのヒューリスティクスが経済的 finality の代役をすることはありません。"]]))))

;; ─────────────────────────── corroboration ───────────────────────────

(defn- render-corroboration! [target obs]
  (let [c (explorer.chain/corroboration obs)]
    (set-html!
     "corr-out"
     [:<>
      (kv-table
       (into [(kv-row "block" [:code (str target)])]
             (map (fn [{:keys [endpoint hash]}]
                    (kv-row [:code endpoint] [:code (or hash "—")]))
                  obs)))
      (if (:ok? c)
        [:<>
         [:p {:class "x-ok"} "✓ " (:agree c) " 個の独立エンドポイントが一致しました。"]
         (kv-table [(kv-row "corroborated hash" [:code (:hash c)])])]
        [:div {:class "x-refusal"}
         [:p [:strong "表示しません。"]
          (if (seq (:disagreement c))
            " エンドポイント間でブロックハッシュが一致していません。これは live reorg か、どちらかのノードが誤っているかのどちらかです。"
            (str " 独立した裏取りが足りません（一致 " (:agree c) " / 必要 "
                 explorer.chain/default-min-endpoints "）。"))]
         (when (seq (:disagreement c))
           (kv-table
            (map (fn [{:keys [hash endpoints]}]
                   (kv-row [:code hash] (kotoba.lang.text/join ", " endpoints)))
                 (:disagreement c))))
         [:p {:class "x-note"}
          "多数決はしません。2対1でも多数派ハッシュを採用しません — 分裂は人間が見るべき事象であって、このページが黙って投票する場面ではないからです。"]])])))

;; ─────────────────────────── transaction ─────────────────────────────

(defn- render-tx! [tx head fin]
  (set-html!
   "tx-out"
   (if (nil? tx)
     [:p {:class "x-refusal"} "そのハッシュのトランザクションは、どのエンドポイントからも取得できませんでした。"]
     (let [v (explorer.chain/tx-view tx {:head-number head :finalized-number fin})]
       [:<>
        (kv-table
         [(kv-row "hash" [:code (:hash v)])
          (kv-row "block" [:code (str (:block-number v))])
          (kv-row "from" [:code (:from v)])
          (kv-row "to" [:code (or (:to v) "（contract creation）")])
          (kv-row "confirmations" [:code (str (:confirmations v))])
          (kv-row "finality" (finality-chip (:finality v)))])
        [:p {:class "x-note"}
         "finality はこの表示の属性であって、横に置いたバッジではありません — "
         [:code "explorer.chain/tx-view"]
         " が返す map に finality を欠いた形は存在しません。ラベル（このアドレスが誰か）は"
         "このページには一切ありません: 出典付きのラベルだけが契約 tier の開示面に出ます。"]]))))

;; ─────────────────────────── actions ─────────────────────────────────

(defn- all
  "js/Promise.all over a seq of promises -> a promise of a vector."
  [ps]
  (.then (js/Promise.all (clj->js (vec ps))) #(vec %)))

(defn- head-context
  "-> promise of {:heads [..] :finalized [..] :head n :fin n}. `head` and
  `fin` are taken from the first endpoint that answered; `fin` stays nil
  when no endpoint serves the `finalized` tag, and nil then propagates all
  the way into `finality-class`, which is what makes `:final` unreachable
  rather than approximated."
  [eps]
  (-> (all [(all (map #(get-block % "latest") eps))
            (all (map #(get-block % "finalized") eps))])
      (.then (fn [r]
               (let [heads (nth r 0)
                     finalized (nth r 1)]
                 {:heads heads
                  :finalized finalized
                  :head (:number (first (remove nil? heads)))
                  :fin (:number (first (remove nil? finalized)))})))))

(defn- corroborate! [eps target head fin]
  (-> (all (map #(get-block % target) eps))
      (.then (fn [bs]
               (let [obs (vec (keep-indexed
                               (fn [i b]
                                 (when b {:endpoint (nth eps i)
                                          :number (:number b)
                                          :hash (:hash b)}))
                               bs))]
                 (render-corroboration! target obs)
                 (status! (str "完了 — head " head
                               (when fin (str " / finalized " fin)))))))))

(defn- read-chain! []
  ;; Clear FIRST, always. A refusal rendered underneath the previous
  ;; successful corroboration reads as "corroborated, and also a warning" —
  ;; the exact misreading this page exists to prevent. Stale output is not
  ;; a cosmetic issue here; it is the failure mode wearing a different hat.
  (clear! "head-out" "corr-out")
  (let [eps (endpoints)]
    (if (< (count eps) explorer.chain/default-min-endpoints)
      (status! (str "独立したエンドポイントを "
                    explorer.chain/default-min-endpoints
                    " つ以上入れてください。1つだけの読み取りは、そのプロバイダの主張を"
                    "このページの名前で再配信することになるので、単一エンドポイントの"
                    "フォールバックは用意していません。"))
      (do
        (status! "読み取り中…")
        (-> (head-context eps)
            (.then (fn [{:keys [heads finalized head fin]}]
                     (render-head! heads finalized)
                     (if (nil? head)
                       (status! "エンドポイントから head が取れませんでした。")
                       ;; read a block a little back from the tip: endpoints
                       ;; legitimately differ by a block or two at the very
                       ;; head, and that lag is not a disagreement about
                       ;; history.
                       (corroborate! eps (- head 5) head fin)))))))))

(defn- decode-tx [tx]
  (when tx
    {:hash (aget tx "hash")
     :block-number (hex->num (aget tx "blockNumber"))
     :from (aget tx "from")
     :to (aget tx "to")
     :value (aget tx "value")}))

(defn- lookup-tx! []
  (clear! "tx-out")
  (let [eps (endpoints)
        h (.trim (.-value (js/document.getElementById "txhash")))]
    (when-not (= "" h)
      (status! "トランザクションを取得中…")
      (-> (all [(head-context eps)
                (rpc (first eps) "eth_getTransactionByHash" [h])])
          (.then (fn [r]
                   (let [{:keys [head fin]} (nth r 0)]
                     (render-tx! (decode-tx (nth r 1)) head fin)
                     (status! "完了"))))))))

;; ─────────────────────────── wiring ──────────────────────────────────

(defn ^:export init []
  (.addEventListener (js/document.getElementById "read-chain") "click" read-chain!)
  (.addEventListener (js/document.getElementById "lookup-tx") "click" lookup-tx!)
  (status! "エンドポイントを確認して「chain を読む」を押してください。"))

(init)
