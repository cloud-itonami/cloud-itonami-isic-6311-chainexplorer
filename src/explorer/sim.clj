(ns explorer.sim
  "Offline demo — drives one OperationActor through every disposition this
  actor can reach, with no network. `clojure -M:dev:run`.

  The scenario is chosen to show the gates that make this an explorer
  rather than an accusation machine, in the order they matter:

    1. a corroborated block index            → commit
    2. the SAME index with one endpoint only → HOLD (corroboration-gate)
    3. a properly sourced contract label     → commit
    4. an unsourced 'looks like an exchange
       hot wallet' label at 0.93 confidence  → HOLD (attribution-gate)
    5. a label naming a natural person       → HOLD (identity-gate)
    6. a sanctions label WITH a real citation→ ESCALATE, then human approves
    7. a disclosure claiming :final on a
       2-confirmation tx                     → HOLD (finality-gate)
    8. an honest disclosure at :probabilistic→ commit
    9. a reorg resolution                    → ESCALATE (never automatic)

  Steps 4 and 5 run at HIGH advisor confidence on purpose: the hard gates
  must not consult confidence at all."
  (:require [clojure.string :as str]
            [langgraph.graph :as g]
            [explorer.chain :as chain]
            [explorer.operation :as op]
            [explorer.store :as store]))

(def ^:private curator {:actor-id "cur-1" :actor-role :curator :phase 3})
(def ^:private subscriber-basic {:actor-id "sub-1" :actor-role :subscriber
                                 :tenant "tenant-basic" :phase 3})

(defn- exec! [actor tid request context]
  (let [res (g/run* actor {:request request :context context} {:thread-id tid})]
    (println (format "  %-26s -> %s%s" tid
                     (name (or (get-in res [:state :disposition]) (:status res)))
                     (let [v (get-in res [:state :verdict :violations])]
                       (if (seq v) (str "  [" (str/join "," (map (comp name :rule) v)) "]") ""))))
    res))

(defn- approve! [actor tid by]
  (let [res (g/run* actor {:approval {:status :approved :by by}}
                    {:thread-id tid :resume? true})]
    (println (format "  %-26s -> %s (human: %s)" (str tid "/approve")
                     (name (get-in res [:state :disposition])) by))
    res))

(def two-endpoints
  [{:endpoint "https://node-a.example" :number 102 :hash "0xaaa102"}
   {:endpoint "https://node-b.example" :number 102 :hash "0xaaa102"}])

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)
        block {:number 102 :hash "0xaaa102" :parent-hash "0xaaa101" :timestamp 1785000024}]

    (println "── chain indexing ──")
    (exec! actor "block-corroborated"
           {:op :block/index :subject "block-102" :block block
            :observations two-endpoints}
           curator)
    (exec! actor "block-single-source"
           {:op :block/index :subject "block-102b"
            :block {:number 103 :hash "0xaaa103" :parent-hash "0xaaa102"}
            :observations [(first two-endpoints)]}
           curator)

    (println "\n── address attribution ──")
    (exec! actor "label-sourced"
           {:op :label/assert :subject "0xdemo0000000000000000000000000000000000c3"
            :address "0xdemo0000000000000000000000000000000000c3"
            :label {:kind :protocol-contract :name "Demo Factory (fictitious)"
                    :subject-type :contract
                    :attribution {:class :protocol-registry
                                  :ref "demo-factory:getPool:0xdemo...c3"}}}
           curator)
    ;; clean attribution clears every HARD gate, and STILL waits for a
    ;; human: labelling someone else's address is not something this actor
    ;; does unattended at any phase.
    (approve! actor "label-sourced" "curator-1")
    (exec! actor "label-guessed"
           {:op :label/assert :subject "0xdemo0000000000000000000000000000000000d4"
            :address "0xdemo0000000000000000000000000000000000d4"
            :guess? true}
           curator)
    (exec! actor "label-identity"
           {:op :label/assert :subject "0xdemo0000000000000000000000000000000000e5"
            :address "0xdemo0000000000000000000000000000000000e5"
            :identity? true}
           curator)

    (println "\n── allegation: sourced, but never automatic ──")
    (exec! actor "label-sanctions"
           {:op :label/assert :subject "0xdemo0000000000000000000000000000000000f6"
            :address "0xdemo0000000000000000000000000000000000f6"
            :label {:kind :sanctioned :subject-type :entity
                    :name "（デモ。実在の指定主体ではない）"
                    :attribution {:class :sanctions-list-publication
                                  :ref "demo-authority:LIST-1:entry-42"}}}
           curator)
    (approve! actor "label-sanctions" "compliance-1")

    (println "\n── disclosure ──")
    (let [ctx {:head-number 101 :finalized-number 90}]
      (exec! actor "disclose-overclaim"
             {:op :disclosure/query :subject "0xtx1" :chain-context ctx
              :claimed-finality :final :overclaim? true}
             subscriber-basic)
      (exec! actor "disclose-honest"
             {:op :disclosure/query :subject "0xtx1" :chain-context ctx}
             subscriber-basic)
      (println "   tx-view:" (pr-str (chain/tx-view (store/tx db "0xtx1") ctx))))

    (println "\n── reorg ──")
    (exec! actor "reorg-resolve"
           {:op :reorg/resolve :subject "block-101" :at 101
            :ours "0xaaa101" :theirs "0xbbb101"}
           curator)

    (println "\n── ledger ──")
    (doseq [f (store/ledger db)]
      (println "  " (store/ledger-line f)))
    (println "\ndone.")))
