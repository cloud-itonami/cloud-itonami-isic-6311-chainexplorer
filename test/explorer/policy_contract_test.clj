(ns explorer.policy-contract-test
  "The governor contract as executable tests — the analog of
  `cloud-itonami-isic-6311`'s policy_contract_test. The single invariant
  under test:

    The ExplorerAdvisor never indexes, labels or discloses anything the
    ExplorerGovernor would reject, and every decision (commit OR hold)
    leaves exactly one ledger fact.

  Read the identity-gate tests first. They are the ones protecting someone
  who is not this actor's customer and will never know it ran."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [explorer.operation :as op]
            [explorer.store :as store]))

(defn- fresh []
  (let [db (store/seed-db)] [db (op/build db)]))

(def curator {:actor-id "cur-1" :actor-role :curator})
(def curator-p3 (assoc curator :phase 3))
(def indexer-p3 {:actor-id "idx-1" :actor-role :indexer :phase 3})
(def sub-basic {:actor-id "sub-1" :actor-role :subscriber :tenant "tenant-basic" :phase 3})
(def sub-pro   {:actor-id "sub-2" :actor-role :subscriber :tenant "tenant-pro" :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(def two-obs
  [{:endpoint "https://a" :number 102 :hash "0xaaa102"}
   {:endpoint "https://b" :number 102 :hash "0xaaa102"}])

(def block-102 {:number 102 :hash "0xaaa102" :parent-hash "0xaaa101" :timestamp 1785000024})

(defn- basis [db] (-> (store/ledger db) first :basis))

;; ───────────────────────── identity (HARD, no override) ────────────────

(deftest identity-attribution-is-refused-outright
  (testing "linking an address to a natural person is permanently out of scope"
    (let [[db actor] (fresh)
          res (exec-op actor "id1"
                       {:op :label/assert :subject "0xdemoe5" :address "0xdemoe5"
                        :identity? true}
                       curator-p3)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:identity-gate} (basis db)))
      (is (nil? (store/label db "0xdemoe5")) "nothing written"))))

(deftest identity-attribution-is-refused-even-with-perfect-sourcing
  (testing "no citation makes de-anonymizing a person acceptable here"
    (let [[db actor] (fresh)
          res (exec-op actor "id2"
                       {:op :label/assert :subject "0xdemoe6" :address "0xdemoe6"
                        :label {:kind :owner :subject-type :natural-person
                                :person-name "Someone"
                                :attribution {:class :onchain-self-attestation
                                              :ref "ens:reverse:0xdemoe6"}}}
                       curator-p3)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:identity-gate} (basis db))))))

(deftest identity-attribution-has-no-human-approval-path
  (testing "a HARD violation holds; it never reaches the approval interrupt"
    (let [[_db actor] (fresh)
          res (exec-op actor "id3"
                       {:op :label/assert :subject "0xdemoe7" :address "0xdemoe7"
                        :identity? true}
                       curator-p3)]
      (is (not= :interrupted (:status res)) "no approval is ever offered")
      (is (= :hold (get-in res [:state :disposition]))))))

(deftest a-nested-identity-field-is-caught-too
  (let [[db actor] (fresh)
        res (exec-op actor "id4"
                     {:op :label/assert :subject "0xdemoe8" :address "0xdemoe8"
                      :label {:kind :entity :subject-type :entity
                              :contact {:email "someone@example.com"}
                              :attribution {:class :official-published-address-list
                                            :ref "https://example.com/addresses"}}}
                     curator-p3)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:identity-gate} (basis db)))))

;; ───────────────────────── attribution (HARD) ──────────────────────────

(deftest a-sourced-label-passes-the-governor-then-waits-for-a-human
  (testing "clean attribution clears every HARD gate — but labelling someone
            else's address is never unattended, so it still stops at the
            approval interrupt (explorer.phase keeps :label/assert out of
            every phase's :auto set, including phase 3)"
    (let [[db actor] (fresh)
          r1 (exec-op actor "lb1"
                      {:op :label/assert :subject "0xdemoc3" :address "0xdemoc3"
                       :label {:kind :protocol-contract :subject-type :contract
                               :name "Demo Factory (fictitious)"
                               :attribution {:class :protocol-registry
                                             :ref "demo-factory:getPool:0xdemoc3"}}}
                      curator-p3)]
      (is (= :interrupted (:status r1)))
      (is (empty? (get-in r1 [:state :verdict :violations])) "no HARD gate fired")
      (is (nil? (store/label db "0xdemoc3")) "nothing written before approval")
      (testing "the human approves -> committed"
        (let [r2 (g/run* actor {:approval {:status :approved :by "curator-1"}}
                         {:thread-id "lb1" :resume? true})]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= :protocol-contract (:kind (store/label db "0xdemoc3"))))
          (is (= 1 (count (store/ledger db)))))))))

(deftest an-unsourced-guess-is-held-regardless-of-confidence
  (testing "the mock advisor states this at 0.93 — hard gates never consult confidence"
    (let [[db actor] (fresh)
          res (exec-op actor "lb2"
                       {:op :label/assert :subject "0xdemod4" :address "0xdemod4"
                        :guess? true}
                       curator-p3)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:attribution-gate} (basis db)))
      (is (nil? (store/label db "0xdemod4"))))))

(deftest a-heuristic-clustering-citation-has-no-class-to-cite
  (testing "how most public explorers actually label — structurally unpublishable here"
    (let [[db actor] (fresh)
          res (exec-op actor "lb3"
                       {:op :label/assert :subject "0xdemod5" :address "0xdemod5"
                        :label {:kind :exchange-hot-wallet :subject-type :entity
                                :attribution {:class :heuristic-clustering
                                              :ref "common-input-ownership:cluster-42"}}}
                       curator-p3)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:attribution-gate} (basis db))))))

(deftest a-label-with-a-class-but-no-ref-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "lb4"
                     {:op :label/assert :subject "0xdemod6" :address "0xdemod6"
                      :label {:kind :protocol-contract :subject-type :contract
                              :attribution {:class :verified-contract-source}}}
                     curator-p3)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:attribution-gate} (basis db)))))

;; ───────────────────────── allegations (SOFT, always) ──────────────────

(deftest a-sourced-allegation-always-reaches-a-human
  (testing "a sanctions listing is a real fact; publishing it about a party is still a decision"
    (let [[db actor] (fresh)
          req {:op :label/assert :subject "0xdemof6" :address "0xdemof6"
               :label {:kind :sanctioned :subject-type :entity
                       :attribution {:class :sanctions-list-publication
                                     :ref "demo-authority:LIST-1:entry-42"}}}
          r1 (exec-op actor "al1" req curator-p3)]
      (is (= :interrupted (:status r1)) "never auto-commits, even at phase 3")
      (is (= :allegation-label (-> r1 :state :audit last :reason)))
      (testing "approve → commit"
        (let [r2 (g/run* actor {:approval {:status :approved :by "compliance-1"}}
                         {:thread-id "al1" :resume? true})]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= :sanctioned (:kind (store/label db "0xdemof6"))))))))
  (testing "reject → hold, nothing published"
    (let [[db actor] (fresh)
          _ (exec-op actor "al2"
                     {:op :label/assert :subject "0xdemof7" :address "0xdemof7"
                      :label {:kind :scam :subject-type :entity
                              :attribution {:class :official-published-address-list
                                            :ref "https://example.org/list"}}}
                     curator-p3)
          r (g/run* actor {:approval {:status :rejected :by "compliance-1"}}
                    {:thread-id "al2" :resume? true})]
      (is (= :hold (get-in r [:state :disposition])))
      (is (nil? (store/label db "0xdemof7"))))))

;; ───────────────────────── corroboration (HARD) ────────────────────────

(deftest a-corroborated-block-index-commits
  (let [[db actor] (fresh)
        res (exec-op actor "cb1"
                     {:op :block/index :subject "block-102" :block block-102
                      :observations two-obs}
                     indexer-p3)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "0xaaa102" (:hash (get-in (store/chain-view db) [:blocks 102]))))))

(deftest a-single-endpoint-index-is-held
  (testing "one node is one party's claim about the chain"
    (let [[db actor] (fresh)
          res (exec-op actor "cb2"
                       {:op :block/index :subject "block-102" :block block-102
                        :observations [(first two-obs)]}
                       indexer-p3)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:corroboration-gate} (basis db)))
      (is (nil? (get-in (store/chain-view db) [:blocks 102])) "nothing indexed"))))

(deftest endpoints-that-disagree-are-held-not-out-voted
  (let [[db actor] (fresh)
        res (exec-op actor "cb3"
                     {:op :block/index :subject "block-102" :block block-102
                      :observations (conj two-obs {:endpoint "https://c" :number 102
                                                   :hash "0xDIFFERENT"})}
                     indexer-p3)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:corroboration-gate} (basis db))
        "2-vs-1 does not publish the majority hash")))

(deftest the-governor-recomputes-corroboration-itself
  (testing "a caller-asserted :ok? cannot substitute for the evidence"
    (let [[db actor] (fresh)
          res (exec-op actor "cb4"
                       {:op :block/index :subject "block-102" :block block-102
                        :observations [(first two-obs)]
                        :corroboration {:ok? true :hash "0xaaa102" :agree 99}}
                       indexer-p3)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:corroboration-gate} (basis db))))))

;; ───────────────────────── finality (HARD) ─────────────────────────────

(def ctx-101 {:head-number 101 :finalized-number 90})

(deftest an-honest-disclosure-commits
  (let [[db actor] (fresh)
        res (exec-op actor "d1"
                     {:op :disclosure/query :subject "0xtx1" :chain-context ctx-101}
                     sub-basic)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= 1 (count (store/ledger db))) "a governed read is still an audited event")))

(deftest claiming-final-on-a-non-final-block-is-held
  (testing "the defining explorer failure: a green checkmark on a reorg-able tx"
    (let [[db actor] (fresh)
          res (exec-op actor "d2"
                       {:op :disclosure/query :subject "0xtx1" :chain-context ctx-101
                        :claimed-finality :final :overclaim? true}
                       sub-basic)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:finality-gate} (basis db))))))

(deftest claiming-no-more-than-the-truth-is-fine
  (let [[_db actor] (fresh)
        res (exec-op actor "d3"
                     {:op :disclosure/query :subject "0xtx1" :chain-context ctx-101
                      :claimed-finality :probabilistic}
                     sub-basic)]
    (is (= :commit (get-in res [:state :disposition]))
        "tx1 is at block 100 with head 101 -> :probabilistic, claim matches")))

(deftest a-finalized-block-may-be-disclosed-as-final
  (let [[_db actor] (fresh)
        res (exec-op actor "d4"
                     {:op :disclosure/query :subject "0xtx1"
                      :chain-context {:head-number 200 :finalized-number 150}
                      :claimed-finality :final}
                     sub-basic)]
    (is (= :commit (get-in res [:state :disposition])))))

(deftest without-a-finalized-head-final-can-never-be-claimed
  (let [[db actor] (fresh)
        res (exec-op actor "d5"
                     {:op :disclosure/query :subject "0xtx1"
                      :chain-context {:head-number 999999 :finalized-number nil}
                      :claimed-finality :final}
                     sub-basic)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:finality-gate} (basis db)))))

(deftest an-unknown-tx-cannot-be-disclosed
  (let [[db actor] (fresh)
        res (exec-op actor "d6"
                     {:op :disclosure/query :subject "0xnope" :chain-context ctx-101}
                     sub-basic)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:finality-gate} (basis db)))))

;; ───────────────────────── disclosure entitlement ──────────────────────

(deftest an-uncontracted-tenant-gets-nothing
  (let [[db actor] (fresh)
        res (exec-op actor "s1"
                     {:op :disclosure/query :subject "0xtx1" :chain-context ctx-101}
                     {:actor-id "x" :actor-role :subscriber :tenant "tenant-ghost" :phase 3})]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:licensed-disclosure} (basis db)))))

(deftest labels-are-a-pro-tier-column-not-a-basic-one
  (testing "objective chain data is basic; the interpretive layer is contracted for"
    (let [[db actor] (fresh)
          res (exec-op actor "s2"
                       {:op :disclosure/query :subject "0xtx1" :chain-context ctx-101
                        :greedy? true}
                       sub-basic)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:licensed-disclosure} (basis db)))))
  (testing "and :raw is above even pro"
    (let [[db actor] (fresh)
          res (exec-op actor "s3"
                       {:op :disclosure/query :subject "0xtx1" :chain-context ctx-101
                        :greedy? true}
                       sub-pro)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:licensed-disclosure} (basis db))))))

;; ───────────────────────── rbac / phases / reorg ───────────────────────

(deftest an-indexer-cannot-label
  (let [[db actor] (fresh)
        res (exec-op actor "r1"
                     {:op :label/assert :subject "0xdemoc3" :address "0xdemoc3"
                      :label {:kind :protocol-contract :subject-type :contract
                              :attribution {:class :protocol-registry :ref "x:y"}}}
                     indexer-p3)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (= [:rbac] (basis db)))))

(deftest a-subscriber-cannot-index
  (let [[db actor] (fresh)
        res (exec-op actor "r2"
                     {:op :block/index :subject "block-102" :block block-102
                      :observations two-obs}
                     sub-basic)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (= [:rbac] (basis db)))))

(deftest a-reorg-resolution-is-never-automatic
  (testing "already-published facts may now be false; that is not an inference"
    (let [[_db actor] (fresh)
          res (exec-op actor "rg1"
                       {:op :reorg/resolve :subject "block-101" :at 101
                        :ours "0xaaa101" :theirs "0xbbb101"}
                       curator-p3)]
      (is (= :interrupted (:status res)))
      (is (= :reorg-resolution (-> res :state :audit last :reason))))))

(deftest labelling-never-auto-commits-at-any-phase
  (testing "even a clean, non-allegation label waits for a human at phase 3"
    (let [[_db actor] (fresh)
          res (exec-op actor "ph1"
                       {:op :label/assert :subject "0xdemoc9" :address "0xdemoc9"
                        :label {:kind :protocol-contract :subject-type :contract
                                :attribution {:class :protocol-registry :ref "f:g:0xdemoc9"}}}
                       (assoc curator :phase 2))]
      (is (= :interrupted (:status res)))
      (is (= :phase-approval (-> res :state :audit last :reason))))))

(deftest omitting-phase-does-not-grant-max-autonomy
  (testing "the accidental-fail-open shape fixed across the sibling actors"
    (let [[db actor] (fresh)
          res (exec-op actor "ph2"
                       {:op :block/index :subject "block-102" :block block-102
                        :observations two-obs}
                       {:actor-id "i" :actor-role :indexer})]
      (is (not= :commit (get-in res [:state :disposition])))
      (is (nil? (get-in (store/chain-view db) [:blocks 102]))))))

(deftest every-decision-leaves-one-ledger-fact
  (let [[db actor] (fresh)]
    (exec-op actor "L1" {:op :block/index :subject "block-102" :block block-102
                         :observations two-obs} indexer-p3)
    (exec-op actor "L2" {:op :label/assert :subject "0xdemod4" :address "0xdemod4"
                         :guess? true} curator-p3)
    (is (= 2 (count (store/ledger db))) "one commit + one hold, both recorded")
    (is (= #{:commit :hold} (set (map :disposition (store/ledger db)))))))
