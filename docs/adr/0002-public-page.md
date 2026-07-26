# ADR-0002: the public page has no backend, and runs the governor's own chain code

**Status**: accepted
**Date**: 2026-07-26
**Superproject record**: ADR-2607262600 (amended)
**Builds on**: ADR-0001 (the actor and its gates)

## Context

ADR-0001 shipped the actor and stated the gap plainly: *"there is no web UI
yet, only the operator console and the governed disclosure surface. A public
explorer front-end is where the finality labelling has to be carried into
the rendering, and that is a separate piece of work."*

That sentence is the whole design brief. An explorer's gates are worthless
if the page in front of a reader renders a 1-confirmation transaction the
same way it renders a finalized one, or shows a block hash one node
asserted as though it were the chain's.

## Decision

### 1. No backend at all

The page is static. The reader's browser reads the reader's own JSON-RPC
endpoints directly. There is no server of ours between the chain and the
reader, so there is nothing of ours to trust — and nothing of ours that
could quietly become the single provider the actor's corroboration gate
exists to reject.

### 2. The browser runs `explorer.chain` — the governor's own namespace

`web/generate.cljs` copies `src/explorer/chain.cljc` next to the UI code
and scittle loads it. Corroboration and finality classification on the page
are not a re-implementation that could drift from the actor's rules; they
are the actor's rules, executing client-side.

`web/verify_page.cljs` asserts the shipped copy is **byte-identical** to
the source, because the moment they diverge the page's central claim
becomes false silently.

### 3. The gates are the UI

- **Finality**: head vs the chain's own `finalized` head are shown with
  their computed classes. When no endpoint serves the `finalized` tag, the
  page says so in red and `:final` becomes unreachable — the same rule as
  the actor, made visible rather than implied.
- **Corroboration**: per-endpoint hashes are listed, then the verdict.
  Below quorum or on disagreement the page **renders no hash at all** and
  explains that it does not take a majority.
- **Quorum below 2 endpoints**: refuses to read, and says why. There is no
  single-endpoint convenience path, on a page whose thesis is that one node
  is one party's claim.
- **Attribution**: the five accepted classes are shown *with their
  re-check instructions*, and the refused classes are listed by name.
- **Identity**: the page has exactly two input fields (`endpoints`,
  `txhash`) and no address-to-person surface, asserted structurally by the
  harness rather than by a keyword scan (the refusal copy itself contains
  words like "KYC").

### 4. UI stack: DADS, as a documented opt-out

Built with `kotoba-lang/jp-go-digital-design-system`, not the kotoba-uiux
default stack. ADR-2607141915 defines DADS as "日本の公共・行政文脈サービス
向けの明示的 opt-out 先" and requires the adopting repo to record its reason.
The reason here: this is a public open-business surface in the
cloud-itonami fleet, sitting next to the sibling `cloud-itonami-isic-6311`
operator console and the fleet catalog, both already migrated to DADS. A
fleet that looks like several unrelated products undercuts the "one
architecture, forkable" claim the catalog makes.

Page CSS is written as EDN (`kotoba-lang/css`), contains no raw hex and no
px font sizes, and every colour is a DADS token — the kotoba-uiux rules
that are stack-independent still apply.

## Verification

- `design-quality` deterministic HIG/WCAG audit: **100.00** (gate ≥95).
- `nbb web/verify_page.cljs`: 14 structural checks, all passing.
- **Live browser run** (local server, real endpoints, 2026-07-26):
  - two independent endpoints → head 25617487 / finalized 25617400, head
    classified `probabilistic` and the finalized head `final`; block
    25617482 agreed by both → corroborated hash shown.
  - one endpoint → the read is refused and **nothing else is rendered**.
- That browser run also **found a real defect**: on a refusal, the previous
  successful corroboration stayed on screen, so a refusal read as
  "corroborated, plus a warning" — precisely the misreading this page
  exists to prevent. Fixed (every action clears prior output first) and
  pinned by two harness checks so it cannot come back.

## Consequences

- (+) A reader can verify every number on the page against their own node,
  and the page tells them how.
- (+) The refusals are visible product surface, not buried policy.
- (−) Client-side RPC means the reader's browser talks to the endpoints
  directly, and those endpoints see the reader's IP. That is a real
  privacy trade against having a backend proxy — but a proxy would make us
  the single provider, and would see every query. The endpoints are
  editable so the reader chooses who sees their reads.
- (−) The default endpoints are two unrelated public providers, and the
  page says outright that **it cannot verify they are independent**. Two
  endpoints behind one upstream would agree without corroborating anything.
- (−) No address/label browsing surface yet: labels live on the governed
  disclosure面 (contracted tiers) and the page currently only explains the
  rules they must satisfy. Block/tx history, log decoding and pagination
  remain unbuilt (ADR-0001).
