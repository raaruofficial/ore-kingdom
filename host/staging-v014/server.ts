// Ore Kingdom — production host service (Phase 2 hardened build, 2026-10-28).
//
// Hosts GENGAR's compiled forum actions (actions.bundle.js, byte-identical
// to the custody server bundle, sha256 a0ac2fcd…) behind the wire contract
// the client speaks:
//   POST /actions {action, args, actionCallId} -> {data: ...} | {error: ...}
// and serves the transformed client statically at the same origin.
// State lives in a local SQLite file (data/app.db, seeded from the pristine
// custody DB by seed-production.sh — never the staged working copy).
//
// Variant S enforcement lives HERE, as an ALLOW-LIST (the Phase 0 surface
// table governs): any action not listed ALLOW never executes.
//   - the 8 checkout actions + simulateBillingEvent refuse with the
//     payments-disabled string (dormant by decision — PLAN_VISIONARY
//     Ruling 2/3; the builder price constants carry no authority);
//   - operator actions refuse (no operator console in S; the bundle's
//     placeholder-string admin fallback is unreachable — census 21);
//   - cancelSubscription refuses (no paid subscription exists in S);
//   - any call carrying a "DEMO-" wallet string refuses;
//   - the public moderation feed returns aggregated + identity-stripped.
// Badge mirror (PLAN delta D-1): polls and the Motherlode key on a Founder
// *membership* row (requireFounder), not on the founding badge. For each
// badge holder the host upserts a founder membership row carrying the
// sentinel checkout_id "badge-cohort:<memberId>" — a free cohort grant,
// not a payment (no checkout ever runs). Sweeps run at boot, after every
// successful claim, and every 60 s.
// Throttles: per-IP token bucket on the two scarce/write-heavy actions
// (registerProspector, claimFoundingSpot).
//
// Config points (environment): OK_PORT (default 8377), OK_DB_PATH,
// OK_CLIENT_DIR. See RUNBOOK.md.
//
// STAGING v0.1.4 (2026-11-02, pass 2 — TEKKADAN bankai B1–B12 applied):
// a copy of the production wrapper carrying the wrapper-side money
// surfaces from V014_BUILD_PACKAGE.md Part 1 + amendments A1–A11.
// NOT deployed; production remains host/deploy/.
//   - The tip/pin CREATE actions are un-refused here; both COMPLETE
//     actions are RE-REFUSED (B1/K4) by THIS wrapper gate. Since the
//     SIM-off bundle build (2026-11-02, bundle-src/ —
//     SIMOFF_BUNDLE_REPORT.md) the bundle itself verifies REAL
//     finalized transfers in both completions and accepts no
//     simulation signature, so the refusal is now purely the deploy
//     gate B1 always was: completions go live when a deployment
//     un-refuses them, not when the bundle learns how. The wrapper's
//     pre-verifications remain in place in front of the bundle's as
//     defense in depth. (The battery's lab phases set
//     OK_LAB_UNREFUSE_COMPLETES=1 to prove that machinery; it
//     defaults off and is never a deploy setting.)
//   - Every gated action requires a verified SIWS proof (B2/K3):
//     the acting wallet is derived from the proof and args.wallet is
//     overwritten with it before anything else runs.

import { Actions } from "./actions.bundle.js";
import { Database } from "bun:sqlite";
import { drizzle } from "drizzle-orm/bun-sqlite";
import { createPublicKey, verify as ed25519Verify } from "node:crypto";

const PORT = Number(process.env.OK_PORT ?? 8377);
const DB_PATH = process.env.OK_DB_PATH ?? new URL("./data/app.db", import.meta.url).pathname;
const CLIENT_DIR = process.env.OK_CLIENT_DIR ??
  new URL("./client", import.meta.url).pathname;

// --- v0.1.4 wrapper config (V014_BUILD_PACKAGE.md Part 2) ---------------------
// Prices of record are USD; token amounts derive by quote at session
// creation and are never config. Every destination is read from the
// forum_settings row at runtime — the wrapper hardcodes no treasury.
// The ruled thread price is $3.50 (post-fee amendment, 2026-11-02) —
// that is the code default. The env override still wins; the battery's
// pre-flip fixture phases pin the legacy 25¢ shape by env (the
// OK_PAID_PIN_CAP fixture precedent), and Phase I proves this default.
const POST_PRICE_THREAD_CENTS = Number(process.env.OK_POST_PRICE_USD_CENTS_THREAD ?? 350);
const POST_PRICE_REPLY_CENTS = Number(process.env.OK_POST_PRICE_USD_CENTS_REPLY ?? 25); // RETIRED (split amendment, 2026-11-02): replies are free for members and no reply session is ever priced; the env name is kept only so old configs still parse.
const TIP_MIN_CENTS = Number(process.env.OK_TIP_MIN_USD_CENTS ?? 500);
// The ruled per-tip cutoff (packet tip-safety amendment, 2026-11-02;
// $500 is the standing proposal, the figure is the owner's to set).
// Enforced wrapper-side at create (tipCreatePreDispatch): the wrapper
// is the guarantee — the client's block is a courtesy.
const TIP_MAX_CENTS = Number(process.env.OK_TIP_MAX_USD_CENTS ?? 50000);
const TIP_FEE_CENTS = Number(process.env.OK_TIP_FEE_USD_CENTS ?? 100);
// The ruled pin_slot_cap is 2 (package pin-capacity amendment,
// 2026-11-02): two live pins rotate through the spotlight. The env
// override remains for the battery's cap-1 fixture phases.
const PAID_PIN_CAP = Number(process.env.OK_PAID_PIN_CAP ?? 2);
// Late-2026-11-02 checkout surfaces: fixed SKR prices are config values
// in whole tokens; base units derive only by the mint's 6 decimals.
const JOIN_PRICE_SKR = Number(process.env.OK_JOIN_PRICE_SKR ?? 5000);
const MEMBER_CAP = Number(process.env.OK_MEMBER_CAP ?? 1000);
const PRESTIGE_PRICE_SKR = Number(process.env.OK_PRESTIGE_PRICE_SKR ?? 10000);
const PRESTIGE_CAP = Number(process.env.OK_PRESTIGE_CAP ?? 100);
const COMMERCIAL_PRICE_CENTS = Number(process.env.OK_COMMERCIAL_PRICE_CENTS ?? 5000);
// M2 (R7–R10): the prestige VIP board is a forum category identified by
// slug. Holders are its only readers and writers; the wrapper resolves
// the category per call so a renamed/absent board fails safe (no slug
// match = no VIP surface, nothing gated, nothing leaked).
const VIP_BOARD_SLUG = process.env.OK_VIP_BOARD_SLUG ?? "vip";
const FOUNDER_AUDIENCE_MODE = process.env.OK_POST_GATE_FOUNDER_AUDIENCE ?? "exempt"; // "exempt" (ruled 2026-11-02) | "gated"
const RPC_URL = process.env.OK_RPC_URL ?? "";
const PRICE_URL = process.env.OK_PRICE_URL ?? "";
// A1: the quote source must carry a fresh timestamp (<= 60 s) and land
// inside the per-asset sanity band (USD), or no session is created.
const PRICE_MAX_AGE_MS = 60_000;
function priceBand(asset: string): { min: number; max: number } {
  const raw = process.env[`OK_PRICE_BAND_${asset}`];
  if (raw) {
    const [min, max] = raw.split(",").map(Number);
    if (Number.isFinite(min) && Number.isFinite(max) && min > 0 && max > min) return { min, max };
  }
  if (asset === "SKR") return { min: 0.0005, max: 0.5 };
  if (asset === "ORE") return { min: 10, max: 2000 };
  if (asset === "USDC") return { min: 0.9, max: 1.1 };
  return { min: 10, max: 2000 }; // SOL
}
// A11: pending post sessions per member are capped so minted sessions
// cannot bury the resume-after-abandon lookup.
const POST_SESSION_PENDING_CAP = Number(process.env.OK_POST_SESSION_PENDING_CAP ?? 10);
// A8: the two-transaction tip fallback is a recorded BUILD FACT about
// the shipped client, never a per-call choice. "1" (default) = the
// client builds one transaction with both transfers, and a completion
// that omits the signature refuses. "0" = the recorded-incapable
// client: the tip completes first and the fee leg goes outstanding.
const TWO_TRANSFER_CAPABLE = (process.env.OK_TIP_TWO_TRANSFER_CAPABLE ?? "1") !== "0";
// B1 lab switch (battery only): un-refuses the two completion actions
// so the machinery behind the refusal can be proven. Defaults OFF.
const LAB_UNREFUSE_COMPLETES = process.env.OK_LAB_UNREFUSE_COMPLETES === "1";

const sqlite = new Database(DB_PATH);
const db = drizzle(sqlite);
const ctx: any = { db: () => db, invalidateQueries: () => {} };

// --- Variant S allow-list (surface table, plan-2026-10-28) ------------------
const ALLOW_ACTIONS = new Set([
  "authenticateSiws", "claimFoundingSpot", "createReply", "createThread",
  "getPublicConfig", "getSubscriptionStatus", "getThread", "listForum",
  "listModerationFeed", "listMotherlode", "listNotifications",
  "markNotificationsRead", "registerProspector", "reportContent",
  "toggleLike", "voteFounderPoll", "voteRestore",
]);
const PAYMENT_ACTIONS = new Set([
  "createFoundingBadgeCheckout", "completeFoundingBadgeCheckout",
  "createCheckout", "completeCheckout",
  "createTipCheckout", "completeTipCheckout",
  "createPaidPinCheckout", "completePaidPinCheckout",
  "simulateBillingEvent",
]);
// v0.1.4 staging: the tip and pin CREATE actions are un-refused (packet
// §7 item 3, scoped to the surfaces V014_BUILD_PACKAGE.md Part 1 builds).
// BOTH COMPLETE actions stay refused (B1) until the bundle ships SIM-off
// with real verification. The remaining payment actions still refuse —
// their live completion is bundle-resident (Route B clock).
const V014_PAYMENT_ACTIONS = new Set([
  "createTipCheckout", "createPaidPinCheckout",
  ...(LAB_UNREFUSE_COMPLETES ? ["completeTipCheckout", "completePaidPinCheckout"] : []),
]);
const OPERATOR_ACTIONS = new Set(["getOwnerOverview", "updateOwnerSettings"]);
// B2: actions that move money or gated content. Each requires a verified
// SIWS proof (args.siwsIdentity, the bundle's authenticateSiws field
// shape); the acting wallet is derived from the proof, never trusted
// from args.wallet.
const GATED_ACTIONS = new Set([
  "createPostSession", "getPostSession", "payOutstandingTipFee",
  "createJoinSession", "completeJoinSession", "getJoinSession",
  "createPrestigeSession", "completePrestigeSession", "getPrestigeSession",
  "createCommercialSession", "getCommercialSession",
  "getCatchUp", "advanceCatchUp",
  "createThread", "createReply",
  "createTipCheckout", "completeTipCheckout",
  "createPaidPinCheckout", "completePaidPinCheckout",
]);
// Bundle actions whose dispatch goes through the bundle's own request
// schema (B11) — the parsed output (defaults applied, unknown keys
// stripped by the schema itself) is what the handler receives.
const SCHEMA_PARSED_ACTIONS = new Set([
  "createThread", "createReply",
  "createTipCheckout", "completeTipCheckout",
  "createPaidPinCheckout", "completePaidPinCheckout",
]);

function carriesDemoWallet(value: unknown): boolean {
  if (typeof value === "string") return value.startsWith("DEMO-");
  if (Array.isArray(value)) return value.some(carriesDemoWallet);
  if (value && typeof value === "object")
    return Object.values(value).some(carriesDemoWallet);
  return false;
}

const IDENTITY_KEY = /wallet|memberid|reporter|reportedby|authorid|userid/i;
function stripIdentities(value: any): any {
  if (Array.isArray(value)) return value.map(stripIdentities);
  if (value && typeof value === "object") {
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value))
      if (!IDENTITY_KEY.test(k)) out[k] = stripIdentities(v);
    return out;
  }
  return value;
}

function aggregateModerationFeed(result: any): any {
  const items = Array.isArray(result) ? result
    : Array.isArray(result?.items) ? result.items
    : Array.isArray(result?.reports) ? result.reports : [];
  const byStatus: Record<string, number> = {};
  for (const item of items) {
    const key = String(item?.status ?? item?.state ?? "reported");
    byStatus[key] = (byStatus[key] ?? 0) + 1;
  }
  return { closed: true, aggregateOnly: true, totalReports: items.length, byStatus };
}

// --- Base58 (decode added pass 2; the encoder is pass 1) ---------------------
const B58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
function base58Encode(bytes: Uint8Array): string {
  let zeros = 0;
  while (zeros < bytes.length && bytes[zeros] === 0) zeros++;
  const digits: number[] = [];
  for (let i = zeros; i < bytes.length; i++) {
    let carry = bytes[i];
    for (let j = 0; j < digits.length; j++) {
      carry += digits[j] << 8;
      digits[j] = carry % 58;
      carry = Math.floor(carry / 58);
    }
    while (carry > 0) { digits.push(carry % 58); carry = Math.floor(carry / 58); }
  }
  return "1".repeat(zeros) + digits.reverse().map((d) => B58_ALPHABET[d]).join("");
}
function base58Decode(text: string): Uint8Array {
  let zeros = 0;
  while (zeros < text.length && text[zeros] === "1") zeros++;
  const bytes: number[] = [];
  for (let i = zeros; i < text.length; i++) {
    const v = B58_ALPHABET.indexOf(text[i]);
    if (v < 0) throw new Error("invalid base58");
    let carry = v;
    for (let j = 0; j < bytes.length; j++) {
      carry += bytes[j] * 58;
      bytes[j] = carry & 0xff;
      carry >>= 8;
    }
    while (carry > 0) { bytes.push(carry & 0xff); carry >>= 8; }
  }
  const out = new Uint8Array(zeros + bytes.length);
  out.set(bytes.reverse(), zeros);
  return out;
}

// --- SIWS identity (B2 / K3) ---------------------------------------------------
// The wrapper cannot import the bundle's internals, so it verifies the
// proof wrapper-side against the SAME message format and the SAME checks
// the bundle's authenticateSiws performs (wallet-standard sign-in, as
// compiled into actions.bundle.js): the message must parse; the parsed
// address must be the proof key's own address; the canonical
// re-serialization of the parsed message must byte-equal the signed
// message; the ed25519 signature must verify for the presented key.
// Two wrapper-side hardenings on top, both fail-closed and compatible
// with the bundle's shape: a present expirationTime / notBefore is
// honored (the bundle's self-referential field comparison cannot see
// them), and the proof is required fresh per gated action — there is no
// session to borrow.
const SIWS_MESSAGE_RE = (() => {
  const DOMAIN = "(?<domain>[^\\n]+?) wants you to sign in with your Solana account:\\n";
  const ADDRESS = "(?<address>[^\\n]+)(?:\\n|$)";
  const STATEMENT = "(?:\\n(?<statement>[\\S\\s]*?)(?:\\n|$))??";
  const URI = "(?:\\nURI: (?<uri>[^\\n]+))?";
  const VERSION = "(?:\\nVersion: (?<version>[^\\n]+))?";
  const CHAIN_ID = "(?:\\nChain ID: (?<chainId>[^\\n]+))?";
  const NONCE = "(?:\\nNonce: (?<nonce>[^\\n]+))?";
  const ISSUED_AT = "(?:\\nIssued At: (?<issuedAt>[^\\n]+))?";
  const EXPIRATION_TIME = "(?:\\nExpiration Time: (?<expirationTime>[^\\n]+))?";
  const NOT_BEFORE = "(?:\\nNot Before: (?<notBefore>[^\\n]+))?";
  const REQUEST_ID = "(?:\\nRequest ID: (?<requestId>[^\\n]+))?";
  const RESOURCES = "(?:\\nResources:(?<resources>(?:\\n- [^\\n]+)*))?";
  return new RegExp(`^${DOMAIN}${ADDRESS}${STATEMENT}${URI}${VERSION}${CHAIN_ID}${NONCE}${ISSUED_AT}${EXPIRATION_TIME}${NOT_BEFORE}${REQUEST_ID}${RESOURCES}\\n*$`);
})();
type SiwsParsed = {
  domain: string; address: string; statement?: string; uri?: string;
  version?: string; chainId?: string; nonce?: string; issuedAt?: string;
  expirationTime?: string; notBefore?: string; requestId?: string;
  resources?: string[];
};
function parseSiwsMessageText(text: string): SiwsParsed | null {
  const match = SIWS_MESSAGE_RE.exec(text);
  if (!match?.groups) return null;
  const g = match.groups as Record<string, string | undefined>;
  return {
    domain: g.domain!, address: g.address!, statement: g.statement,
    uri: g.uri, version: g.version, nonce: g.nonce, chainId: g.chainId,
    issuedAt: g.issuedAt, expirationTime: g.expirationTime,
    notBefore: g.notBefore, requestId: g.requestId,
    resources: g.resources ? g.resources.split("\n- ").slice(1) : undefined,
  };
}
function createSiwsMessageText(p: SiwsParsed): string {
  let message = `${p.domain} wants you to sign in with your Solana account:\n${p.address}`;
  if (p.statement) message += `\n\n${p.statement}`;
  const fields: string[] = [];
  if (p.uri) fields.push(`URI: ${p.uri}`);
  if (p.version) fields.push(`Version: ${p.version}`);
  if (p.chainId) fields.push(`Chain ID: ${p.chainId}`);
  if (p.nonce) fields.push(`Nonce: ${p.nonce}`);
  if (p.issuedAt) fields.push(`Issued At: ${p.issuedAt}`);
  if (p.expirationTime) fields.push(`Expiration Time: ${p.expirationTime}`);
  if (p.notBefore) fields.push(`Not Before: ${p.notBefore}`);
  if (p.requestId) fields.push(`Request ID: ${p.requestId}`);
  if (p.resources) { fields.push("Resources:"); for (const r of p.resources) fields.push(`- ${r}`); }
  if (fields.length) message += `\n\n${fields.join("\n")}`;
  return message;
}
const ED25519_SPKI_PREFIX = Buffer.from("302a300506032b6570032100", "hex");
function verifyEd25519(message: Uint8Array, signature: Uint8Array, publicKeyRaw: Uint8Array): boolean {
  try {
    if (publicKeyRaw.length !== 32 || signature.length !== 64) return false;
    const key = createPublicKey({
      key: Buffer.concat([ED25519_SPKI_PREFIX, Buffer.from(publicKeyRaw)]),
      format: "der", type: "spki",
    });
    return ed25519Verify(null, Buffer.from(message), key, Buffer.from(signature));
  } catch { return false; }
}
// Returns the verified acting wallet. Throws a named refusal otherwise.
function requireSiwsIdentity(args: any): string {
  const proof = args?.siwsIdentity;
  if (!proof || typeof proof !== "object")
    throw new Error("A verified sign-in proof is required for this action. Nothing was done.");
  const { messageBase64, signatureBase64, publicKeyBase64 } = proof as Record<string, unknown>;
  if (typeof messageBase64 !== "string" || typeof signatureBase64 !== "string" || typeof publicKeyBase64 !== "string")
    throw new Error("A verified sign-in proof is required for this action. Nothing was done.");
  let signedMessage: Uint8Array, signature: Uint8Array, publicKey: Uint8Array;
  try {
    signedMessage = new Uint8Array(Buffer.from(messageBase64, "base64"));
    signature = new Uint8Array(Buffer.from(signatureBase64, "base64"));
    publicKey = new Uint8Array(Buffer.from(publicKeyBase64, "base64"));
  } catch { throw new Error("Sign-in proof could not be read. Nothing was done."); }
  const parsed = parseSiwsMessageText(new TextDecoder().decode(signedMessage));
  if (!parsed) throw new Error("Sign-in proof could not be verified. Nothing was done.");
  const address = base58Encode(publicKey);
  if (parsed.address !== address)
    throw new Error("The signed wallet identity did not match the proof key. Nothing was done.");
  if (createSiwsMessageText(parsed) !== new TextDecoder().decode(signedMessage))
    throw new Error("Sign-in proof could not be verified. Nothing was done.");
  const now = Date.now();
  if (parsed.expirationTime && !(now < Date.parse(parsed.expirationTime)))
    throw new Error("This sign-in proof has expired. Sign in again. Nothing was done.");
  if (parsed.notBefore && now < Date.parse(parsed.notBefore))
    throw new Error("This sign-in proof is not yet valid. Nothing was done.");
  if (!verifyEd25519(signedMessage, signature, publicKey))
    throw new Error("Wallet signature verification failed. Nothing was done.");
  return address;
}

// --- Badge mirror (D-1) ------------------------------------------------------
// founderMembershipFor (bundle) accepts tier='founder' rows with
// (state='active' AND expires_at >= now) or (state='past_due' AND
// grace_until >= now). The mirror inserts exactly one such row per badge
// holder lacking a live founder row. expires_at = 2100-01-01 (deliberate,
// auditable); payment_asset 'SOL' follows the seed rows' convention and no
// payment is implied: simulated=0, plan_id + checkout_id carry the
// badge-cohort sentinel an audit reads.
const MIRROR_EXPIRES_AT = 4102444800000; // 2100-01-01T00:00:00Z
function mirrorBadgeHolder(memberId: number): void {
  const now = Date.now();
  const live = sqlite.query(
    `SELECT id FROM memberships WHERE member_id = ? AND tier = 'founder'
       AND ((state = 'active' AND expires_at >= ?) OR (state = 'past_due' AND grace_until >= ?))`,
  ).get(memberId, now, now);
  if (live) return;
  sqlite.query(
    `INSERT INTO memberships (member_id, tier, state, payment_asset, simulated,
       checkout_id, plan_id, subscription_key, period_started_at, expires_at,
       grace_until, cancel_at_period_end, created_at)
     VALUES (?, 'founder', 'active', 'SOL', 0, ?, 'badge-cohort', NULL, ?, ?, NULL, 0, ?)`,
  ).run(memberId, `badge-cohort:${memberId}`, now, MIRROR_EXPIRES_AT, now);
}
function badgeMirrorSweep(): void {
  const holders = sqlite.query(
    `SELECT id FROM members WHERE is_founding_member = 1`,
  ).all() as { id: number }[];
  for (const h of holders) {
    try { mirrorBadgeHolder(h.id); } catch (e) { console.error("badge mirror failed for member", h.id, e); }
  }
}

// --- Throttles ---------------------------------------------------------------
const THROTTLED = new Set(["registerProspector", "claimFoundingSpot"]);
const BUCKET_CAPACITY = 10;          // burst
const BUCKET_REFILL_MS = 60_000;     // +1 token per minute thereafter
const buckets = new Map<string, { tokens: number; updated: number }>();
function throttleAllows(key: string): boolean {
  const now = Date.now();
  const b = buckets.get(key) ?? { tokens: BUCKET_CAPACITY, updated: now };
  b.tokens = Math.min(BUCKET_CAPACITY, b.tokens + (now - b.updated) / BUCKET_REFILL_MS);
  b.updated = now;
  if (b.tokens < 1) { buckets.set(key, b); return false; }
  b.tokens -= 1; buckets.set(key, b); return true;
}
function clientIp(req: Request, server: any): string {
  const peer = server.requestIP(req)?.address ?? "unknown";
  if (peer === "127.0.0.1" || peer === "::1") {
    const xff = req.headers.get("x-forwarded-for");
    if (xff) return xff.split(",")[0].trim();
  }
  return peer;
}

// --- v0.1.4 wrapper state ------------------------------------------------------
// post_sessions: the wrapper owns the paid-post gate (packet §1.2/§1.3).
// destination is stored on the row at creation (A2) — verification never
// reads the mutable forum_settings value later. usd_cents is likewise
// the stored creation quote, surfaced as `usdCents` in postSessionPublic
// so a resuming client re-displays the exact price of record without a
// fresh quote. dispatch_title/body are
// the A6 fingerprint: what a claim intended to post, so boot
// reconciliation can tell "dispatched and landed" from "dispatched and
// never landed" without guessing.
// spent_signatures (A7): every payment signature is claimed globally,
// exactly once, BEFORE the state it pays for moves. A second claim of
// the same signature is the named replay refusal — never a raw
// UNIQUE-constraint error surfacing to a member.
// tip_fee_legs: the standing companion record of a tip's fee leg (A7/A8).
//   signature UNIQUE (via the registry too), fee_expires_at = created_at
//   + 15 min (the bundle's own quote window), statuses
//   pending/verified/outstanding/expired.
// pin_slot_reservations + pin_parked (A9): the create-time reservation
// is what makes the single-slot cap hold across the quote window;
// park data lets a deferred pin activate with its own paid hours when
// the slot frees.
sqlite.exec(`
CREATE TABLE IF NOT EXISTS post_sessions (
  id TEXT PRIMARY KEY,
  member_id INTEGER NOT NULL,
  wallet TEXT NOT NULL,
  kind TEXT NOT NULL CHECK(kind IN ('thread','reply')),
  audience TEXT NOT NULL DEFAULT 'public',
  asset TEXT NOT NULL DEFAULT 'SKR',
  amount INTEGER NOT NULL,
  usd_cents INTEGER,
  destination TEXT NOT NULL,
  reference_pubkey TEXT UNIQUE,
  signature TEXT UNIQUE,
  status TEXT NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','verified','dispatched','consumed','expired')),
  created_at INTEGER NOT NULL,
  pay_expires_at INTEGER NOT NULL,
  verified_at INTEGER,
  dispatched_at INTEGER,
  consume_expires_at INTEGER,
  result_ref TEXT,
  dispatch_title TEXT,
  dispatch_body TEXT
);
CREATE TABLE IF NOT EXISTS spent_signatures (
  signature TEXT PRIMARY KEY,
  kind TEXT NOT NULL,
  ref_id TEXT NOT NULL,
  spent_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS pin_slot_reservations (
  id TEXT PRIMARY KEY,
  member_id INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS pin_parked (
  session_id TEXT PRIMARY KEY,
  hours INTEGER NOT NULL,
  parked_at INTEGER NOT NULL
);
-- Late checkout surfaces (join / prestige / commercial). These follow
-- post_sessions deliberately: destination and amount are bound at
-- creation, timeliness is judged by the payment's blockTime (A4), and
-- the signature is claimed in spent_signatures before the paid state
-- moves (A7). join_sessions.member_id is NULL until completion stamps
-- the member row it created. forward_apply_credits is the O1 record:
-- an exact-or-partial payment that demonstrably landed at the Relay
-- but could not be served is credited toward a later paid action,
-- never silently pocketed and never reusable as a fresh signature.
CREATE TABLE IF NOT EXISTS join_sessions (
  id TEXT PRIMARY KEY,
  member_id INTEGER,
  wallet TEXT NOT NULL,
  display_name TEXT,
  skr_name TEXT,
  asset TEXT NOT NULL DEFAULT 'SKR',
  mint TEXT NOT NULL,
  amount INTEGER NOT NULL,
  skr_price INTEGER NOT NULL,
  destination TEXT NOT NULL,
  reference_pubkey TEXT UNIQUE,
  signature TEXT UNIQUE,
  status TEXT NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','completed','expired','forward_applied')),
  created_at INTEGER NOT NULL,
  pay_expires_at INTEGER NOT NULL,
  verified_at INTEGER,
  completed_at INTEGER
);
CREATE TABLE IF NOT EXISTS forward_apply_credits (
  id TEXT PRIMARY KEY,
  wallet TEXT NOT NULL,
  member_id INTEGER,
  signature TEXT NOT NULL UNIQUE,
  source_kind TEXT NOT NULL,
  source_session_id TEXT NOT NULL,
  asset TEXT NOT NULL,
  amount INTEGER NOT NULL,
  destination TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'recorded' CHECK(status IN ('recorded','applied')),
  created_at INTEGER NOT NULL,
  applied_at INTEGER,
  note TEXT
);
CREATE TABLE IF NOT EXISTS prestige_sessions (
  id TEXT PRIMARY KEY,
  member_id INTEGER NOT NULL,
  wallet TEXT NOT NULL,
  asset TEXT NOT NULL DEFAULT 'SKR',
  mint TEXT NOT NULL,
  amount INTEGER NOT NULL,
  skr_price INTEGER NOT NULL,
  destination TEXT NOT NULL,
  reference_pubkey TEXT UNIQUE,
  signature TEXT UNIQUE,
  status TEXT NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','completed','expired')),
  created_at INTEGER NOT NULL,
  pay_expires_at INTEGER NOT NULL,
  verified_at INTEGER,
  completed_at INTEGER
);
CREATE TABLE IF NOT EXISTS prestige_holders (
  member_id INTEGER PRIMARY KEY,
  wallet TEXT NOT NULL,
  session_id TEXT NOT NULL UNIQUE,
  signature TEXT NOT NULL UNIQUE,
  granted_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS commercial_sessions (
  id TEXT PRIMARY KEY,
  member_id INTEGER NOT NULL,
  wallet TEXT NOT NULL,
  asset TEXT NOT NULL,
  mint TEXT NOT NULL,
  amount INTEGER NOT NULL,
  usd_cents INTEGER NOT NULL,
  destination TEXT NOT NULL,
  reference_pubkey TEXT UNIQUE,
  signature TEXT UNIQUE,
  status TEXT NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','verified','dispatched','completed','expired')),
  created_at INTEGER NOT NULL,
  pay_expires_at INTEGER NOT NULL,
  verified_at INTEGER,
  dispatched_at INTEGER,
  consume_expires_at INTEGER,
  result_ref TEXT,
  dispatch_title TEXT,
  dispatch_body TEXT
);
CREATE TABLE IF NOT EXISTS commercial_threads (
  thread_id INTEGER PRIMARY KEY,
  session_id TEXT NOT NULL UNIQUE,
  member_id INTEGER NOT NULL,
  marked_at INTEGER NOT NULL
);
-- M2 (R9): the Catch-Up watermark is wrapper-owned, one row per
-- member. It moves ONLY through advanceCatchUp (the client's
-- after-display call); reads never move it. A member with no row is
-- at their join time (members.created_at) by definition.
CREATE TABLE IF NOT EXISTS catchup_watermarks (
  member_id INTEGER PRIMARY KEY,
  watermark INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
`);
// tip_fee_legs carries a CHECK the pass-1 shape cannot extend in place
// (sqlite cannot ALTER a CHECK constraint), so an old-shape table is
// recreated. Staging data is disposable by construction; the recreation
// is the migration, and STAGING_NOTES records it.
{
  const row = sqlite.query(
    `SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'tip_fee_legs'`,
  ).get() as { sql: string } | null;
  if (!row || !/fee_expires_at/.test(row.sql) || !/'expired'/.test(row.sql)) {
    sqlite.exec(`DROP TABLE IF EXISTS tip_fee_legs;`);
  }
}
sqlite.exec(`
CREATE TABLE IF NOT EXISTS tip_fee_legs (
  id TEXT PRIMARY KEY,
  tip_session_id TEXT NOT NULL UNIQUE,
  reference_pubkey TEXT UNIQUE,
  asset TEXT NOT NULL,
  amount INTEGER NOT NULL,
  destination TEXT NOT NULL,
  fee_usd_cents INTEGER NOT NULL,
  status TEXT NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','verified','outstanding','expired')),
  signature TEXT UNIQUE,
  created_at INTEGER NOT NULL,
  fee_expires_at INTEGER NOT NULL,
  verified_at INTEGER
);
`);
for (const col of ["destination", "dispatch_title", "dispatch_body"]) {
  const cols = sqlite.query(`PRAGMA table_info(post_sessions)`).all() as { name: string }[];
  if (!cols.some((c) => c.name === col))
    sqlite.exec(`ALTER TABLE post_sessions ADD COLUMN ${col} TEXT`);
}
{
  const cols = sqlite.query(`PRAGMA table_info(post_sessions)`).all() as { name: string }[];
  if (!cols.some((c) => c.name === "usd_cents"))
    sqlite.exec(`ALTER TABLE post_sessions ADD COLUMN usd_cents INTEGER`);
}
// destination is bound at creation for every new row (A2); a pass-1-era
// row predating the column carries NULL and is refused fail-closed at
// the gate rather than verified against a later settings value.

const TOKEN_PROGRAM_ID = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA";
const ATA_PROGRAM_ID = "AToken1vLFEcaFqNbGRaC3XDDtVfMnUkpu2KhjjYKAr";
const ASSETS: Record<string, { mint: string; decimals: number; priceId: string; lamports: boolean }> = {
  SKR: { mint: "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3", decimals: 6, priceId: "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3", lamports: false },
  ORE: { mint: "oreoU2P8bN6jkk3jbaiVxYnG1dCXcYxwhwyK9jSybcp", decimals: 11, priceId: "oreoU2P8bN6jkk3jbaiVxYnG1dCXcYxwhwyK9jSybcp", lamports: false },
  SOL: { mint: "So11111111111111111111111111111111111111112", decimals: 9, priceId: "So11111111111111111111111111111111111111112", lamports: true },
  USDC: { mint: "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", decimals: 6, priceId: "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", lamports: false },
};
const POST_SESSION_TTL_MS = 15 * 60_000; // parity with the bundle's checkout expiresAt (+15 min)
const CONSUME_WINDOW_MS = 24 * 60 * 60_000;

function settingsRow(): Record<string, any> {
  return sqlite.query(`SELECT * FROM forum_settings WHERE id = 1`).get() as Record<string, any>;
}
function treasuryWallet(): string {
  const t = String(settingsRow()?.treasury_wallet ?? "").trim();
  if (!t || t.startsWith("REPLACE_"))
    throw new Error("The treasury wallet is not configured. Payments are unavailable.");
  return t;
}
function memberByWallet(wallet: string): Record<string, any> | null {
  return sqlite.query(`SELECT * FROM members WHERE wallet = ?`).get(wallet) as Record<string, any> | null;
}
function isFounderMember(member: Record<string, any>): boolean {
  if (Number(member.is_founding_member ?? 0) === 1) return true;
  const now = Date.now();
  const row = sqlite.query(
    `SELECT id FROM memberships WHERE member_id = ? AND tier = 'founder'
       AND ((state = 'active' AND expires_at >= ?) OR (state = 'past_due' AND grace_until >= ?))`,
  ).get(member.id, now, now);
  return !!row;
}
function genReferencePubkey(): string {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return base58Encode(bytes);
}

// --- Quote (A1) ------------------------------------------------------------------
// Integer math only, rounding UP (the house never rounds a toll down),
// priced from the named source in config (OK_PRICE_URL). The source's
// answer must carry a timestamp no older than 60 s and a price inside
// the asset's sanity band; anything else fails CLOSED — no session is
// created from an unverifiable quote. A zero amount refuses outright.
function ceilDiv(a: bigint, b: bigint): bigint { return (a + b - 1n) / b; }
function parseDecimalToScaled(text: string, scale: number): bigint {
  const m = /^(\d+)(?:\.(\d+))?$/.exec(String(text).trim());
  if (!m) throw new Error("price parse");
  const frac = (m[2] ?? "").padEnd(scale, "0").slice(0, scale);
  return BigInt(m[1]) * 10n ** BigInt(scale) + BigInt(frac || "0");
}
async function quoteUsdToBaseUnits(asset: string, usdCents: number): Promise<number> {
  const spec = ASSETS[asset];
  if (!spec) throw new Error(`Unsupported payment asset ${asset}.`);
  if (!PRICE_URL) throw new Error("Price source is not configured. Payments are unavailable.");
  const url = new URL(PRICE_URL);
  url.searchParams.set("ids", spec.priceId);
  const res = await fetch(url.toString());
  if (!res.ok) throw new Error("Price source unavailable. Try again shortly.");
  const json: any = await res.json().catch(() => null);
  const entry = json?.data?.[spec.priceId];
  const priceText = entry?.price != null ? String(entry.price) : "";
  if (!priceText || !Number.isFinite(Number(priceText)) || Number(priceText) <= 0)
    throw new Error("Price quote is unusable. Payments are unavailable right now.");
  const tsRaw = json?.ts ?? entry?.ts ?? entry?.timestamp ?? null;
  const tsMs = tsRaw == null ? NaN : Number(tsRaw) < 1e12 ? Number(tsRaw) * 1000 : Number(tsRaw);
  if (!Number.isFinite(tsMs) || Date.now() - tsMs > PRICE_MAX_AGE_MS || tsMs - Date.now() > 5_000)
    throw new Error("Price quote is stale. Payments are unavailable right now.");
  const band = priceBand(asset);
  const priceNum = Number(priceText);
  if (priceNum < band.min || priceNum > band.max)
    throw new Error("Price quote is outside its sanity band. Payments are unavailable right now.");
  // amount = ceil(usd * 10^decimals / price): usd cents scaled to 10^-2
  // USD and the price scaled to 10^-8 USD keep every step integral.
  const usdScaled = BigInt(usdCents) * 10n ** 6n;            // USD * 10^8
  const priceScaled = parseDecimalToScaled(priceText, 8);    // USD/whole * 10^8
  if (priceScaled <= 0n) throw new Error("Price quote is unusable. Payments are unavailable right now.");
  const amount = ceilDiv(usdScaled * 10n ** BigInt(spec.decimals), priceScaled);
  if (amount <= 0n) throw new Error("Quoted amount rounds to zero. Payments are unavailable right now.");
  if (amount > BigInt(Number.MAX_SAFE_INTEGER)) throw new Error("Quoted amount is out of range.");
  return Number(amount);
}

// --- ATA derivation (A5, m3) -----------------------------------------------------
// The destination check binds to the treasury's SPECIFIC token account
// for the mint — never a net across every account the wallet owns.
function isOnCurveEd25519(bytes: Uint8Array): boolean {
  const p = (1n << 255n) - 19n;
  const d = 37095705934669439343138083508754565189542113879843219016388785533085940283555n;
  let y = 0n;
  for (let i = 31; i >= 0; i--) y = (y << 8n) | BigInt(bytes[i]);
  y &= (1n << 255n) - 1n;
  if (y >= p) return false;
  const y2 = (y * y) % p;
  const u = (y2 - 1n + p) % p;
  const v = (d * y2 + 1n) % p;
  const x2 = (u * modInv(v, p)) % p;
  return modPow(x2, (p - 1n) / 2n, p) === 1n;
}
function modPow(base: bigint, exp: bigint, mod: bigint): bigint {
  let r = 1n, b = base % mod, e = exp;
  while (e > 0n) { if (e & 1n) r = (r * b) % mod; b = (b * b) % mod; e >>= 1n; }
  return r;
}
function modInv(a: bigint, mod: bigint): bigint { return modPow(a, mod - 2n, mod); }
const ataCache = new Map<string, string>();
function deriveAta(owner: string, mint: string): string {
  const cacheKey = `${owner}|${mint}`;
  const hit = ataCache.get(cacheKey);
  if (hit) return hit;
  const ownerBytes = base58Decode(owner);
  const programBytes = base58Decode(ATA_PROGRAM_ID);
  const seeds = [ownerBytes, base58Decode(TOKEN_PROGRAM_ID), base58Decode(mint)];
  const hasher = new Bun.CryptoHasher("sha256");
  for (let bump = 255; bump >= 0; bump--) {
    hasher.update(Buffer.concat([
      ...seeds.map((s) => Buffer.from(s)), Buffer.from([bump]),
      Buffer.from(programBytes), Buffer.from("ProgramDerivedAddress", "utf8"),
    ]));
    const digest = new Uint8Array(hasher.digest() as unknown as ArrayBuffer);
    if (!isOnCurveEd25519(digest)) {
      const ata = base58Encode(digest);
      ataCache.set(cacheKey, ata);
      return ata;
    }
  }
  throw new Error("Could not derive the treasury token account.");
}

// --- Chain verification (A4/A5) --------------------------------------------------
async function rpcCall(method: string, params: unknown[]): Promise<any> {
  if (!RPC_URL) throw new Error("Chain RPC is not configured. Payments are unavailable.");
  const res = await fetch(RPC_URL, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ jsonrpc: "2.0", id: 1, method, params }),
  });
  if (!res.ok) throw new Error("Chain RPC unavailable. Try again shortly.");
  const json: any = await res.json().catch(() => null);
  if (!json || json.error) throw new Error("Chain RPC unavailable. Try again shortly.");
  return json.result;
}
// checkTransfer classifies a failure by its own name (the bundle's
// message ladder, reused by the gate): "reference" | "mint" |
// "destination" | "amount" | "source" | "failed" (meta.err — A5's
// FIRST check).
function checkTransfer(tx: any, o: {
  reference: string; asset: string; amount: number; destinationWallet: string; sourceWallet: string;
}): string | null {
  const meta = tx?.meta;
  if (!meta) return "reference";
  if (meta.err != null) return "failed"; // A5: transaction success is the first check
  const keys: string[] = (tx.transaction?.message?.accountKeys ?? []).map((k: any) =>
    typeof k === "string" ? k : k?.pubkey);
  const instructions = tx.transaction?.message?.instructions ?? [];
  const hasReference = instructions.some((ix: any) =>
    (ix.accounts ?? []).some((ai: number) => keys[ai] === o.reference));
  if (!hasReference) return "reference";
  const spec = ASSETS[o.asset];
  const pre = meta.preTokenBalances ?? [], post = meta.postTokenBalances ?? [];
  const preByIndex = new Map<number, any>(pre.map((e: any) => [e.accountIndex, e]));
  const postByIndex = new Map<number, any>(post.map((e: any) => [e.accountIndex, e]));
  const deltaOf = (pred: (e: any, key: string) => boolean): number => {
    let d = 0;
    const idx = new Set<number>([...preByIndex.keys(), ...postByIndex.keys()]);
    for (const i of idx) {
      const e = postByIndex.get(i) ?? preByIndex.get(i);
      if (!e || !pred(e, keys[i])) continue;
      d += Number(postByIndex.get(i)?.uiTokenAmount?.amount ?? 0) - Number(preByIndex.get(i)?.uiTokenAmount?.amount ?? 0);
    }
    return d;
  };
  if (spec.lamports) {
    const destIdx = keys.indexOf(o.destinationWallet);
    if (destIdx < 0) return "destination";
    const gained = Number(meta.postBalances?.[destIdx] ?? 0) - Number(meta.preBalances?.[destIdx] ?? 0);
    if (gained !== o.amount) return gained === 0 ? "destination" : "amount";
  } else {
    const ata = deriveAta(o.destinationWallet, spec.mint);
    const destDelta = deltaOf((e, key) => key === ata);
    if (destDelta !== o.amount) {
      const mintMoved = deltaOf((e) => e.mint === spec.mint) !== 0 ||
        [...preByIndex.values(), ...postByIndex.values()].some((e: any) => e.mint === spec.mint);
      if (!mintMoved) return "mint";
      if (destDelta === 0) return "destination";
      return "amount";
    }
    const srcDelta = deltaOf((e) => e.owner === o.sourceWallet && e.mint === spec.mint);
    // SIM-off bundle fix (2026-11-02): the funder bound is "at least
    // the leg amount", not total-outflow equality. The ruled tip
    // shape is ONE transaction carrying TWO transfers of the same
    // mint (tip + fee, package §2.1); the sender's total outflow for
    // the mint is the sum of both legs, so an equality check refused
    // every genuine two-leg payment — a single-transfer assumption
    // the pass-2 fixtures never exercised. Exactness lives in the
    // destination delta above (bound to the leg's own ATA); this
    // check still refuses any transaction the session wallet did not
    // fund with at least the leg amount. Single-transfer surfaces
    // (post/join/prestige/commercial) are unaffected: their outflow
    // is exactly the leg amount, which satisfies the bound.
    if (srcDelta > -o.amount) return "source";
  }
  return null;
}
type VerifyResult = { ok: boolean; signature?: string; kind?: "refused" | "none-timely"; reason?: string };
const VERIFY_MESSAGES: Record<string, string> = {
  reference: "The payment reference was not attached to the transaction.",
  mint: "The wrong token was sent.",
  destination: "The payment went to the wrong destination.",
  amount: "The payment amount did not match the quote.",
  source: "The payment did not come from the session wallet.",
  failed: "The payment transaction failed on chain.",
};
// A5: the reference lookup pages past the first 25 — a real payment is
// found however much dust carries the same reference — and a candidate
// counts only when its blockTime lands inside [windowStart, windowEnd]
// (A4: timeliness is a property of the transaction, not of when the
// member happened to submit). A failed or wrong candidate never masks a
// later eligible one.
async function verifyTransferByReference(o: {
  reference: string; asset: string; amount: number; destinationWallet: string; sourceWallet: string;
  windowStart: number; windowEnd: number;
}): Promise<VerifyResult> {
  // blockTime is second-granular on chain; the session's created_at is
  // not. The window start floors to the creation second — a payment
  // made in the same second the session was created is timely.
  const startMs = Math.floor(o.windowStart / 1000) * 1000;
  const list: any[] = [];
  let before: string | undefined;
  let fullPage = -1; // a provider may cap pages below the requested limit;
  // a page shorter than the fullest page seen is the end — a capped page is not.
  for (let page = 0; page < 40; page++) {
    const opts: Record<string, unknown> = { limit: 100, commitment: "finalized" };
    if (before) opts.before = before;
    const sigs = await rpcCall("getSignaturesForAddress", [o.reference, opts]);
    if (!Array.isArray(sigs) || sigs.length === 0) break;
    list.push(...sigs);
    if (fullPage < 0) fullPage = sigs.length;
    const oldest = sigs[sigs.length - 1];
    before = oldest?.signature;
    const oldestMs = oldest?.blockTime != null ? Number(oldest.blockTime) * 1000 : null;
    if (oldestMs != null && oldestMs < startMs) break;
    if (sigs.length < fullPage) break;
  }
  let firstRefusal: string | null = null;
  for (const entry of list) {
    const btMs = entry?.blockTime != null ? Number(entry.blockTime) * 1000 : null;
    if (btMs == null) continue;            // cannot judge timeliness — not a candidate
    if (btMs < startMs) break;             // list is newest-first; the window is behind us
    if (btMs > o.windowEnd) continue;      // landed after the window — not this payment
    const tx = await rpcCall("getTransaction", [entry.signature, { encoding: "jsonParsed", commitment: "finalized", maxSupportedTransactionVersion: 0 }]);
    if (!tx) continue;
    const failure = checkTransfer(tx, o);
    if (!failure) return { ok: true, signature: entry.signature };
    if (!firstRefusal) firstRefusal = failure;
  }
  if (firstRefusal) return { ok: false, kind: "refused", reason: firstRefusal };
  return { ok: false, kind: "none-timely" };
}
async function verifyTransferInSignature(signature: string, o: {
  reference: string; asset: string; amount: number; destinationWallet: string; sourceWallet: string;
}): Promise<VerifyResult & { blockTimeMs?: number }> {
  const tx = await rpcCall("getTransaction", [signature, { encoding: "jsonParsed", commitment: "finalized", maxSupportedTransactionVersion: 0 }]);
  if (!tx) return { ok: false, kind: "refused", reason: "reference" };
  const failure = checkTransfer(tx, o);
  const blockTimeMs = tx.blockTime != null ? Number(tx.blockTime) * 1000 : undefined;
  if (failure) return { ok: false, kind: "refused", reason: failure, blockTimeMs };
  return { ok: true, signature, blockTimeMs };
}
// A7: claim-before-dispatch. Returns false when the signature is already
// spent (the caller turns that into the named replay refusal).
function claimSignature(signature: string, kind: string, refId: string): boolean {
  const res = sqlite.query(
    `INSERT INTO spent_signatures (signature, kind, ref_id, spent_at) VALUES (?, ?, ?, ?)
     ON CONFLICT(signature) DO NOTHING`,
  ).run(signature, kind, refId, Date.now());
  return Number(res.changes) === 1;
}
function releaseSignature(signature: string): void {
  sqlite.query(`DELETE FROM spent_signatures WHERE signature = ?`).run(signature);
}

// --- Join / prestige / commercial checkout surfaces ------------------------------
// These surfaces share post_sessions' invariants rather than inventing a
// second money machine: SIWS-bound wallet, stored destination, fixed
// amount, one-use reference, A4 blockTime timeliness, and an A7 registry
// claim in the same synchronous block that moves the paid state.
function memberCount(): number {
  const row = sqlite.query(`SELECT COUNT(*) AS n FROM members`).get() as { n: number };
  return Number(row.n);
}
function isPrestigeMember(member: any): boolean {
  const id = typeof member === "number" ? member : member?.id;
  if (!id) return false;
  return !!sqlite.query(`SELECT member_id FROM prestige_holders WHERE member_id = ?`).get(id);
}
function observedDestinationDelta(tx: any, asset: string, destinationWallet: string): number {
  const spec = ASSETS[asset];
  const meta = tx?.meta;
  if (!spec || !meta) return 0;
  const keys: string[] = (tx.transaction?.message?.accountKeys ?? []).map((k: any) =>
    typeof k === "string" ? k : k?.pubkey);
  if (spec.lamports) {
    const idx = keys.indexOf(destinationWallet);
    if (idx < 0) return 0;
    return Number(meta.postBalances?.[idx] ?? 0) - Number(meta.preBalances?.[idx] ?? 0);
  }
  const ata = deriveAta(destinationWallet, spec.mint);
  const pre = new Map<number, any>((meta.preTokenBalances ?? []).map((e: any) => [e.accountIndex, e]));
  const post = new Map<number, any>((meta.postTokenBalances ?? []).map((e: any) => [e.accountIndex, e]));
  let delta = 0;
  for (const i of new Set([...pre.keys(), ...post.keys()])) {
    const e = post.get(i) ?? pre.get(i);
    if (!e || keys[i] !== ata) continue;
    delta += Number(post.get(i)?.uiTokenAmount?.amount ?? 0) - Number(pre.get(i)?.uiTokenAmount?.amount ?? 0);
  }
  return delta;
}
type InspectResult = VerifyResult & { landedAmount?: number };
// Join needs one fact verifyTransferByReference deliberately does not
// return: when a payment demonstrably landed at the Relay but was
// refused (wrong amount, or a completion-time refusal), O1 forward-apply
// credits the amount that actually arrived. This scan keeps A5's paging
// and candidate rules, and reports that landed amount alongside the
// first refusal.
async function inspectReferencePayment(o: {
  reference: string; asset: string; amount: number; destinationWallet: string; sourceWallet: string;
  windowStart: number; windowEnd: number;
}): Promise<InspectResult> {
  const startMs = Math.floor(o.windowStart / 1000) * 1000;
  const list: any[] = [];
  let before: string | undefined;
  let fullPage = -1;
  for (let page = 0; page < 40; page++) {
    const opts: Record<string, unknown> = { limit: 100, commitment: "finalized" };
    if (before) opts.before = before;
    const sigs = await rpcCall("getSignaturesForAddress", [o.reference, opts]);
    if (!Array.isArray(sigs) || sigs.length === 0) break;
    list.push(...sigs);
    if (fullPage < 0) fullPage = sigs.length;
    const oldest = sigs[sigs.length - 1];
    before = oldest?.signature;
    const oldestMs = oldest?.blockTime != null ? Number(oldest.blockTime) * 1000 : null;
    if (oldestMs != null && oldestMs < startMs) break;
    if (sigs.length < fullPage) break;
  }
  let firstRefusal: string | null = null;
  for (const entry of list) {
    const btMs = entry?.blockTime != null ? Number(entry.blockTime) * 1000 : null;
    if (btMs == null) continue;
    if (btMs < startMs) break;
    if (btMs > o.windowEnd) continue;
    const tx = await rpcCall("getTransaction", [entry.signature, { encoding: "jsonParsed", commitment: "finalized", maxSupportedTransactionVersion: 0 }]);
    if (!tx) continue;
    const failure = checkTransfer(tx, o);
    if (!failure) return { ok: true, signature: entry.signature };
    const landedAmount = observedDestinationDelta(tx, o.asset, o.destinationWallet);
    if (landedAmount > 0)
      return { ok: false, kind: "refused", reason: failure, signature: entry.signature, landedAmount };
    if (!firstRefusal) firstRefusal = failure;
  }
  if (firstRefusal) return { ok: false, kind: "refused", reason: firstRefusal };
  return { ok: false, kind: "none-timely" };
}

type JoinSessionRow = {
  id: string; member_id: number | null; wallet: string; display_name: string | null; skr_name: string | null;
  asset: string; mint: string; amount: number; skr_price: number; destination: string;
  reference_pubkey: string | null; signature: string | null; status: string; created_at: number;
  pay_expires_at: number; verified_at: number | null; completed_at: number | null;
};
function getJoinSessionRow(id: string): JoinSessionRow | null {
  return sqlite.query(`SELECT * FROM join_sessions WHERE id = ?`).get(id) as JoinSessionRow | null;
}
function joinSessionPublic(s: JoinSessionRow) {
  return {
    id: s.id, memberId: s.member_id, wallet: s.wallet, asset: s.asset, mint: s.mint,
    amount: s.amount, skrPrice: s.skr_price, destination: s.destination,
    reference: s.reference_pubkey, signature: s.signature, status: s.status,
    createdAt: s.created_at, payExpiresAt: s.pay_expires_at,
    verifiedAt: s.verified_at, completedAt: s.completed_at,
  };
}
function createJoinSession(args: any): any {
  validateWrapperArgs("createJoinSession", args, {
    wallet: "string", displayName: "string?", skrName: "string?",
  });
  const wallet = args.wallet as string;
  if (!postSessionThrottleAllows(wallet))
    throw new Error("Too many payment sessions started. Wait a moment and try again.");
  if (memberByWallet(wallet)) throw new Error("This wallet is already a member. The join checkout is not offered twice.");
  if (memberCount() >= MEMBER_CAP)
    throw new Error("The kingdom is full — the member cap has been reached. Reading stays free. Nothing was charged.");
  const pending = sqlite.query(
    `SELECT COUNT(*) AS n FROM join_sessions WHERE wallet = ? AND status = 'pending'`,
  ).get(wallet) as { n: number };
  if (Number(pending.n) >= POST_SESSION_PENDING_CAP)
    throw new Error("Too many unpaid join sessions are open. Finish or let one expire before starting another.");
  if (!Number.isFinite(JOIN_PRICE_SKR) || JOIN_PRICE_SKR <= 0)
    throw new Error("The join price is not configured. Payments are unavailable.");
  const now = Date.now();
  const row: JoinSessionRow = {
    id: crypto.randomUUID(), member_id: null, wallet,
    display_name: args.displayName ?? args.skrName ?? wallet,
    skr_name: args.skrName ?? null,
    asset: "SKR", mint: ASSETS.SKR.mint, amount: Math.round(JOIN_PRICE_SKR * 1_000_000),
    skr_price: JOIN_PRICE_SKR, destination: treasuryWallet(), reference_pubkey: genReferencePubkey(),
    signature: null, status: "pending", created_at: now, pay_expires_at: now + POST_SESSION_TTL_MS,
    verified_at: null, completed_at: null,
  };
  sqlite.query(
    `INSERT INTO join_sessions (id, member_id, wallet, display_name, skr_name, asset, mint, amount,
       skr_price, destination, reference_pubkey, signature, status, created_at, pay_expires_at,
       verified_at, completed_at)
     VALUES (?, NULL, ?, ?, ?, 'SKR', ?, ?, ?, ?, ?, NULL, 'pending', ?, ?, NULL, NULL)`,
  ).run(row.id, row.wallet, row.display_name, row.skr_name, row.mint, row.amount, row.skr_price,
    row.destination, row.reference_pubkey, row.created_at, row.pay_expires_at);
  return joinSessionPublic(row);
}
function getJoinSession(args: any): any {
  validateWrapperArgs("getJoinSession", args, { wallet: "string", sessionId: "string?" });
  if (typeof args.sessionId === "string") {
    const s = getJoinSessionRow(args.sessionId);
    return { session: s && s.wallet === args.wallet ? joinSessionPublic(s) : null };
  }
  const rows = sqlite.query(
    `SELECT * FROM join_sessions WHERE wallet = ? ORDER BY created_at DESC LIMIT 20`,
  ).all(args.wallet) as JoinSessionRow[];
  return { sessions: rows.map(joinSessionPublic) };
}
function applyJoinForwardCredit(s: JoinSessionRow, signature: string, amount: number, reason: string): any {
  if (!claimSignature(signature, "join-forward-apply", s.id))
    throw new Error("This payment has already been used to pay for something else. Nothing was joined.");
  const now = Date.now();
  const existing = memberByWallet(s.wallet);
  try {
    sqlite.query(
      `INSERT INTO forward_apply_credits (id, wallet, member_id, signature, source_kind,
         source_session_id, asset, amount, destination, status, created_at, applied_at, note)
       VALUES (?, ?, ?, ?, 'join', ?, ?, ?, ?, 'recorded', ?, NULL, ?)`,
    ).run(crypto.randomUUID(), s.wallet, existing?.id ?? null, signature, s.id, s.asset, amount,
      s.destination, now, reason);
    const upd = sqlite.query(
      `UPDATE join_sessions SET status = 'forward_applied', signature = ?, verified_at = ?
       WHERE id = ? AND status = 'pending'`,
    ).run(signature, now, s.id);
    if (Number(upd.changes) !== 1) throw new Error("join state changed during forward-apply");
  } catch (e) {
    sqlite.query(`DELETE FROM forward_apply_credits WHERE signature = ?`).run(signature);
    releaseSignature(signature);
    throw new Error("That join is still being placed — wait a moment and try again. Nothing was joined twice.");
  }
  const settled = getJoinSessionRow(s.id)!;
  return {
    ok: false, forwardApplied: true, amountCredited: amount,
    error: "The payment landed but could not complete this join, so it was recorded as a forward-apply credit toward your next paid action. It was not pocketed and cannot be reused as a payment.",
    session: joinSessionPublic(settled),
  };
}
async function completeJoinSession(args: any): Promise<any> {
  validateWrapperArgs("completeJoinSession", args, { wallet: "string", sessionId: "string" });
  const s = getJoinSessionRow(String(args.sessionId));
  if (!s) throw new Error("That join session was not found. Nothing was joined.");
  if (s.wallet !== args.wallet)
    throw new Error("That join session belongs to a different wallet. Nothing was joined.");
  if (s.status === "completed")
    throw new Error("This join session has already been completed. Nothing was joined twice.");
  if (s.status === "forward_applied")
    throw new Error("This join payment was already recorded as a forward-apply credit. Nothing was joined twice.");
  if (s.status === "expired")
    throw new Error("That join session has expired. Nothing was joined.");
  if (s.status !== "pending")
    throw new Error("That join session cannot be used. Nothing was joined.");
  const v = await inspectReferencePayment({
    reference: s.reference_pubkey!, asset: s.asset, amount: s.amount,
    destinationWallet: s.destination, sourceWallet: s.wallet,
    windowStart: s.created_at, windowEnd: s.pay_expires_at,
  });
  const now = Date.now();
  if (!v.ok) {
    if (v.signature && v.landedAmount)
      return applyJoinForwardCredit(s, v.signature, v.landedAmount, `landed-but-refused:${v.reason ?? "unknown"}`);
    if (v.kind === "refused")
      throw new Error(`Payment verification refused: ${VERIFY_MESSAGES[v.reason ?? "reference"]} Nothing was joined.`);
    if (now > s.pay_expires_at) {
      sqlite.query(`UPDATE join_sessions SET status = 'expired' WHERE id = ? AND status = 'pending'`).run(s.id);
      throw new Error("That join session has expired. Nothing was joined.");
    }
    throw new Error("No finalized payment found for this join session yet. Wait for confirmation and try again. Nothing was joined.");
  }
  // Synchronous completion block (A3/A7): cap and membership are
  // re-read here, after the async verification, and the signature is
  // claimed before the member row exists.
  const existing = memberByWallet(s.wallet);
  if (existing || memberCount() >= MEMBER_CAP)
    return applyJoinForwardCredit(s, v.signature!, s.amount, existing ? "already-member-at-completion" : "member-cap-at-completion");
  if (!claimSignature(v.signature!, "join", s.id))
    throw new Error("This payment has already been used to pay for something else. Nothing was joined.");
  let insertedMemberId: number | null = null;
  try {
    const info = sqlite.query(
      `INSERT INTO members (wallet, display_name, created_at, skr_name, skr_verified_at, is_founding_member, founding_number)
       VALUES (?, ?, ?, ?, ?, 0, NULL)`,
    ).run(s.wallet, s.display_name ?? s.wallet, now, s.skr_name, s.skr_name ? now : null);
    insertedMemberId = Number(info.lastInsertRowid);
    const upd = sqlite.query(
      `UPDATE join_sessions SET status = 'completed', member_id = ?, signature = ?, verified_at = ?, completed_at = ?
       WHERE id = ? AND status = 'pending'`,
    ).run(insertedMemberId, v.signature!, now, now, s.id);
    if (Number(upd.changes) !== 1) throw new Error("join state changed during completion");
  } catch (e) {
    if (insertedMemberId != null)
      sqlite.query(`DELETE FROM members WHERE id = ?`).run(insertedMemberId);
    releaseSignature(v.signature!);
    throw new Error("That join is still being placed — wait a moment and try again. Nothing was joined twice.");
  }
  return { ok: true, joined: true, memberId: insertedMemberId, session: joinSessionPublic(getJoinSessionRow(s.id)!) };
}

type PrestigeSessionRow = {
  id: string; member_id: number; wallet: string; asset: string; mint: string; amount: number;
  skr_price: number; destination: string; reference_pubkey: string | null; signature: string | null;
  status: string; created_at: number; pay_expires_at: number; verified_at: number | null; completed_at: number | null;
};
function getPrestigeSessionRow(id: string): PrestigeSessionRow | null {
  return sqlite.query(`SELECT * FROM prestige_sessions WHERE id = ?`).get(id) as PrestigeSessionRow | null;
}
function prestigeSessionPublic(s: PrestigeSessionRow) {
  return {
    id: s.id, memberId: s.member_id, wallet: s.wallet, asset: s.asset, mint: s.mint,
    amount: s.amount, skrPrice: s.skr_price, destination: s.destination,
    reference: s.reference_pubkey, signature: s.signature, status: s.status,
    createdAt: s.created_at, payExpiresAt: s.pay_expires_at,
    verifiedAt: s.verified_at, completedAt: s.completed_at,
    prestige: isPrestigeMember(s.member_id),
  };
}
function prestigeHolderCount(): number {
  const row = sqlite.query(`SELECT COUNT(*) AS n FROM prestige_holders`).get() as { n: number };
  return Number(row.n);
}
function prestigeReservedCount(): number {
  const row = sqlite.query(
    `SELECT COUNT(*) AS n FROM prestige_sessions WHERE status = 'pending' AND pay_expires_at > ?`,
  ).get(Date.now()) as { n: number };
  return prestigeHolderCount() + Number(row.n);
}
function createPrestigeSession(args: any): any {
  validateWrapperArgs("createPrestigeSession", args, { wallet: "string" });
  const wallet = args.wallet as string;
  if (!postSessionThrottleAllows(wallet))
    throw new Error("Too many payment sessions started. Wait a moment and try again.");
  const member = memberByWallet(wallet);
  if (!member) throw new Error("Register before buying prestige.");
  if (isPrestigeMember(member)) throw new Error("This member already holds prestige. The prestige checkout is not offered twice.");
  if (prestigeReservedCount() >= PRESTIGE_CAP)
    throw new Error("All prestige places are taken or reserved. Nothing was charged.");
  const pending = sqlite.query(
    `SELECT COUNT(*) AS n FROM prestige_sessions WHERE member_id = ? AND status = 'pending'`,
  ).get(member.id) as { n: number };
  if (Number(pending.n) >= POST_SESSION_PENDING_CAP)
    throw new Error("Too many unpaid prestige sessions are open. Finish or let one expire before starting another.");
  if (!Number.isFinite(PRESTIGE_PRICE_SKR) || PRESTIGE_PRICE_SKR <= 0)
    throw new Error("The prestige price is not configured. Payments are unavailable.");
  const now = Date.now();
  const row: PrestigeSessionRow = {
    id: crypto.randomUUID(), member_id: member.id, wallet, asset: "SKR", mint: ASSETS.SKR.mint,
    amount: Math.round(PRESTIGE_PRICE_SKR * 1_000_000), skr_price: PRESTIGE_PRICE_SKR,
    destination: treasuryWallet(), reference_pubkey: genReferencePubkey(), signature: null,
    status: "pending", created_at: now, pay_expires_at: now + POST_SESSION_TTL_MS,
    verified_at: null, completed_at: null,
  };
  sqlite.query(
    `INSERT INTO prestige_sessions (id, member_id, wallet, asset, mint, amount, skr_price, destination,
       reference_pubkey, signature, status, created_at, pay_expires_at, verified_at, completed_at)
     VALUES (?, ?, ?, 'SKR', ?, ?, ?, ?, ?, NULL, 'pending', ?, ?, NULL, NULL)`,
  ).run(row.id, row.member_id, row.wallet, row.mint, row.amount, row.skr_price, row.destination,
    row.reference_pubkey, row.created_at, row.pay_expires_at);
  return prestigeSessionPublic(row);
}
function getPrestigeSession(args: any): any {
  validateWrapperArgs("getPrestigeSession", args, { wallet: "string", sessionId: "string?" });
  const member = memberByWallet(args.wallet);
  if (!member) return { sessions: [], prestige: false };
  if (typeof args.sessionId === "string") {
    const s = getPrestigeSessionRow(args.sessionId);
    return { session: s && s.member_id === member.id ? prestigeSessionPublic(s) : null, prestige: isPrestigeMember(member) };
  }
  const rows = sqlite.query(
    `SELECT * FROM prestige_sessions WHERE member_id = ? ORDER BY created_at DESC LIMIT 20`,
  ).all(member.id) as PrestigeSessionRow[];
  return { sessions: rows.map(prestigeSessionPublic), prestige: isPrestigeMember(member) };
}
async function completePrestigeSession(args: any): Promise<any> {
  validateWrapperArgs("completePrestigeSession", args, { wallet: "string", sessionId: "string" });
  const s = getPrestigeSessionRow(String(args.sessionId));
  if (!s) throw new Error("That prestige session was not found. Nothing was granted.");
  if (s.wallet !== args.wallet)
    throw new Error("That prestige session belongs to a different wallet. Nothing was granted.");
  if (s.status === "completed")
    throw new Error("This prestige session has already been completed. Nothing was granted twice.");
  if (s.status === "expired")
    throw new Error("That prestige session has expired. Nothing was granted.");
  if (s.status !== "pending")
    throw new Error("That prestige session cannot be used. Nothing was granted.");
  const v = await verifyTransferByReference({
    reference: s.reference_pubkey!, asset: s.asset, amount: s.amount,
    destinationWallet: s.destination, sourceWallet: s.wallet,
    windowStart: s.created_at, windowEnd: s.pay_expires_at,
  });
  const now = Date.now();
  if (!v.ok) {
    if (v.kind === "refused")
      throw new Error(`Payment verification refused: ${VERIFY_MESSAGES[v.reason ?? "reference"]} Nothing was granted.`);
    if (now > s.pay_expires_at) {
      sqlite.query(`UPDATE prestige_sessions SET status = 'expired' WHERE id = ? AND status = 'pending'`).run(s.id);
      throw new Error("That prestige session has expired. Nothing was granted.");
    }
    throw new Error("No finalized payment found for this prestige session yet. Wait for confirmation and try again. Nothing was granted.");
  }
  // Atomic claim-once completion: holder and cap are re-read in this
  // synchronous block, then the registry claim and holder insert move
  // together. A cap that filled during verification refuses here,
  // before any claim is recorded.
  if (isPrestigeMember(s.member_id))
    throw new Error("This member already holds prestige. Nothing was granted twice.");
  if (prestigeHolderCount() >= PRESTIGE_CAP)
    throw new Error("All prestige places are filled. Nothing was granted.");
  if (!claimSignature(v.signature!, "prestige", s.id))
    throw new Error("This payment has already been used to pay for something else. Nothing was granted.");
  try {
    sqlite.query(
      `INSERT INTO prestige_holders (member_id, wallet, session_id, signature, granted_at)
       VALUES (?, ?, ?, ?, ?)`,
    ).run(s.member_id, s.wallet, s.id, v.signature!, now);
    const upd = sqlite.query(
      `UPDATE prestige_sessions SET status = 'completed', signature = ?, verified_at = ?, completed_at = ?
       WHERE id = ? AND status = 'pending'`,
    ).run(v.signature!, now, now, s.id);
    if (Number(upd.changes) !== 1) throw new Error("prestige state changed during completion");
  } catch (e) {
    sqlite.query(`DELETE FROM prestige_holders WHERE session_id = ?`).run(s.id);
    releaseSignature(v.signature!);
    throw new Error("That prestige purchase is still being placed — wait a moment and try again. Nothing was granted twice.");
  }
  return { ok: true, prestige: true, session: prestigeSessionPublic(getPrestigeSessionRow(s.id)!) };
}

type CommercialSessionRow = {
  id: string; member_id: number; wallet: string; asset: string; mint: string; amount: number;
  usd_cents: number; destination: string; reference_pubkey: string | null; signature: string | null;
  status: string; created_at: number; pay_expires_at: number; verified_at: number | null;
  dispatched_at: number | null; consume_expires_at: number | null; result_ref: string | null;
  dispatch_title: string | null; dispatch_body: string | null;
};
function getCommercialSessionRow(id: string): CommercialSessionRow | null {
  return sqlite.query(`SELECT * FROM commercial_sessions WHERE id = ?`).get(id) as CommercialSessionRow | null;
}
function commercialSessionPublic(s: CommercialSessionRow) {
  return {
    id: s.id, kind: "commercial", memberId: s.member_id, wallet: s.wallet, asset: s.asset,
    mint: s.mint, amount: s.amount, usdCents: s.usd_cents, destination: s.destination,
    reference: s.reference_pubkey, signature: s.signature, status: s.status,
    createdAt: s.created_at, payExpiresAt: s.pay_expires_at, verifiedAt: s.verified_at,
    consumeExpiresAt: s.consume_expires_at, resultRef: s.result_ref,
  };
}
async function createCommercialSession(args: any): Promise<any> {
  validateWrapperArgs("createCommercialSession", args, { wallet: "string", asset: "string?" });
  const wallet = args.wallet as string;
  if (!postSessionThrottleAllows(wallet))
    throw new Error("Too many payment sessions started. Wait a moment and try again.");
  const asset = args.asset ?? "SKR";
  if (!ASSETS[asset]) throw new Error(`Unsupported payment asset ${asset}.`);
  const member = memberByWallet(wallet);
  if (!member) throw new Error("Register before starting a commercial thread.");
  const pending = sqlite.query(
    `SELECT COUNT(*) AS n FROM commercial_sessions WHERE member_id = ? AND status = 'pending'`,
  ).get(member.id) as { n: number };
  if (Number(pending.n) >= POST_SESSION_PENDING_CAP)
    throw new Error("Too many unpaid commercial sessions are open. Finish or let one expire before starting another.");
  const amount = await quoteUsdToBaseUnits(asset, COMMERCIAL_PRICE_CENTS);
  const now = Date.now();
  const row: CommercialSessionRow = {
    id: crypto.randomUUID(), member_id: member.id, wallet, asset, mint: ASSETS[asset].mint,
    amount, usd_cents: COMMERCIAL_PRICE_CENTS, destination: treasuryWallet(),
    reference_pubkey: genReferencePubkey(), signature: null, status: "pending",
    created_at: now, pay_expires_at: now + POST_SESSION_TTL_MS, verified_at: null,
    dispatched_at: null, consume_expires_at: null, result_ref: null,
    dispatch_title: null, dispatch_body: null,
  };
  sqlite.query(
    `INSERT INTO commercial_sessions (id, member_id, wallet, asset, mint, amount, usd_cents, destination,
       reference_pubkey, signature, status, created_at, pay_expires_at, verified_at, dispatched_at,
       consume_expires_at, result_ref, dispatch_title, dispatch_body)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, 'pending', ?, ?, NULL, NULL, NULL, NULL, NULL, NULL)`,
  ).run(row.id, row.member_id, row.wallet, row.asset, row.mint, row.amount, row.usd_cents,
    row.destination, row.reference_pubkey, row.created_at, row.pay_expires_at);
  return commercialSessionPublic(row);
}
function getCommercialSession(args: any): any {
  validateWrapperArgs("getCommercialSession", args, { wallet: "string", sessionId: "string?" });
  const member = memberByWallet(args.wallet);
  if (!member) return { sessions: [] };
  if (typeof args.sessionId === "string") {
    const s = getCommercialSessionRow(args.sessionId);
    return { session: s && s.member_id === member.id ? commercialSessionPublic(s) : null };
  }
  const rows = sqlite.query(
    `SELECT * FROM commercial_sessions WHERE member_id = ? ORDER BY created_at DESC LIMIT 20`,
  ).all(member.id) as CommercialSessionRow[];
  return { sessions: rows.map(commercialSessionPublic) };
}
async function commercialGatePreDispatch(args: any): Promise<{ sessionId: string | null; args: any }> {
  const { commercialSessionId, postSessionId, ...cleanArgs } = args ?? {};
  if (postSessionId)
    throw new Error("A commercial thread pays its commercial session instead of a post session — not both. Nothing was posted.");
  if (typeof commercialSessionId !== "string" || !commercialSessionId)
    throw new Error("That commercial session was not found. Nothing was posted.");
  const s = getCommercialSessionRow(commercialSessionId);
  if (!s) throw new Error("That commercial session was not found. Nothing was posted.");
  const member = memberByWallet(args.wallet);
  if (!member) return { sessionId: null, args: cleanArgs }; // bundle refuses non-members itself
  // M2 VIP gate: a commercial session pays for a PUBLIC thread. It is
  // never a way into the holders-only board — refused before any
  // verification, so the session stays unconsumed.
  const vipId = vipCategoryId();
  if (vipId != null && Number(args.categoryId) === vipId)
    throw new Error("Commercial threads run in the public forum, not the VIP board. Nothing was posted.");
  if (s.member_id !== member.id || s.wallet !== args.wallet)
    throw new Error("That commercial session belongs to a different wallet. Nothing was posted.");
  if (!s.destination)
    throw new Error("That commercial session predates destination binding and cannot be verified. Start a new session. Nothing was posted.");
  const now = Date.now();
  if (s.status === "completed")
    throw new Error("This commercial session has already been used. Nothing was posted.");
  if (s.status === "expired")
    throw new Error("That commercial session has expired. Nothing was posted.");
  if (s.status === "dispatched")
    throw new Error("That commercial post is still being placed — wait a moment and try again. Nothing was posted twice.");
  if (s.status === "pending") {
    const v = await verifyTransferByReference({
      reference: s.reference_pubkey!, asset: s.asset, amount: s.amount,
      destinationWallet: s.destination, sourceWallet: s.wallet,
      windowStart: s.created_at, windowEnd: s.pay_expires_at,
    });
    if (!v.ok) {
      if (v.kind === "refused")
        throw new Error(`Payment verification refused: ${VERIFY_MESSAGES[v.reason ?? "reference"]} Nothing was posted.`);
      if (now > s.pay_expires_at) {
        sqlite.query(`UPDATE commercial_sessions SET status = 'expired' WHERE id = ? AND status = 'pending'`).run(s.id);
        throw new Error("That commercial session has expired. Nothing was posted.");
      }
      throw new Error("No finalized payment found for this commercial session yet. Wait for confirmation and try again. Nothing was posted.");
    }
    if (!claimSignature(v.signature!, "commercial", s.id))
      throw new Error("This payment has already been used to pay for something else. Nothing was posted.");
    const upd = sqlite.query(
      `UPDATE commercial_sessions SET status = 'verified', signature = ?, verified_at = ?, consume_expires_at = ?
       WHERE id = ? AND status = 'pending'`,
    ).run(v.signature!, now, now + CONSUME_WINDOW_MS, s.id);
    if (Number(upd.changes) !== 1) {
      releaseSignature(v.signature!);
      throw new Error("That commercial post is still being placed — wait a moment and try again. Nothing was posted twice.");
    }
    s.status = "verified"; s.signature = v.signature!;
    s.verified_at = now; s.consume_expires_at = now + CONSUME_WINDOW_MS;
  }
  if (s.status === "verified") {
    if (s.consume_expires_at != null && now > s.consume_expires_at) {
      sqlite.query(`UPDATE commercial_sessions SET status = 'expired' WHERE id = ? AND status = 'verified'`).run(s.id);
      throw new Error("That commercial session has expired. Nothing was posted.");
    }
    const claim = sqlite.query(
      `UPDATE commercial_sessions SET status = 'dispatched', dispatched_at = ?, dispatch_title = ?, dispatch_body = ?
       WHERE id = ? AND status = 'verified'`,
    ).run(now, String(args.title ?? ""), String(args.body ?? ""), s.id);
    if (Number(claim.changes) !== 1)
      throw new Error("That commercial post is still being placed — wait a moment and try again. Nothing was posted twice.");
    return { sessionId: s.id, args: cleanArgs };
  }
  throw new Error("That commercial session cannot be used. Nothing was posted.");
}
function commercialGateRelease(sessionId: string): void {
  sqlite.query(
    `UPDATE commercial_sessions SET status = 'verified' WHERE id = ? AND status = 'dispatched'`,
  ).run(sessionId);
}
function commercialGateSettle(sessionId: string, out: any): void {
  if (!out || out.ok === false || out.id == null) { commercialGateRelease(sessionId); return; }
  const s = getCommercialSessionRow(sessionId);
  if (!s) return;
  sqlite.query(
    `INSERT INTO commercial_threads (thread_id, session_id, member_id, marked_at)
     VALUES (?, ?, ?, ?) ON CONFLICT(thread_id) DO NOTHING`,
  ).run(Number(out.id), sessionId, s.member_id, Date.now());
  sqlite.query(
    `UPDATE commercial_sessions SET status = 'completed', result_ref = ? WHERE id = ? AND status = 'dispatched'`,
  ).run(String(out.id), sessionId);
}
function reconcileDispatchedCommercialSessions(): void {
  const rows = sqlite.query(`SELECT * FROM commercial_sessions WHERE status = 'dispatched'`).all() as CommercialSessionRow[];
  for (const s of rows) {
    const candidates = sqlite.query(`SELECT id, title, body FROM threads WHERE author_id = ? AND created_at >= ?`)
      .all(s.member_id, (s.dispatched_at ?? s.created_at) - 5_000) as { id: number; title: string; body: string }[];
    const hit = candidates.find((x) => normalizedText(x.title) === normalizedText(s.dispatch_title) &&
      normalizedText(x.body) === normalizedText(s.dispatch_body));
    if (hit) {
      sqlite.query(
        `INSERT INTO commercial_threads (thread_id, session_id, member_id, marked_at)
         VALUES (?, ?, ?, ?) ON CONFLICT(thread_id) DO NOTHING`,
      ).run(hit.id, s.id, s.member_id, Date.now());
      sqlite.query(
        `UPDATE commercial_sessions SET status = 'completed', result_ref = ? WHERE id = ? AND status = 'dispatched'`,
      ).run(String(hit.id), s.id);
    } else if (s.consume_expires_at != null && s.consume_expires_at > Date.now()) {
      commercialGateRelease(s.id);
    } else {
      sqlite.query(`UPDATE commercial_sessions SET status = 'expired' WHERE id = ? AND status = 'dispatched'`).run(s.id);
    }
  }
  if (rows.length) console.log(`commercial-session boot reconciliation: ${rows.length} dispatched session(s) settled`);
}
function commercialSessionSweep(): void {
  sqlite.query(
    `UPDATE commercial_sessions SET status = 'expired' WHERE status = 'verified' AND consume_expires_at IS NOT NULL AND consume_expires_at < ?`,
  ).run(Date.now());
}

// --- M2 read models (R7–R10) -----------------------------------------------------
// The bundle computes none of these: its createReply never touches
// threads.updated_at (the River's ordering key), no read returns
// zero-reply threads, and no per-member last-seen state exists. All
// four live wrapper-side, on the wrapper's own handle, following the
// established precedents (post-dispatch hooks, wrapper-native actions,
// the moderation-feed transform).
function vipCategoryId(): number | null {
  const row = sqlite.query(`SELECT id FROM categories WHERE slug = ?`)
    .get(VIP_BOARD_SLUG) as { id: number } | null;
  return row ? Number(row.id) : null;
}
// The wrapper's commercial marker of record (commercial_threads,
// written at commercial-gate settle) as a lookup set for the read
// transforms. The flag a client renders comes only from this table —
// never from anything the client asserts.
function commercialThreadIdSet(): Set<number> {
  const rows = sqlite.query(`SELECT thread_id FROM commercial_threads`)
    .all() as { thread_id: number }[];
  return new Set(rows.map((r) => Number(r.thread_id)));
}
// Server-derived display amount for a base-unit quantity: BigInt
// arithmetic only, trailing fractional zeros trimmed. Money display
// is computed here so no client ever guesses a conversion.
function formatBaseUnits(amount: unknown, decimals: number): string {
  let v: bigint;
  try { v = BigInt(String(amount)); } catch { return String(amount); }
  const neg = v < 0n;
  if (neg) v = -v;
  const base = 10n ** BigInt(decimals);
  const whole = v / base;
  const frac = v % base;
  let text = whole.toString();
  if (frac !== 0n)
    text += "." + frac.toString().padStart(decimals, "0").replace(/0+$/, "");
  return neg ? `-${text}` : text;
}
// R7: a successful reply bumps its thread's last-activity to the
// reply's own timestamp. The MAX() guard makes the write monotone — a
// later hook can never move a thread backwards. toggleLike never comes
// near this code: reactions do not bump, by rule (R7) and by wiring.
function replyBumpPostDispatch(args: any, out: any): void {
  if (!out || out.ok === false) return;
  const threadId = Number(args?.threadId);
  if (!Number.isFinite(threadId) || threadId <= 0) return;
  const latest = sqlite.query(`SELECT MAX(created_at) AS c FROM replies WHERE thread_id = ?`)
    .get(threadId) as { c: number | null } | null;
  if (latest?.c == null) return;
  sqlite.query(`UPDATE threads SET updated_at = MAX(updated_at, ?) WHERE id = ?`)
    .run(Number(latest.c), threadId);
}
// R8: the Unanswered Rail. Computed server-side from the same
// visibility predicate listForum serves (not hidden, public audience,
// visible replies only in the count) minus the VIP board, longest
// waiting first. A thread leaves the rail the moment its first visible
// reply exists — the NOT EXISTS is evaluated per call, so entry and
// exit are the same query, never a cached state.
function listUnanswered(args: any): any {
  validateWrapperArgs("listUnanswered", args, { wallet: "string?" });
  const vipId = vipCategoryId();
  const rows = sqlite.query(
    `SELECT t.id, t.category_id, t.title, t.body, t.author_id, t.is_pinned, t.is_locked,
            t.is_seed, t.is_announcement, t.created_at,
            c.name AS category_name, m.display_name AS author_name
     FROM threads t
     LEFT JOIN categories c ON c.id = t.category_id
     LEFT JOIN members m ON m.id = t.author_id
     WHERE t.is_hidden = 0 AND t.audience = 'public'
       AND NOT EXISTS (SELECT 1 FROM replies r WHERE r.thread_id = t.id AND r.is_hidden = 0)
       AND (? IS NULL OR t.category_id <> ?)
     ORDER BY t.created_at ASC, t.id ASC`,
  ).all(vipId, vipId) as any[];
  return {
    threads: rows.map((t) => ({
      id: Number(t.id), categoryId: Number(t.category_id), categoryName: t.category_name ?? "Forum",
      title: t.title, body: t.body,
      authorName: t.author_id ? (t.author_name ?? "Member") : "ORE Guide",
      isPinned: !!t.is_pinned, isLocked: !!t.is_locked, isSeed: !!t.is_seed,
      isAnnouncement: !!t.is_announcement, replies: 0,
      createdAt: new Date(Number(t.created_at)).toISOString(),
    })),
    total: rows.length,
  };
}
// R9: the Catch-Up. The read is a pure function of (member, watermark):
// what became visible TO THIS MEMBER since the watermark — new threads
// (others', in surfaces the member may open: public, the VIP board for
// holders, the founder lounge for founders), replies to the member's
// threads, tips received, and pin changes derived from the pin records
// (a deferred pin is not an activation; its activation rewrites
// starts_at and is seen then). The response carries `through`, the
// read's own server time: the client displays, then advances with that
// exact value, so an item landing during display is never skipped.
function catchUpWatermark(member: Record<string, any>): number {
  const row = sqlite.query(`SELECT watermark FROM catchup_watermarks WHERE member_id = ?`)
    .get(member.id) as { watermark: number } | null;
  return row ? Number(row.watermark) : Number(member.created_at);
}
function getCatchUp(args: any): any {
  validateWrapperArgs("getCatchUp", args, { wallet: "string" });
  const member = memberByWallet(args.wallet);
  if (!member) throw new Error("Register before opening your catch-up.");
  const wm = catchUpWatermark(member);
  const through = Date.now();
  const vipId = vipCategoryId();
  const canVip = isPrestigeMember(member) ? 1 : 0;
  const canFounder = isFounderMember(member) ? 1 : 0;
  const newThreads = sqlite.query(
    `SELECT t.id, t.title, t.category_id, t.created_at,
            c.name AS category_name, m.display_name AS author_name
     FROM threads t
     LEFT JOIN categories c ON c.id = t.category_id
     LEFT JOIN members m ON m.id = t.author_id
     WHERE t.is_hidden = 0 AND t.created_at > ? AND t.author_id <> ?
       AND (t.audience = 'public' OR (t.audience = 'founder' AND ? = 1))
       AND (? IS NULL OR t.category_id <> ? OR ? = 1)
     ORDER BY t.created_at ASC, t.id ASC`,
  ).all(wm, member.id, canFounder, vipId, vipId, canVip) as any[];
  const repliesToMine = sqlite.query(
    `SELECT r.id, r.thread_id, r.created_at, t.title AS thread_title,
            m.display_name AS author_name
     FROM replies r
     JOIN threads t ON t.id = r.thread_id
     LEFT JOIN members m ON m.id = r.author_id
     WHERE t.author_id = ? AND r.author_id <> ? AND r.is_hidden = 0 AND r.created_at > ?
     ORDER BY r.created_at ASC, r.id ASC`,
  ).all(member.id, member.id, wm) as any[];
  const tipsReceived = sqlite.query(
    `SELECT tp.id, tp.thread_id, tp.reply_id, tp.asset, tp.amount_base_units, tp.created_at,
            m.display_name AS sender_name
     FROM tips tp
     LEFT JOIN members m ON m.id = tp.sender_member_id
     WHERE tp.recipient_member_id = ? AND tp.created_at > ?
     ORDER BY tp.created_at ASC, tp.id ASC`,
  ).all(member.id, wm) as any[];
  const pinActivated = sqlite.query(
    `SELECT p.id, p.thread_id, p.starts_at, p.ends_at, t.title AS thread_title
     FROM paid_pins p LEFT JOIN threads t ON t.id = p.thread_id
     WHERE p.status IN ('active', 'expired') AND p.starts_at > ?
     ORDER BY p.starts_at ASC, p.id ASC`,
  ).all(wm) as any[];
  const pinExpired = sqlite.query(
    `SELECT p.id, p.thread_id, p.starts_at, p.ends_at, t.title AS thread_title
     FROM paid_pins p LEFT JOIN threads t ON t.id = p.thread_id
     WHERE p.status IN ('active', 'expired') AND p.ends_at > ? AND p.ends_at <= ?
     ORDER BY p.ends_at ASC, p.id ASC`,
  ).all(wm, through) as any[];
  const pinChanges = [
    ...pinActivated.map((p: any) => ({
      type: "activated", pinId: Number(p.id), threadId: Number(p.thread_id),
      threadTitle: p.thread_title ?? null,
      startsAt: new Date(Number(p.starts_at)).toISOString(),
      endsAt: new Date(Number(p.ends_at)).toISOString(),
    })),
    ...pinExpired.map((p: any) => ({
      type: "expired", pinId: Number(p.id), threadId: Number(p.thread_id),
      threadTitle: p.thread_title ?? null,
      startsAt: new Date(Number(p.starts_at)).toISOString(),
      endsAt: new Date(Number(p.ends_at)).toISOString(),
    })),
  ].sort((a, b) => String(a.type === "activated" ? a.startsAt : a.endsAt)
    .localeCompare(String(b.type === "activated" ? b.startsAt : b.endsAt)));
  const counts = {
    newThreads: newThreads.length, repliesToMine: repliesToMine.length,
    tipsReceived: tipsReceived.length, pinChanges: pinChanges.length,
  };
  return {
    watermark: wm, through,
    hasNews: counts.newThreads + counts.repliesToMine + counts.tipsReceived + counts.pinChanges > 0,
    counts,
    newThreads: newThreads.map((t) => ({
      id: Number(t.id), title: t.title, categoryId: Number(t.category_id),
      categoryName: t.category_name ?? "Forum", authorName: t.author_name ?? "Member",
      createdAt: new Date(Number(t.created_at)).toISOString(),
    })),
    repliesToMine: repliesToMine.map((r) => ({
      id: Number(r.id), threadId: Number(r.thread_id), threadTitle: r.thread_title,
      authorName: r.author_name ?? "Member",
      createdAt: new Date(Number(r.created_at)).toISOString(),
    })),
    tipsReceived: tipsReceived.map((tp) => {
      // Display fields are derived HERE, from the asset table's
      // decimals, in integer arithmetic — a raw base-units string
      // alone left the client unable to render the amount honestly.
      // An asset outside the table serves nulls rather than a guess.
      const spec = ASSETS[tp.asset as string];
      return {
        id: Number(tp.id), threadId: tp.thread_id != null ? Number(tp.thread_id) : null,
        replyId: tp.reply_id != null ? Number(tp.reply_id) : null,
        asset: tp.asset, amountBaseUnits: String(tp.amount_base_units),
        decimals: spec ? spec.decimals : null,
        displayAmount: spec ? formatBaseUnits(tp.amount_base_units, spec.decimals) : null,
        senderName: tp.sender_name ?? "Member",
        createdAt: new Date(Number(tp.created_at)).toISOString(),
      };
    }),
    pinChanges,
  };
}
// The watermark moves ONLY here, and only forward, to the `through`
// the client's read issued (clamped to the server's clock) — an
// advance with no through means "caught up to now". A repeated advance
// with the same through is a no-op: exactly once per open-with-news.
function advanceCatchUp(args: any): any {
  validateWrapperArgs("advanceCatchUp", args, { wallet: "string", through: "int?" });
  const member = memberByWallet(args.wallet);
  if (!member) throw new Error("Register before opening your catch-up.");
  const now = Date.now();
  const current = catchUpWatermark(member);
  let target = typeof args.through === "number" ? args.through : now;
  if (target > now) target = now;
  if (target <= current) return { watermark: current, advanced: false };
  sqlite.query(
    `INSERT INTO catchup_watermarks (member_id, watermark, updated_at) VALUES (?, ?, ?)
     ON CONFLICT(member_id) DO UPDATE SET watermark = excluded.watermark, updated_at = excluded.updated_at`,
  ).run(member.id, target, now);
  return { watermark: target, advanced: true };
}
// The listForum read transform (the moderation-feed precedent),
// applied to every payload so the client's contract is stated by the
// host, never inferred: each thread gains isCommercial from the
// wrapper's marker table; each category gains isVip from the
// wrapper's own slug config (the client must not key locked-ness off
// a slug it merely assumes); the payload gains callerPrestige — the
// read identity discipline's verdict (true only for a SIWS-VERIFIED
// holder; an asserted wallet, a failed proof, and a verified
// non-holder all read false). The M2 VIP filter rides the same pass:
// anyone who is not a verified holder has the board's threads
// withheld (its category and count stay listed — the locked board).
function forumReadTransform(out: any, readPrestige: boolean): any {
  const vipId = vipCategoryId();
  const marked = commercialThreadIdSet();
  const threads = (out.threads as any[]).map((t) => ({
    ...t, isCommercial: marked.has(Number(t.id)),
  }));
  const categories = Array.isArray(out.categories)
    ? (out.categories as any[]).map((c) => ({
        ...c, isVip: vipId != null && Number(c.id) === vipId,
      }))
    : out.categories;
  return {
    ...out,
    threads: readPrestige || vipId == null
      ? threads
      : threads.filter((t: any) => Number(t.categoryId) !== vipId),
    categories,
    callerPrestige: readPrestige,
  };
}


// --- Post sessions (wrapper-native) ----------------------------------------------
type PostSessionRow = {
  id: string; member_id: number; wallet: string; kind: string; audience: string;
  asset: string; amount: number; usd_cents: number | null; destination: string | null; reference_pubkey: string | null;
  signature: string | null; status: string; created_at: number; pay_expires_at: number;
  verified_at: number | null; dispatched_at: number | null; consume_expires_at: number | null;
  result_ref: string | null; dispatch_title: string | null; dispatch_body: string | null;
};
function getPostSessionRow(id: string): PostSessionRow | null {
  return sqlite.query(`SELECT * FROM post_sessions WHERE id = ?`).get(id) as PostSessionRow | null;
}
function postSessionPublic(s: PostSessionRow) {
  return {
    id: s.id, kind: s.kind, audience: s.audience, asset: s.asset, amount: s.amount,
    usdCents: s.usd_cents,
    destination: s.destination, reference: s.reference_pubkey, signature: s.signature,
    status: s.status, createdAt: s.created_at, payExpiresAt: s.pay_expires_at,
    verifiedAt: s.verified_at, consumeExpiresAt: s.consume_expires_at, resultRef: s.result_ref,
  };
}
function validateWrapperArgs(action: string, args: any, spec: Record<string, "string" | "int" | "bool" | "string?">): void {
  for (const key of Object.keys(args ?? {})) {
    if (!(key in spec)) throw new Error(`Unexpected argument "${key}" for ${action}.`);
  }
  for (const [key, kind] of Object.entries(spec)) {
    const v = args?.[key];
    if (kind.endsWith("?")) { if (v == null) continue; }
    else if (v == null) throw new Error(`Argument "${key}" is required for ${action}.`);
    const base = kind.replace("?", "");
    if (base === "string" && typeof v !== "string") throw new Error(`Argument "${key}" must be a string.`);
    if (base === "int" && !(typeof v === "number" && Number.isInteger(v))) throw new Error(`Argument "${key}" must be an integer.`);
    if (base === "bool" && typeof v !== "boolean") throw new Error(`Argument "${key}" must be a boolean.`);
  }
}
const postSessionBuckets = new Map<string, { tokens: number; updated: number }>();
function postSessionThrottleAllows(wallet: string): boolean {
  const key = `post-session:${wallet}`;
  const now = Date.now();
  const b = postSessionBuckets.get(key) ?? { tokens: 30, updated: now };
  b.tokens = Math.min(30, b.tokens + (now - b.updated) / 60_000);
  b.updated = now;
  if (b.tokens < 1) { postSessionBuckets.set(key, b); return false; }
  b.tokens -= 1; postSessionBuckets.set(key, b); return true;
}
async function createPostSession(args: any): Promise<any> {
  // args.wallet is the VERIFIED SIWS identity (B2) — the session binds
  // to it at creation (A11), never to a client-asserted string.
  validateWrapperArgs("createPostSession", args, {
    wallet: "string", kind: "string", audience: "string?", asset: "string?",
  });
  const wallet = args.wallet as string;
  if (!postSessionThrottleAllows(wallet))
    throw new Error("Too many payment sessions started. Wait a moment and try again.");
  const kind = args.kind;
  if (kind !== "thread" && kind !== "reply") throw new Error("Post session kind must be \"thread\" or \"reply\".");
  // Split amendment (owner, 2026-11-02 12:02): the $3.50 fee applies
  // to THREAD creation only — replies are free for members, so a
  // reply session is a thing that no longer exists to sell. Refused
  // by name, before any exemption answer: there is nothing to exempt.
  if (kind === "reply")
    throw new Error("Replies are free for members — no payment session is created for a reply.");
  const audience = args.audience ?? "public";
  if (audience !== "public" && audience !== "founder") throw new Error("Unknown post audience.");
  const asset = args.asset ?? "SKR";
  if (!ASSETS[asset]) throw new Error(`Unsupported payment asset ${asset}.`);
  const member = memberByWallet(wallet);
  if (!member) throw new Error("Register before posting.");
  if (audience === "founder" && FOUNDER_AUDIENCE_MODE === "exempt" && isFounderMember(member))
    return { exempt: true, kind, audience, asset, note: "Founder posts are exempt from the post toll in this build." };
  // M2 prestige consumer (create side): a holder never starts a thread
  // session — the exemption is the wrapper's status read, and the gate
  // re-checks it at dispatch, so this answer is a courtesy, not a grant.
  if (kind === "thread" && isPrestigeMember(member))
    return { exempt: true, kind, audience, asset, note: "Prestige holders are exempt from the thread fee in this build." };
  const pending = sqlite.query(
    `SELECT COUNT(*) AS n FROM post_sessions WHERE member_id = ? AND status = 'pending'`,
  ).get(member.id) as { n: number };
  if (Number(pending.n) >= POST_SESSION_PENDING_CAP)
    throw new Error("Too many unpaid payment sessions are open. Finish or let one expire before starting another.");
  const usdCents = POST_PRICE_THREAD_CENTS; // kind is thread-only here — reply sessions are refused above
  const amount = await quoteUsdToBaseUnits(asset, usdCents);
  const destination = treasuryWallet(); // stored on the row (A2)
  const now = Date.now();
  const row: PostSessionRow = {
    id: crypto.randomUUID(), member_id: member.id, wallet, kind, audience, asset, amount,
    usd_cents: usdCents,
    destination, reference_pubkey: genReferencePubkey(), signature: null, status: "pending",
    created_at: now, pay_expires_at: now + POST_SESSION_TTL_MS, verified_at: null,
    dispatched_at: null, consume_expires_at: null, result_ref: null,
    dispatch_title: null, dispatch_body: null,
  };
  sqlite.query(
    `INSERT INTO post_sessions (id, member_id, wallet, kind, audience, asset, amount, usd_cents, destination,
       reference_pubkey, signature, status, created_at, pay_expires_at, verified_at, dispatched_at,
       consume_expires_at, result_ref, dispatch_title, dispatch_body)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, 'pending', ?, ?, NULL, NULL, NULL, NULL, NULL, NULL)`,
  ).run(row.id, row.member_id, row.wallet, row.kind, row.audience, row.asset, row.amount, row.usd_cents,
    row.destination, row.reference_pubkey, row.created_at, row.pay_expires_at);
  return postSessionPublic(row);
}
function getPostSession(args: any): any {
  validateWrapperArgs("getPostSession", args, { wallet: "string", sessionId: "string?" });
  const member = memberByWallet(args.wallet);
  if (!member) return { sessions: [] };
  if (typeof args.sessionId === "string") {
    const s = getPostSessionRow(args.sessionId);
    return { session: s && s.member_id === member.id ? postSessionPublic(s) : null };
  }
  const rows = sqlite.query(
    `SELECT * FROM post_sessions WHERE member_id = ? ORDER BY created_at DESC LIMIT 20`,
  ).all(member.id) as PostSessionRow[];
  return { sessions: rows.map(postSessionPublic) };
}
// A6: boot reconciliation is REQUIRED, not optional. A `dispatched`
// session means the wrapper claimed it and then the process died around
// the bundle call. The dispatch fingerprint decides: content matching
// what was claimed (same member, same normalized title/body, created no
// earlier than the claim) → the post landed: consumed. No match → the
// dispatch never landed: the session reverts to verified and the member
// retries the post against the payment already made.
function normalizedText(x: unknown): string {
  return String(x ?? "").toLowerCase().replace(/\s+/g, " ").trim();
}
function reconcileDispatchedSessions(): void {
  const rows = sqlite.query(`SELECT * FROM post_sessions WHERE status = 'dispatched'`).all() as PostSessionRow[];
  for (const s of rows) {
    let landedId: number | null = null;
    if (s.kind === "thread") {
      const t = sqlite.query(`SELECT id, title, body FROM threads WHERE author_id = ? AND created_at >= ?`)
        .all(s.member_id, (s.dispatched_at ?? s.created_at) - 5_000) as { id: number; title: string; body: string }[];
      const hit = t.find((x) => normalizedText(x.title) === normalizedText(s.dispatch_title) &&
        normalizedText(x.body) === normalizedText(s.dispatch_body));
      if (hit) landedId = hit.id;
    } else {
      const r = sqlite.query(`SELECT id, body FROM replies WHERE author_id = ? AND created_at >= ?`)
        .all(s.member_id, (s.dispatched_at ?? s.created_at) - 5_000) as { id: number; body: string }[];
      const hit = r.find((x) => normalizedText(x.body) === normalizedText(s.dispatch_body));
      if (hit) landedId = hit.id;
    }
    if (landedId != null) {
      sqlite.query(`UPDATE post_sessions SET status = 'consumed', result_ref = ? WHERE id = ? AND status = 'dispatched'`)
        .run(s.kind === "thread" ? String(landedId) : s.result_ref, s.id);
    } else if (s.consume_expires_at != null && s.consume_expires_at > Date.now()) {
      sqlite.query(`UPDATE post_sessions SET status = 'verified' WHERE id = ? AND status = 'dispatched'`).run(s.id);
    } else {
      sqlite.query(`UPDATE post_sessions SET status = 'expired' WHERE id = ? AND status = 'dispatched'`).run(s.id);
    }
  }
  if (rows.length) console.log(`post-session boot reconciliation: ${rows.length} dispatched session(s) settled`);
}
// The post-session sweep (A4): a pending session is NEVER expired by
// the clock alone — its fate is decided at use time by a chain check
// for a timely payment. Only a verified session whose consume window
// lapses, unconsumed, expires here.
function postSessionSweep(): void {
  const now = Date.now();
  sqlite.query(
    `UPDATE post_sessions SET status = 'expired' WHERE status = 'verified' AND consume_expires_at IS NOT NULL AND consume_expires_at < ?`,
  ).run(now);
}

// --- Post gate (packet §1.2/§1.3, §9.2) -------------------------------------------
// Clean args (postSessionId stripped — m11, EVERY return path) are built
// before anything can return, so no path can leak the session id into
// the bundle call.
async function postGatePreDispatch(action: string, args: any): Promise<{ sessionId: string | null; args: any }> {
  const { postSessionId, ...rest } = args ?? {};
  const cleanArgs = rest;
  const kind = action === "createThread" ? "thread" : "reply";
  let audience = "public";
  let targetCategoryId: number | null = null;
  if (action === "createThread") {
    audience = args.audience ?? "public";
    targetCategoryId = Number(args.categoryId) || null;
  } else {
    const t = sqlite.query(`SELECT audience, category_id FROM threads WHERE id = ?`).get(Number(args.threadId)) as { audience: string; category_id: number } | null;
    if (!t) return { sessionId: null, args: cleanArgs }; // bundle refuses the unknown thread
    audience = t.audience ?? "public";
    targetCategoryId = Number(t.category_id) || null;
  }
  const member = memberByWallet(args.wallet);
  if (!member) {
    // Split amendment: the reply gate IS membership, checked here —
    // a non-member's reply is refused by the wrapper itself, not
    // left to the bundle. Threads keep the standing shape below.
    if (action === "createReply")
      throw new Error("Replies are for members — register before replying. Nothing was posted.");
    return { sessionId: null, args: cleanArgs }; // bundle refuses non-members itself
  }
  // M2 VIP write gate: the board's posts are holders-only, checked
  // BEFORE any session logic — a non-holder's paid session must never
  // buy a way in, and no session is verified or consumed on a VIP
  // refusal. Holders post in their own board fee-free (the tier's
  // exemption covers its own surface first).
  const vipId = vipCategoryId();
  if (vipId != null && targetCategoryId === vipId) {
    if (!isPrestigeMember(member))
      throw new Error("The VIP board is for prestige holders. Nothing was posted.");
    return { sessionId: null, args: cleanArgs };
  }
  // Split amendment (owner, 2026-11-02 12:02): replies are FREE for
  // members — membership (checked above, on the SIWS-verified wallet)
  // is the whole reply gate, and the VIP gate has already run, so
  // holders-only boards stay holders-only. A presented postSessionId
  // was stripped into cleanArgs at the top and is IGNORED here:
  // never verified, never consumed. There is no reply session left
  // to settle (createPostSession refuses the kind), and refusing the
  // reply over a legacy attachment would punish an old client for
  // the wrapper's own retired shape.
  if (action === "createReply") return { sessionId: null, args: cleanArgs };
  // B10/M14: an exempt founder ANNOUNCEMENT is a public-republication
  // channel — the only thing keeping it private today is
  // first_strike_delay_hours being NULL, which a settings change could
  // silently lift. Announcements therefore never ride the exemption: an
  // announcement requires a paid PUBLIC session at creation, like any
  // public post. first_strike_delay_hours stays NULL (flip row 21).
  const isAnnouncement = action === "createThread" && args.isAnnouncement === true;
  let expectedAudience = audience;
  if (audience === "founder" && FOUNDER_AUDIENCE_MODE === "exempt" && isFounderMember(member)) {
    if (!isAnnouncement) return { sessionId: null, args: cleanArgs };
    expectedAudience = "public";
  }
  // M2 prestige consumer (gate side): the tier's permanent thread-fee
  // exemption, read from prestige_holders at dispatch — never from a
  // client claim. Threads only: replies never reach this far (the
  // membership gate above returns first), and the exemption's ruled
  // scope is the thread fee in any case.
  if (kind === "thread" && isPrestigeMember(member))
    return { sessionId: null, args: cleanArgs };
  if (typeof postSessionId !== "string" || !postSessionId)
    throw new Error("Posting is paid in this build — start a payment session first (the app creates one automatically). Nothing was posted.");
  const s = getPostSessionRow(postSessionId);
  if (!s) throw new Error("That payment session was not found. Nothing was posted.");
  if (s.member_id !== member.id || s.wallet !== args.wallet)
    throw new Error("That payment session belongs to a different wallet. Nothing was posted.");
  if (s.kind !== kind)
    throw new Error(`That payment session pays for a ${s.kind}, not a ${kind}. Nothing was posted.`);
  if (s.audience !== expectedAudience)
    throw new Error("That payment session was created for a different audience. Nothing was posted.");
  if (!s.destination)
    throw new Error("That payment session predates destination binding and cannot be verified. Start a new session. Nothing was posted.");
  const now = Date.now();
  if (s.status === "consumed")
    throw new Error("This post session has already been used. Nothing was posted.");
  if (s.status === "expired")
    throw new Error("That payment session has expired. Nothing was posted.");
  if (s.status === "dispatched")
    throw new Error("That post is still being placed — wait a moment and try again. Nothing was posted twice.");
  if (s.status === "pending") {
    const v = await verifyTransferByReference({
      reference: s.reference_pubkey!, asset: s.asset, amount: s.amount,
      destinationWallet: s.destination, sourceWallet: s.wallet,
      windowStart: s.created_at, windowEnd: s.pay_expires_at,
    });
    if (!v.ok) {
      if (v.kind === "refused")
        throw new Error(`Payment verification refused: ${VERIFY_MESSAGES[v.reason ?? "reference"]} Nothing was posted.`);
      // A4: no timely candidate. Only the clock running out — with no
      // timely finalized payment behind it — expires the session.
      if (now > s.pay_expires_at) {
        sqlite.query(`UPDATE post_sessions SET status = 'expired' WHERE id = ? AND status = 'pending'`).run(s.id);
        throw new Error("That payment session has expired. Nothing was posted.");
      }
      throw new Error("No finalized payment found for this session yet. Wait for confirmation and try again. Nothing was posted.");
    }
    // A7/A3: claim the signature in the global registry AND flip the
    // session in the same synchronous block — the conditional UPDATEs
    // are the race defense, not a check-then-write pair. A registry
    // conflict is the named replay refusal, never a raw DB error.
    const claimed = claimSignature(v.signature!, "post", s.id);
    if (!claimed)
      throw new Error("This payment has already been used to pay for something else. Nothing was posted.");
    const upd = sqlite.query(
      `UPDATE post_sessions SET status = 'verified', signature = ?, verified_at = ?, consume_expires_at = ?
       WHERE id = ? AND status = 'pending'`,
    ).run(v.signature!, now, now + CONSUME_WINDOW_MS, s.id);
    if (Number(upd.changes) !== 1) {
      releaseSignature(v.signature!);
      throw new Error("That post is still being placed — wait a moment and try again. Nothing was posted twice.");
    }
    s.status = "verified"; s.signature = v.signature!;
    s.verified_at = now; s.consume_expires_at = now + CONSUME_WINDOW_MS;
  }
  if (s.status === "verified") {
    if (s.consume_expires_at != null && now > s.consume_expires_at) {
      sqlite.query(`UPDATE post_sessions SET status = 'expired' WHERE id = ? AND status = 'verified'`).run(s.id);
      throw new Error("That payment session has expired. Nothing was posted.");
    }
    const claim = sqlite.query(
      `UPDATE post_sessions SET status = 'dispatched', dispatched_at = ?, dispatch_title = ?, dispatch_body = ?
       WHERE id = ? AND status = 'verified'`,
    ).run(now, action === "createThread" ? String(args.title ?? "") : null, String(args.body ?? ""), s.id);
    if (Number(claim.changes) !== 1)
      throw new Error("That post is still being placed — wait a moment and try again. Nothing was posted twice.");
    return { sessionId: s.id, args: cleanArgs };
  }
  throw new Error("That payment session cannot be used. Nothing was posted.");
}
function postGateSettle(sessionId: string, action: string, out: any): void {
  if (out && out.ok === false) { postGateRelease(sessionId); return; }
  const resultRef = action === "createThread" && out?.id != null ? String(out.id) : null;
  sqlite.query(
    `UPDATE post_sessions SET status = 'consumed', result_ref = COALESCE(?, result_ref) WHERE id = ? AND status = 'dispatched'`,
  ).run(resultRef, sessionId);
}
function postGateRelease(sessionId: string): void {
  sqlite.query(
    `UPDATE post_sessions SET status = 'verified' WHERE id = ? AND status = 'dispatched'`,
  ).run(sessionId);
}

// --- Tip fee legs (packet §1.5/§1.6; A7/A8) ---------------------------------------
type TipFeeLeg = {
  id: string; tip_session_id: string; reference_pubkey: string | null; asset: string;
  amount: number; destination: string; fee_usd_cents: number; status: string;
  signature: string | null; created_at: number; fee_expires_at: number; verified_at: number | null;
};
function tipFeeLegBySession(tipSessionId: string): TipFeeLeg | null {
  return sqlite.query(`SELECT * FROM tip_fee_legs WHERE tip_session_id = ?`).get(tipSessionId) as TipFeeLeg | null;
}
// A8: create counts pending legs (whose bundle session is still open)
// AND outstanding legs — one unresolved fee blocks the sender's next tip.
function tipCreatePreDispatch(args: any): void {
  const member = memberByWallet(args.wallet);
  if (!member) return; // bundle refuses non-members itself
  const outstanding = sqlite.query(
    `SELECT l.id FROM tip_fee_legs l JOIN tip_sessions t ON t.id = l.tip_session_id
     WHERE t.sender_member_id = ? AND l.status = 'outstanding' LIMIT 1`,
  ).get(member.id);
  if (outstanding)
    throw new Error("A previous tip's platform fee is still outstanding. Settle it with payOutstandingTipFee before tipping again.");
  const pending = sqlite.query(
    `SELECT l.id FROM tip_fee_legs l JOIN tip_sessions t ON t.id = l.tip_session_id
     WHERE t.sender_member_id = ? AND l.status = 'pending' AND t.expires_at > ? LIMIT 1`,
  ).get(member.id, Date.now());
  if (pending)
    throw new Error("Your previous tip is still completing — finish it or let it expire before tipping again.");
  const amountUsdCents = args.amountUsdCents;
  if (typeof amountUsdCents === "number" && amountUsdCents < TIP_MIN_CENTS)
    throw new Error(`The minimum tip is $${(TIP_MIN_CENTS / 100).toFixed(2)}. Nothing was sent.`);
  // Tip safety (packet amendment, 2026-11-02): the per-tip cutoff is a
  // hard refusal at creation, enforced here — not left to the client.
  // The effective amount is the caller's figure, else the settings
  // default the bundle would charge (§2.5's computation, mirrored).
  const effectiveTipCents = typeof amountUsdCents === "number" ? amountUsdCents
    : (sqlite.query(`SELECT tip_default_usd_cents AS d FROM forum_settings WHERE id = 1`)
        .get() as { d: number } | null)?.d;
  if (typeof effectiveTipCents === "number" && effectiveTipCents > TIP_MAX_CENTS)
    throw new Error(`The maximum tip is $${(TIP_MAX_CENTS / 100).toFixed(2)}. Nothing was sent.`);
}
async function tipCreatePostDispatch(args: any, out: any): Promise<void> {
  // A7 fail-closed: if the companion row cannot be recorded, the create
  // FAILS — its error replaces the create response, and a tip session
  // never silently exists without its fee leg. (The orphaned bundle
  // session is unusable: completion refuses for the missing leg, and
  // the session expires on the bundle's own 15-minute clock.)
  const feeAmount = await quoteUsdToBaseUnits(args.asset, TIP_FEE_CENTS);
  const now = Date.now();
  sqlite.query(
    `INSERT INTO tip_fee_legs (id, tip_session_id, reference_pubkey, asset, amount, destination,
       fee_usd_cents, status, signature, created_at, fee_expires_at, verified_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, 'pending', NULL, ?, ?, NULL)`,
  ).run(crypto.randomUUID(), String(out.checkoutId ?? out.id), genReferencePubkey(), args.asset, feeAmount,
    treasuryWallet(), TIP_FEE_CENTS, now, now + POST_SESSION_TTL_MS);
}
type TipCompletePlan =
  | { mode: "none" }
  | { mode: "fallback"; leg: TipFeeLeg }
  | { mode: "claimed"; leg: TipFeeLeg; signature: string };
async function tipCompletePreDispatch(args: any): Promise<TipCompletePlan> {
  const leg = tipFeeLegBySession(String(args.checkoutId));
  // A7/A5 fail-closed: no companion row, no completion — the wrapper
  // never trusts bundle idempotency or a bare foreign reference again.
  if (!leg)
    throw new Error("No platform fee record exists for this tip, so it cannot be completed. Nothing was sent.");
  if (leg.status === "verified") return { mode: "none" }; // bundle answers idempotently
  if (leg.status === "expired")
    throw new Error("This tip's fee window has expired. Start a new tip. Nothing was sent.");
  if (leg.status === "outstanding")
    throw new Error("This tip already completed; settle its fee with payOutstandingTipFee. Nothing was sent.");
  // leg.status === 'pending'
  const presented = typeof args.signature === "string" && args.signature ? args.signature : null;
  if (!presented) {
    if (TWO_TRANSFER_CAPABLE)
      throw new Error("This build completes a tip in one transaction — the platform fee transfer must ride the tip transaction. Nothing was sent.");
    return { mode: "fallback", leg }; // A8: recorded build fact, not a client choice
  }
  const v = await verifyTransferInSignature(presented, {
    reference: leg.reference_pubkey!, asset: leg.asset, amount: leg.amount,
    destinationWallet: leg.destination, sourceWallet: args.wallet,
  });
  if (!v.ok)
    throw new Error(`Fee payment verification refused: ${VERIFY_MESSAGES[v.reason ?? "reference"]} Nothing was sent.`);
  if (v.blockTimeMs != null && v.blockTimeMs > leg.fee_expires_at)
    throw new Error("The fee payment landed after this tip's fee window closed. Nothing was sent.");
  // B9/A7: CAS-claim the leg AND register the signature in one
  // synchronous block, BEFORE dispatch. The bundle's own idempotency is
  // never the guard.
  if (!claimSignature(presented, "tip-fee", leg.id))
    throw new Error("This payment has already been used to pay for something else. Nothing was sent.");
  const upd = sqlite.query(
    `UPDATE tip_fee_legs SET status = 'verified', signature = ?, verified_at = ?
     WHERE id = ? AND status = 'pending'`,
  ).run(presented, Date.now(), leg.id);
  if (Number(upd.changes) !== 1) {
    releaseSignature(presented);
    throw new Error("This tip is already being completed. Nothing was sent twice.");
  }
  return { mode: "claimed", leg, signature: presented };
}
function tipCompletePostDispatch(out: any, plan: TipCompletePlan): void {
  if (plan.mode === "none") return;
  const failed = !out || out.ok === false;
  if (plan.mode === "claimed") {
    if (failed) {
      // The bundle refused after the claim (e.g. its own expiry): the
      // fee claim rolls back whole, so the member can retry or let the
      // leg expire — the fee is never stranded as verified-without-tip.
      sqlite.query(
        `UPDATE tip_fee_legs SET status = 'pending', signature = NULL, verified_at = NULL
         WHERE id = ? AND status = 'verified' AND signature = ?`,
      ).run(plan.leg.id, plan.signature);
      releaseSignature(plan.signature);
    }
    return;
  }
  // fallback (A8): the tip went through without its fee riding along.
  if (!failed) {
    sqlite.query(
      `UPDATE tip_fee_legs SET status = 'outstanding' WHERE id = ? AND status = 'pending'`,
    ).run(plan.leg.id);
  }
}
function tipCompleteReleaseClaim(plan: TipCompletePlan | undefined): void {
  if (plan?.mode === "claimed") tipCompletePostDispatch({ ok: false }, plan);
}
// A8: settle an outstanding fee leg. Wrapper-native, SIWS-gated; the fee
// is verified inside the presented signature under the leg's own
// reference — amount, Relay ATA destination, and the leg's sender.
// fee_expires_at binds the completion path; settlement exists precisely
// because the fee arrives late, so lateness itself is not a refusal
// here (the outstanding row IS the lateness record).
async function payOutstandingTipFee(args: any): Promise<any> {
  validateWrapperArgs("payOutstandingTipFee", args, {
    wallet: "string", checkoutId: "string", signature: "string",
  });
  const leg = tipFeeLegBySession(String(args.checkoutId));
  if (!leg) throw new Error("No platform fee record exists for this tip. Nothing was sent.");
  if (leg.status === "verified") return { ok: true, alreadySettled: true };
  if (leg.status !== "outstanding")
    throw new Error("This tip's fee is not outstanding, so there is nothing to settle here. Nothing was sent.");
  const session = sqlite.query(`SELECT sender_member_id FROM tip_sessions WHERE id = ?`)
    .get(leg.tip_session_id) as { sender_member_id: number } | null;
  const sender = session ? sqlite.query(`SELECT wallet FROM members WHERE id = ?`)
    .get(session.sender_member_id) as { wallet: string } | null : null;
  if (!sender || sender.wallet !== args.wallet)
    throw new Error("Only the tip's sender can settle its fee. Nothing was sent.");
  const v = await verifyTransferInSignature(String(args.signature), {
    reference: leg.reference_pubkey!, asset: leg.asset, amount: leg.amount,
    destinationWallet: leg.destination, sourceWallet: args.wallet,
  });
  if (!v.ok)
    throw new Error(`Fee payment verification refused: ${VERIFY_MESSAGES[v.reason ?? "reference"]} Nothing was sent.`);
  if (!claimSignature(String(args.signature), "tip-fee", leg.id))
    throw new Error("This payment has already been used to pay for something else. Nothing was sent.");
  const upd = sqlite.query(
    `UPDATE tip_fee_legs SET status = 'verified', signature = ?, verified_at = ?
     WHERE id = ? AND status = 'outstanding'`,
  ).run(args.signature, Date.now(), leg.id);
  if (Number(upd.changes) !== 1) {
    releaseSignature(String(args.signature));
    throw new Error("This fee is already being settled. Nothing was sent twice.");
  }
  return { ok: true };
}
// A8: pending legs die with their bundle sessions — the sweep moves a
// pending leg past fee_expires_at to 'expired' (its fee window and the
// bundle session share the +15-minute clock by construction).
function tipFeeLegSweep(): void {
  sqlite.query(
    `UPDATE tip_fee_legs SET status = 'expired' WHERE status = 'pending' AND fee_expires_at < ?`,
  ).run(Date.now());
}

// --- Paid pins (packet §1.7/§7 item 9; A9) -----------------------------------------
function livePinEndsAt(): number | null {
  const row = sqlite.query(
    `SELECT MIN(ends_at) AS ends FROM paid_pins WHERE status = 'active' AND ends_at > ?`,
  ).get(Date.now()) as { ends: number | null } | null;
  return row?.ends ?? null;
}
function slotTakenError(): Error {
  const ends = livePinEndsAt();
  const until = ends ? ` The top slot is taken until ${new Date(ends).toISOString()}.` : "";
  return new Error(`Only ${PAID_PIN_CAP} paid pin runs at a time.${until} Nothing was charged.`);
}
function countSlotHolders(): number {
  const now = Date.now();
  const live = sqlite.query(
    `SELECT COUNT(*) AS n FROM paid_pins WHERE status = 'active' AND ends_at > ?`,
  ).get(now) as { n: number };
  const pendingSessions = sqlite.query(
    `SELECT COUNT(*) AS n FROM paid_pin_sessions WHERE status = 'pending' AND expires_at > ?`,
  ).get(now) as { n: number };
  const reservations = sqlite.query(
    `SELECT COUNT(*) AS n FROM pin_slot_reservations WHERE expires_at > ?`,
  ).get(now) as { n: number };
  return Number(live.n) + Number(pendingSessions.n) + Number(reservations.n);
}
function activateDeferredPins(): void {
  const now = Date.now();
  for (;;) {
    const live = sqlite.query(
      `SELECT COUNT(*) AS n FROM paid_pins WHERE status = 'active' AND ends_at > ?`,
    ).get(now) as { n: number };
    if (Number(live.n) >= PAID_PIN_CAP) return;
    const next = sqlite.query(
      `SELECT p.session_id AS session_id, k.hours AS hours FROM paid_pins p
       JOIN pin_parked k ON k.session_id = p.session_id
       WHERE p.status = 'deferred' ORDER BY k.parked_at ASC LIMIT 1`,
    ).get() as { session_id: string; hours: number } | null;
    if (!next) return;
    const upd = sqlite.query(
      `UPDATE paid_pins SET status = 'active', starts_at = ?, ends_at = ? WHERE session_id = ? AND status = 'deferred'`,
    ).run(now, now + next.hours * 3600_000, next.session_id);
    if (Number(upd.changes) === 1)
      sqlite.query(`DELETE FROM pin_parked WHERE session_id = ?`).run(next.session_id);
    else return; // lost a race with another activation — stop and retry next sweep
  }
}
// A9: the reservation is taken SYNCHRONOUSLY in the create pre-dispatch —
// no await separates the count from the insert, so two creates in one
// process cannot both see a free slot. The reservation is what the
// second creator is refused against; it is released the moment the
// bundle session exists (the session's pending row carries the count
// from then on) or the create fails.
function pinCreatePreDispatch(args: any): string {
  activateDeferredPins();
  sqlite.query(`DELETE FROM pin_slot_reservations WHERE expires_at <= ?`).run(Date.now());
  if (countSlotHolders() >= PAID_PIN_CAP) throw slotTakenError();
  const member = memberByWallet(args.wallet);
  if (!member) return ""; // bundle refuses non-members itself; nothing to reserve for
  const id = crypto.randomUUID();
  const now = Date.now();
  sqlite.query(
    `INSERT INTO pin_slot_reservations (id, member_id, created_at, expires_at) VALUES (?, ?, ?, ?)`,
  ).run(id, member.id, now, now + POST_SESSION_TTL_MS);
  if (countSlotHolders() > PAID_PIN_CAP) {
    sqlite.query(`DELETE FROM pin_slot_reservations WHERE id = ?`).run(id);
    throw slotTakenError();
  }
  return id;
}
function pinCreateRelease(reservationId: string): void {
  if (reservationId) sqlite.query(`DELETE FROM pin_slot_reservations WHERE id = ?`).run(reservationId);
}
type PinCompletePlan = { mode: "none" | "activate" | "defer"; sessionId?: string };
function pinCompletePreDispatch(args: any): PinCompletePlan {
  activateDeferredPins();
  const session = sqlite.query(`SELECT id FROM paid_pin_sessions WHERE id = ?`)
    .get(String(args.checkoutId)) as { id: string } | null;
  if (!session) return { mode: "none" }; // bundle refuses the unknown checkout
  const existing = sqlite.query(`SELECT id FROM paid_pins WHERE session_id = ?`).get(session.id);
  if (existing) return { mode: "none" };  // idempotent re-complete: the pin already exists
  const live = sqlite.query(
    `SELECT COUNT(*) AS n FROM paid_pins WHERE status = 'active' AND ends_at > ?`,
  ).get(Date.now()) as { n: number };
  return Number(live.n) >= PAID_PIN_CAP
    ? { mode: "defer", sessionId: session.id }
    : { mode: "activate", sessionId: session.id };
}
function pinCompletePostDispatch(out: any, plan: PinCompletePlan): void {
  if (plan.mode !== "defer" || !out || out.ok === false || !plan.sessionId) return;
  // The loser is never refused after payment (A9): the bundle's own
  // completion stands (session verified, pin row written), and the
  // wrapper parks the row as 'deferred' — invisible to listForum, which
  // only surfaces status='active' — until activation moves it into the
  // slot with a fresh full window. The response names the deferral.
  const settings = settingsRow();
  const hours = Number(settings?.paid_pin_hours ?? 12) || 12;
  sqlite.query(
    `INSERT INTO pin_parked (session_id, hours, parked_at) VALUES (?, ?, ?)
     ON CONFLICT(session_id) DO NOTHING`,
  ).run(plan.sessionId, hours, Date.now());
  sqlite.query(
    `UPDATE paid_pins SET status = 'deferred' WHERE session_id = ? AND status = 'active'`,
  ).run(plan.sessionId);
  out.pinDeferred = true;
}
function pinStateSweep(): void {
  activateDeferredPins();
  sqlite.query(`DELETE FROM pin_slot_reservations WHERE expires_at <= ?`).run(Date.now());
}
// Wrapper-native read: the pin strip's slot state, served whole (the
// package's vacant-slot amendment: "the wrapper serves each slot's
// state") so the client never derives it from a thread payload that
// filtering may have trimmed. Exactly PAID_PIN_CAP slots: a slot is
// filled only by a live pin — status 'active' with its window open,
// the predicate every pin query in this file uses. A deferred pin is
// NOT a filled slot: it is reported as a count and takes its slot
// only when activation moves it in. A pure read — activation stays
// with the create/complete paths and the sweep, never with a read.
function getPinSlots(args: any): any {
  validateWrapperArgs("getPinSlots", args, { wallet: "string?" });
  const now = Date.now();
  const live = sqlite.query(
    `SELECT p.id, p.thread_id, p.starts_at, p.ends_at, t.title AS thread_title
     FROM paid_pins p LEFT JOIN threads t ON t.id = p.thread_id
     WHERE p.status = 'active' AND p.ends_at > ?
     ORDER BY p.starts_at ASC, p.id ASC`,
  ).all(now) as any[];
  const deferred = sqlite.query(
    `SELECT COUNT(*) AS n FROM paid_pins WHERE status = 'deferred'`,
  ).get() as { n: number };
  const slots: any[] = [];
  for (let i = 0; i < PAID_PIN_CAP; i++) {
    const p = live[i];
    slots.push(p ? {
      state: "filled", pinId: Number(p.id), threadId: Number(p.thread_id),
      threadTitle: p.thread_title ?? null,
      startsAt: new Date(Number(p.starts_at)).toISOString(),
      endsAt: new Date(Number(p.ends_at)).toISOString(),
    } : { state: "vacant" });
  }
  return {
    cap: PAID_PIN_CAP, slots,
    activeCount: live.length, deferredCount: Number(deferred.n),
  };
}

// --- Sweeps: boot reconciliation first (A6), then intervals -----------------------
reconcileDispatchedSessions();
reconcileDispatchedCommercialSessions();
badgeMirrorSweep();
tipFeeLegSweep();
pinStateSweep();
postSessionSweep();
commercialSessionSweep();
setInterval(() => {
  try { badgeMirrorSweep(); } catch (e) { console.error("badge mirror sweep failed", e); }
  try { tipFeeLegSweep(); } catch (e) { console.error("tip fee leg sweep failed", e); }
  try { pinStateSweep(); } catch (e) { console.error("pin state sweep failed", e); }
  try { postSessionSweep(); } catch (e) { console.error("post session sweep failed", e); }
  try { commercialSessionSweep(); } catch (e) { console.error("commercial session sweep failed", e); }
}, 60_000);

async function handleAction(body: any, ip: string): Promise<{ status: number; payload: unknown }> {
  const action = body?.action as string;
  let args: any = body?.args ?? {};
  const a = (Actions as any)[action];
  if (carriesDemoWallet(args))
    return { status: 200, payload: { error: "DEMO fixtures are disabled in this build." } };
  // B2: identity first. For gated actions, the acting wallet is the
  // SIWS-verified identity; a client-asserted args.wallet that disagrees
  // is a refusal (a spoof attempt), and the proof fields never reach
  // the bundle or any wrapper logic downstream.
  if (GATED_ACTIONS.has(action)) {
    try {
      const identity = requireSiwsIdentity(args);
      if (typeof args?.wallet === "string" && args.wallet !== identity)
        throw new Error("The acting wallet does not match the signed identity. Nothing was done.");
      const { siwsIdentity: _proof, ...restArgs } = args ?? {};
      args = { ...restArgs, wallet: identity };
    } catch (e: any) {
      return { status: 200, payload: { error: e?.message ?? "Sign-in proof required." } };
    }
  }
  if (action === "createPostSession") {
    try { return { status: 200, payload: { data: await createPostSession(args) } }; }
    catch (e: any) { return { status: 200, payload: { error: e?.message ?? "createPostSession failed" } }; }
  }
  if (action === "getPostSession") {
    try { return { status: 200, payload: { data: getPostSession(args) } }; }
    catch (e: any) { return { status: 200, payload: { error: e?.message ?? "getPostSession failed" } }; }
  }
  if (action === "payOutstandingTipFee") {
    try { return { status: 200, payload: { data: await payOutstandingTipFee(args) } }; }
    catch (e: any) { return { status: 200, payload: { error: e?.message ?? "payOutstandingTipFee failed" } }; }
  }
  if (action === "createJoinSession") {
    try { return { status: 200, payload: { data: createJoinSession(args) } }; }
    catch (e: any) { return { status: 200, payload: { error: e?.message ?? "createJoinSession failed" } }; }
  }
  if (action === "completeJoinSession") {
    try { return { status: 200, payload: { data: await completeJoinSession(args) } }; }
    catch (e: any) { return { status: 200, payload: { error: e?.message ?? "completeJoinSession failed" } }; }
  }
  if (action === "getJoinSession") {
    try { return { status: 200, payload: { data: getJoinSession(args) } }; }
    catch (e: any) { return { status: 200, payload: { error: e?.message ?? "getJoinSession failed" } }; }
  }
  if (action === "createPrestigeSession") {
    try { return { status: 200, payload: { data: createPrestigeSession(args) } }; }
    catch (e: any) { return { status: 200, payload: { error: e?.message ?? "createPrestigeSession failed" } }; }
  }
  if (action === "completePrestigeSession") {
    try { return { status: 200, payload: { data: await completePrestigeSession(args) } }; }
    catch (e: any) { return { status: 200, payload: { error: e?.message ?? "completePrestigeSession failed" } }; }
  }
  if (action === "getPrestigeSession") {
    try { return { status: 200, payload: { data: getPrestigeSession(args) } }; }
    catch (e: any) { return { status: 200, payload: { error: e?.message ?? "getPrestigeSession failed" } }; }
  }
  if (action === "createCommercialSession") {
    try { return { status: 200, payload: { data: await createCommercialSession(args) } }; }
    catch (e: any) { return { status: 200, payload: { error: e?.message ?? "createCommercialSession failed" } }; }
  }
  if (action === "getCommercialSession") {
    try { return { status: 200, payload: { data: getCommercialSession(args) } }; }
    catch (e: any) { return { status: 200, payload: { error: e?.message ?? "getCommercialSession failed" } }; }
  }
  if (action === "listUnanswered") {
    try { return { status: 200, payload: { data: listUnanswered(args) } }; }
    catch (e: any) { return { status: 200, payload: { error: e?.message ?? "listUnanswered failed" } }; }
  }
  if (action === "getPinSlots") {
    try { return { status: 200, payload: { data: getPinSlots(args) } }; }
    catch (e: any) { return { status: 200, payload: { error: e?.message ?? "getPinSlots failed" } }; }
  }
  if (action === "getCatchUp") {
    try { return { status: 200, payload: { data: getCatchUp(args) } }; }
    catch (e: any) { return { status: 200, payload: { error: e?.message ?? "getCatchUp failed" } }; }
  }
  if (action === "advanceCatchUp") {
    try { return { status: 200, payload: { data: advanceCatchUp(args) } }; }
    catch (e: any) { return { status: 200, payload: { error: e?.message ?? "advanceCatchUp failed" } }; }
  }
  if (PAYMENT_ACTIONS.has(action) && !V014_PAYMENT_ACTIONS.has(action))
    return { status: 200, payload: { error: "Payments are disabled in this build." } };
  if (OPERATOR_ACTIONS.has(action))
    return { status: 200, payload: { error: "Operator actions are unavailable in this build." } };
  if (action === "cancelSubscription")
    return { status: 200, payload: { error: "No paid subscription exists in this build." } };
  if (!ALLOW_ACTIONS.has(action) && !V014_PAYMENT_ACTIONS.has(action) && !(a && typeof a.handler === "function"))
    return { status: 200, payload: { error: `Action ${action} is not available in this build.` } };
  if (!a || typeof a.handler !== "function")
    return { status: 200, payload: { error: `Unknown action ${action}.` } };
  if (THROTTLED.has(action) && !throttleAllows(`${ip}:${action}`))
    return { status: 200, payload: { error: "Too many attempts. Wait a moment and try again." } };

  // M2 VIP read discipline: listForum/getThread stay public reads, but
  // VIP-board content is released only to a SIWS-VERIFIED prestige
  // holder. args.wallet alone never unlocks it; a presented proof is
  // verified exactly as for gated actions, and a proof that fails
  // verification reads as anonymous (fail-closed). The proof is
  // consumed here and never forwarded to the bundle.
  let readPrestige = false;
  if (action === "listForum" || action === "getThread") {
    if ((args as any)?.siwsIdentity) {
      try {
        const proofWallet = requireSiwsIdentity(args);
        const m = memberByWallet(proofWallet);
        readPrestige = !!m && isPrestigeMember(m);
      } catch { readPrestige = false; }
      const { siwsIdentity: _consumed, ...restArgs } = args ?? {};
      args = restArgs;
    }
  }

  let postSessionId: string | null = null;
  let commercialSessionId: string | null = null;
  let dispatchArgs = args;
  let tipCompletePlan: TipCompletePlan | undefined;
  let pinReservationId = "";
  let pinCompletePlan: PinCompletePlan | undefined;
  try {
    if ((action === "createThread" || action === "createReply") && args.commercialSessionId != null) {
      if (action !== "createThread")
        throw new Error("A commercial session pays for a thread, not a reply. Nothing was posted.");
      const gated = await commercialGatePreDispatch(args);
      commercialSessionId = gated.sessionId;
      dispatchArgs = gated.args;
    } else if (action === "createThread" || action === "createReply") {
      const gated = await postGatePreDispatch(action, args);
      postSessionId = gated.sessionId;
      dispatchArgs = gated.args;
    } else if (action === "createTipCheckout") {
      tipCreatePreDispatch(args);
    } else if (action === "completeTipCheckout") {
      tipCompletePlan = await tipCompletePreDispatch(args);
      if (tipCompletePlan.mode === "none" && tipFeeLegBySession(String(args.checkoutId))?.status !== "verified")
        tipCompletePlan = undefined; // unreachable behind the leg checks; belt-and-braces
    } else if (action === "createPaidPinCheckout") {
      pinReservationId = pinCreatePreDispatch(args);
    } else if (action === "completePaidPinCheckout") {
      pinCompletePlan = pinCompletePreDispatch(args);
    }
  } catch (e: any) {
    if (pinReservationId) pinCreateRelease(pinReservationId);
    return { status: 200, payload: { error: e?.message ?? "Payment checks failed." } };
  }
  // B11: the bundle's own request schema parses gated dispatch args —
  // defaults land and keys the action does not declare (postSessionId
  // included) never reach the handler. (The post gate already strips
  // its session id on every path; this is the second lock.)
  if (SCHEMA_PARSED_ACTIONS.has(action)) {
    const parsed = a.request.safeParse(dispatchArgs);
    if (!parsed.success) {
      if (postSessionId) postGateRelease(postSessionId);
      if (commercialSessionId) commercialGateRelease(commercialSessionId);
      if (pinReservationId) pinCreateRelease(pinReservationId);
      tipCompleteReleaseClaim(tipCompletePlan);
      const issue = parsed.error.issues?.[0];
      return { status: 200, payload: { error: `Invalid arguments for this action${issue ? `: ${issue.path.join(".")} ${issue.message}` : ""}. Nothing was done.` } };
    }
    dispatchArgs = parsed.data;
  }
  let out: any;
  try {
    out = await a.handler(ctx, dispatchArgs);
  } catch (e) {
    if (postSessionId) postGateRelease(postSessionId);
    if (commercialSessionId) commercialGateRelease(commercialSessionId);
    if (pinReservationId) pinCreateRelease(pinReservationId);
    tipCompleteReleaseClaim(tipCompletePlan);
    throw e;
  }
  pinCreateRelease(pinReservationId); // the bundle session row now carries the slot count
  if (postSessionId) postGateSettle(postSessionId, action, out);
  if (commercialSessionId) commercialGateSettle(commercialSessionId, out);
  if (action === "createReply") {
    // R7: the bump is a post-dispatch hook on the wrapper's handle —
    // the bundle's handler is never modified, and a hook failure can
    // never un-post a reply that already landed.
    try { replyBumpPostDispatch(dispatchArgs, out); } catch (e) { console.error("reply bump failed", e); }
  }
  if (action === "createTipCheckout" && out && out.ok !== false) {
    try { await tipCreatePostDispatch(dispatchArgs, out); }
    catch (e: any) { return { status: 200, payload: { error: e?.message ?? "Tip fee leg could not be recorded — the tip was not started." } }; }
    const injectedLeg = tipFeeLegBySession(String(out.checkoutId ?? out.id));
    out.platformFee = {
      asset: dispatchArgs.asset, amount: injectedLeg?.amount ?? null,
      usdCents: TIP_FEE_CENTS, note: "Platform fee, paid to the treasury in the same transaction.",
      // §2.3's designed shape: the leg's own reference and destination
      // ride the injection — the client builds the fee transfer from
      // exactly these fields, and completion verifies by the same
      // reference (M3 client gap 1, cured 2026-11-02).
      reference: injectedLeg?.reference_pubkey ?? null,
      destination: injectedLeg?.destination ?? null,
    };
  }
  if (action === "completeTipCheckout" && tipCompletePlan) {
    try { tipCompletePostDispatch(out, tipCompletePlan); } catch (e) { console.error("tip complete post-dispatch failed", e); }
  }
  if (action === "completePaidPinCheckout" && pinCompletePlan) {
    try { pinCompletePostDispatch(out, pinCompletePlan); } catch (e) { console.error("pin complete post-dispatch failed", e); }
  }
  if (out?.membership?.tier === "founder" || action === "claimFoundingSpot")
    badgeMirrorSweep();
  if (action === "listModerationFeed")
    out = stripIdentities(aggregateModerationFeed(out));
  // Read transforms (the moderation-feed precedent): the listForum
  // payload passes through forumReadTransform on every call — the
  // commercial marker, the VIP board's stated identity, the caller's
  // verified prestige, and the M2 VIP thread filter (content is
  // withheld from non-holders, never the board's existence — the
  // categories list and its count stay, as the Motherlode teaser
  // shows counts to non-holders). getThread keeps its VIP refusal
  // for non-holders and gains the same commercial marker on the
  // thread it serves.
  if (action === "listForum" && out && Array.isArray(out.threads))
    out = forumReadTransform(out, readPrestige);
  if (action === "getThread" && out && out.ok !== false) {
    if (!readPrestige) {
      const vipId = vipCategoryId();
      if (vipId != null) {
        const row = sqlite.query(`SELECT category_id FROM threads WHERE id = ?`)
          .get(Number(args.threadId)) as { category_id: number } | null;
        if (row && Number(row.category_id) === vipId)
          return { status: 200, payload: { data: { ok: false, error: "This board is for prestige holders.", replies: [] } } };
      }
    }
    if (out.thread)
      out = { ...out, thread: { ...out.thread, isCommercial: commercialThreadIdSet().has(Number(out.thread.id)) } };
  }
  return { status: 200, payload: { data: out } };
}

const server = Bun.serve({
  port: PORT,
  async fetch(req, server) {
    const url = new URL(req.url);
    if (url.pathname === "/actions" && req.method === "POST") {
      let body: any;
      try { body = await req.json(); }
      catch { return Response.json({ error: "Invalid JSON body." }, { status: 400 }); }
      try {
        const { status, payload } = await handleAction(body, clientIp(req, server));
        return Response.json(payload as any, { status });
      } catch (e: any) {
        return Response.json({ error: e?.message ?? "Internal error" }, { status: 200 });
      }
    }
    if (url.pathname === "/healthz") return new Response("ok");
    const rel = url.pathname === "/" ? "/index.html" : url.pathname;
    const file = Bun.file(CLIENT_DIR + decodeURIComponent(rel));
    if (await file.exists()) return new Response(file);
    return new Response("Not found", { status: 404 });
  },
});
console.log(`ore-kingdom host listening on :${server.port} (db: ${DB_PATH})`);
