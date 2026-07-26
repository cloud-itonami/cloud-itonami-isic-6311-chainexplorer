(ns explorer.chain
  "Canonical-chain tracking, finality classification and reorg detection —
  pure, portable `.cljc`, no I/O and no clock.

  The single fact a block explorer most often gets wrong is presenting a
  recent transaction as if it had happened. It did happen *on the chain the
  node showed you a second ago*; a reorg can remove it. Most explorers
  render a confirmation count in small text next to a large green
  checkmark and leave the reader to work out the difference.

  Here, **finality is a first-class property of every disclosed fact**
  (`finality-class`), the governor refuses to disclose a fact at a stronger
  finality than it actually has (`explorer.policy`'s finality-gate), and a
  reorg is an explicit, always-escalated event rather than a silent
  overwrite (`reorg` / `apply-block`).

  Ethereum post-Merge has real economic finality, so this does not guess
  from a magic confirmation depth when it does not have to: the node's own
  `finalized` block tag is authoritative, and `finality-class` uses it when
  the caller supplies it. The depth-based classes below the finalized head
  are honest labels for 'not final yet', never substitutes for it."
  (:require [clojure.string :as str]))

;; ───────────────────────── finality ────────────────────────────────────

(def confirmed-depth
  "Confirmations at which a not-yet-finalized block is labelled
  `:confirmed` rather than `:probabilistic`. 12 is the long-standing
  convention and is a LABEL, not a safety claim — under this actor's rules
  `:confirmed` still cannot be disclosed as `:final`."
  12)

(defn confirmations
  "How many blocks sit on top of `block-number`, inclusive of itself
  (head == block → 1). Returns 0 for a block the head has not reached
  (a node that is behind, or a block from a different fork)."
  [block-number head-number]
  (if (and (number? block-number) (number? head-number) (>= head-number block-number))
    (inc (- head-number block-number))
    0))

(defn finality-class
  "-> :final | :confirmed | :probabilistic | :pending

  `finalized-number` is the chain's OWN finalized head (Ethereum's
  `finalized` block tag) when the caller has it; nil when the caller could
  not obtain one. A block at or below the finalized head is `:final` —
  reverting it would require destroying a third of the stake, which is a
  categorically different statement from '12 blocks have passed'.

  Without a finalized head this NEVER returns `:final`. Downgrading to a
  depth heuristic and calling the result final is precisely the conflation
  this namespace exists to prevent."
  [block-number head-number finalized-number]
  (let [c (confirmations block-number head-number)]
    (cond
      (and (number? finalized-number) (number? block-number)
           (<= block-number finalized-number))          :final
      (>= c confirmed-depth)                            :confirmed
      (>= c 1)                                          :probabilistic
      :else                                             :pending)))

(def finality-rank
  "Total order on finality classes, so 'at least as strong as' is a
  comparison rather than a pile of conditionals."
  {:pending 0 :probabilistic 1 :confirmed 2 :final 3})

(defn at-least?
  "Is `actual` at least as strong a finality claim as `claimed`?"
  [actual claimed]
  (>= (get finality-rank actual -1) (get finality-rank claimed 99)))

;; ───────────────────────── canonical chain ─────────────────────────────

(defn empty-chain
  "The canonical-chain view this actor maintains: number -> {:number :hash
  :parent-hash}, plus the head number. Deliberately just the header
  linkage — enough to detect a reorg, small enough to keep in the store."
  []
  {:blocks {} :head nil})

(defn block-at [chain n] (get-in chain [:blocks n]))

(defn head-block [{:keys [blocks head]}] (get blocks head))

(defn reorg
  "Classify what `block` means for `chain` WITHOUT mutating anything:

    {:kind :genesis}                      first block this chain has seen
    {:kind :extend}                       parent-hash matches our tip
    {:kind :duplicate}                    we already have this exact block
    {:kind :gap :from .. :to ..}          block is ahead of our tip with a
                                          gap between (we simply have not
                                          indexed the middle yet)
    {:kind :reorg :at n :ours .. :theirs ..}
                                          a block we already have at that
                                          height has a DIFFERENT hash, or
                                          the parent linkage disagrees with
                                          what we recorded — the chain we
                                          published is not the chain the
                                          node now has

  `:reorg` is never resolved here. `explorer.policy` always escalates it to
  a human, because a reorg means facts this actor already disclosed may now
  be false, and deciding what to do about that (re-disclose? notify
  subscribers? withdraw?) is not an inference."
  [chain {:keys [number hash parent-hash] :as block}]
  (let [{:keys [head]} chain
        existing (block-at chain number)]
    (cond
      (nil? block) {:kind :invalid}
      (nil? head) {:kind :genesis}

      (and existing (= (:hash existing) hash)) {:kind :duplicate}

      existing {:kind :reorg :at number :ours (:hash existing) :theirs hash}

      (= number (inc head))
      (let [tip (block-at chain head)]
        (if (= (:hash tip) parent-hash)
          {:kind :extend}
          {:kind :reorg :at head :ours (:hash tip) :theirs parent-hash}))

      (> number (inc head)) {:kind :gap :from (inc head) :to (dec number)}

      :else {:kind :reorg :at number :ours nil :theirs hash})))

(defn apply-block
  "Extend `chain` with `block`. Only `:extend`, `:genesis`, `:gap` and
  `:duplicate` are applied; a `:reorg` returns the chain UNCHANGED with the
  classification attached, so nothing this actor already published is
  silently rewritten by an index operation. Returns
  `{:chain .. :effect <reorg classification>}`."
  [chain block]
  (let [r (reorg chain block)]
    (if (= :reorg (:kind r))
      {:chain chain :effect r}
      {:chain (-> chain
                  (assoc-in [:blocks (:number block)]
                            (select-keys block [:number :hash :parent-hash :timestamp]))
                  (update :head (fnil max 0) (:number block)))
       :effect r})))

;; ───────────────────────── corroboration ───────────────────────────────

(def default-min-endpoints
  "How many INDEPENDENT nodes must agree on a block's hash before this
  actor treats it as canonical. One node is one party's claim about the
  chain — it can be behind, on a minority fork, or lying, and an explorer
  that reads a single RPC provider is republishing that provider's view
  under its own name."
  2)

(defn corroboration
  "`observations` are `{:endpoint .. :number .. :hash ..}` maps from
  independent nodes for the SAME block number. Returns

    {:ok? bool :hash <agreed hash>|nil :agree n :endpoints n
     :disagreement [{:hash .. :endpoints [..]} ..]}

  `:ok?` requires at least `min-endpoints` DISTINCT endpoints reporting the
  same hash and no competing hash reported at all. A split is not resolved
  by majority here: two nodes disagreeing about a block hash is either a
  live reorg or one of them being wrong, and both are things a human should
  see rather than a vote this actor casts silently."
  ([observations] (corroboration observations default-min-endpoints))
  ([observations min-endpoints]
   (let [obs (filterv #(and (:endpoint %) (:hash %)) observations)
         by-hash (reduce (fn [m {:keys [endpoint hash]}]
                           (update m hash (fnil conj #{}) endpoint))
                         {} obs)
         endpoints (count (distinct (map :endpoint obs)))]
     (if (= 1 (count by-hash))
       (let [[hash eps] (first by-hash)]
         {:ok? (>= (count eps) min-endpoints)
          :hash (when (>= (count eps) min-endpoints) hash)
          :agree (count eps) :endpoints endpoints :disagreement []})
       {:ok? false :hash nil
        :agree 0 :endpoints endpoints
        :disagreement (mapv (fn [[h eps]] {:hash h :endpoints (vec (sort eps))})
                            (sort-by key by-hash))}))))

;; ───────────────────────── normalization ───────────────────────────────

(defn normalize-address
  "Lowercase 0x-prefixed form. Explorers routinely index the same address
  under two spellings because EIP-55 checksummed and lowercase forms are
  both common; one canonical form in the store avoids labels attaching to
  a spelling instead of an address."
  [a]
  (when (string? a) (str/lower-case a)))

(defn tx-view
  "A decoded transaction + its context -> the disclosure-shaped view this
  actor serves. `:finality` is computed, never asserted by a caller, and
  travels WITH the data — there is no shape of this map that carries a
  transaction without saying how final it is."
  [tx {:keys [head-number finalized-number]}]
  (when tx
    (let [bn (:block-number tx)]
      {:hash (:hash tx)
       :block-number bn
       :from (normalize-address (:from tx))
       :to (normalize-address (:to tx))
       :value (:value tx)
       :confirmations (confirmations bn head-number)
       :finality (finality-class bn head-number finalized-number)})))
