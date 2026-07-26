(ns explorer.facts-test
  "The attribution catalog is the whole ground truth for what this explorer
  will and will not say about an address. These tests guard its honesty:
  every advertised class is backed by a real, re-checkable evidence
  description, and every class a conventional explorer relies on stays
  OUT."
  (:require [clojure.test :refer [deftest is testing]]
            [explorer.facts :as facts]))

(deftest catalog-entries-are-well-formed-and-recheckable
  (doseq [{:keys [id name class evidence recheckable-by]} facts/catalog]
    (testing (str id)
      (is (keyword? id))
      (is (string? name))
      (is (keyword? class))
      (is (string? evidence))
      (is (and (string? recheckable-by) (seq recheckable-by))
          "every class must state how a READER re-verifies it without trusting us"))))

(deftest allowed-classes-matches-catalog
  (is (= (into #{} (map :class facts/catalog)) facts/allowed-classes)))

(deftest the-classes-a-conventional-explorer-uses-are-all-excluded
  (testing "this is the design, not an oversight — each is unfalsifiable about a third party"
    (doseq [c facts/excluded-classes]
      (is (not (facts/class-allowed? c)) (str c " must have no class to cite"))))
  (is (not (facts/class-allowed? :heuristic-clustering)))
  (is (not (facts/class-allowed? :vendor-risk-score)))
  (is (not (facts/class-allowed? :llm-inference)))
  (is (not (facts/class-allowed? nil))))

(deftest an-attribution-needs-a-class-AND-a-recheckable-ref
  (is (facts/attribution-ok? {:class :verified-contract-source :ref "verifier:0xabc:solc-0.8.26"}))
  (testing "a real class with no ref is not re-checkable, which defeats the point"
    (is (not (facts/attribution-ok? {:class :verified-contract-source})))
    (is (not (facts/attribution-ok? {:class :verified-contract-source :ref "   "}))))
  (is (not (facts/attribution-ok? {:class :heuristic-clustering :ref "cluster-42"})))
  (is (not (facts/attribution-ok? nil))))

;; ───────────────────────── identity ────────────────────────────────────

(deftest identity-attribution-is-detected-structurally
  (testing "by subject type"
    (is (facts/identity-claim? {:subject-type :natural-person})))
  (testing "by any identity field, at the top level"
    (doseq [f facts/identity-fields]
      (is (facts/identity-claim? {f "x"}) (str f " must be caught"))))
  (testing "by an identity field nested one level down"
    (is (facts/identity-claim? {:kind :owner :details {:email "a@b.c"}})))
  (testing "attribution to a COMPANY is in scope and normal"
    (is (not (facts/identity-claim? {:subject-type :entity :name "Some Exchange Ltd"})))
    (is (not (facts/identity-claim? {:subject-type :contract :name "Router"})))
    (is (not (facts/identity-claim? nil)))))

;; ───────────────────────── allegations ─────────────────────────────────

(deftest allegation-kinds-are-recognized
  (is (facts/allegation? {:kind :sanctioned}))
  (is (facts/allegation? {:kind :scam}))
  (is (facts/allegation? {:kind :hack-proceeds}))
  (is (not (facts/allegation? {:kind :protocol-contract})))
  (is (not (facts/allegation? {:kind :exchange-deposit})))
  (is (not (facts/allegation? nil))))

(deftest coverage-states-the-refusals-not-just-the-capabilities
  (let [c (facts/coverage)]
    (is (= (count facts/catalog) (:class-count c)))
    (is (= :never (:identity-attribution c)))
    (is (seq (:excluded c)))
    (is (re-find #"heuristic|Heuristic" (:note c))
        "the note must name what is refused, so nobody reads it as full coverage")))
