(ns explorer.chain-test
  "Finality classification, reorg detection and multi-endpoint
  corroboration — all pure, all offline."
  (:require [clojure.test :refer [deftest is testing]]
            [explorer.chain :as chain]))

;; ───────────────────────── finality ────────────────────────────────────

(deftest confirmations-counts-inclusively-and-never-goes-negative
  (is (= 1 (chain/confirmations 100 100)) "head == block is 1 confirmation")
  (is (= 13 (chain/confirmations 100 112)))
  (is (= 0 (chain/confirmations 100 99))
      "a block the head has not reached has no confirmations, not -1")
  (is (= 0 (chain/confirmations nil 100)))
  (is (= 0 (chain/confirmations 100 nil))))

(deftest finality-class-uses-the-chains-own-finalized-head
  (is (= :final (chain/finality-class 90 100 90)) "at the finalized head")
  (is (= :final (chain/finality-class 50 100 90)) "below the finalized head")
  (is (= :confirmed (chain/finality-class 91 200 90)) ">= 12 confirmations but not finalized")
  (is (= :probabilistic (chain/finality-class 99 100 90)) "1 confirmation")
  (is (= :pending (chain/finality-class 101 100 90)) "head has not reached it"))

(deftest without-a-finalized-head-nothing-is-ever-final
  (testing "a depth heuristic is never allowed to masquerade as finality"
    (is (= :confirmed (chain/finality-class 100 100000 nil))
        "100k confirmations and still not :final without the chain saying so")
    (is (= :probabilistic (chain/finality-class 100 105 nil)))
    (is (not= :final (chain/finality-class 1 999999999 nil)))))

(deftest at-least?-orders-finality-claims
  (is (chain/at-least? :final :confirmed))
  (is (chain/at-least? :confirmed :confirmed))
  (is (not (chain/at-least? :probabilistic :confirmed)))
  (is (not (chain/at-least? :pending :probabilistic)))
  (is (not (chain/at-least? :confirmed :final))
      "confirmed is NOT final, which is the whole point"))

;; ───────────────────────── reorg ───────────────────────────────────────

(def b100 {:number 100 :hash "0xa100" :parent-hash "0xa099"})
(def b101 {:number 101 :hash "0xa101" :parent-hash "0xa100"})
(def b101' {:number 101 :hash "0xB101" :parent-hash "0xa100"})

(defn- chain-with [& blocks]
  (reduce (fn [c b] (:chain (chain/apply-block c b))) (chain/empty-chain) blocks))

(deftest first-block-is-genesis-then-parent-linkage-extends
  (is (= :genesis (:kind (chain/reorg (chain/empty-chain) b100))))
  (is (= :extend (:kind (chain/reorg (chain-with b100) b101)))))

(deftest a-known-height-with-a-different-hash-is-a-reorg
  (let [c (chain-with b100 b101)
        r (chain/reorg c b101')]
    (is (= :reorg (:kind r)))
    (is (= 101 (:at r)))
    (is (= "0xa101" (:ours r)))
    (is (= "0xB101" (:theirs r)))))

(deftest a-parent-hash-that-disagrees-with-our-tip-is-a-reorg
  (let [c (chain-with b100)
        orphan {:number 101 :hash "0xa101" :parent-hash "0xDIFFERENT"}]
    (is (= :reorg (:kind (chain/reorg c orphan))))))

(deftest the-same-block-twice-is-a-duplicate-not-a-reorg
  (is (= :duplicate (:kind (chain/reorg (chain-with b100 b101) b101)))))

(deftest a-block-beyond-our-tip-is-a-gap-not-a-reorg
  (let [r (chain/reorg (chain-with b100) {:number 105 :hash "0xa105" :parent-hash "0xa104"})]
    (is (= :gap (:kind r)))
    (is (= 101 (:from r)))
    (is (= 104 (:to r)))))

(deftest apply-block-never-rewrites-history-on-a-reorg
  (testing "an index operation cannot silently replace what was published"
    (let [c (chain-with b100 b101)
          {:keys [chain effect]} (chain/apply-block c b101')]
      (is (= :reorg (:kind effect)))
      (is (= c chain) "chain returned UNCHANGED")
      (is (= "0xa101" (:hash (chain/block-at chain 101))) "our block still stands"))))

(deftest apply-block-extends-and-tracks-the-head
  (let [c (chain-with b100 b101)]
    (is (= 101 (:head c)))
    (is (= "0xa101" (:hash (chain/head-block c))))))

;; ───────────────────────── corroboration ───────────────────────────────

(defn- obs [ep hash] {:endpoint ep :number 100 :hash hash})

(deftest two-independent-endpoints-agreeing-is-corroboration
  (let [r (chain/corroboration [(obs "a" "0xa100") (obs "b" "0xa100")])]
    (is (:ok? r))
    (is (= "0xa100" (:hash r)))
    (is (= 2 (:agree r)))
    (is (empty? (:disagreement r)))))

(deftest one-endpoint-is-not-corroboration
  (testing "a single node is one party's claim about the chain"
    (let [r (chain/corroboration [(obs "a" "0xa100")])]
      (is (not (:ok? r)))
      (is (nil? (:hash r))))))

(deftest the-same-endpoint-twice-is-still-one-endpoint
  (let [r (chain/corroboration [(obs "a" "0xa100") (obs "a" "0xa100")])]
    (is (not (:ok? r)) "distinct endpoints, not distinct responses")
    (is (= 1 (:agree r)))))

(deftest disagreement-is-reported-not-voted-on
  (testing "2-vs-1 does NOT publish the majority hash"
    (let [r (chain/corroboration [(obs "a" "0xa100") (obs "b" "0xa100") (obs "c" "0xBAD")])]
      (is (not (:ok? r)))
      (is (nil? (:hash r)) "no majority winner is picked")
      (is (= 2 (count (:disagreement r))))
      (is (= #{"0xa100" "0xBAD"} (set (map :hash (:disagreement r))))))))

(deftest corroboration-threshold-is-configurable-upward
  (let [three [(obs "a" "0xa100") (obs "b" "0xa100") (obs "c" "0xa100")]]
    (is (:ok? (chain/corroboration three 3)))
    (is (not (:ok? (chain/corroboration three 4))))))

;; ───────────────────────── views ───────────────────────────────────────

(deftest tx-view-always-carries-its-own-finality
  (let [tx {:hash "0xt" :block-number 99 :from "0xAbC" :to "0xDeF" :value "1"}]
    (testing "two confirmations, above the finalized head -> probabilistic"
      (let [v (chain/tx-view tx {:head-number 100 :finalized-number 90})]
        (is (= :probabilistic (:finality v)))
        (is (= 2 (:confirmations v)))))
    (testing "the SAME tx once the chain finalizes its block -> final"
      (is (= :final (:finality (chain/tx-view tx {:head-number 200 :finalized-number 150})))))
    (testing "addresses are canonicalized so a label attaches to an address, not a spelling"
      (let [v (chain/tx-view tx {:head-number 100 :finalized-number 90})]
        (is (= "0xabc" (:from v)))
        (is (= "0xdef" (:to v))))))
  (is (nil? (chain/tx-view nil {:head-number 100}))))
