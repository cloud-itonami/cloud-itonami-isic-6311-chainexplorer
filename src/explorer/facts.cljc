(ns explorer.facts
  "The attribution catalog — the ONLY bases on which this explorer will
  attach a label to an address, and the closed list of what it will never
  attach at all.

  This is the namespace that decides whether this project is an explorer or
  an accusation machine. A block explorer's chain data (blocks, txs, logs,
  balances) is objective and re-derivable by anyone with a node. Its
  *labels* are not: 'this address belongs to Binance', 'this address is a
  scammer', 'this cluster is one person' are factual claims about people
  and companies, published at scale, that the subject usually cannot
  contest. Getting them wrong is a defamation and safety problem, not a
  data-quality one — and an LLM asked to label an address will produce a
  confident, plausible, unfalsifiable answer every time.

  So attribution here is deliberately narrow. Four classes, all of which
  share one property: **the claim traces to something the subject or an
  official publisher put on the record**, and a reader can re-check it.

    :onchain-self-attestation      the address itself put the claim on
                                   chain (ENS reverse record, a contract's
                                   own name()/symbol(), an EIP-1271 signed
                                   registration)
    :official-published-address-list
                                   the entity itself published the address
                                   (a protocol's deployment registry, an
                                   exchange's published proof-of-reserves
                                   address list)
    :sanctions-list-publication    an official sanctions authority's own
                                   published list, cited by list + entry
    :verified-contract-source      deployed bytecode verified to match
                                   published source, so the contract's
                                   behaviour itself is the evidence
    :protocol-registry             an on-chain canonical registry answers
                                   for it (e.g. a factory contract
                                   confirming an address is one of its own
                                   pools)

  **There is deliberately NO class for:**

    heuristic address clustering / 'common input ownership'
    chain-analytics vendor scoring
    social-media or forum reports
    LLM inference from transaction patterns

  Those are how most public explorers actually label addresses, and every
  one of them is an unfalsifiable assertion about a third party. Because
  `allowed-classes` is a closed set derived from this catalog, a label
  citing any of them has nothing truthful to cite and the ExplorerGovernor
  rejects it — this is structural, not a policy someone can decide to relax
  in a hurry.

  **And no class can ever justify identity attribution.** Linking an
  address to a natural person is not a labelling decision this actor gets
  to make correctly; it is out of scope permanently
  (`identity-claim?`, enforced as a HARD gate with no human override)."
  (:require [kotoba.lang.text :as str]))

(def catalog
  "Each entry: {:id :name :class :evidence :recheckable-by}.
  `:recheckable-by` states, in one line, how a READER re-verifies a label
  of this class without trusting this actor — the property that separates
  these classes from the excluded ones."
  [{:id :onchain-self-attestation
    :name "On-chain self-attestation by the address itself"
    :class :onchain-self-attestation
    :evidence "ENS reverse record / contract name()/symbol() / EIP-1271 signed registration"
    :recheckable-by "read the same record from any node at the cited block"}
   {:id :official-published-address-list
    :name "Address list published by the entity itself"
    :class :official-published-address-list
    :evidence "protocol deployment registry, exchange-published reserve address list"
    :recheckable-by "fetch the cited publication URL and find the address in it"}
   {:id :sanctions-list-publication
    :name "Official sanctions authority publication"
    :class :sanctions-list-publication
    :evidence "OFAC SDN and equivalent national/supranational lists, cited by list id + entry id"
    :recheckable-by "look the cited entry up in the authority's own published list"}
   {:id :verified-contract-source
    :name "Deployed bytecode verified against published source"
    :class :verified-contract-source
    :evidence "compiler version + settings + source that reproduce the deployed bytecode"
    :recheckable-by "recompile the cited source and byte-compare with the on-chain code"}
   {:id :protocol-registry
    :name "On-chain canonical registry answers for the address"
    :class :protocol-registry
    :evidence "a factory/registry contract call that returns this address as one of its own"
    :recheckable-by "make the same read-only call against any node"}])

(def allowed-classes
  "The closed set of attribution classes the governor accepts. A class not
  in `catalog` — :heuristic-clustering, :vendor-risk-score, :social-report,
  :llm-inference — must be rejected, not quietly accepted because it looks
  like a keyword."
  (into #{} (map :class catalog)))

(def excluded-classes
  "Named explicitly so the refusal is documentation, not an oversight, and
  so a test can assert each one stays out. These are the classes a
  conventional explorer *does* use."
  #{:heuristic-clustering :common-input-ownership :vendor-risk-score
    :chain-analytics-score :social-report :forum-report :llm-inference
    :pattern-inference :self-reported-unverified})

(defn class-allowed? [c] (contains? allowed-classes c))

(defn attribution-ok?
  "Is an attribution citation well-formed? Requires an allowed class AND a
  non-blank `:ref` (the coordinates a reader re-checks it by). A label with
  a real class but no ref is not re-checkable, which is the whole point of
  restricting the classes in the first place."
  [{:keys [class ref]}]
  (boolean (and (class-allowed? class)
                (string? ref)
                (not (str/blank? ref)))))

;; ───────────────────────── identity attribution ────────────────────────

(def identity-fields
  "Fields whose presence means the proposal is trying to link an address to
  a natural person. Checked structurally rather than by reading free text:
  a rule that depended on spotting a name inside prose would be a rule that
  fails exactly when it matters."
  #{:person :person-name :legal-name :real-name :individual
    :email :phone :postal-address :national-id :passport :date-of-birth
    :ip-address :device-id :kyc-record :identity-claim})

(defn identity-claim?
  "Does this proposal (or its label value) attempt identity attribution?
  Any `identity-fields` key anywhere in the label value, or a `:subject-type
  :natural-person`, counts.

  Deliberately conservative: entity attribution to a COMPANY (an exchange,
  a protocol) is in scope and normal; attribution to a PERSON is not, and
  the ambiguous middle is treated as a person."
  [label]
  (boolean
   (or (= :natural-person (:subject-type label))
       (some identity-fields (keys (or label {})))
       (some #(and (map? %) (some identity-fields (keys %))) (vals (or label {}))))))

;; ───────────────────────── allegations ─────────────────────────────────

(def allegation-kinds
  "Label kinds that characterize the address's conduct negatively. Even
  when properly sourced (a sanctions listing IS a real, citable fact),
  publishing one is a decision with consequences for a third party, so the
  governor always routes these to a human — never auto-commit, at any
  phase, at any confidence. This mirrors `marketdata.policy`'s
  never-auto-resolve treatment of correction disputes, and
  `cloud-itonami-isic-6312`'s allegation-subject handling."
  #{:sanctioned :illicit :scam :phishing :hack-proceeds :mixer :ransomware
    :fraud :stolen-funds :high-risk})

(defn allegation? [label]
  (contains? allegation-kinds (:kind label)))

(defn coverage
  "Honest, machine-checkable statement of what this catalog does and does
  not support — printed by the operator console so the published claim
  cannot drift from the code."
  []
  {:class-count (count catalog)
   :classes allowed-classes
   :excluded excluded-classes
   :identity-attribution :never
   :note (str "Labels are accepted from " (count catalog) " re-checkable classes only "
              "(self-attestation, entity-published list, sanctions publication, "
              "verified source, on-chain registry). Heuristic clustering, vendor "
              "risk scores, social reports and LLM inference have NO class to cite "
              "and are structurally unpublishable. Linking an address to a natural "
              "person is refused outright, with no human override. Chain data "
              "itself (blocks/txs/logs) is not an attribution and needs no class — "
              "it needs corroboration and a finality class instead.")})
