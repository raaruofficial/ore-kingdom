# Ore Kingdom native client — M1 report (shell + identity, R0)

Built 2026-11-02 per `NATIVE_CLIENT_PLAN.md` M1 and the
`NATIVE_CLIENT_CENSUS.md` reuse inventory. New local project,
not pushed anywhere; no repo created; no keystore generated.

## What M1 is

- Hardened-shell scaffold from the Seeker Citadel client:
  package `com.raaru.orekingdom`, app name Ore Kingdom,
  versionCode 5 / versionName 0.1.4 (the sealed v0.1.3
  WebView candidate shipped versionCode 4; the lineage
  continues, it does not restart).
- Wallet connect + SIWS sign-in against the production host,
  with the verbatim linkability disclosure shown before any
  wallet action exists — and the same words as the signed
  message's statement.
- Read-only browsing cold, no wallet: thread list
  (`listForum`) + thread view (`getThread`), exactly as the
  sealed build behaves.
- Host-unreachable renders an error + Retry state (or saved
  reading marked stale) — never a blank screen; the app-level
  crash path is the shell's recovery-marker error boundary.

## File map vs Citadel

**Ported verbatim** (package rename only; fault-cure shapes
intact, incl. KFC-001/005/006/007/008 notes):
- `wallet/WalletManager.kt` — except identity: identityUri
  → `https://github.com/raaruofficial/ore-kingdom` (Citadel's
  own pattern), identityName "Ore Kingdom", log tag renamed.
- `wallet/ClaimRace.kt`, `util/Base58.kt`, `util/SolanaTx.kt`
  (message compilation, transferChecked, ATA/PDA helpers —
  the R2 machinery, ready for M3), `data/SolanaRpc.kt`.
- Tests: `Base58Test`, `SolanaTxTest`, `wallet/ClaimRaceTest`.
- `gradle.properties`, `app/proguard-rules.pro`, plugin/SDK/
  dependency versions in the Gradle files.

**Adapted:**
- `data/HostApi.kt` — same Ok/Refused/Unreachable pattern;
  envelope re-pointed to this host's contract:
  `POST {HOST_URL}/actions {action, args, actionCallId}` →
  `{data} | {error}` (no `/v1` prefix, no session token —
  identity here is the SIWS proof). Typed wrappers with the
  bundle's arg shapes: `getPublicConfig`, `authenticateSiws`,
  `listForum`, `getThread`, `getSubscriptionStatus`,
  `listNotifications`, `markNotificationsRead` (the last two
  wired for M2's Inbox).
- `data/Stores.kt` — SessionStore now persists wallet +
  skrName + MWA auth token (this host issues no session
  token); parked-signature discipline kept, keyed by wrapper
  session id for M3. CacheStore carried over unchanged in
  shape. Citadel's DraftStore was NOT ported (composer
  drafts are an M2/M3 surface, not M1).
- `OreKingdomApp.kt` (from CitadelApp, minus DraftStore),
  `MainActivity.kt` (nav trimmed to home / thread / signin;
  single ActivityResultSender in onCreate; KFC-008
  foreground/background hooks), `ui/theme/Theme.kt` (amber
  ore + cyan gem on near-black, from the concept-4 mark).
- Screens written for Ore Kingdom payloads on Citadel's
  patterns: `ThreadListScreen` (host order as served —
  pinned first, then updatedAt desc; category chips call
  `listForum` with categoryId), `ThreadScreen`,
  `SignInScreen` (disclosure-first order copied from
  Citadel's), shared `Components.kt` (top bar, unreachable
  state, stale banner, thread row).

**New:**
- `util/SiwsMessage.kt` — the canonical SIWS message
  builder (see Identity, below) + `SiwsMessageTest` pinning
  its exact byte shape.
- `data/Models.kt` — listForum/getThread payload models,
  parsed tolerantly from the bundle's response schemas.
- `ui/ForumViewModel.kt` — a lean M1 view model (identity +
  reads with Loading/Ready(stale)/Unreachable states).
  Citadel's 700-line ForumViewModel was NOT ported: most of
  it is boards/rooms/invoice state Ore Kingdom doesn't have
  until M2/M3; its patterns (state flows, callback-shaped
  sign-in) are followed.
- Icons generated from `~/workspace/ore-kingdom/brand/`
  concept-4 art (rounded 512 for legacy/round densities;
  square 512 inset to the adaptive safe zone for
  foregrounds, all densities — Citadel shipped a foreground
  only at xxxhdpi).

**Workflow:** `.github/workflows/android.yml` modeled on
Citadel's — Temurin 17, gradle/actions/setup-gradle 8.11.1,
`assembleDebug` on push to main + workflow_dispatch, debug
APK artifact. The release job is deliberately absent: signer
-lineage continuity is census open question §4.4 and release
signing is out of scope for M1. `app/build.gradle.kts` keeps
Citadel's env-only signing block (inert without env secrets).

## What the flows call

- **Cold read (no wallet):** `listForum` with no wallet arg →
  member is null, threads render. Thread tap: `getThread`
  with `wallet: ""` (the bundle schema requires the string;
  empty is accepted — verified live, below).
- **Sign-in:** WalletManager.connect (MWA) → build SIWS text
  locally → WalletManager.signMessageRaw → one
  `authenticateSiws {wallet, messageBase64, signatureBase64,
  publicKeyBase64}` → host returns `{ok, wallet, skrName}`;
  wallet + skrName persist in SessionStore; the member
  record from the next `listForum` (wallet arg) is the
  signed-in state. Host `{ok:false, error}` and envelope
  `{error}` refusals surface verbatim (e.g. the host's .skr
  requirement) — R6-style truthfulness from M1 on.
- **SIWS construction (the M1 risk, retired on paper):** the
  host's verifier (`@solana/wallet-standard-util`
  verifySignIn, read firsthand in the bundle's dependency
  tree) re-derives the canonical message text from the
  parsed fields of the signed message and verifies the
  signature against those derived bytes. `SiwsMessage`
  mirrors `createSignInMessageText` field-for-field (domain
  line, address, statement, then URI / Version / Chain ID /
  Issued At), so parse → re-derive returns the exact signed
  bytes. There is no challenge/nonce in this host's contract
  (single-shot, census §2), so the client constructs the
  message itself and signs it with the already-proven
  signMessage path — no new wallet API surface was added.

## Verified from this VM (live, production host)

- `listForum {}` → `{data}` with member null, 5 categories,
  threads in host order; payload fields match `Models.kt`
  (one extra category field, `position`, ignored by the
  tolerant parser).
- `getPublicConfig {}` → `{data}` (observed: simulationMode
  true on the production S build, as the census records).
- `getThread {wallet:"", threadId:1}` → `{ok:true, thread,
  replies:[]}` — the cold thread-view path works as coded.
- `getThread {threadId:99999}` → `{ok:false, error:"This
  thread is unavailable."}` — the refusal shape the thread
  screen renders verbatim.
- Brace/paren balance clean on all 21 Kotlin files; no
  WebView anywhere; no leftover Citadel identifiers outside
  provenance comments.

## NOT verified — CI-pending, named honestly

- **Compilation.** Local Gradle is dead in this estate
  (standing fact); nothing here has been compiled. The
  workflow above is the first compile. Residual risks a
  compiler may catch: Compose/material3 API details in the
  new screens and any import drift in adapted files.
- **SIWS end-to-end.** The message byte-shape is proven
  against the verifier's source and pinned by unit test,
  but no real wallet signature has been run through
  `authenticateSiws` from this client. That proof needs a
  device/emulator wallet rig with a .skr-holding wallet
  (the M4 gate's wallet-abandonment + sign-in proofs).
- **MWA identity.** identityUri points at the public
  ore-kingdom repo, following Citadel's pattern; wallet-side
  display of that identity is unverified until a real
  connect runs.
- Icon rendering on a launcher (adaptive mask crop of the
  inset foreground) is eyeballed from the source art only.

## Census claims that proved wrong (or incomplete)

1. **HostApi "speaks several Ore Kingdom action names"** —
   true, but the bigger miss is elsewhere: the census's R0
   row doesn't mention that Citadel's sign-in is a two-call
   challenge flow while Ore Kingdom's message must be
   constructed client-side with NO server template. The
   canonical-text derivation above is the M1-critical detail
   and it lives in the verifier's source, not in any spec
   the census cited. (Census §4.1's "message shape must
   match exactly" pointed the right way; the shape's source
   of truth is `wallet-standard-util`, now pinned by test.)
2. **`getThread` cold access** — the census lists getThread
   as a plain read; its bundle schema REQUIRES the wallet
   string (empty string works, verified live). Minor, but
   exactly the kind of thing that reads as "thread view
   broken" if assumed instead of checked.
3. Everything else checked held: wallet/tx plumbing ≈90%
   direct (verbatim, in fact); wrapper/session shapes as
   specced; the 43/43 staging wrapper untouched by M1
   (production host is M1's target, per the plan).
