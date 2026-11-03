# Ore Kingdom native client — M2 report (nav core set R7–R10, pin rotation, VIP)

Built 2026-11-02 on top of the M1 scaffold, per
`NATIVE_CLIENT_PLAN.md` M2, the R7–R10 section of
`V014_BUILD_PACKAGE.md`, and the contract in
`host/staging-v014/M2_HOST_REPORT.md`. Commit `fe8d64d` on main.

## CI

- Workflow **Android (native client)**, run **41565408561** —
  **success** on the first push, zero code fixes. Job "Build
  native debug APK" green incl. artifact upload
  (`ore-kingdom-native-debug-apk`).
- (This report's own commit re-triggers the path-filtered
  workflow; that run is a docs-only rebuild of the same tree.)

## Screens / surfaces built

- **The River (R7)** — `ThreadListScreen` reworked: one feed in
  the host's last-activity order (the ordering is the host's —
  wrapper reply-bump; the client renders it, never re-sorts).
  Boards are filter views over the single full payload:
  **All / Commercial / per-board chips**. To make that coherent
  the client no longer asks the host for category-filtered
  payloads (M1 did); the full payload is the one read, so the
  pin strip and every view derive from the same data.
- **Pin spotlight (pin spec)** — above the river, one spotlight
  position; the slot list always carries `PIN_SLOT_COUNT` = 2
  entries (the ruled `pin_slot_cap`), rotating every 6 s with
  equal exposure. Filled slot = the pinned thread (tap opens
  it) with its end date. Vacant slot = the house ad (spot +
  **$250 / 12 hours** + "Take this spot"), whose action opens
  the **pin explainer dialog** — spot, price of record,
  rotation, deferred start when both spots are taken, and the
  plain statement that buying arrives with the payments (M3)
  build. Never an empty frame, never a dead button.
- **Unanswered Rail (R8)** — horizontal rail above the river on
  the All view, fed by wrapper-native `listUnanswered`
  (server-computed zero-reply threads, longest-waiting first;
  the client never derives "unanswered" from a partial page).
  Cards show title, author, waiting-since date, "Be the first
  reply"; tap opens the thread.
- **Catch-Up (R9)** — on open with a signed-in member the
  client calls SIWS-gated `getCatchUp`. If `hasNews`, the
  Catch-Up screen is presented automatically, exactly once per
  open-with-news; if nothing is new, no screen — the member
  lands on the River (package shape). Sections: new threads,
  replies to mine, tips received (amount shown in base units
  as served — see mismatch 3), pin changes (activated /
  expired — the two event kinds the host honestly derives;
  no fabricated "rotated" event). **Watermark discipline per
  the host contract**: the read never moves the watermark;
  the screen calls `advanceCatchUp` with the read's `through`
  after first display (LaunchedEffect on `through`), guarded
  in the view model so it fires exactly once per read. Items
  landing during display are after `through` and remain news
  next open.
- **Inbox (R10)** — `InboxScreen` on bundle
  `listNotifications` / `markNotificationsRead` (the M1
  HostApi wrappers): server-held read state, unread styling,
  per-type labels (reply / tip / mention / poll / milestone),
  "Mark all read", tap an entry → marks that entry read
  server-side and follows `threadId` when it names one. The
  River's top bar carries an **Inbox (n)** unread badge for
  signed-in members, refreshed with the river.
- **VIP board** — the category whose slug is `vip`
  (`OK_VIP_BOARD_SLUG` default): the board chip renders 🔒;
  selecting the board with no VIP threads in the payload
  renders the **locked card** (board named, content withheld,
  "Verify with sign-in proof" for signed-in members, "Sign in"
  otherwise) — matching the host's transform, which lists the
  category but withholds its threads from anyone but a
  SIWS-verified holder. For holders, reads carry the cached
  SIWS proof (`siwsIdentity` on `listForum`/`getThread`), the
  board lists and opens normally, and VIP threads also appear
  in their All view as the host serves them. A VIP thread
  opened without holder proof surfaces the host's own refusal
  ("This board is for prestige holders.") verbatim, via the M1
  thread-refusal path.
- **Composer (minimal, M2 scope)** — `ComposerScreen` (board
  chip-picker, title, body) for new threads, and an inline
  reply composer on `ThreadScreen` (hidden on locked threads).
  Both call the bundle actions through the wrapper's B2 gate
  with the member's SIWS proof; **no payment session is
  attached — that wiring is M3**. Consequences, by design and
  surfaced truthfully: a prestige holder's thread passes the
  gate session-free (the wrapper reads the tier status
  server-side); a VIP-board post passes only for a holder; any
  other refusal (including the standing thread fee) returns as
  the host's verbatim error in the composer. No fake success
  anywhere.
- **Founding Member mark** — renders from payload data only:
  `authorIsFoundingMember` on thread/reply rows and the thread
  header draw the "✦ FOUNDING MEMBER" label. **Not faked**:
  the per-author founding *number* is not in the thread
  payloads (only on the member record), so no number is shown
  on rows; the member's own number rides the member record as
  in M1. Display only — carries no permissions, per the
  founder-kill ruling.

## Identity handling (B2)

Sign-in now also **caches the SIWS proof in memory** (message,
signature, public key — process memory only, never persisted;
SessionStore still holds no signature material). Gated calls
reuse it; a restored session has none, so the first gated
action re-makes it (wallet connect + sign) via the same
`makeProof` path sign-in uses, and a proof whose wallet differs
from the signed-in member is refused client-side before any
host call. One honest consequence: on a cold open with a
restored session, the Catch-Up check may prompt a wallet
signature — the host cannot answer "what's new for this
member" without the proof, and skipping silently would drop
R9's on-open guarantee. If the member declines, the river
simply opens without a Catch-Up.

## Contract shapes consumed

- `listForum {wallet?, siwsIdentity?}` →
  `{locked, member, categories[], threads[]}` (bundle shape,
  M1 models + `isCommercial` added, parsed tolerantly).
- `listUnanswered {wallet?}` → `{threads[], total}` —
  listForum-shaped summaries, `replies: 0`, ISO `createdAt`.
- `getCatchUp {wallet, siwsIdentity}` → `{watermark, through,
  hasNews, counts, newThreads[], repliesToMine[],
  tipsReceived[], pinChanges[]}` (epoch-ms watermark/through;
  ISO timestamps inside items; tip `amountBaseUnits` a string).
- `advanceCatchUp {wallet, through, siwsIdentity}` →
  `{watermark, advanced}`.
- `listNotifications {wallet}` → `{unread, items[{id, type,
  title, detail, threadId, read, createdAt}]}`;
  `markNotificationsRead {wallet, ids?}` → `{ok}`.
- `createThread {wallet, categoryId, title, body, audience,
  siwsIdentity}` / `createReply {wallet, threadId, body,
  siwsIdentity}` → `{ok, id?, error?}` inside `{data}`; the
  wrapper derives the acting wallet from the proof.
- `siwsIdentity` = `{messageBase64, signatureBase64,
  publicKeyBase64}` exactly as the wrapper's
  `requireSiwsIdentity` and the bundle's `authenticateSiws`
  field shape define it.

## Stubs (named, deliberate)

1. **Pin purchase** — the vacant slot's take-it action opens
   the explainer dialog, not a checkout: pin checkout is an
   M3 payment flow (R1–R6). The explainer says so in words.
2. **Composer payment sessions** — the composer attaches no
   post/commercial session; the thread fee's session flow is
   M3 (see above). The composer is complete for the two
   session-free paths that exist today (prestige exemption,
   VIP board) and truthfully refused for the rest.

## Host-shape mismatches found (host fixes, not client bugs)

1. **The commercial marker is recorded but never served.**
   The wrapper's `commercial_threads` table is the marker of
   record (WRAPPER_SURFACES_REPORT), but no read emits it:
   bundle `listForum` has no commercial field (zero
   "commercial" occurrences in the staging bundle), and the
   wrapper adds no listForum transform for it. The client
   therefore parses `isCommercial` tolerantly (default false)
   and the Commercial view + row label are built and correct
   the moment the host serves the flag — until then the
   Commercial view is empty even when commercial threads
   exist. **Host fix**: a listForum post-dispatch transform
   in the wrapper (the VIP-filter precedent) marking threads
   whose ids are in `commercial_threads`, e.g.
   `isCommercial: true`.
2. **No pin slot-state read exists.** The package's vacant-
   slot amendment says "the wrapper serves each slot's
   state", but no action serves slots; the only pin data on
   the wire is per-thread `isPaidPinned` / `paidPinEndsAt`
   in `listForum`. The client derives the strip from the full
   payload at the ruled cap of 2 — exact for the active set,
   but a dedicated read (e.g. `getPinSlots` returning
   filled/vacant + windows) would remove the derivation and
   let the strip survive payload filtering. Also noted: the
   staging wrapper's `OK_PAID_PIN_CAP` defaults to **1** in
   code while the ruled cap is **2** — a staging config
   setting, flagged for the flip sheet.
3. **Catch-Up tip amounts are raw base units.** `getCatchUp`'s
   `tipsReceived` serves `amountBaseUnits` as a string with
   the asset name and no decimals/display amount, so the
   Catch-Up can only show "N base-units ASSET" honestly
   (it currently shows the raw amount + asset). A served
   display amount (or per-asset decimals) would let it render
   like the rest of the money UI. Not guessed client-side.
4. **VIP read release depends on a cached proof.** By host
   design a VIP thread is released only when `siwsIdentity`
   rides the read; a holder on a restored session sees the
   locked card until they tap "Verify with sign-in proof"
   (which re-makes the proof and reloads). This is the host's
   fail-closed discipline working as intended — recorded so
   the behaviour is not mistaken for a bug at the gate.
5. **Board locked-ness is inferred, not stated.** Nothing in
   the payload says "this category is the VIP board" beyond
   its slug, nor "you are/are not a holder". The client keys
   on slug `vip` (the wrapper's own default) and treats
   "VIP board selected + no threads served" as locked. If
   the host ever renames the slug via `OK_VIP_BOARD_SLUG`,
   the client's constant must follow — a served
   `isVip`/`gated` flag on categories (and a prestige flag
   on the member record) would make both this and the
   composer's expectations explicit.

## Not in M2 (by plan)

Payment flows R1–R6 (join gate, thread fee sessions, pin
checkout, commercial sessions, two-leg tips, prestige
purchase) — M3, against this same staging wrapper. Member
pages beyond the payload's own member record — the plan's
M2 line names them; the payload serves no per-member page
read beyond `member`, so nothing was fabricated for them.
