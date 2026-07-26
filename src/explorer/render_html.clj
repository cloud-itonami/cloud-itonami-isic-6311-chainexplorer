(ns explorer.render-html
  "Build `docs/samples/operator-console.html` BY RUNNING THE REAL ACTOR.

  The console is not hand-written HTML describing what the governor would
  do — it is the ledger of what the governor actually did, rendered. Every
  row below came out of `explorer.operation` in this process, including the
  holds. Same discipline (and same CI sanity-check shape) as the sibling
  market-data actor's console.

  Usage: `clojure -M:dev:render-html [out-file]`"
  (:require [clojure.string :as str]
            [langgraph.graph :as g]
            [explorer.facts :as facts]
            [explorer.operation :as op]
            [explorer.store :as store]))

(def ^:private curator {:actor-id "cur-1" :actor-role :curator :phase 3})
(def ^:private indexer {:actor-id "idx-1" :actor-role :indexer :phase 3})
(def ^:private sub-basic {:actor-id "sub-1" :actor-role :subscriber
                          :tenant "tenant-basic" :phase 3})

(def ^:private two-obs
  [{:endpoint "https://node-a.example" :number 102 :hash "0xaaa102"}
   {:endpoint "https://node-b.example" :number 102 :hash "0xaaa102"}])

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn run-demo!
  "Runs a fresh seeded store through a scenario mixing every disposition
  this actor can reach. Returns the store so the ledger can be rendered."
  []
  (let [db (store/seed-db)
        actor (op/build db)]
    (exec! actor "b1" {:op :block/index :subject "block-102"
                       :block {:number 102 :hash "0xaaa102" :parent-hash "0xaaa101"}
                       :observations two-obs} indexer)
    (exec! actor "b2" {:op :block/index :subject "block-103"
                       :block {:number 103 :hash "0xaaa103" :parent-hash "0xaaa102"}
                       :observations [(first two-obs)]} indexer)
    (exec! actor "l1" {:op :label/assert :subject "0xdemoc3" :address "0xdemoc3"
                       :label {:kind :protocol-contract :subject-type :contract
                               :name "Demo Factory (fictitious)"
                               :attribution {:class :protocol-registry
                                             :ref "demo-factory:getPool:0xdemoc3"}}}
           curator)
    (g/run* actor {:approval {:status :approved :by "curator-1"}}
            {:thread-id "l1" :resume? true})
    (exec! actor "l2" {:op :label/assert :subject "0xdemod4" :address "0xdemod4"
                       :guess? true} curator)
    (exec! actor "l3" {:op :label/assert :subject "0xdemoe5" :address "0xdemoe5"
                       :identity? true} curator)
    (exec! actor "d1" {:op :disclosure/query :subject "0xtx1"
                       :chain-context {:head-number 101 :finalized-number 90}
                       :claimed-finality :final :overclaim? true} sub-basic)
    (exec! actor "d2" {:op :disclosure/query :subject "0xtx1"
                       :chain-context {:head-number 101 :finalized-number 90}}
           sub-basic)
    db))

(defn- esc [s]
  (-> (str s) (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;")))

(defn- row [{:keys [t op subject disposition basis detail]}]
  (str "        <tr><td><code>" (esc (some-> op name)) "</code></td>"
       "<td><code>" (esc subject) "</code></td>"
       "<td class=\"" (if (= :commit disposition) "ok" "warn") "\">"
       (esc (some-> disposition name)) "</td>"
       "<td>" (esc (str/join ", " (map name (or basis [])))) "</td>"
       "<td class=\"detail\">" (esc (or (first detail) (name (or t :fact)))) "</td></tr>"))

(defn render [db]
  (let [ledger (store/ledger db)
        cov (facts/coverage)]
    (str "<!doctype html>\n<html lang=\"ja\"><head><meta charset=\"utf-8\">\n"
         "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n"
         "<title>chainexplorer — operator console</title>\n"
         "<style>\n"
         ":root{color-scheme:light dark}\n"
         "body{font:15px/1.6 ui-sans-serif,system-ui,sans-serif;margin:0;padding:2rem;max-width:64rem}\n"
         "h1{font-size:1.5rem;margin:0 0 .25rem}\n"
         "p.sub{color:#666;margin:0 0 2rem}\n"
         "table{border-collapse:collapse;width:100%;margin:0 0 2rem}\n"
         "th,td{text-align:left;padding:.5rem .6rem;border-bottom:1px solid #8883;vertical-align:top}\n"
         "th{font-weight:600;font-size:.85rem;color:#666}\n"
         "td.ok{color:#1a7f37;font-weight:600}\n"
         "td.warn{color:#b35900;font-weight:600}\n"
         "td.detail{color:#666;font-size:.85rem}\n"
         "code{font:13px ui-monospace,monospace}\n"
         "ul{padding-left:1.2rem} li{margin:.2rem 0}\n"
         ".note{background:#8881;padding:1rem;border-radius:.5rem;font-size:.9rem}\n"
         "</style></head><body>\n"
         "  <h1>cloud-itonami-isic-6311-chainexplorer</h1>\n"
         "  <p class=\"sub\">explorer.render-html — every row below was produced by running the real"
         " OperationActor, holds included.</p>\n"
         "  <h2>Decision ledger</h2>\n"
         "  <table>\n"
         "    <thead><tr><th>op</th><th>subject</th><th>disposition</th><th>basis</th><th>detail</th></tr></thead>\n"
         "    <tbody>\n"
         (str/join "\n" (map row ledger))
         "\n    </tbody>\n  </table>\n"
         "  <h2>Attribution catalog</h2>\n"
         "  <p class=\"note\">" (esc (:note cov)) "</p>\n"
         "  <ul>\n"
         (str/join "\n" (map #(str "    <li><code>" (esc (name (:class %))) "</code> — "
                                   (esc (:name %)) "<br><small>再検証: "
                                   (esc (:recheckable-by %)) "</small></li>")
                             facts/catalog))
         "\n  </ul>\n"
         "  <h2>Refused, by construction</h2>\n"
         "  <ul>\n"
         (str/join "\n" (map #(str "    <li><code>" (esc (name %)) "</code></li>")
                             (sort (:excluded cov))))
         "\n  </ul>\n"
         "  <p class=\"note\">自然人への紐付け: <strong>" (esc (name (:identity-attribution cov)))
         "</strong>（人的承認による解除経路も無い）</p>\n"
         "</body></html>\n")))

(defn -main [& [out]]
  (let [f (or out "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (.mkdirs (java.io.File. (.getParent (java.io.File. f))))
    (spit f html)
    (println "wrote" f "(" (count (store/ledger db)) "ledger facts )")))
