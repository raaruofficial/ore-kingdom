// Contract-surface tests for the local Kingdom backend.
// Boots server.mjs on a scratch port and exercises the exact surface
// the Android host layer calls (KingdomTransport.kt): REST reads and
// actions + the CONTRACT.md §2–§4 websocket frames. Zero deps.
// Run: node test.mjs   (exit 0 = all pass)

import { spawn } from "node:child_process";
import { readFileSync } from "node:fs";
import net from "node:net";
import crypto from "node:crypto";

const PORT = 18799;
const BASE = `http://127.0.0.1:${PORT}`;
let failures = 0;
let checks = 0;
function ok(cond, label) {
  checks++;
  if (!cond) { failures++; console.error(`FAIL: ${label}`); }
  else console.log(`ok: ${label}`);
}
const asUser = (id) => ({ authorization: `Bearer local:${id}`, "content-type": "application/json" });
async function get(path, user = "u_host") {
  const r = await fetch(BASE + path, { headers: asUser(user) });
  return { status: r.status, body: await r.json().catch(() => ({})) };
}
async function post(path, obj, user = "u_host") {
  const r = await fetch(BASE + path, { method: "POST", headers: asUser(user), body: JSON.stringify(obj) });
  return { status: r.status, body: await r.json().catch(() => ({})) };
}

/* ---------------- minimal WS client (masked frames) ---------------- */
class WsTest {
  constructor() { this.frames = []; this.waiters = []; }
  async connect(user) {
    const key = crypto.randomBytes(16).toString("base64");
    this.socket = net.connect(PORT, "127.0.0.1");
    await new Promise((resolve, reject) => {
      this.socket.once("connect", resolve);
      this.socket.once("error", reject);
    });
    const handshake =
      `GET /ws HTTP/1.1\r\nHost: 127.0.0.1:${PORT}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n` +
      `Sec-WebSocket-Key: ${key}\r\nSec-WebSocket-Version: 13\r\nAuthorization: Bearer local:${user}\r\n\r\n`;
    this.buf = Buffer.alloc(0);
    this.handshook = false;
    this.socket.on("data", (chunk) => {
      this.buf = Buffer.concat([this.buf, chunk]);
      if (!this.handshook) {
        const idx = this.buf.indexOf("\r\n\r\n");
        if (idx === -1) return;
        const head = this.buf.subarray(0, idx).toString();
        if (!head.includes("101")) throw new Error("handshake failed: " + head);
        this.handshook = true;
        this.buf = this.buf.subarray(idx + 4);
        this.onReady?.();
      }
      this.drain();
    });
    await new Promise((resolve) => { this.onReady = resolve; this.socket.write(handshake); });
    return this;
  }
  drain() {
    for (;;) {
      if (this.buf.length < 2) return;
      let len = this.buf[1] & 0x7f;
      let off = 2;
      if (len === 126) { if (this.buf.length < 4) return; len = this.buf.readUInt16BE(2); off = 4; }
      else if (len === 127) { if (this.buf.length < 10) return; len = Number(this.buf.readBigUInt64BE(2)); off = 10; }
      if (this.buf.length < off + len) return;
      const payload = this.buf.subarray(off, off + len).toString("utf8");
      this.buf = this.buf.subarray(off + len);
      const obj = JSON.parse(payload);
      this.frames.push(obj);
      this.waiters = this.waiters.filter((w) => !w(obj));
    }
  }
  send(obj) {
    const payload = Buffer.from(JSON.stringify(obj), "utf8");
    const mask = crypto.randomBytes(4);
    let header;
    if (payload.length < 126) header = Buffer.from([0x81, 0x80 | payload.length]);
    else if (payload.length < 65536) { header = Buffer.alloc(4); header[0] = 0x81; header[1] = 0x80 | 126; header.writeUInt16BE(payload.length, 2); }
    else { header = Buffer.alloc(10); header[0] = 0x81; header[1] = 0x80 | 127; header.writeBigUInt64BE(BigInt(payload.length), 2); }
    const masked = Buffer.from(payload.map((b, i) => b ^ mask[i % 4]));
    this.socket.write(Buffer.concat([header, mask, masked]));
  }
  sendRaw(text) {
    const payload = Buffer.from(text, "utf8");
    const mask = crypto.randomBytes(4);
    const header = Buffer.from([0x81, 0x80 | payload.length]);
    const masked = Buffer.from(payload.map((b, i) => b ^ mask[i % 4]));
    this.socket.write(Buffer.concat([header, mask, masked]));
  }
  waitFor(pred, label, timeoutMs = 3000) {
    const existing = this.frames.find(pred);
    if (existing) return Promise.resolve(existing);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error("timeout waiting: " + label)), timeoutMs);
      this.waiters.push((obj) => {
        if (pred(obj)) { clearTimeout(timer); resolve(obj); return true; }
        return false;
      });
    });
  }
  count(pred) { return this.frames.filter(pred).length; }
  close() { this.socket?.end(); }
}

/* --------------------------------- run --------------------------------- */
const server = spawn("node", ["server.mjs"], {
  cwd: new URL(".", import.meta.url).pathname,
  env: { ...process.env, KINGDOM_PORT: String(PORT), KINGDOM_HOST: "127.0.0.1" },
  stdio: ["ignore", "pipe", "inherit"],
});
await new Promise((resolve) => server.stdout.once("data", resolve));

try {
  // 1 — entitlements shape (member), exact capability keys
  const ent = await get("/api/entitlements", "u_host");
  ok(ent.status === 200 && ent.body.role === "member", "entitlements: member role");
  const caps = ent.body.capabilities ?? {};
  ok(["post", "react", "tip", "gift", "commercial_post", "spotlight_bid", "pump_module",
    "pump_day_included", "atm_unlock_included", "nightshift_console", "autopilot_surface", "mod_loft",
  ].every((k) => k in caps), "entitlements: all 12 capability keys present");
  ok(Array.isArray(caps.post) && caps.post.includes("ch_general") && !caps.post.includes("ch_announcements"),
    "entitlements: post list excludes house channel");
  ok(ent.body.modules?.nightshift === "preview", "entitlements: member nightshift module is preview");

  // 2 — channel list is server-filtered by read role (SW2-03 read half)
  const freeCh = await get("/api/channels", "u_free");
  ok(!freeCh.body.channels.some((c) => c.id === "ch_strategy"), "channels: free user does NOT see member-gated ch_strategy");
  const memberCh = await get("/api/channels", "u_host");
  ok(memberCh.body.channels.some((c) => c.id === "ch_strategy" && c.slowmode_sec === 30), "channels: member sees ch_strategy with slowmode 30");

  // 3 — history pagination
  const hist = await get("/api/history?channel_id=ch_general&limit=2", "u_host");
  ok(hist.status === 200 && hist.body.messages.length === 2 && hist.body.has_more === true, "history: page of 2 with has_more");
  ok(hist.body.messages[0].seq < hist.body.messages[1].seq, "history: ascending seq");
  const older = await get(`/api/history?channel_id=ch_general&before_seq=${hist.body.messages[0].seq}&limit=50`, "u_host");
  ok(older.body.messages.every((m) => m.seq < hist.body.messages[0].seq), "history: before_seq cursor respected");
  const gated = await get("/api/history?channel_id=ch_strategy", "u_free");
  ok(gated.status === 404, "history: gated channel is 404 for free user (no existence leak)");

  // 4–8 — websocket behaviour
  const a = await new WsTest().connect("u_host");
  const b = await new WsTest().connect("u_op");
  a.send({ type: "subscribe", payload: { channel_ids: ["ch_general"] } });
  b.send({ type: "subscribe", payload: { channel_ids: ["ch_general", "ch_strategy"] } });
  await a.waitFor((f) => f.type === "presence", "presence snapshot for A");
  ok(true, "ws: presence snapshot delivered on subscribe");

  const aBad = await new WsTest().connect("u_free");
  aBad.send({ type: "subscribe", payload: { channel_ids: ["ch_strategy"] } });
  const refusal = await aBad.waitFor((f) => f.type === "error", "subscribe refusal");
  ok(refusal.payload.code === "not_entitled" && refusal.payload.channel_id === "ch_strategy",
    "ws: unentitled subscribe refused per-id with channel_id");

  const ref = "c_test_1";
  a.send({ type: "send", id: ref, channel_id: "ch_general", payload: { body: "hello from A" } });
  const ack = await a.waitFor((f) => f.type === "ack" && f.payload.for_id === ref, "ack for A send");
  ok(typeof ack.payload.message_id === "string" && ack.payload.seq > 0, "ws: ack carries message_id + seq");
  const echoed = await b.waitFor((f) => f.type === "message" && f.payload.message.id === ack.payload.message_id, "broadcast to B");
  ok(echoed.payload.message.body === "hello from A", "ws: second subscriber receives the message");

  // idempotent retry: same frame id -> same ack, no second broadcast
  const bCountBefore = b.count((f) => f.type === "message" && f.payload.message.id === ack.payload.message_id);
  a.send({ type: "send", id: ref, channel_id: "ch_general", payload: { body: "hello from A" } });
  const ack2 = await a.waitFor((f) => f.type === "ack" && f.payload.for_id === ref && f !== ack, "idempotent re-ack");
  ok(ack2.payload.message_id === ack.payload.message_id, "ws: idempotent retry returns the same message_id");
  await new Promise((r) => setTimeout(r, 250));
  ok(b.count((f) => f.type === "message" && f.payload.message.id === ack.payload.message_id) === bCountBefore,
    "ws: idempotent retry does not re-broadcast");

  // free user cannot post
  aBad.send({ type: "send", id: "c_free_1", channel_id: "ch_general", payload: { body: "let me in" } });
  const denied = await aBad.waitFor((f) => f.type === "error" && f.payload.for_id === "c_free_1", "free send refusal");
  ok(denied.payload.code === "not_entitled", "ws: free user's send refused not_entitled with for_id");

  // slowmode: first send in ch_strategy ok, immediate second refused
  b.send({ type: "send", id: "c_slow_1", channel_id: "ch_strategy", payload: { body: "first slow post" } });
  await b.waitFor((f) => f.type === "ack" && f.payload.for_id === "c_slow_1", "slowmode first ack");
  b.send({ type: "send", id: "c_slow_2", channel_id: "ch_strategy", payload: { body: "too fast" } });
  const slow = await b.waitFor((f) => f.type === "error" && f.payload.for_id === "c_slow_2", "slowmode refusal");
  ok(slow.payload.code === "slowmode", "ws: second send inside 30s refused with code slowmode");

  // resume replays the gap
  const c = await new WsTest().connect("u_op");
  c.send({ type: "resume", payload: { last_seq: { ch_general: ack.payload.seq - 1 } } });
  const replay = await c.waitFor((f) => f.type === "message" && f.payload.message.id === ack.payload.message_id, "resume replay");
  ok(replay.payload.message.seq === ack.payload.seq, "ws: resume replays messages after last_seq");

  // reactions aggregate
  a.send({ type: "react", channel_id: "ch_general", payload: { message_id: ack.payload.message_id, emoji: "🔥", op: "add" } });
  const reaction = await b.waitFor((f) => f.type === "reaction" && f.payload.message_id === ack.payload.message_id, "reaction frame");
  ok(reaction.payload.count === 1 && reaction.payload.actor_ids.includes("u_host"), "ws: reaction aggregates with actor_ids");

  // 9 — report -> queue -> claim -> content_removed -> tombstone + log
  const before = await get("/api/history?channel_id=ch_general&limit=50", "u_host");
  const spam = before.body.messages.find((m) => m.author_id === "u_spammer");
  ok(!!spam, "seed: spam message present in ch_general");
  const rep = await post("/api/reports", { target_kind: "message", target_id: spam.id, reason: "scam", note: "test" }, "u_host");
  ok(rep.status === 200, "reports: submission accepted");
  const queue = await get("/api/mod/queue", "u_mod");
  const item = queue.body.items.find((i) => i.snapshot.body === spam.body && i.state === "open");
  ok(!!item && item.snapshot.context.length > 0, "mod queue: report lands with snapshot + context");
  const noMod = await get("/api/mod/queue", "u_host");
  ok(noMod.status === 403, "mod queue: non-moderator is refused");
  const claim = await post("/api/mod/claim", { report_id: item.report_id }, "u_mod");
  ok(claim.status === 200, "mod claim: succeeds");
  const act = await post("/api/mod/action", { report_id: item.report_id, action: "content_removed", reason_code: "scam", note: "", override_claim: false }, "u_mod");
  ok(act.status === 200, "mod action: content_removed succeeds");
  const after = await get("/api/history?channel_id=ch_general&limit=50", "u_host");
  ok(after.body.messages.find((m) => m.id === spam.id)?.deleted_at !== null, "mod action: message tombstoned");
  const log = await get("/api/mod/log", "u_mod");
  ok(log.body.entries.some((e) => e.action === "content_removed" && e.target_id === "u_spammer"), "mod log: action appended");

  // 10 — SW2-03: lapse push carries entitlements + filtered channels
  const d = await new WsTest().connect("u_host");
  d.send({ type: "subscribe", payload: { channel_ids: ["ch_general"] } });
  await d.waitFor((f) => f.type === "presence", "presence for D");
  await post("/api/dev/lapse", { user_id: "u_host" }, "u_host");
  const push = await d.waitFor((f) => f.type === "role.update", "lapse role.update");
  ok(push.payload.entitlements.role === "free", "SW2-03: lapse push carries free-role entitlements");
  ok(Array.isArray(push.payload.channels) && !push.payload.channels.some((c) => c.id === "ch_strategy"),
    "SW2-03: lapse push carries the server-filtered channel list (no ch_strategy)");
  await post("/api/dev/restore", { user_id: "u_host" }, "u_host");
  const restored = await d.waitFor((f) => f.type === "role.update" && f.payload.entitlements.role === "member", "restore push");
  ok(restored.payload.channels.some((c) => c.id === "ch_strategy"), "SW2-03: restore push re-includes gated channels");

  // 11 — sanctions + appeal round trip
  const sanc = await get("/api/sanctions", "u_free");
  ok(sanc.body.sanctions.length === 1 && sanc.body.sanctions[0].appeal.state === "none", "sanctions: seeded mute with appeal none");
  await post("/api/appeals", { sanction_id: "san_1", statement: "I did not send that" }, "u_free");
  const queue2 = await get("/api/mod/queue", "u_mod");
  const appealItem = queue2.body.items.find((i) => i.appeal && i.state === "open");
  ok(!!appealItem && appealItem.appeal.sanction_by === "u_mod", "appeals: appeal item lands in the mod queue");
  await post("/api/mod/appeal", { report_id: appealItem.report_id, outcome: "overturned", reason_code: "other", note: "", override_claim: false }, "u_mod");
  const sanc2 = await get("/api/sanctions", "u_free");
  ok(sanc2.body.sanctions.length === 0, "appeals: overturned lifts the sanction");

  // 12 — module / passes / crown read shapes
  const pump = await get("/api/modules/pump", "u_op");
  ok(pump.body.wallets.length === 2 && pump.body.day_bank.first_day_free === true && pump.body.atm.state === "included",
    "pump read: wallets + day bank + atm included for operator");
  const ns = await get("/api/modules/nightshift", "u_op");
  ok(ns.body.fleet.headline.length > 0 && ns.body.suggestions.length === 2, "nightshift read: fleet + suggestions");
  const sug = await post("/api/modules/nightshift/suggestions", { suggestion_id: "sug_1", accept: true }, "u_op");
  ok(sug.status === 200, "nightshift: suggestion decision accepted");
  const intel = await get("/api/intel", "u_host");
  ok(intel.body.items.length === 2 && intel.body.items.every((i) => i.title && i.body && i.severity && i.at), "intel read: contract §8 item shape");
  const passes = await get("/api/passes", "u_host");
  ok(passes.body.lifetime_holder === false && passes.body.prestige_seats_remaining === 97, "passes read shape");
  const crown = await get("/api/crown", "u_host");
  ok(crown.body.holder_name === "Operator Ola", "crown read shape");
  const me = await get("/api/me", "u_mod");
  ok(me.body.user_id === "u_mod" && me.body.member.moderator === true, "me: caller identity + moderator flag");
  const spot = await get("/api/spotlight", "u_host");
  ok(spot.body.occupied === false && spot.body.floor_usd === 50, "spotlight: vacant at the $50 floor");

  // 13 — search is entitlement-filtered
  const found = await get("/api/search?q=motherlode", "u_host");
  ok(found.body.messages.length === 1 && found.body.threads.length === 1, "search: message + thread hits in member channel for member");
  const hidden = await get("/api/search?q=motherlode", "u_free");
  ok(hidden.body.threads.length === 0 && hidden.body.messages.length === 0, "search: member-gated results invisible to free user");


  // 14 — thread reads (list endpoint, history filter, reply bookkeeping)
  const threadsList = await get("/api/threads?channel_id=ch_strategy", "u_host");
  ok(threadsList.status === 200 && threadsList.body.threads.length === 1, "threads: list returns the seeded thread for a member");
  const seededThread = threadsList.body.threads[0];
  ok(seededThread.id === "t_1" && seededThread.channel_id === "ch_strategy" && seededThread.state === "active" &&
    seededThread.reply_count === 2 && seededThread.anchor_message_id === null && seededThread.title.includes("Motherlode"),
    "threads: seeded thread carries the contract shape");
  const threadsFree = await get("/api/threads?channel_id=ch_strategy", "u_free");
  ok(threadsFree.status === 404, "threads: gated channel is 404 for a free user");
  const threadsEmpty = await get("/api/threads?channel_id=ch_general", "u_host");
  ok(threadsEmpty.status === 200 && threadsEmpty.body.threads.length === 0, "threads: channel without threads returns an empty list");
  const threadsMissing = await get("/api/threads?channel_id=ch_missing", "u_host");
  ok(threadsMissing.status === 404, "threads: unknown channel is 404");

  const th = await new WsTest().connect("u_host");
  const tm = await new WsTest().connect("u_mod");
  th.send({ type: "send", id: "c_thread_1", channel_id: "ch_strategy", payload: { body: "thread reply one", thread_id: "t_1" } });
  const thAck = await th.waitFor((f) => f.type === "ack" && f.payload.for_id === "c_thread_1", "thread reply 1 ack");
  tm.send({ type: "send", id: "c_thread_2", channel_id: "ch_strategy", payload: { body: "thread reply two", thread_id: "t_1" } });
  const tmAck = await tm.waitFor((f) => f.type === "ack" && f.payload.for_id === "c_thread_2", "thread reply 2 ack");
  ok(!!thAck && !!tmAck, "threads: both replies acked");

  const thHist = await get("/api/history?channel_id=ch_strategy&thread_id=t_1", "u_host");
  ok(thHist.status === 200 && thHist.body.messages.length === 2 &&
    thHist.body.messages.every((m) => m.thread_id === "t_1"),
    "threads: history thread_id filter returns only that thread's messages");
  ok(thHist.body.messages.map((m) => m.body).join("|") === "thread reply one|thread reply two",
    "threads: filtered history carries the two replies in seq order");
  const thHistFree = await get("/api/history?channel_id=ch_strategy&thread_id=t_1", "u_free");
  ok(thHistFree.status === 404, "threads: history thread filter still 404 for a free user (channel gate dominates)");
  const thNone = await get("/api/history?channel_id=ch_strategy&thread_id=t_999", "u_host");
  ok(thNone.status === 200 && thNone.body.messages.length === 0, "threads: unknown thread_id filters to an empty page");

  const threadsAfter = await get("/api/threads?channel_id=ch_strategy", "u_host");
  ok(threadsAfter.body.threads[0].reply_count === 4, "threads: reply_count grows by the two real replies (2 seed + 2)");
  ok(threadsAfter.body.threads[0].last_activity_at === thHist.body.messages[1].created_at,
    "threads: last_activity_at tracks the newest reply");

  const acksBefore = tm.count((f) => f.type === "ack" && f.payload.for_id === "c_thread_2");
  tm.send({ type: "send", id: "c_thread_2", channel_id: "ch_strategy", payload: { body: "thread reply two", thread_id: "t_1" } });
  await new Promise((r) => setTimeout(r, 250));
  ok(tm.count((f) => f.type === "ack" && f.payload.for_id === "c_thread_2") === acksBefore + 1,
    "threads: idempotent retry re-acks");
  const threadsFinal = await get("/api/threads?channel_id=ch_strategy", "u_host");
  ok(threadsFinal.body.threads[0].reply_count === 4, "threads: idempotent retry does not double-count the reply");
  th.close(); tm.close();


  // 15 — backend edges (DeepSeek programme item 3): slowmode shape + isolation,
  // presence lifecycle, unsubscribe, edit/delete rights, malformed frames,
  // idempotency collisions, resume replay boundary, lapse-push consistency.
  // NOTE: the slowmode checks run first — they rely on u_host's ch_strategy
  // window opened by section 14 still being live (< 30s elapsed).
  const hS = await new WsTest().connect("u_host");
  const stratBefore = await get("/api/history?channel_id=ch_strategy&limit=200", "u_host");
  hS.send({ type: "send", id: "c_slow_shape", channel_id: "ch_strategy", payload: { body: "too soon" } });
  const slowErr = await hS.waitFor((f) => f.type === "error" && f.payload.for_id === "c_slow_shape", "slowmode shape refusal");
  const slowMatch = /^slowmode: wait (\d+)s$/.exec(slowErr.payload.message ?? "");
  ok(slowErr.payload.code === "slowmode" && !!slowMatch && Number(slowMatch[1]) >= 1 && Number(slowMatch[1]) <= 30,
    "slowmode edge: refusal names the remaining seconds (1..30)");
  const stratAfter = await get("/api/history?channel_id=ch_strategy&limit=200", "u_host");
  ok(stratAfter.body.messages.length === stratBefore.body.messages.length &&
    !stratAfter.body.messages.some((m) => m.body === "too soon"),
    "slowmode edge: a refused send writes no message");
  hS.send({ type: "send", id: "c_slow_other_ch", channel_id: "ch_general", payload: { body: "general is fine" } });
  await hS.waitFor((f) => f.type === "ack" && f.payload.for_id === "c_slow_other_ch", "other-channel ack");
  hS.send({ type: "send", id: "c_slow_other_ch2", channel_id: "ch_general", payload: { body: "general again" } });
  await hS.waitFor((f) => f.type === "ack" && f.payload.for_id === "c_slow_other_ch2", "other-channel ack 2");
  ok(true, "slowmode edge: the window is per-channel — ch_general sends are unaffected");

  // presence lifecycle: connect promotes, invisible is carried, last socket out goes offline
  // Section 4's aBad socket is still open and would hold u_free "connected"
  // (and already promoted) — close it first so the lifecycle below is real.
  aBad.close();
  await new Promise((r) => setTimeout(r, 150));
  const po = await new WsTest().connect("u_host");
  po.send({ type: "subscribe", payload: { channel_ids: ["ch_general"] } });
  // The snapshot is one frame per user and they can arrive in separate
  // chunks — wait until all five users are represented before counting.
  const snapDeadline = Date.now() + 3000;
  const snapUsers = () => new Set(po.frames.filter((f) => f.type === "presence").map((f) => f.payload.presence.user_id));
  while (snapUsers().size < 5 && Date.now() < snapDeadline) await new Promise((r) => setTimeout(r, 25));
  ok(snapUsers().size === 5, "presence edge: subscribe snapshot covers every user");
  const freeOffline = (f) => f.type === "presence" && f.payload.presence.user_id === "u_free" && f.payload.presence.state === "offline";
  const freeOnline = (f) => f.type === "presence" && f.payload.presence.user_id === "u_free" && f.payload.presence.state === "online";
  const offlineBase = po.count(freeOffline);
  const ps1 = await new WsTest().connect("u_free");
  await po.waitFor((f) => freeOnline(f) && f.payload.presence.invisible === false, "u_free promoted online");
  ok(true, "presence edge: connecting promotes an offline user to online for observers");
  ps1.send({ type: "presence.set", payload: { invisible: true } });
  const inv = await po.waitFor((f) => freeOnline(f) && f.payload.presence.invisible === true, "u_free invisible");
  ok(inv.payload.presence.state === "online", "presence edge: invisible is a flag on the shape, state stays as set");
  const ps2 = await new WsTest().connect("u_free");
  ps1.close();
  await new Promise((r) => setTimeout(r, 250));
  ok(po.count(freeOffline) === offlineBase, "presence edge: closing one of two sockets keeps the user online");
  ps2.close();
  const offDeadline = Date.now() + 3000;
  while (po.count(freeOffline) < offlineBase + 1 && Date.now() < offDeadline) await new Promise((r) => setTimeout(r, 25));
  ok(po.count(freeOffline) === offlineBase + 1, "presence edge: last socket out broadcasts offline");
  po.close();

  // unsubscribe stops delivery; resubscribe restores it
  const sub = await new WsTest().connect("u_mod");
  sub.send({ type: "subscribe", payload: { channel_ids: ["ch_general"] } });
  await sub.waitFor((f) => f.type === "presence", "unsub observer snapshot");
  const pub = await new WsTest().connect("u_op");
  pub.send({ type: "send", id: "c_unsub_1", channel_id: "ch_general", payload: { body: "unsub one" } });
  await sub.waitFor((f) => f.type === "message" && f.payload.message.body === "unsub one", "first delivery");
  sub.send({ type: "unsubscribe", payload: { channel_ids: ["ch_general"] } });
  await new Promise((r) => setTimeout(r, 100));
  pub.send({ type: "send", id: "c_unsub_2", channel_id: "ch_general", payload: { body: "unsub two" } });
  await pub.waitFor((f) => f.type === "ack" && f.payload.for_id === "c_unsub_2", "second send ack");
  await new Promise((r) => setTimeout(r, 250));
  ok(sub.count((f) => f.type === "message" && f.payload.message.body === "unsub two") === 0,
    "unsubscribe edge: no delivery after unsubscribe");
  sub.send({ type: "subscribe", payload: { channel_ids: ["ch_general"] } });
  await new Promise((r) => setTimeout(r, 100));
  pub.send({ type: "send", id: "c_unsub_3", channel_id: "ch_general", payload: { body: "unsub three" } });
  await sub.waitFor((f) => f.type === "message" && f.payload.message.body === "unsub three", "delivery after resubscribe");
  ok(true, "unsubscribe edge: resubscribe restores delivery");

  // edit/delete rights: author edits, others cannot, a moderator deletes
  const author = await new WsTest().connect("u_host");
  author.send({ type: "send", id: "c_edit_1", channel_id: "ch_general", payload: { body: "edit target" } });
  const editAck = await author.waitFor((f) => f.type === "ack" && f.payload.for_id === "c_edit_1", "edit target ack");
  const targetId = editAck.payload.message_id;
  pub.send({ type: "edit", channel_id: "ch_general", payload: { message_id: targetId, body: "hijacked" } });
  const editDeny = await pub.waitFor((f) => f.type === "error" && f.payload.code === "not_entitled", "edit denial");
  ok(!!editDeny, "edit edge: a non-author cannot edit");
  pub.send({ type: "delete", channel_id: "ch_general", payload: { message_id: targetId } });
  const delDeny = await pub.waitFor((f) => f.type === "error" && f.payload.code === "not_entitled" && f !== editDeny, "delete denial");
  ok(!!delDeny, "delete edge: a non-author non-moderator cannot delete");
  author.send({ type: "edit", channel_id: "ch_general", payload: { message_id: targetId, body: "edited body" } });
  const upd = await sub.waitFor((f) => f.type === "message.update" && f.payload.message.id === targetId, "edit broadcast");
  ok(upd.payload.message.body === "edited body" && upd.payload.message.edited_at !== null,
    "edit edge: author edit broadcasts message.update with edited_at");
  sub.send({ type: "delete", channel_id: "ch_general", payload: { message_id: targetId } });
  const del = await sub.waitFor((f) => f.type === "message.delete" && f.payload.message_id === targetId, "mod delete broadcast");
  ok(del.payload.seq === editAck.payload.seq, "delete edge: moderator delete broadcasts the tombstone frame");
  const histAfterDelete = await get("/api/history?channel_id=ch_general&limit=200", "u_host");
  ok(histAfterDelete.body.messages.find((m) => m.id === targetId)?.deleted_at !== null,
    "delete edge: history shows the tombstone");
  author.close();

  // malformed + unknown frames never kill the socket
  const mal = await new WsTest().connect("u_host");
  mal.sendRaw("{not json");
  mal.send({ type: "bogus", payload: {} });
  mal.send({ type: "send", id: "c_malformed_1", channel_id: "ch_general", payload: { body: "still alive" } });
  const malAck = await mal.waitFor((f) => f.type === "ack" && f.payload.for_id === "c_malformed_1", "post-malformed ack");
  ok(!!malAck, "malformed edge: socket survives non-JSON and unknown-type frames");
  mal.close();

  // idempotency collisions: same key + different body keeps the original; new key + same body is new
  hS.send({ type: "send", id: "c_dup_1", channel_id: "ch_general", payload: { body: "original body" } });
  const dupAck1 = await hS.waitFor((f) => f.type === "ack" && f.payload.for_id === "c_dup_1", "dup ack 1");
  hS.send({ type: "send", id: "c_dup_1", channel_id: "ch_general", payload: { body: "changed body" } });
  const dupAck2 = await hS.waitFor((f) => f.type === "ack" && f.payload.for_id === "c_dup_1" && f !== dupAck1, "dup ack 2");
  ok(dupAck2.payload.message_id === dupAck1.payload.message_id, "idempotency edge: same key + different body returns the original message");
  const dupHist = await get("/api/history?channel_id=ch_general&limit=200", "u_host");
  ok(dupHist.body.messages.filter((m) => m.id === dupAck1.payload.message_id).length === 1 &&
    !dupHist.body.messages.some((m) => m.body === "changed body"),
    "idempotency edge: the colliding body never lands");
  hS.send({ type: "send", id: "c_dup_2", channel_id: "ch_general", payload: { body: "original body" } });
  const dupAck3 = await hS.waitFor((f) => f.type === "ack" && f.payload.for_id === "c_dup_2", "dup ack 3");
  ok(dupAck3.payload.message_id !== dupAck1.payload.message_id, "idempotency edge: same body + new key is a new message");
  hS.close();

  // send does not require a subscription on the socket
  const noSub = await new WsTest().connect("u_mod");
  noSub.send({ type: "send", id: "c_nosub_1", channel_id: "ch_general", payload: { body: "no sub send" } });
  const noSubAck = await noSub.waitFor((f) => f.type === "ack" && f.payload.for_id === "c_nosub_1", "no-sub ack");
  ok(!!noSubAck, "send edge: an entitled send succeeds without subscribing first");
  noSub.close();

  // resume boundary: a gap of 501 resyncs; a gap of exactly 500 replays in full
  const bulk = await new WsTest().connect("u_op");
  for (let i = 1; i <= 501; i++) {
    const id = `c_bulk_${i}`;
    bulk.send({ type: "send", id, channel_id: "ch_help_desk", payload: { body: `bulk ${i}` } });
    await bulk.waitFor((f) => f.type === "ack" && f.payload.for_id === id, `bulk ack ${i}`);
  }
  const rBehind = await new WsTest().connect("u_op");
  rBehind.send({ type: "resume", payload: { last_seq: { ch_help_desk: 0 } } });
  const resync = await rBehind.waitFor((f) => f.type === "resync" && f.payload.channel_id === "ch_help_desk", "behind resync");
  ok(resync.payload.reason === "behind", "resume edge: a gap of 501 answers resync/behind");
  rBehind.close();
  const rEdge = await new WsTest().connect("u_op");
  rEdge.send({ type: "resume", payload: { last_seq: { ch_help_desk: 1 } } });
  const replayDeadline = Date.now() + 8000;
  const replayed = () => rEdge.frames.filter((f) => f.type === "message" && f.channel_id === "ch_help_desk");
  while (replayed().length < 500 && Date.now() < replayDeadline) await new Promise((r) => setTimeout(r, 25));
  ok(replayed().length === 500 && rEdge.count((f) => f.type === "resync") === 0,
    "resume edge: a gap of exactly 500 replays in full, no resync");
  ok(replayed()[0]?.payload.message.seq === 2 && replayed()[499]?.payload.message.seq === 501,
    "resume edge: replay covers seq 2..501 in order");
  rEdge.close();
  bulk.close();

  // lapse push consistency: the pushed channel list equals the REST read, both ways
  const lp = await new WsTest().connect("u_op");
  lp.send({ type: "subscribe", payload: { channel_ids: ["ch_general"] } });
  await lp.waitFor((f) => f.type === "presence", "lapse-push observer ready");
  await post("/api/dev/lapse", { user_id: "u_op" }, "u_op");
  const lapsePush = await lp.waitFor((f) => f.type === "role.update", "u_op lapse push");
  const lapsedRest = await get("/api/channels", "u_op");
  ok(lapsePush.payload.entitlements.role === "free" &&
    lapsePush.payload.channels.map((c) => c.id).sort().join(",") === lapsedRest.body.channels.map((c) => c.id).sort().join(","),
    "SW2-03 edge: lapse push channel set equals the REST channel read");
  await post("/api/dev/restore", { user_id: "u_op" }, "u_op");
  const restorePush = await lp.waitFor((f) => f.type === "role.update" && f.payload.entitlements.role === "operator", "u_op restore push");
  const restoredRest = await get("/api/channels", "u_op");
  ok(restorePush.payload.channels.map((c) => c.id).sort().join(",") === restoredRest.body.channels.map((c) => c.id).sort().join(","),
    "SW2-03 edge: restore push channel set equals the REST channel read");
  lp.close();
  sub.close();
  pub.close();


  // 17 — pass + moderation edges (DeepSeek programme item 5): claim/action
  // state machine, appeal paths, mod-gate coverage, lapse read half.
  // Moderation state here is built fresh (sections 9/11 already consumed
  // the seeded queue item and sanction).
  const logDenied = await get("/api/mod/log", "u_host");
  ok(logDenied.status === 403, "mod edge: mod log is 403 for a non-moderator");
  const claimDenied = await post("/api/mod/claim", { report_id: "r_seed1" }, "u_host");
  ok(claimDenied.status === 403, "mod edge: claim is 403 for a non-moderator");
  const actionDenied = await post("/api/mod/action", { report_id: "r_seed1", action: "mute", reason_code: "spam", note: "x", override_claim: false }, "u_host");
  ok(actionDenied.status === 403, "mod edge: action is 403 for a non-moderator");
  const appealDecideDenied = await post("/api/mod/appeal", { report_id: "r_seed1", outcome: "upheld", reason_code: "other", note: "", override_claim: false }, "u_host");
  ok(appealDecideDenied.status === 403, "mod edge: appeal decision is 403 for a non-moderator");

  const badReport = await post("/api/reports", { target_kind: "message", target_id: "m_missing", reason: "spam", note: "" }, "u_host");
  ok(badReport.status === 404, "report edge: unknown message is 404");
  const profileReport = await post("/api/reports", { target_kind: "profile", target_id: "u_spammer", reason: "spam", note: "" }, "u_host");
  ok(profileReport.status === 404, "report edge: non-message target kind is 404");

  // Two reports on the same message create two distinct open items
  // (the server keeps no per-message dedupe — pinned as current truth).
  const genHist = await get("/api/history?channel_id=ch_general&limit=200", "u_host");
  const reportTarget = genHist.body.messages.find((m) => m.author_id === "u_host" && m.body === "general is fine");
  ok(!!reportTarget, "mod edge: report target message exists");
  await post("/api/reports", { target_kind: "message", target_id: reportTarget.id, reason: "spam", note: "one" }, "u_free");
  await post("/api/reports", { target_kind: "message", target_id: reportTarget.id, reason: "spam", note: "two" }, "u_free");
  const queueNow = await get("/api/mod/queue", "u_mod");
  const dupItems = queueNow.body.items.filter((i) => i.snapshot.body === "general is fine" && i.state === "open");
  ok(dupItems.length === 2 && dupItems[0].report_id !== dupItems[1].report_id,
    "report edge: duplicate reports land as distinct open items");

  // Claim state machine: claim -> re-claim 409 -> action closes -> claim on closed 409, action on closed 404.
  const itemA = dupItems[0];
  const claim1 = await post("/api/mod/claim", { report_id: itemA.report_id }, "u_mod");
  ok(claim1.status === 200, "claim edge: first claim succeeds");
  const claim2 = await post("/api/mod/claim", { report_id: itemA.report_id }, "u_mod");
  ok(claim2.status === 409 && claim2.body.error === "not_open", "claim edge: claiming a claimed item is 409 not_open");
  const claimMissing = await post("/api/mod/claim", { report_id: "r_missing" }, "u_mod");
  ok(claimMissing.status === 409, "claim edge: claiming a missing item is 409");
  const appealOnPlain = await post("/api/mod/appeal", { report_id: itemA.report_id, outcome: "upheld", reason_code: "other", note: "", override_claim: false }, "u_mod");
  ok(appealOnPlain.status === 404, "appeal edge: deciding an item with no appeal is 404");
  const muteAct = await post("/api/mod/action", { report_id: itemA.report_id, action: "mute", reason_code: "spam", note: "edge test mute", override_claim: false, duration_sec: 3600 }, "u_mod");
  ok(muteAct.status === 200, "mod edge: claimant's mute action succeeds");
  const hostSanctions = await get("/api/sanctions", "u_host");
  const muteSanction = hostSanctions.body.sanctions.find((x) => x.rung === "mute");
  ok(!!muteSanction && muteSanction.appeal.state === "none", "mod edge: mute lands as a sanction with appeal state none");
  const actClosed = await post("/api/mod/action", { report_id: itemA.report_id, action: "mute", reason_code: "spam", note: "", override_claim: false }, "u_mod");
  ok(actClosed.status === 404, "mod edge: action on a closed item is 404");
  const claimClosed = await post("/api/mod/claim", { report_id: itemA.report_id }, "u_mod");
  ok(claimClosed.status === 409, "mod edge: claim on a closed item is 409");

  // A mute does NOT push role.update (no entitlement change); a ban does.
  const watch = await new WsTest().connect("u_host");
  watch.send({ type: "subscribe", payload: { channel_ids: ["ch_general"] } });
  await watch.waitFor((f) => f.type === "presence", "mute/ban watch ready");
  const itemB = dupItems[1];
  await post("/api/mod/claim", { report_id: itemB.report_id }, "u_mod");
  const pushBase = watch.count((f) => f.type === "role.update");
  await post("/api/mod/action", { report_id: itemB.report_id, action: "mute", reason_code: "spam", note: "second mute", override_claim: false, duration_sec: 3600 }, "u_mod");
  await new Promise((r) => setTimeout(r, 250));
  ok(watch.count((f) => f.type === "role.update") === pushBase, "mod edge: mute action pushes no role.update");
  const banReport = await post("/api/reports", { target_kind: "message", target_id: reportTarget.id, reason: "scam", note: "ban path" }, "u_free");
  ok(banReport.status === 200, "mod edge: ban-path report accepted");
  const queueForBan = await get("/api/mod/queue", "u_mod");
  const banItem = queueForBan.body.items.find((i) => i.snapshot.body === "general is fine" && i.state === "open");
  await post("/api/mod/action", { report_id: banItem.report_id, action: "ban", reason_code: "scam", note: "edge test ban", override_claim: false }, "u_mod");
  const banPush = await watch.waitFor((f) => f.type === "role.update" && f.payload.entitlements.role === "free", "ban role.update");
  ok(!!banPush, "mod edge: ban pushes role.update with free-role entitlements");
  await post("/api/dev/restore", { user_id: "u_host" }, "u_host");
  await watch.waitFor((f) => f.type === "role.update" && f.payload.entitlements.role === "member", "post-ban restore push");
  watch.close();

  // Appeals: someone else's sanction is 404; own appeal round-trips;
  // a second appeal on a decided sanction reopens a fresh item (pinned).
  const foreignAppeal = await post("/api/appeals", { sanction_id: muteSanction.sanction_id, statement: "not mine" }, "u_free");
  ok(foreignAppeal.status === 404, "appeal edge: appealing another user's sanction is 404");
  const ownAppeal = await post("/api/appeals", { sanction_id: muteSanction.sanction_id, statement: "edge appeal" }, "u_host");
  ok(ownAppeal.status === 200, "appeal edge: own appeal is accepted");
  const queueAppeal = await get("/api/mod/queue", "u_mod");
  const appealItem2 = queueAppeal.body.items.find((i) => i.appeal && i.snapshot.body === "edge appeal" && i.state === "open");
  ok(!!appealItem2, "appeal edge: appeal item lands in the queue");
  const uphold = await post("/api/mod/appeal", { report_id: appealItem2.report_id, outcome: "upheld", reason_code: "spam", note: "", override_claim: false }, "u_mod");
  ok(uphold.status === 200, "appeal edge: upheld decision succeeds");
  const afterUphold = await get("/api/sanctions", "u_host");
  ok(afterUphold.body.sanctions.some((x) => x.sanction_id === muteSanction.sanction_id && x.appeal.state === "upheld"),
    "appeal edge: upheld keeps the sanction, marked upheld");
  await post("/api/appeals", { sanction_id: muteSanction.sanction_id, statement: "second edge appeal" }, "u_host");
  const queueAppeal2 = await get("/api/mod/queue", "u_mod");
  const appealItem3 = queueAppeal2.body.items.find((i) => i.appeal && i.snapshot.body === "second edge appeal" && i.state === "open");
  ok(!!appealItem3 && appealItem3.report_id !== appealItem2.report_id,
    "appeal edge: a second appeal on a decided sanction opens a fresh item");
  await post("/api/mod/appeal", { report_id: appealItem3.report_id, outcome: "overturned", reason_code: "other", note: "", override_claim: false }, "u_mod");
  const afterOverturn = await get("/api/sanctions", "u_host");
  ok(!afterOverturn.body.sanctions.some((x) => x.sanction_id === muteSanction.sanction_id),
    "appeal edge: overturned removes the sanction");

  // Lapse read half (the push half is section 10/15): entitlements read
  // collapses to free while lapsed and returns on restore.
  await post("/api/dev/lapse", { user_id: "u_host" }, "u_host");
  const lapsedEnt = await get("/api/entitlements", "u_host");
  ok(lapsedEnt.body.role === "free" && lapsedEnt.body.capabilities.post.length === 0 && lapsedEnt.body.expires_at === null,
    "lapse edge: lapsed entitlements read is free with no post caps and no expiry");
  await post("/api/dev/restore", { user_id: "u_host" }, "u_host");
  const restoredEnt = await get("/api/entitlements", "u_host");
  ok(restoredEnt.body.role === "member" && restoredEnt.body.capabilities.post.length > 0,
    "lapse edge: restored entitlements read is member again");


  // 16 — shared contract fixtures (DeepSeek programme item 4): the golden
  // frames in fixtures/frames.json are the wire contract. Every entry must
  // be self-consistent, and every server-emitted frame type must match the
  // fixture's key sets exactly, so the two sides cannot drift silently.
  // (Runs after section 17 in file order; section 15's bulk sends are what
  // make the resync case below deterministic.)
  const fixtureDoc = JSON.parse(readFileSync(new URL("./fixtures/frames.json", import.meta.url), "utf8"));
  const fixtureByName = new Map(fixtureDoc.frames.map((e) => [e.name, e]));
  const keySet = (o) => Object.keys(o ?? {}).sort().join(",");
  for (const e of fixtureDoc.frames) {
    ok(keySet(e.frame) === e.envelope_keys.join(",") && keySet(e.frame.payload) === e.payload_keys.join(","),
      `fixtures: ${e.name} declares its own key sets honestly`);
  }
  const shapeIs = (liveFrame, name) => {
    const e = fixtureByName.get(name);
    return keySet(liveFrame) === e.envelope_keys.join(",") && keySet(liveFrame.payload) === e.payload_keys.join(",");
  };

  const fx = await new WsTest().connect("u_host");
  const fxObs = await new WsTest().connect("u_mod");
  fxObs.send({ type: "subscribe", payload: { channel_ids: ["ch_general"] } });
  const snapFrame = await fxObs.waitFor((f) => f.type === "presence", "fixture presence snapshot");
  ok(shapeIs(snapFrame, "presence") && keySet(snapFrame.payload.presence) === fixtureByName.get("presence").presence_keys.join(","),
    "fixtures: live presence frame matches the golden shape");

  // The golden client send frame, sent verbatim, is acked in golden shape.
  const goldenSend = fixtureByName.get("send").frame;
  fx.send(goldenSend);
  const fxAck = await fx.waitFor((f) => f.type === "ack" && f.payload.for_id === goldenSend.id, "fixture send ack");
  ok(shapeIs(fxAck, "ack"), "fixtures: live ack matches the golden shape");
  const fxMsg = await fxObs.waitFor((f) => f.type === "message" && f.payload.message.id === fxAck.payload.message_id, "fixture broadcast");
  ok(shapeIs(fxMsg, "message") && keySet(fxMsg.payload.message) === fixtureByName.get("message").message_keys.join(","),
    "fixtures: live message frame + nested message match the golden shape");
  const fxMid = fxAck.payload.message_id;

  fx.send({ type: "react", channel_id: "ch_general", payload: { message_id: fxMid, emoji: "🔥", op: "add" } });
  const fxReact = await fxObs.waitFor((f) => f.type === "reaction" && f.payload.message_id === fxMid, "fixture reaction");
  ok(shapeIs(fxReact, "reaction"), "fixtures: live reaction frame matches the golden shape");

  fx.send({ type: "typing", channel_id: "ch_general", payload: { state: "start" } });
  const fxTyping = await fxObs.waitFor((f) => f.type === "typing" && f.payload.user_id === "u_host", "fixture typing relay");
  ok(shapeIs(fxTyping, "typing (server relay)"), "fixtures: live typing relay matches the golden shape");

  fx.send({ type: "edit", channel_id: "ch_general", payload: { message_id: fxMid, body: "fixture edited" } });
  const fxUpd = await fxObs.waitFor((f) => f.type === "message.update" && f.payload.message.id === fxMid, "fixture update");
  ok(shapeIs(fxUpd, "message.update") && keySet(fxUpd.payload.message) === fixtureByName.get("message.update").message_keys.join(","),
    "fixtures: live message.update matches the golden shape");

  fx.send({ type: "send", id: "c_fixture_err", channel_id: "ch_announcements", payload: { body: "cannot post" } });
  const fxErr = await fx.waitFor((f) => f.type === "error" && f.payload.for_id === "c_fixture_err", "fixture error");
  ok(shapeIs(fxErr, "error"), "fixtures: live error frame matches the golden shape");

  fx.send({ type: "delete", channel_id: "ch_general", payload: { message_id: fxMid } });
  const fxDel = await fxObs.waitFor((f) => f.type === "message.delete" && f.payload.message_id === fxMid, "fixture delete");
  ok(shapeIs(fxDel, "message.delete"), "fixtures: live message.delete matches the golden shape");

  await post("/api/dev/lapse", { user_id: "u_host" }, "u_host");
  const fxRole = await fx.waitFor((f) => f.type === "role.update", "fixture role.update");
  const roleFx = fixtureByName.get("role.update");
  ok(shapeIs(fxRole, "role.update") &&
    keySet(fxRole.payload.entitlements) === roleFx.entitlements_keys.join(",") &&
    keySet(fxRole.payload.entitlements.capabilities) === roleFx.capability_keys.join(",") &&
    keySet(fxRole.payload.channels[0]) === roleFx.channel_keys.join(","),
    "fixtures: live role.update + nested shapes match the golden shape");
  await post("/api/dev/restore", { user_id: "u_host" }, "u_host");
  await fx.waitFor((f) => f.type === "role.update" && f.payload.entitlements.role === "member", "fixture restore push");

  fx.send({ type: "resume", payload: { last_seq: { ch_help_desk: 0 } } });
  const fxResync = await fx.waitFor((f) => f.type === "resync" && f.payload.channel_id === "ch_help_desk", "fixture resync");
  ok(shapeIs(fxResync, "resync"), "fixtures: live resync frame matches the golden shape");
  fx.close();
  fxObs.close();

  // 18 — history paging + search + routing edges (DeepSeek full-spend
  // programme Q-11..Q-13): the limit cursor arithmetic and the
  // fall-through shapes, pinned exactly.
  const page1 = await get("/api/history?channel_id=ch_general&limit=1", "u_host");
  ok(page1.status === 200 && page1.body.messages.length === 1 && page1.body.has_more === true,
    "history edge: limit=1 returns exactly one message with has_more");
  const page2 = await get("/api/history?channel_id=ch_general&limit=2", "u_host");
  ok(page2.status === 200 && page2.body.messages.length === 2 && page2.body.has_more === true,
    "history edge: limit=2 returns exactly two messages with has_more");
  const page0 = await get("/api/history?channel_id=ch_general&before_seq=0", "u_host");
  ok(page0.status === 200 && page0.body.messages.length === 0 && page0.body.has_more === false,
    "history edge: before_seq=0 returns an empty page with has_more false");
  const page200 = await get("/api/history?channel_id=ch_general&limit=200", "u_host");
  const pageDefault = await get("/api/history?channel_id=ch_general&limit=0", "u_host");
  ok(pageDefault.body.messages.length === Math.min(50, page200.body.messages.length),
    "history edge: limit=0 falls back to the default page size of 50");
  const readableIds = ["ch_announcements", "ch_general", "ch_introductions", "ch_help_desk", "ch_strategy", "ch_town_square_tips", "ch_pump", "ch_nightshift"];
  let expectedSearchCount = 0;
  for (const cid of readableIds) {
    let before = null;
    for (;;) {
      const page = await get(`/api/history?channel_id=${cid}&limit=200${before === null ? "" : `&before_seq=${before}`}`, "u_host");
      expectedSearchCount += page.body.messages.filter((m) => m.deleted_at === null).length;
      if (!page.body.has_more) break;
      before = page.body.messages[0].seq;
    }
  }
  const emptySearch = await get("/api/search?q=", "u_host");
  ok(emptySearch.body.messages.length === expectedSearchCount,
    "search edge: empty q returns every readable non-deleted message");
  const upperSearch = await get("/api/search?q=MOTHERLODE", "u_host");
  ok(upperSearch.body.messages.length === 1 && upperSearch.body.messages[0].body.toLowerCase().includes("motherlode"),
    "search edge: an uppercase query matches a lowercase body");
  const probe = await new WsTest().connect("u_host");
  probe.send({ type: "subscribe", payload: { channel_ids: ["ch_general"] } });
  await probe.waitFor((f) => f.type === "presence", "search probe subscribed");
  probe.send({ type: "send", id: "c_q12_probe", channel_id: "ch_general", payload: { body: "q12uniqueprobe" } });
  const probeAck = await probe.waitFor((f) => f.type === "ack" && f.payload.for_id === "c_q12_probe", "search probe ack");
  const probeFound = await get("/api/search?q=q12uniqueprobe", "u_host");
  ok(probeFound.body.messages.length === 1, "search edge: a live message is findable by its unique body");
  probe.send({ type: "delete", channel_id: "ch_general", payload: { message_id: probeAck.payload.message_id } });
  await probe.waitFor((f) => f.type === "message.delete" && f.payload.message_id === probeAck.payload.message_id, "search probe delete");
  const probeGone = await get("/api/search?q=q12uniqueprobe", "u_host");
  ok(probeGone.body.messages.length === 0 && probeGone.body.threads.length === 0,
    "search edge: a deleted message returns no hit for its unique body");
  probe.close();
  const unknownPath = await get("/api/nope", "u_host");
  ok(unknownPath.status === 404 && unknownPath.body.error === "not_found",
    "routing edge: an unknown path returns 404 not_found");
  const malformedReport = await fetch(BASE + "/api/reports", {
    method: "POST", headers: asUser("u_host"), body: "{not json",
  });
  const malformedReportBody = await malformedReport.json().catch(() => ({}));
  ok(malformedReport.status === 404 && malformedReportBody.error === "not_found",
    "routing edge: a malformed JSON body is treated as empty and 404s, never a 500");
  const wrongMethod = await fetch(BASE + "/api/channels", { method: "DELETE", headers: asUser("u_host") });
  const wrongMethodBody = await wrongMethod.json().catch(() => ({}));
  ok(wrongMethod.status === 404 && wrongMethodBody.error === "not_found",
    "routing edge: a wrong-method request on a known path falls through to 404");

  a.close(); b.close(); c.close(); d.close(); aBad.close();
} finally {
  server.kill();
}
console.log(`\n${checks - failures}/${checks} checks passed`);
process.exit(failures === 0 ? 0 : 1);
