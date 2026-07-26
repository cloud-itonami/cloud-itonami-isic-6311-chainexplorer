# cloud-itonami-isic-6311-chainexplorer

Open Business Blueprint for a **read-only chain explorer** — the
Etherscan / Blockstream-info class of business — published as an OSS
business any operator can fork, deploy, run, improve and sell. A
role-suffix satellite of **ISIC Rev.4 6311** (data processing, hosting and
related activities), sibling to
[`cloud-itonami-isic-6311`](https://github.com/cloud-itonami/cloud-itonami-isic-6311)
(market data): that actor collects *prices*, this one observes *chains*.

Built on this workspace's
[`langgraph`](https://github.com/kotoba-lang/langgraph) StateGraph runtime
(portable `.cljc`, supervised superstep loop, interrupts, checkpoints) and
[`kotoba-lang/org-ethereum-jsonrpc`](https://github.com/kotoba-lang/org-ethereum-jsonrpc)
(read-only JSON-RPC with the method whitelist enforced in code) — the same
actor pattern as every sibling in this fleet. Here it is
**ExplorerAdvisor-LLM ⊣ ExplorerGovernor**.

## Why an actor layer at all?

An ExplorerAdvisor-LLM is good at summarizing a transaction and drafting
address labels. But it has **no notion of finality, of what makes an
attribution re-checkable, of the difference between a company and a
person, or of what a reorg means for something already published.** Point a
language model at chain data and ask "who owns this address?" and you will
get a confident, plausible, unfalsifiable answer, every time.

That is not a hypothetical adversary input. It is the default behaviour,
and it is also — heuristics instead of an LLM — how most public explorers
actually label addresses today.

So the advisor is sealed into a single node and wrapped in an independent
**ExplorerGovernor**, a human review workflow, and an immutable **audit
ledger**.

## The four things this explorer does differently

### 1. It never links an address to a person

`:identity-gate` is a HARD refusal with **no approval path at all** — not
"needs sign-off", not "high tier only". A proposal carrying any identity
field (name, email, phone, IP, device id, KYC record, `:subject-type
:natural-person`) is rejected even when it cites a perfect source, and the
store schema has nowhere to put one if the gate were bypassed.

Attributing an address to a *company* (an exchange, a protocol) is in
scope and normal. Attributing it to a *human being* is out of scope
permanently. This is the one gate protecting someone who is not the
operator's customer and will never know this software ran.

### 2. Labels must be re-checkable, or they do not exist

An address label must cite one of five classes from a **closed catalog**
(`explorer.facts`), each with a non-blank ref:

| class | re-checkable by |
|---|---|
| `:onchain-self-attestation` | read the same record from any node at the cited block |
| `:official-published-address-list` | fetch the cited publication and find the address in it |
| `:sanctions-list-publication` | look the cited entry up in the authority's own list |
| `:verified-contract-source` | recompile the cited source, byte-compare with on-chain code |
| `:protocol-registry` | make the same read-only call against any node |

**Deliberately absent:** heuristic clustering, common-input-ownership,
chain-analytics risk scores, social reports, LLM inference. Because
`allowed-classes` is a closed set derived from the catalog, a label citing
any of them has nothing truthful to cite and is structurally unpublishable
here — enforced by tests, not by convention.

### 3. Finality is a property of every fact, not a badge next to it

`explorer.chain/finality-class` returns `:pending` → `:probabilistic` →
`:confirmed` → `:final`, and the governor refuses to disclose a fact at a
stronger finality than it actually has. Serving a 1-confirmation
transaction with a green checkmark is *the* defining explorer failure.

Ethereum post-Merge has real economic finality, so this does not guess from
a magic confirmation depth: the chain's **own `finalized` block tag** is
authoritative. Without one, `:final` is unreachable — a depth heuristic
never gets to masquerade as finality.

### 4. One node is one party's claim about the chain

`:corroboration-gate` requires ≥2 **independent** endpoints to agree on a
block hash before it is indexed. An explorer reading a single RPC provider
is republishing that provider's view under its own name, and its readers
cannot tell. When endpoints disagree, this actor **publishes nothing and
escalates** — it does not take the majority hash, because a split is either
a live reorg or a wrong node, and both deserve a human.

Likewise a reorg never silently rewrites the index: `apply-block` returns
the chain **unchanged** and classifies the conflict, and `:reorg/resolve`
is never auto-eligible at any phase.

## Run

```bash
clojure -M:dev:test    # governor contract · chain semantics · catalog honesty
clojure -M:dev:run     # offline 9-operation demo through one OperationActor
clojure -M:lint

# live, against REAL nodes (>= 2 independent endpoints required — there is
# no single-endpoint fallback, by design)
ETH_RPC_URLS=https://node-a,https://node-b clojure -M:rpc:dev:run-rpc
```

A real run (2026-07-26, two independent public endpoints):

```
── head / finality context ──
   head: 25617344   finalized: 25617272
   finality of head: probabilistic
   finality of finalized head: final

── multi-endpoint observation of the same block ──
   https://ethereum-rpc.publicnode.com -> 0xef9de7124c29057210dcf2d655d500a66eab6a35f71a58e7eeb5cfd767c634f5
   https://eth.drpc.org                -> 0xef9de7124c29057210dcf2d655d500a66eab6a35f71a58e7eeb5cfd767c634f5
   corroboration: {:ok? true, :agree 2, :endpoints 2, :disagreement []}

── governed index ──
  index-corroborated   -> commit
  index-single-source  -> hold  [corroboration-gate]
       独立ノードの裏取りが不足: agree=1 endpoints=1 required=2
```

## Scope (read this before anything else)

This actor **observes and serves public chain data**. It never constructs,
signs or broadcasts a transaction, never handles a key, and has no wallet
functionality — the JSON-RPC method whitelist in
`kotoba-lang/org-ethereum-jsonrpc` enforces that in code, not just in this
paragraph. It stores no personal data: there is no field anywhere in the
schema for a person, a KYC record, an IP address or a device id.

It is also **not** a risk-scoring or compliance-screening service. It will
record that a sanctions authority published an address, citing the entry —
and even that reaches a human before publication.

## Layout

| Namespace | File | Shape |
|---|---|---|
| `explorer.facts` | `facts.cljc` | attribution catalog (closed), identity fields, allegation kinds |
| `explorer.chain` | `chain.cljc` | finality classification, reorg detection, multi-endpoint corroboration — pure, no clock |
| `explorer.policy` | `policy.cljc` | the ExplorerGovernor: 7 checks, 5 HARD |
| `explorer.phase` | `phase.cljc` | 0→3 rollout; labels and reorgs are in no phase's auto set |
| `explorer.llm` | `llm.cljc` | the sealed advisor (+ deterministic mock that emits the real failure modes) |
| `explorer.operation` | `operation.cljc` | the StateGraph: intake → advise → govern → decide → commit/hold/approval |
| `explorer.store` | `store.cljc` | SSoT seam + append-only ledger |
| `explorer.rpc` | `rpc.clj` | multi-endpoint read-only transport (`:rpc` alias only) |

`explorer.rpc` is deliberately the only namespace depending on
`org-ethereum-jsonrpc`, declared in the `:rpc` alias only: nothing under
`test/` requires it, so `clojure -M:dev:test` stays offline and CI needs no
extra checkout. Everything worth testing about chain semantics is pure and
lives in `explorer.chain`.

## Open business

| Layer | What is open |
|---|---|
| OSS core | Actor runtime, ExplorerGovernor, governed disclosure, audit ledger |
| Business blueprint | Customer, offer, pricing, unit economics |
| Operator playbook | How to fork, deploy, support and sell |
| Trust controls | Governance, security reporting, policy tests |

## Non-Negotiables

- Do not add a class that would let a heuristic or a vendor score be cited.
- Do not add an approval path to the identity gate.
- Do not add a single-endpoint fallback for indexing.
- Do not let `:final` be reachable without the chain's own finalized head.
- Do not add wallet, signing or broadcast functionality.
- Do not bypass the ExplorerGovernor for production indexing or disclosure.

License: AGPL-3.0-or-later.
