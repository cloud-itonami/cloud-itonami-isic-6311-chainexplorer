(ns explorer.store
  "SSoT seam + append-only audit ledger, same shape as the sibling actors
  (`cloud-itonami-isic-6311`'s `marketdata.store`): a `Store` protocol with
  an in-memory reference implementation, so the actor graph runs offline
  and a production operator swaps in a Datomic/kotobase-backed store
  without touching the governor or the advisor.

  What this actor stores is deliberately small:

    :chain        the canonical header linkage (explorer.chain), NOT a full
                  copy of the chain — this is an explorer's index, and the
                  chain itself remains the authority
    :txs          observed transactions by hash
    :labels       address -> attribution label (with its citation)
    :subscribers  tenant -> contract/tier for governed disclosure
    :ledger       append-only decision facts

  Note what is absent: there is no field anywhere for a person, a KYC
  record, an IP address or a device id. `explorer.facts/identity-fields`
  rejects them at the gate, and the schema gives them nowhere to live even
  if the gate were bypassed — the same belt-and-braces the sibling
  cryptoexchange applies to order-routing fields it refuses to have."
  (:require [clojure.string :as str]
            [explorer.chain :as chain]))

(defprotocol Store
  (chain-view [s] "the canonical chain map (explorer.chain)")
  (tx [s hash] "an observed transaction by hash")
  (label [s address] "the attribution label attached to an address, if any")
  (all-labels [s])
  (subscriber [s tenant])
  (ledger [s])
  (commit-record! [s record] "apply a committed op's effect to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (with-chain [s c])
  (with-txs [s txs])
  (with-labels [s ls])
  (with-subscribers [s subs]))

;; ───────────────────────── demo data ───────────────────────────────────

(defn demo-data
  "A tiny fictitious dataset so the actor and its tests run offline.

  The addresses below are DEMO strings, not real mainnet addresses, and
  carry no claim about anyone: the point of the fixtures is to exercise the
  governor's gates (a well-sourced label, an unsourced one, an allegation,
  an identity attempt), not to assert anything about a real party. The one
  real-shaped thing here is the chain linkage, because reorg detection is
  only meaningful on well-formed parent hashes."
  []
  {:chain {:blocks {100 {:number 100 :hash "0xaaa100" :parent-hash "0xaaa099" :timestamp 1785000000}
                    101 {:number 101 :hash "0xaaa101" :parent-hash "0xaaa100" :timestamp 1785000012}}
           :head 101}
   :txs {"0xtx1" {:hash "0xtx1" :block-number 100
                  :from "0xdemo0000000000000000000000000000000000a1"
                  :to   "0xdemo0000000000000000000000000000000000b2"
                  :value "1000000000000000000"}}
   :labels {"0xdemo0000000000000000000000000000000000b2"
            {:address "0xdemo0000000000000000000000000000000000b2"
             :kind :protocol-contract
             :name "Demo Router (fictitious)"
             :subject-type :contract
             :attribution {:class :verified-contract-source
                           :ref "demo-verifier:0xdemo...b2:solc-0.8.26"}}}
   :subscribers {"tenant-basic" {:tenant "tenant-basic" :tier :tier/basic :active? true}
                 "tenant-pro"   {:tenant "tenant-pro"   :tier :tier/pro   :active? true}}})

;; ───────────────────────── MemStore ────────────────────────────────────

(defrecord MemStore [a]
  Store
  (chain-view [_] (:chain @a))
  (tx [_ h] (get-in @a [:txs h]))
  (label [_ addr] (get-in @a [:labels (chain/normalize-address addr)]))
  (all-labels [_] (sort-by :address (vals (:labels @a))))
  (subscriber [_ t] (get-in @a [:subscribers t]))
  (ledger [_] (:ledger @a))
  (commit-record! [s {:keys [effect value]}]
    (case effect
      :chain-extend (swap! a assoc :chain (:chain value))
      :tx-index     (swap! a assoc-in [:txs (:hash value)] value)
      :label-assert (swap! a assoc-in [:labels (chain/normalize-address (:address value))] value)
      nil)
    s)
  (append-ledger! [_ f] (swap! a update :ledger conj f) f)
  (with-chain [s c] (when c (swap! a assoc :chain c)) s)
  (with-txs [s txs] (when (seq txs) (swap! a assoc :txs txs)) s)
  (with-labels [s ls] (when (seq ls) (swap! a assoc :labels ls)) s)
  (with-subscribers [s subs] (when (seq subs) (swap! a assoc :subscribers subs)) s))

(defn seed-db
  "A MemStore seeded with the demo data. The deterministic default."
  []
  (->MemStore (atom (assoc (demo-data) :ledger []))))

(defn empty-db
  "A MemStore with an empty canonical chain — the shape a fresh operator
  starts from, and the one the reorg/corroboration tests use."
  []
  (->MemStore (atom {:chain (chain/empty-chain) :txs {} :labels {}
                     :subscribers (:subscribers (demo-data)) :ledger []})))

(defn ledger-line
  "One-line rendering of a ledger fact, for the operator console and the
  demo. Kept here so console and CLI cannot drift apart."
  [{:keys [t op subject disposition basis]}]
  (str (name (or t :fact)) " " (some-> op name) " " subject
       " -> " (some-> disposition name)
       (when (seq basis) (str " [" (str/join "," (map name basis)) "]"))))
