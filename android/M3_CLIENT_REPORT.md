# M3 CLIENT REPORT — payment flows R1–R6 (2026-11-02)

**Verdict: BUILT, CI GREEN.** Commit `522d4ab` (flows) + `3456394`
(one-line exhaustiveness fix). CI `android-native.yml` run
**41568781673 — completed, success**, including the new
`:app:testDebugUnitTest` step (49s, BUILD SUCCESSFUL) and
`assembleDebug`. First run `41568676027` failed compilation on a
single `when`-exhaustiveness error in the composer; fixed forward
with one added branch, noted here per instructions.

**Test count: 33 unit tests, all passing in CI — 16 new in M3**
(`PaymentTxTest` 6, `MoneyTest` 10) plus the 17 pre-existing, which
CI executes for the first time (see "CI change" below).

The client targets the host through the existing `HOST_URL` build
config; the production default is unchanged.

## The money discipline (once, for every surface)

- **The quote is the host's session/checkout.** Amount, asset,
  destination, reference and pay window are read from the served
  response — the client carries no price table. Displayed USD prices
  are the served `usdCents` (the price of record as served).
- **Paid exactly once.** The signature is parked (SessionStore's
  parked signatures + the new PaymentStore's parked flows) the
  moment it exists, BEFORE the first completion call. Two guards sit
  at the payment choke point: a session with a parked signature is
  never paid twice (the completion loop takes over), and a session
  whose pay window already closed is never paid at all.
- **Completion rides the pending loop** (~4s × 15) on the host's own
  pending answers only — the wrapper's "No finalized payment found…"
  family and the bundle's "not found at finalized commitment" (R3).
  Refusals are terminal and surface **verbatim**; expiry ends the
  flow and the next attempt re-quotes fresh (R6).
- **Halt-before-signing** wherever the host does not serve a field a
  verifiable transfer needs (two cases below): the surface names the
  gap and nothing is signed — a transfer the host cannot verify is
  never built.

## Flows per surface

**R1 — Join gate.** Sign-in state is now answered by the host's own
member record on the live `listForum` read (new VM state:
`hostMember` / `membershipChecked`), not by the locally restored
display identity. A signed-in wallet with no record gets the join
offer on the Sign-In screen → JoinScreen: `createJoinSession` quote
(**5,000 SKR**, served by the session; SKR is the price of record —
the host serves no dollar rate for the join, so none is shown, see
gaps) → one SKR transfer to the Relay with the session reference →
`completeJoinSession` by reference through the pending loop →
membership active (forum reload shows the member record). Existing
members are refused at quote time by the host and never see a
charge. Forward-apply answers (`{ok:false, forwardApplied:true}`)
surface in the host's own words.

**R2 — Thread fee (composer).** Continue calls `createPostSession`
first: an **exempt** answer (prestige holder, founder audience)
posts session-free with the host's note shown — prestige holders see
the standing line "Prestige — no thread fee" before Continue and the
exemption confirmed after. Otherwise the quote card shows the served
USD price, the exact asset amount (**SKR / ORE / SOL / USDC**
picker; ORE rendered in ORE **and** grams), Relay destination, and
pay-by time → pay → `createThread` with `postSessionId` through the
pending loop. Editing the draft/board/kind after a quote discards
the quote (fresh re-quote, never silent re-price). **Commercial**:
the toggle takes `createCommercialSession` ($50 flat, served) instead
of the thread fee, posts with `commercialSessionId` (never both
ids), labeled commercial. **VIP board**: never takes a session —
holders post session-free (the gate passes them before session
logic), non-holders are refused before any session exists; when the
holder flag is unserved the composer attempts session-free and lets
the gate decide rather than charging on a guess. The composer also
parses the M2 host fixes' new served fields (`Category.isVip`,
payload `callerPrestige`).

**Replies.** First attempt is session-free (the gate passes
VIP-holder / founder / exempt cases by its own reads — paying first
would strand a session). Only the gate's own paid answer ("start a
payment session first") opens the paid path: a `kind:"reply"`
session in the thread's served audience, paid once, dispatched
through the pending loop. The thread screen offers the payment asset
picker up front so the paid path never interrupts mid-compose.
Note: the staged host charges reply sessions today; the final ruling
is free member replies. This flow follows the host's answers either
way — a host that stops charging needs no client change (the
session-free first attempt and the exempt branch are already the
code path).

**Tips (double opt-in).** Entry points ride the host's own flags
(`canTip` on the thread and each reply) → TipScreen. Compose is
USD-denominated (the bundle checkout's own contract), asset
**SOL | ORE** (the bundle's assetSchema — the picker offers nothing
wider). Client-side ceiling **$500** and floor **$5** refuse before
any round-trip (see gaps for the config note). `createTipCheckout`
returns the checkout + the wrapper's injected fee leg; the **review
sheet** shows recipient .skr name (served), tip in the asset (ORE
also in grams), dollar value, the $1 fee as its own line, and the
**total debit**. The confirm arms only when (a) the fee leg is fully
served — amount AND its own reference — and (b) the sheet total
equals the checkout's stored total (`tipConfirmArmed`, unit-pinned).
One transaction then carries both legs, each with its own reference
(A7) → `completeTipCheckout` with the signature. An outstanding-fee
refusal surfaces verbatim; the `payOutstandingTipFee` settle (fee
leg alone) is built and offered in exactly the state it settles,
when the leg's reference is served. After any attempt the checkout
cannot be re-confirmed from the sheet — completion continues from
the parked record (never a second payment).

**Pins.** The spotlight now renders from the **`getPinSlots`** read
(cap / filled / vacant / deferred count; the M2 payload derivation
remains only as a fallback while the read hasn't answered). The
vacancy ad's action opens PinBuyScreen (sign-in first for
non-members): pick an own thread (`canPaidPin`), asset SOL | ORE,
live spot state from the same read, `createPaidPinCheckout` quote
(served USD price, amount, hours, Relay destination) → pay →
`completePaidPinCheckout` with the signature. A `pinDeferred`
completion is a success state shown truthfully ("activates when a
slot frees", with the earliest freeing time the read serves); the
create-time slot-full refusal surfaces verbatim with nothing
charged.

**Prestige.** From the Sign-In account area → PrestigeScreen:
holder state read via `getPrestigeSession`; purchase is the join's
discipline at **10,000 SKR** (`createPrestigeSession` → transfer →
by-reference completion). The cap is shown as "at most 100 prestige
places" **without a claimed count** — the host serves none, and the
client invents none. Sold-out and already-holder refusals surface
verbatim.

**R4 — Resume-after-abandon.** Every flow parks (kind, session/
checkout id, wallet, draft, signature) before its first completion
call. On relaunch the River shows **"Payments to finish"** for the
signed-in wallet's parked flows. Finishing re-reads host state:
wrapper sessions via their `get*Session` reads — `consumed` /
`completed` drop with the truth told, `expired` / `forward_applied`
drop with the forward-apply explained, otherwise the completion
dispatch runs through the pending loop; bundle checkouts (no read
exists) re-run their idempotent completion with the parked
signature. Each record also has a member-controlled Dismiss.
Nothing in resume ever builds a second payment.

**R5 — Disclosures.** One shared composable (`PaymentDisclosures`)
renders the same words on **every** payment surface, above the sign
action: join quote, prestige quote, composer quote (with the
paid-post retry fact added), tip review sheet, pin quote.
Contents: payment is final (all sales final, no refund path); an
expired session cannot be revived by a late transfer and
landed-but-refused amounts are forward-applied to the next paid
action (never pocketed, never cash-refunded); paid actions create a
public, permanent on-chain record (amount, time, wallet).

## Construction proof (unit tests, all in CI)

- `PaymentTxTest` (6): parses compiled transactions back apart and
  asserts — two-leg tip in ORE (exactly 2 transfers + 2 ATA creates;
  both reference keys present as read-only non-signers; tip leg →
  author's ATA, fee leg → Relay ATA; exact amounts; ORE 11dp);
  two-leg tip in SOL (2 system transfers, exact lamports, both
  references); single-leg shapes for join (5,000 SKR), prestige
  (10,000 SKR), SOL thread fee, USDC commercial — one transfer,
  exact amount, reference present, destination = the destination
  wallet's own ATA (or the wallet itself for SOL).
- `MoneyTest` (10): `decimalToBaseUnits` / `baseUnitsToDecimal`
  vectors incl. the bundle's truncation rule and exact round-trips
  of served display amounts (this inversion is what derives a
  checkout's stored base units from its served display string —
  exact by the bundle's own construction, not an estimate);
  display formatting (grouping, ORE grams dual display, USD cents);
  the tip arming check (arms on equality with a fully served fee
  leg; stays disarmed when the fee leg is unserved, when its
  reference is unserved — the staged host's current shape — and on
  any total mismatch).

## Host gaps — named, with the exact fixes

Two flows cannot complete at runtime against the current staging
host because a field the transfer is built from is not served. In
both cases the client is fully built and unit-pinned, parses the
field tolerantly, and **arms automatically the moment the host
serves it**; until then the surface halts before signing with the
gap named on the surface itself. No stub silently pretends
otherwise.

1. **Tip fee leg reference (and destination) not served.** The
   staging wrapper's `createTipCheckout` post-dispatch injection is
   `platformFee {asset, amount, usdCents, note}` — the package's
   §2.3 design carries the leg's `reference` and `destination` too,
   and the wrapper's own completion verifies the fee leg by the
   leg's reference (it reads `tip_fee_legs.reference_pubkey` from
   its DB — no client can). **Fix (wrapper, one object):** add
   `reference: leg.reference_pubkey` and `destination:
   leg.destination` to the injected `platformFee`. Until then: tip
   compose + review sheet render fully from served data; the
   confirm stays disarmed with the reason on the sheet; the
   `payOutstandingTipFee` settle is likewise built but unarmable.
2. **Pin checkout reference not served.** The bundle's
   `createPaidPinCheckout` handler generates the checkout's
   reference, but its response schema doesn't return it, and the
   bundle's completion verifies the transfer by that reference.
   **Fix (bundle, one field):** add `referencePubkey` to the
   `createPaidPinCheckout` response schema and returned object.
   Until then: the pin purchase halts before signing with the gap
   named; slot display, quotes and the deferred-state handling are
   all built.

Further host notes (not blockers; recorded so the flip doesn't
rediscover them):

- **Tip maximum is not served.** `getPublicConfig`'s response schema
  carries no tip maximum, so the client carries the proposed figure
  of record (50,000¢) as a named constant with the situation
  commented (`util/Money.kt` TIP_MAX_USD_CENTS). The wrapper also
  enforces no maximum yet (its schema admits up to 100,000¢) — the
  flip's host leg still owes the enforcement the ruling assigns to
  it; the client's block is the interim client-side half.
- **Join USD equivalent is not served anywhere** (no SKR/USD rate
  on any surface). The tasking asked the join quote to name 5,000
  SKR + its USD equivalent; the client shows SKR as the price of
  record and states plainly that the host quotes no dollar rate —
  it does not print the ≈$65 planning figure as a quote.
- **Prestige claimed-count is not served** (`getPrestigeSession`
  answers the caller's own flag only) → cap shown without a count.
- **Staging thread price default is 25¢ in code**
  (`OK_POST_PRICE_USD_CENTS_THREAD`) vs the ruled $3.50. The client
  displays the served `usdCents` as the price of record; the flip's
  config step sets 350. Not a client defect.
- **Staging charges reply sessions** (above, under Replies).

## CI change (minimal, noted)

`android-native.yml` gains one step — `gradle
:app:testDebugUnitTest` — before the assemble. Reason: this leg's
proof standard is "construction correctness by test + CI green",
and the workflow previously only assembled, which never compiles
the test tree at all. The rot that hid: the pre-existing
`SolanaTxTest` referenced a `com.raaru.orekingdom.data.formatSkr`
that did not exist in the main source set (nothing had ever
compiled it). `formatSkr` is now implemented in the data layer
(delegating to the money formatter) and all 33 tests pass.

## What remains for the M4 gate (honest list)

- **Real-wallet end-to-end against the staging host** (device or
  emulator): sign-and-send of the new shapes through MWA, the
  pending loop against real finality timing, and the SIM-off
  report's named **mainnet dress rehearsal** (two real small
  transfers — one tip, one pin). Construction is test-proven; the
  live round trip is not claimed here. Note the client fetches its
  blockhash from the public mainnet endpoint while the staging host
  verifies against its own RPC — the rehearsal is where that pairing
  is proven.
- **The two host fixes above** (fee-leg reference injection;
  pin `referencePubkey`) — until they land, the tip confirm and pin
  purchase remain in their named halt states by design.
- **Wallet-abandonment proof for the payment sheets** (the
  prove-wallet-abandonment discipline on the new sign-and-send
  calls, incl. abandon → relaunch → resume-from-parked).
- Cold-start enumeration with the payment surfaces present
  (offline / no-wallet / mid-flow process death), the fault-registry
  pass, and listing-truth re-derivation against the new binary —
  the standing pre-submission gate, unchanged.
