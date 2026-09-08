(ns explorer.rpc-demo
  "Live smoke test against REAL Ethereum endpoints -- NOT run by
  `clojure -M:dev:test` (network required). Run explicitly:

    ETH_RPC_URLS=https://node-a,https://node-b clojure -M:rpc:dev:run-rpc

  Reads the endpoint list from env HERE (the one place in this actor
  allowed to -- `explorer.rpc` itself never does, see its docstring) and
  pushes the corroborated observation through the real OperationActor, so
  this doubles as an end-to-end proof that a live multi-node read satisfies
  the ExplorerGovernor's corroboration-gate -- and that a single-endpoint
  read does NOT.

  What it prints is exactly what the actor would publish, including
  publishing nothing."
  (:require [kotoba.lang.text :as str]
            [langgraph.graph :as g]
            [explorer.chain :as chain]
            [explorer.operation :as op]
            [explorer.rpc :as rpc]
            [explorer.store :as store]))

(def ^:private curator {:actor-id "rpc-demo" :actor-role :curator :phase 3})

(defn- exec! [actor tid request]
  (let [res (g/run* actor {:request request :context curator} {:thread-id tid})]
    (println (format "  %-24s -> %s%s" tid
                     (name (or (get-in res [:state :disposition]) (:status res)))
                     (let [v (get-in res [:state :verdict :violations])]
                       (if (seq v)
                         (str "  [" (str/join "," (map (comp name :rule) v)) "]")
                         ""))))
    (doseq [v (get-in res [:state :verdict :violations])]
      (println "      " (:detail v)))
    res))

(defn -main [& _]
  (let [endpoints (rpc/parse-endpoints (System/getenv "ETH_RPC_URLS"))]
    (if (< (count endpoints) 2)
      (println (str "SKIPPED: set ETH_RPC_URLS to at least 2 comma-separated "
                    "independent endpoints. This actor refuses to index a "
                    "chain it has only one party's word for -- that refusal "
                    "is the feature, so there is no single-endpoint fallback."))
      (let [db (store/empty-db)
            actor (op/build db)]
        (println "── endpoints ──")
        (doseq [e endpoints] (println "  " e))

        (println "\n── head / finality context ──")
        (let [{:keys [head-number finalized-number]} (rpc/head-context endpoints)]
          (println "   head:" head-number "  finalized:" (or finalized-number "(none served)"))
          (when head-number
            (println "   finality of head:" (name (chain/finality-class head-number head-number finalized-number)))
            (when finalized-number
              (println "   finality of finalized head:"
                       (name (chain/finality-class finalized-number head-number finalized-number))))))

        (println "\n── multi-endpoint observation of the same block ──")
        (let [head (:head-number (rpc/head-context endpoints))
              ;; read a block a little back from the tip: endpoints
              ;; legitimately differ by a block or two at the very head,
              ;; and that is a lag artifact rather than a disagreement
              ;; about history.
              target (when head (- head 5))
              obs (rpc/observe-block endpoints target)]
          (doseq [o (:observations obs)]
            (println "  " (:endpoint o) "->" (:hash o)))
          (doseq [e (:errors obs)]
            (println "   ERROR" (:endpoint e) (:error e)))
          (println "   corroboration:" (pr-str (chain/corroboration (:observations obs))))

          (println "\n── governed index ──")
          (when-let [req (rpc/index-request obs)]
            (exec! actor "index-corroborated" req)
            (println "   (same block, ONE endpoint only:)")
            (exec! actor "index-single-source"
                   (assoc req :subject (str (:subject req) "-single")
                          :observations [(first (:observations obs))]))))

        (println "\n── ledger ──")
        (doseq [f (store/ledger db)] (println "  " (store/ledger-line f)))
        (println "\ndone.")))))
