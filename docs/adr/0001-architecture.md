# ADR-0001: a chain explorer whose hard gates are about the people it describes

**Status**: accepted
**Date**: 2026-07-26
**Superproject record**: ADR-2607262600 in `com-junkawasaki/root`

## Context

The superproject audit that produced ADR-2607262100 (direct-venue crypto
price collection) answered three questions. Two of them — "is there
anything collecting crypto prices?" and "what about stocks/FX/commodities?"
— had partial answers. The third, "is there an explorer?", had none: this
workspace had read-only chain *libraries*
(`kotoba-lang/org-ethereum-jsonrpc`, `org-bitcoin-p2p`,
`kotoba-lang/treasury`) but nothing that operates as an explorer. It was a
consumer of explorer APIs, never a publisher.

The obvious build is "index blocks, serve a web UI". That build is also
where explorers do their real damage, and the damage has nothing to do with
uptime:

1. **Attribution without evidence.** The valuable part of an explorer is
   the labels — "Binance hot wallet", "known scammer", "this cluster is one
   entity". Almost all of it is produced by clustering heuristics or bought
   from analytics vendors, published at scale about parties who cannot
   contest it, and treated by readers as fact.
2. **De-anonymization.** Linking addresses to people is the most
   commercially valuable and most harmful thing an explorer can do.
3. **Finality theatre.** A one-confirmation transaction rendered with a
   green check has been the proximate cause of a long list of losses.
4. **Single-provider truth.** An explorer reading one RPC provider is
   republishing that provider's view of the chain under its own brand.

An LLM in this loop makes 1 and 2 worse, not better: asked "who owns this
address?", it produces a confident, plausible, unfalsifiable answer every
time. That is the default behaviour, not an adversarial edge case.

## Decision

Build the explorer as a governed actor in the fleet pattern
(ExplorerAdvisor-LLM ⊣ ExplorerGovernor, langgraph StateGraph, append-only
ledger), with the four failures above as HARD gates rather than
documentation.

### 1. `:identity-gate` — no natural-person attribution, no override

Structural detection (`explorer.facts/identity-fields`, `:subject-type
:natural-person`, nested one level), refused as a HARD violation with **no
approval path**. Not "requires sign-off", not "institutional tier only".
The store schema also has no field for a person, a KYC record, an IP or a
device id, so a bypassed gate still has nowhere to write.

Company/protocol attribution stays in scope; the ambiguous middle is
treated as a person.

Structural rather than textual on purpose: a rule that depended on
spotting a name inside free prose would fail exactly when it matters.

### 2. `:attribution-gate` — five re-checkable classes, closed set

`explorer.facts/catalog` admits only classes where a READER can
independently verify the claim (`:recheckable-by` is a required field of
every catalog entry): on-chain self-attestation, an entity-published
address list, a sanctions authority's own publication, verified contract
source, an on-chain registry answer. A citation also needs a non-blank
`:ref` — a real class with no coordinates is not re-checkable, which
defeats the purpose of restricting classes at all.

`excluded-classes` names what is refused (heuristic clustering,
common-input-ownership, vendor risk scores, social reports, LLM inference)
so the refusal is documentation with a test behind it, rather than an
omission someone later "fixes".

### 3. `:finality-gate` — finality travels with the fact

`explorer.chain/finality-class` classifies `:pending` → `:probabilistic` →
`:confirmed` → `:final` using **the chain's own `finalized` tag** when
available. Without it, `:final` is unreachable; a depth heuristic never
gets to stand in for economic finality. Every `tx-view` carries its
finality — there is no shape of that map that transports a transaction
without saying how final it is — and the governor rejects a disclosure
claiming more than the data supports.

### 4. `:corroboration-gate` — ≥2 independent endpoints, and no voting

`explorer.chain/corroboration` requires N distinct endpoints reporting the
same block hash **and no competing hash at all**. A 2-vs-1 split does not
publish the majority: a disagreement is either a live reorg or a wrong
node, and both are things a human should see rather than a vote this actor
casts silently. The governor re-runs the computation itself rather than
trusting a caller-supplied `:ok?`.

### 5. Reorgs never rewrite silently

`apply-block` returns the chain **unchanged** on a reorg, with the conflict
classified. `:reorg/resolve` is a separate op that is never auto-eligible
at any phase — a reorg means facts already disclosed may now be false, and
deciding what to do about that is not an inference.

### 6. Allegations and labels always reach a human

Any negative characterization (`:sanctioned`, `:scam`, `:hack-proceeds`,
…) always escalates, at any phase, at any confidence, **even when
perfectly sourced** — a sanctions listing is a real fact, and publishing it
about a party at scale is still a decision. More broadly, `:label/assert`
is absent from every phase's `:auto` set: attaching a name to someone
else's address is never unattended.

### 7. Read-only, enforced upstream

All chain access goes through `kotoba-lang/org-ethereum-jsonrpc`, whose
`eth-method-whitelist` throws on any non-read method. No transaction is
constructed, signed or broadcast; no key is handled. `explorer.rpc` is the
only namespace depending on it, declared in the `:rpc` alias only, so the
test path stays offline.

## Consequences

- (+) `clojure -M:dev:test`: 51 tests / 193 assertions, 0 failures.
  `clojure -M:lint`: 0 errors, 0 warnings.
- (+) Verified live 2026-07-26 against two independent public endpoints:
  they agreed on block 25617339 → committed; the same block from a single
  endpoint → held by the corroboration gate. Real finalized head 25617272
  against head 25617344 classified `:final` / `:probabilistic`.
- (+) The offline demo reaches every disposition, including three HARD
  holds the mock advisor triggers at 0.92–0.93 confidence — proving the
  hard gates do not consult confidence.
- (−) **The labels a commercial explorer sells are the ones this refuses to
  produce.** Heuristic clustering and vendor risk scores are most of the
  perceived value of Etherscan-class products. An operator wanting that
  business cannot get it from this blueprint without adding a class, which
  is exactly the change this design makes visible and testable.
- (−) Ethereum only, and only the header linkage + txs an operator indexes.
  No log/event indexing, no token-transfer view, no address-history
  pagination — all buildable on `explorer.chain`, none built here.
- (−) There is no web UI yet, only the operator console and the governed
  disclosure surface. A public explorer front-end is where the finality
  labelling has to be carried into the rendering, and that is a separate
  piece of work.
- (−) Corroboration is per-block-hash. Two endpoints backed by the same
  upstream provider would agree without being independent; endpoint
  independence is an operator responsibility this actor cannot verify.
