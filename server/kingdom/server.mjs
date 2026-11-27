// Ore Kingdom — LOCAL contract backend (stage 3 of the 2026-11-26
// autonomous run). Implements the pinned client contract
// (goals/.../fable-build/CONTRACT.md) plus the INTEGRATION_MAP §3
// TODO-CONTRACT read models, exactly as the Android host layer
// (android/app/.../kingdom/KingdomTransport.kt) calls them.
//
// LOCAL PROOF ONLY: in-memory state, seeded fixtures, a stub
// identity rule (see README.md). No deployment, no persistence,
// no payment / signing / money shape of any kind (CONTRACT.md §8).
// Zero dependencies: node:http + a hand-rolled RFC 6455 server,
// mirroring the client's hand-rolled WsClient.
//
// Config (environment): KINGDOM_PORT (default 8799),
// KINGDOM_HOST (default 0.0.0.0 — the Android emulator reaches the
// host at 10.0.2.2, which is the client's default KINGDOM_API_URL).

import http from "node:http";
import crypto from "node:crypto";

const PORT = Number(process.env.KINGDOM_PORT ?? 8799);
const HOST = process.env.KINGDOM_HOST ?? "0.0.0.0";
const nowIso = () => new Date().toISOString();
const plusDays = (d) => new Date(Date.now() + d * 86_400_000).toISOString();

/* ============================== seed state ============================== */

const ROLE_RANK = { free: 0, member: 1, operator: 2, syndicate: 3, prestige: 4 };

const users = new Map(); // user_id -> user record
function seedUser(id, displayName, role, opts = {}) {
  users.set(id, {
    id, displayName, role,
    founding: !!opts.founding,
    moderator: !!opts.moderator,
    wallet: opts.wallet ?? null,
    lapsed: false,
    banned: false,
  });
}
seedUser("u_host", "Host Miner", "member", { founding: true, wallet: "wallet_host" });
seedUser("u_mod", "Mod Mara", "member", { founding: true, moderator: true, wallet: "wallet_mod" });
seedUser("u_op", "Operator Ola", "operator", { wallet: "wallet_op" });
seedUser("u_free", "Free Fran", "free", { wallet: "wallet_free" });
seedUser("u_spammer", "Spammy Pete", "member", { wallet: "wallet_spam" });

const channels = [
  { id: "ch_announcements", category: "TOWN CRIER", name: "announcements", kind: "text", slowmode_sec: 0, min_role_read: "free", min_role_post: "house", position: 0 },
  { id: "ch_general", category: "THE FLOOR", name: "general", kind: "text", slowmode_sec: 0, min_role_read: "free", min_role_post: "member", position: 0 },
  { id: "ch_introductions", category: "THE FLOOR", name: "introductions", kind: "text", slowmode_sec: 0, min_role_read: "free", min_role_post: "member", position: 1 },
  { id: "ch_help_desk", category: "THE FLOOR", name: "help-desk", kind: "text", slowmode_sec: 0, min_role_read: "free", min_role_post: "member", position: 2 },
  { id: "ch_strategy", category: "THE FLOOR", name: "strategy", kind: "text", slowmode_sec: 30, min_role_read: "member", min_role_post: "member", position: 3 },
  { id: "ch_town_square_tips", category: "THE FLOOR", name: "town-square-tips", kind: "text", slowmode_sec: 0, min_role_read: "member", min_role_post: "member", position: 4 },
  { id: "ch_pump", category: "THE PUMP", name: "pump-console", kind: "module", slowmode_sec: 0, min_role_read: "free", min_role_post: "member", position: 0 },
  { id: "ch_nightshift", category: "NIGHTSHIFT", name: "nightshift-console", kind: "module", slowmode_sec: 0, min_role_read: "free", min_role_post: "member", position: 0 },
];
const channelById = new Map(channels.map((c) => [c.id, c]));

let msgCounter = 0;
let seqByChannel = new Map(channels.map((c) => [c.id, 0]));
const messages = []; // ContractMessage store records (+ reactions as actor sets)
function seedMessage(channelId, authorId, body, opts = {}) {
  const seq = seqByChannel.get(channelId) + 1;
  seqByChannel.set(channelId, seq);
  const m = {
    id: `m_${++msgCounter}`,
    channel_id: channelId,
    seq,
    author_id: authorId,
    body,
    thread_id: opts.threadId ?? null,
    reply_to: null,
    created_at: opts.at ?? nowIso(),
    edited_at: null,
    deleted_at: null,
    reactions: new Map(), // emoji -> Set<userId>
    commercial: !!opts.commercial,
  };
  if (opts.reactions) for (const [emoji, actors] of opts.reactions) m.reactions.set(emoji, new Set(actors));
  messages.push(m);
  return m;
}
seedMessage("ch_announcements", "u_mod", "Welcome to Ore Kingdom. Read the rules, then say hello in #introductions.");
seedMessage("ch_general", "u_host", "Morning. Round window opens soon — rigs ready?");
seedMessage("ch_general", "u_op", "Ready. Strategy notes are in #strategy for members.", { reactions: [["🔥", ["u_host", "u_mod"]]] });
const spamMessage = seedMessage("ch_general", "u_spammer", "Send 1 SOL to double it, trust me — limited time!!");
seedMessage("ch_general", "u_mod", "That last one is exactly what the report button is for.");
seedMessage("ch_strategy", "u_op", "Slowmode is on here (30s) — make each post count.");
seedMessage("ch_strategy", "u_host", "Noted. Watching the motherlode rounds before I commit a wallet.");
seedMessage("ch_introductions", "u_free", "Hi all — new here, reading for now.");

const threads = [
  { id: "t_1", channel_id: "ch_strategy", anchor_message_id: null, title: "Motherlode watch — weekly thread", state: "active", reply_count: 2, last_activity_at: nowIso() },
];

// presence: user_id -> { state, activity, invisible }
const presence = new Map([
  ["u_op", { state: "online", activity: "in a round", invisible: false }],
  ["u_mod", { state: "online", activity: null, invisible: false }],
  ["u_spammer", { state: "idle", activity: null, invisible: false }],
  ["u_free", { state: "offline", activity: null, invisible: false }],
  ["u_host", { state: "offline", activity: null, invisible: false }],
]);

const sanctions = [ // per-recipient records
  { sanction_id: "san_1", user_id: "u_free", rung: "mute", reason_category: "spam", duration_sec: 86400, appeal: { state: "none" }, statement: null },
];
let reportCounter = 0;
let sanctionCounter = 1;
const modQueue = [
  {
    report_id: "r_seed1", state: "open", claimed_by: null, reason: "scam", reporter_count: 3,
    snapshot: null, // filled below from spamMessage
    author_history: [{ rung: "mute", reason_category: "spam", at: nowIso() }],
    appeal: null, sanction_id: null,
  },
];
modQueue[0].snapshot = snapshotOf(spamMessage);
const modLog = [
  { actor_id: "u_mod", action: "mute", target_id: "u_spammer", reason: "spam", at: nowIso() },
];
const suggestions = [
  { id: "sug_1", title: "Move rig 2 to the night window", body: "Rounds after 02:00 UTC paid out 12% more this week." },
  { id: "sug_2", title: "Arm the standing pump rule", body: "Wallet 1 drops below the tank threshold most mornings." },
];

function snapshotOf(m) {
  const ctx = messages
    .filter((x) => x.channel_id === m.channel_id && Math.abs(x.seq - m.seq) <= 2 && x.id !== m.id)
    .sort((a, b) => a.seq - b.seq);
  return {
    body: m.body, author_id: m.author_id, channel_id: m.channel_id,
    created_at: m.created_at, context: ctx,
  };
}

/* ============================ identity (stub) =========================== */
// LOCAL ONLY: `Authorization: Bearer local:<user_id>` or an
// X-Kingdom-Wallet header matching a seed wallet selects the user;
// anything else falls back to u_host. Never a production auth shape.
function callerOf(req) {
  const auth = req.headers["authorization"] ?? "";
  const m = /^Bearer local:(.+)$/.exec(auth);
  if (m && users.has(m[1])) return users.get(m[1]);
  const wallet = req.headers["x-kingdom-wallet"];
  if (wallet) for (const u of users.values()) if (u.wallet === wallet) return u;
  return users.get("u_host");
}

/* ============================ entitlements ============================== */

function effectiveRole(user) {
  if (user.lapsed || user.banned) return "free";
  return user.role;
}
function readableChannels(user) {
  const rank = ROLE_RANK[effectiveRole(user)];
  return channels.filter((c) => ROLE_RANK[c.min_role_read] !== undefined && rank >= ROLE_RANK[c.min_role_read]);
}
function entitlementsFor(user) {
  const role = effectiveRole(user);
  const rank = ROLE_RANK[role];
  const postable = channels
    .filter((c) => c.kind === "text" && ROLE_RANK[c.min_role_post] !== undefined && rank >= ROLE_RANK[c.min_role_post])
    .map((c) => c.id);
  const member = rank >= 1;
  const operator = rank >= 2;
  return {
    role,
    capabilities: {
      post: member ? postable : [],
      react: member ? postable : [],
      tip: member,
      gift: member,
      commercial_post: member,
      spotlight_bid: member,
      pump_module: member ? "enter" : "none",
      pump_day_included: operator,
      atm_unlock_included: operator,
      nightshift_console: operator,
      autopilot_surface: false,
      mod_loft: !!user.moderator && !user.lapsed && !user.banned,
    },
    expires_at: role === "free" ? null : plusDays(30),
    founding: user.founding,
    modules: operator
      ? { pump: "on", nightshift: "on" }
      : member
        ? { pump: "on", nightshift: "preview" }
        : { pump: "off", nightshift: "off" },
  };
}
function memberShape(user) {
  return {
    user_id: user.id,
    display_name: user.displayName,
    role: effectiveRole(user),
    founding: user.founding,
    moderator: !!user.moderator,
  };
}
function messageShape(m, viewer) {
  return {
    id: m.id, channel_id: m.channel_id, seq: m.seq, author_id: m.author_id,
    body: m.body, thread_id: m.thread_id, reply_to: m.reply_to,
    created_at: m.created_at, edited_at: m.edited_at, deleted_at: m.deleted_at,
    reactions: [...m.reactions.entries()]
      .filter(([, actors]) => actors.size > 0)
      .map(([emoji, actors]) => ({ emoji, count: actors.size, mine: actors.has(viewer.id) })),
    commercial: m.commercial,
  };
}
function presenceShape(userId) {
  const p = presence.get(userId) ?? { state: "offline", activity: null, invisible: false };
  return { user_id: userId, state: p.state, activity: p.activity, invisible: p.invisible };
}

/* ============================== HTTP layer ============================== */

const sockets = new Set(); // live WS connections: { user, subs:Set, send(obj) }

function broadcast(channelId, frame, except = null) {
  for (const s of sockets) {
    if (s === except) continue;
    if (channelId === null || s.subs.has(channelId)) s.send(frame);
  }
}
function pushEntitlements(user) {
  // SW2-03 server half: the push carries the server-filtered channel
  // list alongside the entitlements, so a lapse lands atomically.
  const frame = {
    type: "role.update",
    payload: {
      entitlements: entitlementsFor(user),
      channels: readableChannels(user),
    },
  };
  for (const s of sockets) if (s.user.id === user.id) s.send(frame);
}

function json(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, { "content-type": "application/json" });
  res.end(body);
}
function readBody(req) {
  return new Promise((resolve) => {
    let data = "";
    req.on("data", (c) => { data += c; });
    req.on("end", () => {
      try { resolve(data ? JSON.parse(data) : {}); } catch { resolve({}); }
    });
  });
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, "http://localhost");
  const path = url.pathname;
  const user = callerOf(req);
  try {
    if (req.method === "GET" && path === "/api/entitlements") return json(res, 200, entitlementsFor(user));
    if (req.method === "GET" && path === "/api/channels") return json(res, 200, { channels: readableChannels(user) });
    if (req.method === "GET" && path === "/api/spotlight") {
      return json(res, 200, { occupied: false, post: null, window_ends_at: null, floor_usd: 50 });
    }
    if (req.method === "GET" && path === "/api/me") return json(res, 200, { user_id: user.id, member: memberShape(user) });
    if (req.method === "GET" && path === "/api/members") {
      return json(res, 200, { members: [...users.values()].map(memberShape) });
    }
    if (req.method === "GET" && path === "/api/history") {
      const channelId = url.searchParams.get("channel_id");
      const ch = channelById.get(channelId);
      if (!ch || !readableChannels(user).includes(ch)) return json(res, 404, { error: "not_found" });
      const beforeSeq = url.searchParams.get("before_seq");
      const threadId = url.searchParams.get("thread_id");
      const limit = Math.min(Number(url.searchParams.get("limit") ?? 50) || 50, 200);
      const eligible = messages
        .filter((m) => m.channel_id === channelId && (beforeSeq === null || m.seq < Number(beforeSeq)) && (threadId === null || m.thread_id === threadId))
        .sort((a, b) => a.seq - b.seq);
      const page = eligible.slice(-limit);
      return json(res, 200, {
        messages: page.map((m) => messageShape(m, user)),
        has_more: eligible.length > page.length,
      });
    }
    if (req.method === "GET" && path === "/api/search") {
      const q = (url.searchParams.get("q") ?? "").toLowerCase();
      const readable = new Set(readableChannels(user).map((c) => c.id));
      return json(res, 200, {
        messages: messages
          .filter((m) => readable.has(m.channel_id) && m.deleted_at === null && m.body.toLowerCase().includes(q))
          .map((m) => messageShape(m, user)),
        threads: threads.filter((t) => readable.has(t.channel_id) && t.title.toLowerCase().includes(q)),
      });
    }
    if (req.method === "GET" && path === "/api/threads") {
      const channelId = url.searchParams.get("channel_id");
      const ch = channelById.get(channelId);
      if (!ch || !readableChannels(user).includes(ch)) return json(res, 404, { error: "not_found" });
      return json(res, 200, { threads: threads.filter((t) => t.channel_id === channelId) });
    }
    if (req.method === "GET" && path === "/api/modules/pump") {
      const operator = ROLE_RANK[effectiveRole(user)] >= 2;
      return json(res, 200, {
        wallets: [
          { address_short: "HoSt…1111", balance_text: "1.2400 SOL", tank_state_text: "Tank armed" },
          { address_short: "MiNe…2222", balance_text: "0.8500 SOL", tank_state_text: "Tank low" },
        ],
        day_bank: { banked_days: 2, active_remaining_seconds: null, first_day_free: true },
        atm: { state: operator ? "included" : "locked" },
      });
    }
    if (req.method === "GET" && path === "/api/modules/nightshift") {
      return json(res, 200, {
        fleet: {
          headline: "Fleet nominal — 4 rigs reporting",
          lines: [
            { label: "Active rigs", value: "4" },
            { label: "Best round", value: "128 ORE" },
          ],
        },
        suggestions,
      });
    }
    if (req.method === "GET" && path === "/api/intel") {
      return json(res, 200, {
        items: [
          { title: "Round window shifting earlier", body: "Motherlode rounds landed 40 minutes earlier than the weekly median.", severity: "INFO", at: nowIso() },
          { title: "Two wallets went dark", body: "Regular winners from last week have not placed in 48 hours.", severity: "ALERT", at: nowIso() },
        ],
      });
    }
    if (req.method === "GET" && path === "/api/passes") {
      return json(res, 200, {
        lifetime_holder: effectiveRole(user) === "prestige",
        prestige_seats_remaining: 97,
      });
    }
    if (req.method === "GET" && path === "/api/crown") {
      return json(res, 200, { holder_name: "Operator Ola", month: "November 2026" });
    }
    if (req.method === "GET" && path === "/api/sanctions") {
      return json(res, 200, {
        sanctions: sanctions
          .filter((s) => s.user_id === user.id)
          .map((s) => ({
            sanction_id: s.sanction_id, rung: s.rung, reason_category: s.reason_category,
            duration_sec: s.duration_sec, appeal: { state: s.appeal.state },
          })),
      });
    }
    if (req.method === "GET" && path === "/api/mod/queue") {
      if (!entitlementsFor(user).capabilities.mod_loft) return json(res, 403, { error: "not_entitled" });
      return json(res, 200, {
        items: modQueue.map((item) => ({
          report_id: item.report_id, state: item.state, claimed_by: item.claimed_by,
          reason: item.reason, reporter_count: item.reporter_count,
          snapshot: {
            body: item.snapshot.body, author_id: item.snapshot.author_id,
            channel_id: item.snapshot.channel_id, created_at: item.snapshot.created_at,
            context: item.snapshot.context.map((m) => messageShape(m, user)),
          },
          author_history: item.author_history,
          appeal: item.appeal,
        })),
      });
    }
    if (req.method === "GET" && path === "/api/mod/log") {
      if (!entitlementsFor(user).capabilities.mod_loft) return json(res, 403, { error: "not_entitled" });
      return json(res, 200, { entries: modLog });
    }
    if (req.method === "POST" && path === "/api/reports") {
      const body = await readBody(req);
      const target = messages.find((m) => m.id === body.target_id);
      if (body.target_kind !== "message" || !target) return json(res, 404, { error: "not_found" });
      modQueue.push({
        report_id: `r_${++reportCounter}`, state: "open", claimed_by: null,
        reason: body.reason ?? "other", reporter_count: 1,
        snapshot: snapshotOf(target), author_history: [], appeal: null, sanction_id: null,
      });
      return json(res, 200, { ok: true });
    }
    if (req.method === "POST" && path === "/api/appeals") {
      const body = await readBody(req);
      const sanction = sanctions.find((s) => s.sanction_id === body.sanction_id && s.user_id === user.id);
      if (!sanction) return json(res, 404, { error: "not_found" });
      sanction.appeal.state = "pending";
      sanction.statement = body.statement ?? "";
      modQueue.push({
        report_id: `r_${++reportCounter}`, state: "open", claimed_by: null,
        reason: sanction.reason_category, reporter_count: 1,
        snapshot: { body: sanction.statement, author_id: user.id, channel_id: "ch_general", created_at: nowIso(), context: [] },
        author_history: [],
        appeal: { statement: sanction.statement, sanction_by: "u_mod", state: "pending" },
        sanction_id: sanction.sanction_id,
      });
      return json(res, 200, { ok: true });
    }
    if (req.method === "POST" && path === "/api/mod/claim") {
      if (!entitlementsFor(user).capabilities.mod_loft) return json(res, 403, { error: "not_entitled" });
      const body = await readBody(req);
      const item = modQueue.find((i) => i.report_id === body.report_id);
      if (!item || item.state !== "open") return json(res, 409, { error: "not_open" });
      item.state = "claimed";
      item.claimed_by = user.id;
      return json(res, 200, { ok: true });
    }
    if (req.method === "POST" && path === "/api/mod/action") {
      if (!entitlementsFor(user).capabilities.mod_loft) return json(res, 403, { error: "not_entitled" });
      const body = await readBody(req);
      const item = modQueue.find((i) => i.report_id === body.report_id);
      if (!item || item.state === "closed") return json(res, 404, { error: "not_found" });
      if (item.claimed_by && item.claimed_by !== user.id && !body.override_claim) {
        return json(res, 409, { error: "claimed_by_other" });
      }
      if (!["content_removed", "slowmode", "mute", "suspension", "ban"].includes(body.action)) {
        return json(res, 400, { error: "unknown_action" });
      }
      const authorId = item.snapshot.author_id;
      if (body.action === "content_removed") {
        const target = messages.find((m) => m.body === item.snapshot.body && m.author_id === authorId && m.channel_id === item.snapshot.channel_id);
        if (target && !target.deleted_at) {
          target.deleted_at = nowIso();
          broadcast(target.channel_id, {
            type: "message.delete",
            channel_id: target.channel_id,
            payload: { channel_id: target.channel_id, message_id: target.id, seq: target.seq },
          });
        }
      } else if (["slowmode", "mute", "suspension", "ban"].includes(body.action)) {
        sanctions.push({
          sanction_id: `san_${++sanctionCounter}`, user_id: authorId,
          rung: body.action, reason_category: body.reason_code ?? "other",
          duration_sec: body.duration_sec ?? null, appeal: { state: "none" }, statement: null,
        });
        if (body.action === "suspension" || body.action === "ban") {
          const author = users.get(authorId);
          if (author) { author.banned = true; pushEntitlements(author); }
        }
      }
      item.state = "closed";
      modLog.push({ actor_id: user.id, action: body.action, target_id: authorId, reason: body.reason_code ?? "other", at: nowIso() });
      return json(res, 200, { ok: true });
    }
    if (req.method === "POST" && path === "/api/mod/appeal") {
      if (!entitlementsFor(user).capabilities.mod_loft) return json(res, 403, { error: "not_entitled" });
      const body = await readBody(req);
      const item = modQueue.find((i) => i.report_id === body.report_id);
      if (!item || !item.appeal || item.state === "closed") return json(res, 404, { error: "not_found" });
      const sanction = sanctions.find((s) => s.sanction_id === item.sanction_id);
      if (sanction) {
        sanction.appeal.state = body.outcome;
        if (body.outcome === "overturned") {
          sanctions.splice(sanctions.indexOf(sanction), 1);
          const author = users.get(sanction.user_id);
          if (author && author.banned) { author.banned = false; pushEntitlements(author); }
        }
      }
      if (item.appeal) item.appeal.state = body.outcome;
      item.state = "closed";
      modLog.push({ actor_id: user.id, action: `appeal_${body.outcome}`, target_id: item.snapshot.author_id, reason: body.reason_code ?? "other", at: nowIso() });
      return json(res, 200, { ok: true });
    }
    if (req.method === "POST" && path === "/api/modules/nightshift/suggestions") {
      const body = await readBody(req);
      const idx = suggestions.findIndex((s) => s.id === body.suggestion_id);
      if (idx === -1) return json(res, 404, { error: "not_found" });
      suggestions.splice(idx, 1);
      return json(res, 200, { ok: true });
    }
    // LOCAL TEST HOOKS (not contract surface): drive SW2-03 — a lapse
    // (or restore) pushes role.update carrying the filtered channels.
    if (req.method === "POST" && path === "/api/dev/lapse") {
      const body = await readBody(req);
      const target = users.get(body.user_id);
      if (!target) return json(res, 404, { error: "not_found" });
      target.lapsed = true;
      pushEntitlements(target);
      return json(res, 200, { ok: true });
    }
    if (req.method === "POST" && path === "/api/dev/restore") {
      const body = await readBody(req);
      const target = users.get(body.user_id);
      if (!target) return json(res, 404, { error: "not_found" });
      target.lapsed = false;
      target.banned = false;
      pushEntitlements(target);
      return json(res, 200, { ok: true });
    }
    return json(res, 404, { error: "not_found" });
  } catch (err) {
    return json(res, 500, { error: String(err && err.message || err) });
  }
});

/* ============================ WebSocket layer =========================== */

const WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
const acceptKey = (key) => crypto.createHash("sha1").update(key + WS_GUID).digest("base64");

function encodeFrame(text) {
  const payload = Buffer.from(text, "utf8");
  const len = payload.length;
  let header;
  if (len < 126) {
    header = Buffer.from([0x81, len]);
  } else if (len < 65536) {
    header = Buffer.alloc(4);
    header[0] = 0x81; header[1] = 126; header.writeUInt16BE(len, 2);
  } else {
    header = Buffer.alloc(10);
    header[0] = 0x81; header[1] = 127; header.writeBigUInt64BE(BigInt(len), 2);
  }
  return Buffer.concat([header, payload]);
}

// Minimal frame parser: handles masked client frames, fragmentation
// is not expected from the client (single-frame sends) but partial
// TCP chunks are buffered correctly.
class FrameParser {
  constructor(onText, onClose) {
    this.buf = Buffer.alloc(0);
    this.onText = onText;
    this.onClose = onClose;
  }
  push(chunk) {
    this.buf = Buffer.concat([this.buf, chunk]);
    for (;;) {
      if (this.buf.length < 2) return;
      const b0 = this.buf[0], b1 = this.buf[1];
      const opcode = b0 & 0x0f;
      const masked = (b1 & 0x80) !== 0;
      let len = b1 & 0x7f;
      let off = 2;
      if (len === 126) {
        if (this.buf.length < off + 2) return;
        len = this.buf.readUInt16BE(off); off += 2;
      } else if (len === 127) {
        if (this.buf.length < off + 8) return;
        len = Number(this.buf.readBigUInt64BE(off)); off += 8;
      }
      const maskLen = masked ? 4 : 0;
      if (this.buf.length < off + maskLen + len) return;
      let payload = this.buf.subarray(off + maskLen, off + maskLen + len);
      if (masked) {
        const mask = this.buf.subarray(off, off + 4);
        payload = Buffer.from(payload.map((b, i) => b ^ mask[i % 4]));
      }
      this.buf = this.buf.subarray(off + maskLen + len);
      if (opcode === 0x8) { this.onClose(); return; }
      if (opcode === 0x9) continue; // ping: the client's own keepalive is app-level; ignore
      if (opcode === 0x1 || opcode === 0x0) this.onText(payload.toString("utf8"));
    }
  }
}

const idempotency = new Map(); // `${userId}:${frameId}` -> { message_id, seq }
const lastSendAt = new Map(); // `${userId}:${channelId}` -> epoch ms

function frame(type, payload, channelId) {
  const env = { type, ts: nowIso(), payload };
  if (channelId) env.channel_id = channelId;
  return env;
}
function errorFrame(forId, code, message, channelId) {
  const payload = { code, message };
  if (forId) payload.for_id = forId;
  if (channelId) payload.channel_id = channelId;
  return { type: "error", ts: nowIso(), payload };
}

function handleClientFrame(sock, text) {
  let env;
  try { env = JSON.parse(text); } catch { return; }
  const user = sock.user;
  const payload = env.payload ?? {};
  const channelId = env.channel_id ?? payload.channel_id;
  switch (env.type) {
    case "subscribe": {
      const readable = new Set(readableChannels(user).map((c) => c.id));
      for (const id of payload.channel_ids ?? []) {
        if (readable.has(id)) {
          sock.subs.add(id);
        } else {
          // Per-id refusal, never silently granted (CONTRACT §3).
          sock.send(errorFrame(null, "not_entitled", `cannot subscribe to ${id}`, id));
        }
      }
      // Presence snapshot on subscribe (CONTRACT §6).
      for (const uid of users.keys()) sock.send(frame("presence", { presence: presenceShape(uid) }));
      break;
    }
    case "unsubscribe": {
      for (const id of payload.channel_ids ?? []) sock.subs.delete(id);
      break;
    }
    case "resume": {
      const readable = new Set(readableChannels(user).map((c) => c.id));
      for (const [chId, lastSeq] of Object.entries(payload.last_seq ?? {})) {
        if (!readable.has(chId)) continue;
        sock.subs.add(chId);
        const gap = messages
          .filter((m) => m.channel_id === chId && m.seq > Number(lastSeq))
          .sort((a, b) => a.seq - b.seq);
        if (gap.length > 500) {
          sock.send(frame("resync", { channel_id: chId, reason: "behind" }, chId));
        } else {
          for (const m of gap) sock.send(frame("message", { message: messageShape(m, user) }, chId));
        }
      }
      break;
    }
    case "send": {
      const key = `${user.id}:${env.id}`;
      if (env.id && idempotency.has(key)) {
        const prior = idempotency.get(key);
        sock.send(frame("ack", { for_id: env.id, message_id: prior.message_id, seq: prior.seq }, channelId));
        return;
      }
      const ch = channelById.get(channelId);
      const ents = entitlementsFor(user);
      if (!ch || !ents.capabilities.post.includes(channelId)) {
        sock.send(errorFrame(env.id, "not_entitled", "posting is not entitled in this channel", channelId));
        return;
      }
      if (ch.slowmode_sec > 0) {
        const last = lastSendAt.get(`${user.id}:${channelId}`) ?? 0;
        const waitMs = ch.slowmode_sec * 1000 - (Date.now() - last);
        if (waitMs > 0) {
          sock.send(errorFrame(env.id, "slowmode", `slowmode: wait ${Math.ceil(waitMs / 1000)}s`, channelId));
          return;
        }
      }
      const seq = seqByChannel.get(channelId) + 1;
      seqByChannel.set(channelId, seq);
      const m = {
        id: `m_${++msgCounter}`, channel_id: channelId, seq,
        author_id: user.id, body: String(payload.body ?? ""),
        thread_id: payload.thread_id ?? null, reply_to: payload.reply_to ?? null,
        created_at: nowIso(), edited_at: null, deleted_at: null,
        reactions: new Map(), commercial: false,
      };
      messages.push(m);
      if (m.thread_id !== null) {
        const thread = threads.find((t) => t.id === m.thread_id);
        if (thread) {
          thread.reply_count += 1;
          thread.last_activity_at = m.created_at;
        }
      }
      lastSendAt.set(`${user.id}:${channelId}`, Date.now());
      if (env.id) idempotency.set(key, { message_id: m.id, seq });
      sock.send(frame("ack", { for_id: env.id, message_id: m.id, seq }, channelId));
      // Broadcast incl. the sender — the client reconciles by id
      // (CONTRACT §4). A fresh message has no reactions, so the
      // serialization is viewer-independent here.
      broadcast(channelId, frame("message", { message: messageShape(m, user) }, channelId));
      break;
    }
    case "edit": {
      const m = messages.find((x) => x.id === payload.message_id && x.channel_id === channelId);
      if (!m || m.author_id !== user.id) { sock.send(errorFrame(null, "not_entitled", "own messages only")); return; }
      m.body = String(payload.body ?? m.body);
      m.edited_at = nowIso();
      sendToChannel(channelId, (viewer) => frame("message.update", { message: messageShape(m, viewer) }, channelId));
      break;
    }
    case "delete": {
      const m = messages.find((x) => x.id === payload.message_id && x.channel_id === channelId);
      if (!m || (m.author_id !== user.id && !user.moderator)) { sock.send(errorFrame(null, "not_entitled", "own messages only")); return; }
      if (!m.deleted_at) {
        m.deleted_at = nowIso();
        broadcast(channelId, frame("message.delete", { channel_id: channelId, message_id: m.id, seq: m.seq }, channelId));
      }
      break;
    }
    case "react": {
      const ents = entitlementsFor(user);
      if (!ents.capabilities.react.includes(channelId)) { sock.send(errorFrame(null, "not_entitled", "reactions are not entitled in this channel")); return; }
      const m = messages.find((x) => x.id === payload.message_id && x.channel_id === channelId);
      if (!m) return;
      const actors = m.reactions.get(payload.emoji) ?? new Set();
      if (payload.op === "add") actors.add(user.id); else actors.delete(user.id);
      m.reactions.set(payload.emoji, actors);
      broadcast(channelId, frame("reaction", {
        message_id: m.id, emoji: payload.emoji, count: actors.size, actor_ids: [...actors],
      }, channelId));
      break;
    }
    case "typing": {
      // Relay is an extension beyond CONTRACT §4 (the client's inbound
      // typing path is a stub — applyTypingStub); harmless if ignored.
      broadcast(channelId, frame("typing", { user_id: user.id, state: payload.state }, channelId), sock);
      break;
    }
    case "presence.set": {
      const p = presence.get(user.id) ?? { state: "online", activity: null, invisible: false };
      p.invisible = !!payload.invisible;
      presence.set(user.id, p);
      broadcast(null, frame("presence", { presence: presenceShape(user.id) }));
      break;
    }
    default:
      break;
  }
}

// Broadcast with per-viewer serialization (reaction `mine` differs).
function sendToChannel(channelId, makeFrame) {
  for (const s of sockets) if (s.subs.has(channelId)) s.send(makeFrame(s.user));
}

server.on("upgrade", (req, socket, head) => {
  const url = new URL(req.url, "http://localhost");
  if (url.pathname !== "/ws") { socket.destroy(); return; }
  const key = req.headers["sec-websocket-key"];
  if (!key) { socket.destroy(); return; }
  const user = callerOf(req);
  socket.write(
    "HTTP/1.1 101 Switching Protocols\r\n" +
    "Upgrade: websocket\r\n" +
    "Connection: Upgrade\r\n" +
    `Sec-WebSocket-Accept: ${acceptKey(key)}\r\n\r\n`,
  );
  const sock = {
    user,
    subs: new Set(),
    send(obj) {
      try { socket.write(encodeFrame(JSON.stringify(obj))); } catch { /* closed */ }
    },
  };
  sockets.add(sock);
  const p = presence.get(user.id) ?? { state: "offline", activity: null, invisible: false };
  if (p.state === "offline" && !p.invisible) {
    p.state = "online";
    presence.set(user.id, p);
    broadcast(null, frame("presence", { presence: presenceShape(user.id) }));
  }
  const parser = new FrameParser(
    (text) => handleClientFrame(sock, text),
    () => socket.end(),
  );
  socket.on("data", (chunk) => parser.push(chunk));
  const onGone = () => {
    if (!sockets.delete(sock)) return;
    const stillConnected = [...sockets].some((s) => s.user.id === user.id);
    if (!stillConnected) {
      const pp = presence.get(user.id);
      if (pp && pp.state !== "offline") {
        pp.state = "offline";
        presence.set(user.id, pp);
        broadcast(null, frame("presence", { presence: presenceShape(user.id) }));
      }
    }
  };
  socket.on("close", onGone);
  socket.on("error", onGone);
  // HTTP upgrade sockets are allowHalfOpen: a peer that drops TCP
  // without a WS close frame emits "end" here but never "close", so
  // without this the socket wrapper (and its presence) leaks forever.
  // Complete the close; onGone is delete-guarded and idempotent.
  socket.on("end", () => { socket.end(); onGone(); });
  if (head && head.length) parser.push(head);
});

server.listen(PORT, HOST, () => {
  console.log(`kingdom local backend listening on ${HOST}:${PORT} (ws at /ws)`);
});
