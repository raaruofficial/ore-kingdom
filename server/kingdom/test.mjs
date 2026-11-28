// Contract-surface tests for the local Kingdom backend.
// Boots server.mjs on a scratch port and exercises the exact surface
// the Android host layer calls (KingdomTransport.kt): REST reads and
// actions + the CONTRACT.md §2–§4 websocket frames. Zero deps.
// Run: node test.mjs   (exit 0 = all pass)

import { spawn } from "node:child_process";
import { readFileSync } from "node:fs";
import { PIN_SLOT_COUNT, PIN_AUCTION_FLOOR_CENTS, PIN_WINDOW_MS, scheduleWin, computeSpotlightState } from "./spotlight.mjs";
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
  env: { ...process.env, KINGDOM_PORT: String(PORT), KINGDOM_HOST: "127.0.0.1", KINGDOM_DEV_HOOKS: "1" },
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
  // B-9 (Phase 3 audit): dismissal mutates shared state and requires
  // the nightshift console entitlement. Pinned here, while sug_2 is
  // still live (the nightshift edge section later dismisses it as
  // the operator — the positive control). Audit PoC P3.
  const b9Deny = await post("/api/modules/nightshift/suggestions", { suggestion_id: "sug_2" }, "u_free");
  ok(b9Deny.status === 403, "B-9: a free-tier caller cannot dismiss a nightshift suggestion (403)");
  const b9Member = await post("/api/modules/nightshift/suggestions", { suggestion_id: "sug_2" }, "u_host");
  ok(b9Member.status === 403, "B-9: a member (nightshift preview, read-only) cannot dismiss either");
  const b9Still = await get("/api/modules/nightshift", "u_host");
  ok(b9Still.body.suggestions.some((s) => s.id === "sug_2"), "B-9: the suggestion survives the refused dismissals");
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

  // 12b — spotlight auction state model (spotlight.mjs; price model of
  // record 2026-11-27: pins are auctioned, deferred activation, 12h windows)
  const T0 = 1800000000000;
  const w1 = { author_id: "u_a", body: "A", commercial: false, starts_at: T0 - 1000, ends_at: 1800043199000 };
  const w2 = { author_id: "u_b", body: "B", commercial: true, starts_at: T0 - 2000, ends_at: 1800043198000 };
  ok(PIN_SLOT_COUNT === 2 && PIN_AUCTION_FLOOR_CENTS === 5000 && PIN_WINDOW_MS === 43200000,
    "spotlight auction: interim constants pinned (2 slots, $50 floor, 12h window)");
  const firstWin = scheduleWin([], { author_id: "u_a", body: "A", commercial: false }, T0);
  ok(firstWin.starts_at === 1800000000000 && firstWin.ends_at === 1800043200000,
    "spotlight auction: first win starts now and runs 12h");
  ok(scheduleWin([w1], { author_id: "u_c", body: "C", commercial: false }, T0).starts_at === T0,
    "spotlight auction: free slot starts immediately (not deferred)");
  const deferredWin = scheduleWin([w1, w2], { author_id: "u_c", body: "C", commercial: false }, T0);
  ok(deferredWin.starts_at === 1800043198000 && deferredWin.ends_at === 1800086398000,
    "spotlight auction: full slots defer to the earliest expiry, never early");
  const rawWin = { author_id: "u_c", body: "C", commercial: false };
  scheduleWin([w1, w2], rawWin, T0);
  ok(("starts_at" in rawWin) === false, "spotlight auction: scheduleWin mutates nothing");
  const vacantState = computeSpotlightState([], T0);
  ok(vacantState.occupied === false && vacantState.post === null && vacantState.window_ends_at === null && vacantState.floor_usd === 50,
    "spotlight auction: no wins reads vacant at the floor");
  const servedState = computeSpotlightState([w1, w2], T0);
  ok(servedState.occupied === true && servedState.post.author_id === "u_b" && servedState.post.commercial === true &&
    servedState.window_ends_at === new Date(1800043198000).toISOString() && servedState.floor_usd === 50,
    "spotlight auction: earliest-ending active win is served");
  ok(computeSpotlightState([{ author_id: "u_c", body: "C", commercial: false, starts_at: T0 + 1000, ends_at: 1800043201000 }], T0).occupied === false,
    "spotlight auction: a deferred win does not occupy");

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

  // 19 — server behaviour pins, second wave (DeepSeek full-spend
  // programme Q-28..Q-40): refusal aftermath, relays, and read shapes
  // the first wave did not cover.
  const q28Free = await new WsTest().connect("u_free");
  q28Free.send({ type: "subscribe", payload: { channel_ids: ["ch_strategy"] } });
  const q28Refusal = await q28Free.waitFor((f) => f.type === "error", "q28 refusal");
  ok(q28Refusal.payload.code === "not_entitled" && q28Refusal.payload.channel_id === "ch_strategy",
    "subscribe edge: refusal carries not_entitled + the channel id");
  const q28Spam = await new WsTest().connect("u_spammer");
  q28Spam.send({ type: "send", id: "c_q28_1", channel_id: "ch_strategy", payload: { body: "q28 strategy secret" } });
  await q28Spam.waitFor((f) => f.type === "ack" && f.payload.for_id === "c_q28_1", "q28 strategy ack");
  await new Promise((r) => setTimeout(r, 250));
  ok(q28Free.count((f) => f.type === "message" && f.payload.message.body === "q28 strategy secret") === 0,
    "subscribe edge: a refused subscription receives no messages from the channel");
  q28Free.send({ type: "subscribe", payload: { channel_ids: ["ch_general", "ch_strategy"] } });
  await q28Free.waitFor((f) => f.type === "error" && f.payload.channel_id === "ch_strategy" && f !== q28Refusal, "q28 mixed refusal");
  const q28Host = await new WsTest().connect("u_host");
  q28Host.send({ type: "send", id: "c_q28_2", channel_id: "ch_general", payload: { body: "q28 general hello" } });
  const q28Got = await q28Free.waitFor((f) => f.type === "message" && f.payload.message.body === "q28 general hello", "q28 mixed delivery");
  ok(!!q28Got, "subscribe edge: a mixed subscribe delivers the readable channel and refuses the other per-id");
  q28Free.close(); q28Spam.close(); q28Host.close();

  const q29A = await new WsTest().connect("u_host");
  const q29B = await new WsTest().connect("u_mod");
  q29A.send({ type: "subscribe", payload: { channel_ids: ["ch_general"] } });
  q29B.send({ type: "subscribe", payload: { channel_ids: ["ch_general"] } });
  await q29B.waitFor((f) => f.type === "presence", "q29 observer ready");
  const q29C = await new WsTest().connect("u_free");
  q29A.send({ type: "typing", channel_id: "ch_general", payload: { state: "start" } });
  const q29Relay = await q29B.waitFor((f) => f.type === "typing" && f.payload.user_id === "u_host", "q29 typing relay");
  ok(q29Relay.payload.state === "start", "typing edge: a subscriber receives the relay carrying user_id + state");
  await new Promise((r) => setTimeout(r, 250));
  ok(q29A.count((f) => f.type === "typing") === 0, "typing edge: the sender does not receive its own typing frame");
  ok(q29C.count((f) => f.type === "typing") === 0, "typing edge: a connected non-subscriber receives nothing");
  q29A.close(); q29B.close(); q29C.close();
  const q30Host = await new WsTest().connect("u_host");
  const q30Obs = await new WsTest().connect("u_mod");
  q30Obs.send({ type: "subscribe", payload: { channel_ids: ["ch_general"] } });
  await q30Obs.waitFor((f) => f.type === "presence", "q30 observer ready");
  q30Host.send({ type: "send", id: "c_q30_1", channel_id: "ch_general", payload: { body: "q30 react target" } });
  const q30Ack = await q30Host.waitFor((f) => f.type === "ack" && f.payload.for_id === "c_q30_1", "q30 target ack");
  const q30Mid = q30Ack.payload.message_id;
  q30Host.send({ type: "react", channel_id: "ch_general", payload: { message_id: q30Mid, emoji: "🔥", op: "add" } });
  const q30Added = await q30Obs.waitFor((f) => f.type === "reaction" && f.payload.message_id === q30Mid, "q30 add broadcast");
  ok(q30Added.payload.count === 1 && q30Added.payload.actor_ids.includes("u_host"), "react edge: add broadcasts count 1 with the actor");
  q30Host.send({ type: "react", channel_id: "ch_general", payload: { message_id: q30Mid, emoji: "🔥", op: "remove" } });
  const q30Removed = await q30Obs.waitFor((f) => f.type === "reaction" && f.payload.message_id === q30Mid && f !== q30Added, "q30 remove broadcast");
  ok(q30Removed.payload.count === 0 && !q30Removed.payload.actor_ids.includes("u_host"),
    "react edge: remove decrements the count and drops the actor");
  const q30Before = q30Obs.count((f) => f.type === "reaction" && f.payload.message_id === q30Mid);
  q30Obs.send({ type: "react", channel_id: "ch_general", payload: { message_id: q30Mid, emoji: "🔥", op: "remove" } });
  await new Promise((r) => setTimeout(r, 250));
  const q30Ghost = q30Obs.frames.filter((f) => f.type === "reaction" && f.payload.message_id === q30Mid).at(-1);
  ok(q30Obs.count((f) => f.type === "reaction" && f.payload.message_id === q30Mid) === q30Before + 1 && q30Ghost.payload.count === 0,
    "react edge: removing a reaction the caller never added still broadcasts with the count unchanged");
  const q30Silence = q30Obs.count((f) => f.type === "reaction");
  q30Host.send({ type: "react", channel_id: "ch_general", payload: { message_id: "m_missing", emoji: "🔥", op: "add" } });
  await new Promise((r) => setTimeout(r, 250));
  ok(q30Obs.count((f) => f.type === "reaction") === q30Silence, "react edge: a react naming an unknown message produces no frame");
  q30Host.close(); q30Obs.close();
  const q30bHost = await new WsTest().connect("u_host");
  q30bHost.send({ type: "send", id: "c_q30b_1", channel_id: "ch_general", payload: { body: "q30b react target" } });
  const q30bAck = await q30bHost.waitFor((f) => f.type === "ack" && f.payload.for_id === "c_q30b_1", "q30b target ack");
  const q30bFree = await new WsTest().connect("u_free");
  q30bFree.send({ type: "react", channel_id: "ch_general", payload: { message_id: q30bAck.payload.message_id, emoji: "🔥", op: "add" } });
  const q30bDenied = await q30bFree.waitFor((f) => f.type === "error", "q30b free react refusal");
  ok(q30bDenied.payload.code === "not_entitled", "react edge: a free user's react answers not_entitled");
  q30bHost.close(); q30bFree.close();
  const q31Author = await new WsTest().connect("u_host");
  const q31Obs = await new WsTest().connect("u_mod");
  q31Obs.send({ type: "subscribe", payload: { channel_ids: ["ch_general"] } });
  await q31Obs.waitFor((f) => f.type === "presence", "q31 observer ready");
  q31Author.send({ type: "send", id: "c_q31_1", channel_id: "ch_general", payload: { body: "q31 delete target" } });
  const q31Ack = await q31Author.waitFor((f) => f.type === "ack" && f.payload.for_id === "c_q31_1", "q31 target ack");
  const q31Mid = q31Ack.payload.message_id;
  q31Author.send({ type: "delete", channel_id: "ch_general", payload: { message_id: q31Mid } });
  await q31Obs.waitFor((f) => f.type === "message.delete" && f.payload.message_id === q31Mid, "q31 first delete");
  q31Author.send({ type: "delete", channel_id: "ch_general", payload: { message_id: q31Mid } });
  await new Promise((r) => setTimeout(r, 300));
  ok(q31Obs.count((f) => f.type === "message.delete" && f.payload.message_id === q31Mid) === 1,
    "delete edge: deleting the same message twice broadcasts exactly one tombstone");
  q31Author.send({ type: "send", id: "c_q31_2", channel_id: "ch_general", payload: { body: "q31 edit target" } });
  const q31Ack2 = await q31Author.waitFor((f) => f.type === "ack" && f.payload.for_id === "c_q31_2", "q31 edit target ack");
  q31Author.send({ type: "edit", channel_id: "ch_general", payload: { message_id: q31Ack2.payload.message_id } });
  const q31Upd = await q31Obs.waitFor((f) => f.type === "message.update" && f.payload.message.id === q31Ack2.payload.message_id, "q31 bodyless edit");
  ok(q31Upd.payload.message.body === "q31 edit target" && q31Upd.payload.message.edited_at !== null,
    "edit edge: an edit with no body field keeps the body, stamps edited_at, and broadcasts");
  q31Author.send({ type: "edit", channel_id: "ch_general", payload: { message_id: "m_missing", body: "nobody" } });
  const q31Err = await q31Author.waitFor((f) => f.type === "error", "q31 unknown edit refusal");
  ok(q31Err.payload.code === "not_entitled", "edit edge: an edit naming an unknown message answers not_entitled");
  q31Author.close(); q31Obs.close();

  const q32Free = await new WsTest().connect("u_free");
  q32Free.send({ type: "resume", payload: { last_seq: { ch_town_square_tips: 0 } } });
  await new Promise((r) => setTimeout(r, 300));
  ok(q32Free.count((f) => f.type === "message") === 0 && q32Free.count((f) => f.type === "resync") === 0 && q32Free.count((f) => f.type === "error") === 0,
    "resume edge: an unreadable channel is skipped silently — no replay, no resync, no error");
  const q32Host = await new WsTest().connect("u_host");
  q32Host.send({ type: "send", id: "c_q32_1", channel_id: "ch_town_square_tips", payload: { body: "q32 tips post" } });
  await q32Host.waitFor((f) => f.type === "ack" && f.payload.for_id === "c_q32_1", "q32 tips ack");
  await new Promise((r) => setTimeout(r, 250));
  ok(q32Free.count((f) => f.type === "message" && f.payload.message.body === "q32 tips post") === 0,
    "resume edge: a skipped resume does not subscribe — later messages are not delivered");
  const q32Op = await new WsTest().connect("u_op");
  q32Op.send({ type: "resume", payload: { last_seq: { ch_general: 999999 } } });
  await new Promise((r) => setTimeout(r, 250));
  ok(q32Op.count((f) => f.type === "message") === 0, "resume edge: a last_seq beyond the head replays nothing");
  q32Host.send({ type: "send", id: "c_q32_2", channel_id: "ch_general", payload: { body: "q32 after resume" } });
  const q32Next = await q32Op.waitFor((f) => f.type === "message" && f.payload.message.body === "q32 after resume", "q32 post-resume delivery");
  ok(!!q32Next, "resume edge: a beyond-head resume still subscribes — the next new message is delivered");
  q32Free.close(); q32Host.close(); q32Op.close();
  const q33Obs = await new WsTest().connect("u_mod");
  q33Obs.send({ type: "subscribe", payload: { channel_ids: ["ch_general"] } });
  await q33Obs.waitFor((f) => f.type === "presence", "q33 observer ready");
  await new Promise((r) => setTimeout(r, 300));
  const q33FreeFrame = (f) => f.type === "presence" && f.payload.presence.user_id === "u_free";
  const q33SnapState = q33Obs.frames.find(q33FreeFrame).payload.presence.state;
  const q33Actor = await new WsTest().connect("u_free");
  await new Promise((r) => setTimeout(r, 300));
  const q33BaseVis = q33Obs.count((f) => q33FreeFrame(f) && f.payload.presence.invisible === false);
  const q33BaseInv = q33Obs.count((f) => q33FreeFrame(f) && f.payload.presence.invisible === true);
  q33Actor.send({ type: "presence.set", payload: { invisible: false } });
  const q33VisDeadline = Date.now() + 3000;
  while (q33Obs.count((f) => q33FreeFrame(f) && f.payload.presence.invisible === false) <= q33BaseVis && Date.now() < q33VisDeadline) await new Promise((r) => setTimeout(r, 25));
  const q33Vis = q33Obs.frames.filter((f) => q33FreeFrame(f) && f.payload.presence.invisible === false).at(-1);
  ok(q33Vis !== undefined && q33Vis.payload.presence.state === q33SnapState,
    "presence edge: presence.set invisible=false broadcasts the flag cleared with the state unchanged");
  q33Actor.send({ type: "presence.set", payload: { invisible: true } });
  const q33InvDeadline = Date.now() + 3000;
  while (q33Obs.count((f) => q33FreeFrame(f) && f.payload.presence.invisible === true) <= q33BaseInv && Date.now() < q33InvDeadline) await new Promise((r) => setTimeout(r, 25));
  const q33Inv = q33Obs.frames.filter((f) => q33FreeFrame(f) && f.payload.presence.invisible === true).at(-1);
  ok(q33Inv !== undefined && q33Inv.payload.presence.state === q33SnapState,
    "presence edge: presence.set invisible=true broadcasts the flag set with the state unchanged");
  q33Obs.close(); q33Actor.close();

  const meAnon = await (await fetch(BASE + "/api/me")).json();
  ok(meAnon.user_id === "u_host", "identity edge: no auth headers falls back to u_host");
  const meUnknown = await (await fetch(BASE + "/api/me", { headers: { authorization: "Bearer local:u_nobody" } })).json();
  ok(meUnknown.user_id === "u_host", "identity edge: an unknown Bearer local id falls back to u_host");
  const meWallet = await (await fetch(BASE + "/api/me", { headers: { "x-kingdom-wallet": "wallet_op" } })).json();
  ok(meWallet.user_id === "u_op", "identity edge: a matching X-Kingdom-Wallet selects that user");
  const meBadWallet = await (await fetch(BASE + "/api/me", { headers: { "x-kingdom-wallet": "wallet_nope" } })).json();
  ok(meBadWallet.user_id === "u_host", "identity edge: a non-matching wallet falls back to u_host");
  const membersRes = await get("/api/members", "u_host");
  ok(membersRes.status === 200 && membersRes.body.members.length === 5, "members edge: the list returns all five seeded users");
  ok(membersRes.body.members.every((m) => Object.keys(m).sort().join(",") === "display_name,founding,moderator,role,user_id"),
    "members edge: every member carries exactly the memberShape keys");
  const mods = membersRes.body.members.filter((m) => m.moderator);
  ok(mods.length === 1 && mods[0].user_id === "u_mod", "members edge: moderator is true for exactly u_mod");
  const freeEnt = await get("/api/entitlements", "u_free");
  ok(freeEnt.body.role === "free" && Array.isArray(freeEnt.body.capabilities.post) && freeEnt.body.capabilities.post.length === 0 &&
    Array.isArray(freeEnt.body.capabilities.react) && freeEnt.body.capabilities.react.length === 0 &&
    freeEnt.body.capabilities.pump_module === "none",
    "entitlements edge: the free role posts and reacts nowhere and has no pump module");
  ok(freeEnt.body.modules.pump === "off" && freeEnt.body.modules.nightshift === "off" &&
    freeEnt.body.expires_at === null && freeEnt.body.capabilities.autopilot_surface === false,
    "entitlements edge: the free role has both modules off, no expiry, and no autopilot surface");
  const opEnt = await get("/api/entitlements", "u_op");
  ok(opEnt.body.role === "operator" && opEnt.body.capabilities.pump_day_included === true &&
    opEnt.body.capabilities.atm_unlock_included === true && opEnt.body.capabilities.nightshift_console === true,
    "entitlements edge: the operator role includes the pump day, the ATM unlock, and the nightshift console");
  ok(opEnt.body.modules.pump === "on" && opEnt.body.modules.nightshift === "on" && opEnt.body.expires_at !== null,
    "entitlements edge: the operator role has both modules on and a non-null expiry");
  const pumpMember = await get("/api/modules/pump", "u_host");
  ok(pumpMember.body.wallets.length === 2 && pumpMember.body.day_bank.banked_days === 2 &&
    pumpMember.body.day_bank.first_day_free === true && pumpMember.body.day_bank.active_remaining_seconds === null,
    "pump edge: the read returns the two seeded wallets and the day_bank fields verbatim");
  ok(pumpMember.body.atm.state === "locked", "pump edge: the ATM is locked for a member caller");
  const pumpOp = await get("/api/modules/pump", "u_op");
  ok(pumpOp.body.atm.state === "included", "pump edge: the ATM is included for the operator");
  const nsUnknown = await post("/api/modules/nightshift/suggestions", { suggestion_id: "sug_missing", accept: true }, "u_op");
  ok(nsUnknown.status === 404, "nightshift edge: deciding an unknown suggestion is 404");
  const nsBefore = await get("/api/modules/nightshift", "u_op");
  ok(nsBefore.body.suggestions.some((s) => s.id === "sug_2"), "nightshift edge: the remaining seeded suggestion is listed");
  const nsDecide = await post("/api/modules/nightshift/suggestions", { suggestion_id: "sug_2", accept: true }, "u_op");
  const nsAfter = await get("/api/modules/nightshift", "u_op");
  ok(nsDecide.status === 200 && !nsAfter.body.suggestions.some((s) => s.id === "sug_2"),
    "nightshift edge: deciding a suggestion removes it from the next read");
  const nsAgain = await post("/api/modules/nightshift/suggestions", { suggestion_id: "sug_2", accept: false }, "u_op");
  ok(nsAgain.status === 404, "nightshift edge: deciding the same suggestion twice is 404 the second time");
  const opSanctions = await get("/api/sanctions", "u_op");
  ok(opSanctions.status === 200 && Array.isArray(opSanctions.body.sanctions) && opSanctions.body.sanctions.length === 0,
    "sanctions edge: a user with no sanctions reads an empty list");
  const introHist = await get("/api/history?channel_id=ch_introductions&limit=50", "u_host");
  const introMsg = introHist.body.messages.find((m) => m.author_id === "u_free");
  await post("/api/reports", { target_kind: "message", target_id: introMsg.id, reason: "spam", note: "q39 setup" }, "u_host");
  const q39Queue = await get("/api/mod/queue", "u_mod");
  const q39Item = q39Queue.body.items.find((i) => i.snapshot.body === introMsg.body && i.state === "open");
  await post("/api/mod/claim", { report_id: q39Item.report_id }, "u_mod");
  await post("/api/mod/action", { report_id: q39Item.report_id, action: "mute", reason_code: "spam", note: "q39 mute", override_claim: false, duration_sec: 3600 }, "u_mod");
  const freeSanctions = await get("/api/sanctions", "u_free");
  const q39Sanction = freeSanctions.body.sanctions.find((s) => s.rung === "mute");
  await post("/api/appeals", { sanction_id: q39Sanction.sanction_id, statement: "q39 first appeal" }, "u_free");
  await post("/api/appeals", { sanction_id: q39Sanction.sanction_id, statement: "q39 second appeal" }, "u_free");
  const q39Queue2 = await get("/api/mod/queue", "u_mod");
  const q39AppealItems = q39Queue2.body.items.filter((i) => i.appeal && i.state === "open" &&
    (i.snapshot.body === "q39 first appeal" || i.snapshot.body === "q39 second appeal"));
  // A-1 reversal (Phase 3 audit, verdict LAND): this pin originally
  // documented the defect as observed behaviour ("no pending
  // guard" — two appeals, two queue items). The guard now exists:
  // the repeat appeal queues nothing and the first statement stands.
  ok(q39AppealItems.length === 1 && q39AppealItems[0].snapshot.body === "q39 first appeal",
    "appeal guard (A-1): appealing a sanction whose appeal is already pending queues no second item");
  await post("/api/reports", { target_kind: "message", target_id: introMsg.id, reason: "spam", note: "q39 unknown action" }, "u_host");
  const q39Queue3 = await get("/api/mod/queue", "u_mod");
  const q39Item2 = q39Queue3.body.items.find((i) => i.snapshot.body === introMsg.body && i.state === "open" && !i.appeal);
  const freeSanctionCount = (await get("/api/sanctions", "u_free")).body.sanctions.length;
  const q39Act = await post("/api/mod/action", { report_id: q39Item2.report_id, action: "explode", reason_code: "spam", note: "q39", override_claim: false }, "u_mod");
  const q39Queue4 = await get("/api/mod/queue", "u_mod");
  const q39Closed = q39Queue4.body.items.find((i) => i.report_id === q39Item2.report_id);
  const q39Log = await get("/api/mod/log", "u_mod");
  const freeSanctionCountAfter = (await get("/api/sanctions", "u_free")).body.sanctions.length;
  const introAfter = await get("/api/history?channel_id=ch_introductions&limit=50", "u_host");
  ok(q39Act.status === 400 && q39Act.body.error === "unknown_action" && q39Closed.state === "open" && !q39Log.body.entries.some((e) => e.action === "explode"),
    "mod-action guard: an unknown action string is rejected, the item stays open, and no mod-log entry is written");
  ok(freeSanctionCountAfter === freeSanctionCount && introAfter.body.messages.find((m) => m.id === introMsg.id).deleted_at === null,
    "mod-action finding: an unknown action creates no sanction and tombstones nothing");
  const q40Host = await new WsTest().connect("u_host");
  q40Host.send({ type: "send", id: "c_q40_1", channel_id: "ch_nope", payload: { body: "nowhere" } });
  const q40Err = await q40Host.waitFor((f) => f.type === "error", "q40 unknown channel refusal");
  ok(q40Err.payload.code === "not_entitled" && q40Err.payload.for_id === "c_q40_1",
    "send edge: a send naming an unknown channel answers not_entitled carrying the frame id");
  q40Host.send({ type: "send", id: "c_q40_2", channel_id: "ch_general", payload: { body: "q40 ghost thread", thread_id: "t_missing" } });
  const q40Ack = await q40Host.waitFor((f) => f.type === "ack" && f.payload.for_id === "c_q40_2", "q40 ghost thread ack");
  ok(!!q40Ack, "send edge: a send carrying an unknown thread_id is acked");
  const q40Hist = await get("/api/history?channel_id=ch_general&limit=200", "u_host");
  const q40Threads = await get("/api/threads?channel_id=ch_general", "u_host");
  ok(q40Hist.body.messages.some((m) => m.id === q40Ack.payload.message_id && m.thread_id === "t_missing") && q40Threads.body.threads.length === 0,
    "send edge: the ghost-thread message lands in history and the thread list is untouched");
  q40Host.close();

  // 20 — REST + WS behaviour pins, third wave (DeepSeek full-spend
  // programme tranche 4, Cluster E/F): read shapes, moderation
  // branches, and malformed-input truths the earlier waves did not
  // pin. C-48 is not here: section 17 already pins duplicate
  // reports (see the tranche-4 scorecard).
  const freeChannels = await get("/api/channels", "u_free");
  ok(freeChannels.body.channels.every((c) => Object.keys(c).sort().join(",") === "category,id,kind,min_role_post,min_role_read,name,position,slowmode_sec"),
    "channels shape: every channel object carries exactly the eight contract keys");
  ok(freeChannels.body.channels.some((c) => c.id === "ch_pump" && c.kind === "module") && freeChannels.body.channels.some((c) => c.id === "ch_nightshift" && c.kind === "module"),
    "channels shape: a free caller's list includes both module channels");
  ok(freeChannels.body.channels.find((c) => c.id === "ch_announcements")?.min_role_post === "house",
    "channels shape: announcements is house-posted in the free caller's list");

  const modEnt = await get("/api/entitlements", "u_mod");
  ok(modEnt.body.capabilities.mod_loft === true, "mod entitlements: the seeded moderator holds mod_loft");
  ok(modEnt.body.founding === true && modEnt.body.role === "member", "mod entitlements: founding member role");
  ok(JSON.stringify(modEnt.body.capabilities.post) === JSON.stringify(["ch_general", "ch_introductions", "ch_help_desk", "ch_strategy", "ch_town_square_tips"]),
    "mod entitlements: post list is the member postable text set, announcements excluded");
  ok(modEnt.body.modules.pump === "on" && modEnt.body.modules.nightshift === "preview",
    "mod entitlements: modules are pump on, nightshift preview");

  const pumpFree = await get("/api/modules/pump", "u_free");
  ok(pumpFree.status === 200 && pumpFree.body.wallets.length === 2 && pumpFree.body.wallets[0].address_short === "HoSt…1111" && pumpFree.body.wallets[1].address_short === "MiNe…2222",
    "pump free: the free caller reads both seeded wallets verbatim");
  ok(pumpFree.body.day_bank.banked_days === 2 && pumpFree.body.day_bank.active_remaining_seconds === null && pumpFree.body.day_bank.first_day_free === true,
    "pump free: day_bank is 2 banked, none active, first day free");
  ok(pumpFree.body.atm.state === "locked", "pump free: the ATM is locked for a free caller");

  const c47Hist = await get("/api/history?channel_id=ch_introductions&limit=50", "u_host");
  const c47Msg = c47Hist.body.messages.find((m) => m.author_id === "u_free");
  const c47Post = await post("/api/reports", { target_kind: "message", target_id: c47Msg.id, note: "c47 no reason" }, "u_host");
  ok(c47Post.status === 200, "report defaults: a report with no reason field is accepted");
  const c47Queue = await get("/api/mod/queue", "u_mod");
  const c47Item = c47Queue.body.items.filter((i) => i.snapshot.body === c47Msg.body && i.state === "open" && !i.appeal).at(-1);
  ok(c47Item.reason === "other" && c47Item.reporter_count === 1 && c47Item.claimed_by === null && Array.isArray(c47Item.author_history) && c47Item.author_history.length === 0,
    "report defaults: the item lands with reason other, one reporter, unclaimed, empty author history");

  const c45Hist = await get("/api/history?channel_id=ch_introductions&limit=50", "u_host");
  const c45Msg = c45Hist.body.messages.find((m) => m.author_id === "u_free");
  await post("/api/reports", { target_kind: "message", target_id: c45Msg.id, reason: "spam", note: "c45 close me" }, "u_host");
  const c45Queue1 = await get("/api/mod/queue", "u_mod");
  const c45Item = c45Queue1.body.items.filter((i) => i.snapshot.body === c45Msg.body && i.state === "open" && !i.appeal).at(-1);
  const c45Act = await post("/api/mod/action", { report_id: c45Item.report_id, action: "content_removed", reason_code: "spam", note: "c45", override_claim: false }, "u_mod");
  ok(c45Act.status === 200, "mod queue: the closing action succeeds");
  const c45Queue2 = await get("/api/mod/queue", "u_mod");
  const c45Closed = c45Queue2.body.items.find((i) => i.report_id === c45Item.report_id);
  ok(!!c45Closed && c45Closed.state === "closed", "mod queue: a closed item is still listed, with state closed");
  ok(c45Queue2.body.items.every((i) => Object.keys(i).sort().join(",") === "appeal,author_history,claimed_by,reason,report_id,reporter_count,snapshot,state"),
    "mod queue: every listed item carries exactly the eight item keys");

  const c46Log1 = await get("/api/mod/log", "u_mod");
  ok(c46Log1.body.entries.every((e) => Object.keys(e).sort().join(",") === "action,actor_id,at,reason,target_id"),
    "mod log: every entry carries exactly the five entry keys");
  ok(c46Log1.body.entries[0].actor_id === "u_mod" && c46Log1.body.entries[0].action === "mute" && c46Log1.body.entries[0].target_id === "u_spammer" && c46Log1.body.entries[0].reason === "spam",
    "mod log: the seeded entry is first");
  const c46Hist = await get("/api/history?channel_id=ch_introductions&limit=50", "u_host");
  const c46Msg = c46Hist.body.messages.find((m) => m.author_id === "u_free");
  await post("/api/reports", { target_kind: "message", target_id: c46Msg.id, reason: "spam", note: "c46 log order" }, "u_host");
  const c46Queue = await get("/api/mod/queue", "u_mod");
  const c46Item = c46Queue.body.items.filter((i) => i.snapshot.body === c46Msg.body && i.state === "open" && !i.appeal).at(-1);
  await post("/api/mod/action", { report_id: c46Item.report_id, action: "mute", reason_code: "spam", note: "c46", override_claim: false, duration_sec: 60 }, "u_mod");
  const c46Log2 = await get("/api/mod/log", "u_mod");
  const c46Last = c46Log2.body.entries.at(-1);
  ok(c46Log2.body.entries.length === c46Log1.body.entries.length + 1 && c46Last.actor_id === "u_mod" && c46Last.action === "mute" && c46Last.target_id === "u_free" && c46Last.reason === "spam",
    "mod log: a new action's entry is appended last, in order");

  const c50Hist = await get("/api/history?channel_id=ch_introductions&limit=50", "u_host");
  const c50Msg = c50Hist.body.messages.find((m) => m.author_id === "u_free");
  await post("/api/reports", { target_kind: "message", target_id: c50Msg.id, reason: "spam", note: "c50 slowmode" }, "u_host");
  const c50Queue = await get("/api/mod/queue", "u_mod");
  const c50Item = c50Queue.body.items.filter((i) => i.snapshot.body === c50Msg.body && i.state === "open" && !i.appeal).at(-1);
  const c50Watch = await new WsTest().connect("u_free");
  c50Watch.send({ type: "subscribe", payload: { channel_ids: ["ch_general"] } });
  await c50Watch.waitFor((f) => f.type === "presence", "c50 watch ready");
  const c50PushBase = c50Watch.count((f) => f.type === "role.update");
  const c50Act = await post("/api/mod/action", { report_id: c50Item.report_id, action: "slowmode", reason_code: "spam", note: "c50", override_claim: false, duration_sec: 1800 }, "u_mod");
  ok(c50Act.status === 200, "mod action slowmode: the action succeeds");
  await new Promise((r) => setTimeout(r, 250));
  ok(c50Watch.count((f) => f.type === "role.update") === c50PushBase, "mod action slowmode: no role.update push fires for the author");
  c50Watch.close();
  const c50Sanctions = await get("/api/sanctions", "u_free");
  ok(c50Sanctions.body.sanctions.some((s) => s.rung === "slowmode" && s.duration_sec === 1800),
    "mod action slowmode: the author gains a slowmode sanction with the dictated duration");
  const c50Queue2 = await get("/api/mod/queue", "u_mod");
  const c50Log = await get("/api/mod/log", "u_mod");
  ok(c50Queue2.body.items.find((i) => i.report_id === c50Item.report_id)?.state === "closed" && c50Log.body.entries.at(-1).action === "slowmode",
    "mod action slowmode: the item closes and the log records action slowmode");

  const c49Hist = await get("/api/history?channel_id=ch_introductions&limit=50", "u_host");
  const c49Msg = c49Hist.body.messages.find((m) => m.author_id === "u_free");
  await post("/api/reports", { target_kind: "message", target_id: c49Msg.id, reason: "spam", note: "c49 reduced" }, "u_host");
  const c49Queue = await get("/api/mod/queue", "u_mod");
  const c49Item = c49Queue.body.items.filter((i) => i.snapshot.body === c49Msg.body && i.state === "open" && !i.appeal).at(-1);
  await post("/api/mod/action", { report_id: c49Item.report_id, action: "mute", reason_code: "spam", note: "c49", override_claim: false, duration_sec: 3601 }, "u_mod");
  const c49Sanctions = await get("/api/sanctions", "u_free");
  const c49Sanction = c49Sanctions.body.sanctions.filter((s) => s.rung === "mute" && s.duration_sec === 3601).at(-1);
  ok(!!c49Sanction, "appeal reduced: the setup sanction exists");
  await post("/api/appeals", { sanction_id: c49Sanction.sanction_id, statement: "c49 appeal" }, "u_free");
  const c49Queue2 = await get("/api/mod/queue", "u_mod");
  const c49AppealItem = c49Queue2.body.items.find((i) => i.appeal && i.snapshot.body === "c49 appeal" && i.state === "open");
  const c49Decide = await post("/api/mod/appeal", { report_id: c49AppealItem.report_id, outcome: "reduced", reason_code: "spam", note: "", override_claim: false }, "u_mod");
  ok(c49Decide.status === 200, "appeal reduced: the reduced decision succeeds");
  const c49After = await get("/api/sanctions", "u_free");
  ok(c49After.body.sanctions.some((s) => s.sanction_id === c49Sanction.sanction_id && s.duration_sec === 3601 && s.appeal.state === "reduced"),
    "appeal reduced: the sanction survives with appeal state reduced");
  const c49Queue3 = await get("/api/mod/queue", "u_mod");
  const c49Log = await get("/api/mod/log", "u_mod");
  ok(c49Queue3.body.items.find((i) => i.report_id === c49AppealItem.report_id)?.state === "closed" && c49Log.body.entries.at(-1).action === "appeal_reduced",
    "appeal reduced: the item closes and the log gains appeal_reduced");

  const c51LapseUnknown = await post("/api/dev/lapse", { user_id: "u_ghost" }, "u_host");
  ok(c51LapseUnknown.status === 404 && c51LapseUnknown.body.error === "not_found", "dev hooks: lapse of an unknown user is 404 not_found");
  const c51RestoreUnknown = await post("/api/dev/restore", { user_id: "u_ghost" }, "u_host");
  ok(c51RestoreUnknown.status === 404 && c51RestoreUnknown.body.error === "not_found", "dev hooks: restore of an unknown user is 404 not_found");
  await post("/api/dev/lapse", { user_id: "u_free" }, "u_host");
  const c51Again = await post("/api/dev/lapse", { user_id: "u_free" }, "u_host");
  ok(c51Again.status === 200, "dev hooks: lapsing an already lapsed user is 200 (idempotent)");
  await post("/api/dev/restore", { user_id: "u_free" }, "u_host");

  const c52Clamped = await get("/api/history?channel_id=ch_help_desk&limit=500", "u_host");
  const c52Max = await get("/api/history?channel_id=ch_help_desk&limit=200", "u_host");
  ok(c52Clamped.body.messages.length === 200 && c52Max.body.messages.length === 200,
    "history clamp: limit=500 returns the same 200-message ceiling as limit=200");
  ok(c52Clamped.body.messages[0].seq === c52Max.body.messages[0].seq && c52Clamped.body.messages.at(-1).seq === c52Max.body.messages.at(-1).seq,
    "history clamp: the clamped page is identical, first and last seq");

  const c53Bad = await get("/api/history?channel_id=ch_general&before_seq=abc", "u_host");
  ok(c53Bad.status === 200 && c53Bad.body.messages.length === 0 && c53Bad.body.has_more === false,
    "history malformed: before_seq=abc returns a 200 empty page (NaN cursor)");
  const c53Full = await get("/api/history?channel_id=ch_general&limit=200", "u_host");
  const c53Neg = await get("/api/history?channel_id=ch_general&limit=-5", "u_host");
  const c53Default = await get("/api/history?channel_id=ch_general", "u_host");
  ok(c53Neg.body.messages.length === c53Default.body.messages.length && c53Neg.body.messages[0].seq === c53Default.body.messages[0].seq,
    "history malformed: limit=-5 falls back to the default page instead of slicing the eligible list");

  const c54Sock = await new WsTest().connect("u_free");
  c54Sock.send({ type: "subscribe", payload: { channel_ids: [] } });
  await new Promise((r) => setTimeout(r, 300));
  const c54Snap = c54Sock.frames.filter((f) => f.type === "presence").slice(-5);
  ok(c54Sock.count((f) => f.type === "error") === 0 && c54Snap.length === 5 &&
    c54Snap.map((f) => f.payload.presence.user_id).sort().join(",") === "u_free,u_host,u_mod,u_op,u_spammer",
    "subscribe edge: an empty channel list still delivers the five-user presence snapshot, no error");
  c54Sock.send({ type: "subscribe", payload: { channel_ids: ["ch_nope"] } });
  const c54Err = await c54Sock.waitFor((f) => f.type === "error", "c54 unknown id refusal");
  ok(c54Err.payload.code === "not_entitled" && c54Err.payload.channel_id === "ch_nope",
    "subscribe edge: an unknown channel id is refused per-id with not_entitled + the id");
  await new Promise((r) => setTimeout(r, 300));
  const c54Before = c54Sock.count((f) => f.type === "presence");
  c54Sock.send({ type: "subscribe", payload: { channel_ids: ["ch_general"] } });
  await new Promise((r) => setTimeout(r, 300));
  ok(c54Sock.count((f) => f.type === "presence") === c54Before + 5,
    "subscribe edge: a second subscribe re-sends the full presence snapshot");
  c54Sock.close();
  c54Sock.close();

const c55Sock = await new WsTest().connect("u_host");
c55Sock.send({ type: "subscribe", payload: { channel_ids: ["ch_general"] } });
await c55Sock.waitFor((f) => f.type === "presence", "c55 subscribed");
await new Promise((r) => setTimeout(r, 300));
const c55Base = c55Sock.frames.length;
c55Sock.send({ type: "unsubscribe", payload: { channel_ids: ["ch_strategy"] } });
await new Promise((r) => setTimeout(r, 250));
ok(c55Sock.frames.length === c55Base, "unsubscribe edge: unsubscribing a never-subscribed id produces no frame at all");
const c55Other = await new WsTest().connect("u_op");
c55Other.send({ type: "send", id: "c_c55_1", channel_id: "ch_general", payload: { body: "c55 still subscribed" } });
const c55Got = await c55Sock.waitFor((f) => f.type === "message" && f.payload.message.body === "c55 still subscribed", "c55 delivery after no-op unsubscribe");
ok(!!c55Got, "unsubscribe edge: existing subscriptions keep delivering after the no-op");
c55Sock.close(); c55Other.close();
  c55Sock.close(); c55Other.close();

  const c56ThreadsBefore = await get("/api/threads?channel_id=ch_general", "u_host");
  const c56Author = await new WsTest().connect("u_host");
  const c56Obs = await new WsTest().connect("u_mod");
  c56Obs.send({ type: "subscribe", payload: { channel_ids: ["ch_general"] } });
  await c56Obs.waitFor((f) => f.type === "presence", "c56 observer ready");
  c56Author.send({ type: "send", id: "c_c56_1", channel_id: "ch_general", payload: { body: "c56 quoting you", reply_to: "m_seed_42" } });
  const c56Ack = await c56Author.waitFor((f) => f.type === "ack" && f.payload.for_id === "c_c56_1", "c56 ack");
  const c56Echo = await c56Obs.waitFor((f) => f.type === "message" && f.payload.message.id === c56Ack.payload.message_id, "c56 broadcast");
  ok(c56Echo.payload.message.reply_to === "m_seed_42", "send edge: a send carrying reply_to broadcasts it verbatim");
  const c56ThreadsAfter = await get("/api/threads?channel_id=ch_general", "u_host");
  ok(c56ThreadsAfter.body.threads.length === c56ThreadsBefore.body.threads.length,
    "send edge: a reply_to send leaves the channel's thread list untouched");
  c56Author.close(); c56Obs.close();

  const c57Author = await new WsTest().connect("u_host");
  const c57Obs = await new WsTest().connect("u_mod");
  c57Obs.send({ type: "subscribe", payload: { channel_ids: ["ch_general"] } });
  await c57Obs.waitFor((f) => f.type === "presence", "c57 observer ready");
  c57Author.send({ type: "send", id: "c_c57_1", channel_id: "ch_general", payload: {} });
  const c57Ack1 = await c57Author.waitFor((f) => f.type === "ack" && f.payload.for_id === "c_c57_1", "c57 bodyless ack");
  const c57Echo1 = await c57Obs.waitFor((f) => f.type === "message" && f.payload.message.id === c57Ack1.payload.message_id, "c57 bodyless broadcast");
  ok(c57Echo1.payload.message.body === "", "send edge: a send with no body field is acked and broadcast with an empty body");
  c57Author.send({ type: "send", id: "c_c57_2", channel_id: "ch_general", payload: { body: "" } });
  const c57Ack2 = await c57Author.waitFor((f) => f.type === "ack" && f.payload.for_id === "c_c57_2", "c57 empty ack");
  const c57Echo2 = await c57Obs.waitFor((f) => f.type === "message" && f.payload.message.id === c57Ack2.payload.message_id, "c57 empty broadcast");
  ok(c57Echo2.payload.message.body === "" && c57Ack2.payload.message_id !== c57Ack1.payload.message_id,
    "send edge: a send with an explicitly empty body behaves identically, a distinct message");
  c57Author.close(); c57Obs.close();

  const c58Author = await new WsTest().connect("u_host");
  c58Author.send({ type: "send", channel_id: "ch_general", payload: { body: "c58 no id here" } });
  const c58Ack1 = await c58Author.waitFor((f) => f.type === "ack", "c58 first ack");
  ok(!("for_id" in c58Ack1.payload) && typeof c58Ack1.payload.message_id === "string",
    "send edge: an id-less send is acked with no for_id key in the payload");
  c58Author.send({ type: "send", channel_id: "ch_general", payload: { body: "c58 no id here" } });
  const c58Ack2 = await c58Author.waitFor((f) => f.type === "ack" && f !== c58Ack1, "c58 second ack");
  ok(c58Ack2.payload.message_id !== c58Ack1.payload.message_id,
    "send edge: a repeated id-less send mints a second message (no idempotency without a key)");
  const c58Hist = await get("/api/history?channel_id=ch_general&limit=200", "u_host");
  ok(c58Hist.body.messages.filter((m) => m.body === "c58 no id here").length === 2,
    "send edge: both id-less sends persist in history");
  c58Author.close();

  const c59Member = await new WsTest().connect("u_host");
  c59Member.send({ type: "send", id: "c_c59_1", channel_id: "ch_announcements", payload: { body: "c59 hear ye" } });
  const c59Err = await c59Member.waitFor((f) => f.type === "error", "c59 send refusal");
  ok(c59Err.payload.code === "not_entitled" && c59Err.payload.for_id === "c_c59_1",
    "house channel: a member's send to announcements answers not_entitled");
  const c59Hist = await get("/api/history?channel_id=ch_announcements&limit=5", "u_host");
  c59Member.send({ type: "react", channel_id: "ch_announcements", payload: { message_id: c59Hist.body.messages[0].id, emoji: "🔥", op: "add" } });
  const c59Err2 = await c59Member.waitFor((f) => f.type === "error" && f !== c59Err, "c59 react refusal");
  ok(c59Err2.payload.code === "not_entitled", "house channel: a member's react in announcements answers not_entitled");
  c59Member.close();

  const c60Author = await new WsTest().connect("u_host");
  c60Author.send({ type: "send", id: "c_c60_1", channel_id: "ch_general", payload: { body: "c60 original" } });
  const c60Ack = await c60Author.waitFor((f) => f.type === "ack" && f.payload.for_id === "c_c60_1", "c60 ack");
  c60Author.send({ type: "edit", channel_id: "ch_help_desk", payload: { message_id: c60Ack.payload.message_id, body: "c60 hijacked" } });
  const c60Err = await c60Author.waitFor((f) => f.type === "error", "c60 mismatched edit refusal");
  ok(c60Err.payload.code === "not_entitled", "edit edge: an edit naming the message under the wrong channel answers not_entitled");
  const c60Hist = await get("/api/history?channel_id=ch_general&limit=200", "u_host");
  const c60Msg = c60Hist.body.messages.find((m) => m.id === c60Ack.payload.message_id);
  ok(c60Msg.body === "c60 original" && c60Msg.edited_at === null, "edit edge: the mismatched edit leaves the message untouched");
  c60Author.close();

  const c61Author = await new WsTest().connect("u_host");
  const c61Obs = await new WsTest().connect("u_mod");
  c61Obs.send({ type: "subscribe", payload: { channel_ids: ["ch_general"] } });
  await c61Obs.waitFor((f) => f.type === "presence", "c61 observer ready");
  c61Author.send({ type: "send", id: "c_c61_1", channel_id: "ch_general", payload: { body: "c61 delete then edit" } });
  const c61Ack = await c61Author.waitFor((f) => f.type === "ack" && f.payload.for_id === "c_c61_1", "c61 ack");
  c61Author.send({ type: "delete", channel_id: "ch_general", payload: { message_id: c61Ack.payload.message_id } });
  await c61Obs.waitFor((f) => f.type === "message.delete" && f.payload.message_id === c61Ack.payload.message_id, "c61 delete broadcast");
  c61Author.send({ type: "edit", channel_id: "ch_general", payload: { message_id: c61Ack.payload.message_id, body: "c61 edited corpse" } });
  const c61Err = await c61Author.waitFor((f) => f.type === "error", "c61 edit of deleted refusal");
  ok(c61Err.payload.code === "message_deleted", "edit guard: editing a deleted message is refused with message_deleted");
  await new Promise((r) => setTimeout(r, 300));
  ok(c61Obs.count((f) => f.type === "message.update" && f.payload.message.id === c61Ack.payload.message_id) === 0,
    "edit guard: no message.update is broadcast for the tombstone");
  const c61Hist = await get("/api/history?channel_id=ch_general&limit=200", "u_host");
  const c61Msg = c61Hist.body.messages.find((m) => m.id === c61Ack.payload.message_id);
  // B-3 reversal (Phase 3 audit, verdict LAND): this pin originally
  // asserted the tombstone KEEPS its original body — the behaviour
  // the audit proved serves deleted content. The tombstone now
  // carries no body; the edit-refusal half of the pin is unchanged.
  ok(c61Msg.body === "" && c61Msg.edited_at === null && c61Msg.deleted_at !== null,
    "edit guard: the tombstone carries no body (B-3) and gains no edited_at");
  c61Author.close(); c61Obs.close();

  const c62Author = await new WsTest().connect("u_host");
  const c62Obs = await new WsTest().connect("u_mod");
  c62Obs.send({ type: "subscribe", payload: { channel_ids: ["ch_general"] } });
  await c62Obs.waitFor((f) => f.type === "presence", "c62 observer ready");
  c62Author.send({ type: "send", id: "c_c62_1", channel_id: "ch_general", payload: { body: "c62 react target" } });
  const c62Ack = await c62Author.waitFor((f) => f.type === "ack" && f.payload.for_id === "c_c62_1", "c62 ack");
  const c62Mid = c62Ack.payload.message_id;
  c62Author.send({ type: "react", channel_id: "ch_general", payload: { message_id: c62Mid, emoji: "🔥", op: "add" } });
  const c62Added = await c62Obs.waitFor((f) => f.type === "reaction" && f.payload.message_id === c62Mid, "c62 add broadcast");
  ok(c62Added.payload.count === 1, "react edge: the setup add lands with count 1");
  c62Author.send({ type: "react", channel_id: "ch_general", payload: { message_id: c62Mid, emoji: "🔥", op: "bogus" } });
  const c62Bogus = await c62Obs.waitFor((f) => f.type === "reaction" && f.payload.message_id === c62Mid && f !== c62Added, "c62 bogus op broadcast");
  ok(c62Bogus.payload.count === 0 && !c62Bogus.payload.actor_ids.includes("u_host"),
    "react edge: a bogus op is treated as a remove — the broadcast fires with the actor removed");
  c62Author.close(); c62Obs.close();

  const c63Author = await new WsTest().connect("u_host");
  c63Author.send({ type: "send", id: "c_c63_1", channel_id: "ch_general", payload: { body: "c63 vanishing reaction" } });
  const c63Ack = await c63Author.waitFor((f) => f.type === "ack" && f.payload.for_id === "c_c63_1", "c63 ack");
  c63Author.send({ type: "react", channel_id: "ch_general", payload: { message_id: c63Ack.payload.message_id, emoji: "🔥", op: "add" } });
  await new Promise((r) => setTimeout(r, 250));
  c63Author.send({ type: "react", channel_id: "ch_general", payload: { message_id: c63Ack.payload.message_id, emoji: "🔥", op: "remove" } });
  await new Promise((r) => setTimeout(r, 250));
  const c63Hist = await get("/api/history?channel_id=ch_general&limit=200", "u_host");
  const c63Msg = c63Hist.body.messages.find((m) => m.id === c63Ack.payload.message_id);
  ok(c63Msg.reactions.length === 0, "react edge: after the only actor removes their reaction, history shows no entry for the emoji");
  c63Author.close();

  const c64Obs = await new WsTest().connect("u_mod");
  c64Obs.send({ type: "subscribe", payload: { channel_ids: ["ch_general"] } });
  await c64Obs.waitFor((f) => f.type === "presence", "c64 observer ready");
  const c64Stranger = await new WsTest().connect("u_op");
  c64Stranger.send({ type: "typing", channel_id: "ch_general", payload: { state: "start" } });
  const c64Relay = await c64Obs.waitFor((f) => f.type === "typing" && f.payload.user_id === "u_op", "c64 stranger typing relay");
  ok(c64Relay.payload.state === "start", "typing finding: a non-subscriber's typing is relayed to the channel's subscribers");
  c64Obs.close(); c64Stranger.close();

  const c65Obs = await new WsTest().connect("u_mod");
  const c65Actor = await new WsTest().connect("u_free");
  await new Promise((r) => setTimeout(r, 300));
  c65Obs.send({ type: "subscribe", payload: { channel_ids: ["ch_general"] } });
  const c65Before = await c65Obs.waitFor((f) => f.type === "presence" && f.payload.presence.user_id === "u_free", "c65 snapshot read");
  c65Actor.send({ type: "presence.set", payload: { invisible: true, state: "idle", activity: "faking it" } });
  const c65Frame = await c65Obs.waitFor((f) => f.type === "presence" && f.payload.presence.user_id === "u_free" && f.payload.presence.invisible === true, "c65 set broadcast");
  ok(c65Frame.payload.presence.state === c65Before.payload.presence.state && c65Frame.payload.presence.activity === c65Before.payload.presence.activity,
    "presence edge: presence.set honours only invisible — state and activity in the frame are ignored");
  c65Actor.send({ type: "presence.set", payload: { invisible: false } });
  await c65Obs.waitFor((f) => f.type === "presence" && f.payload.presence.user_id === "u_free" && f.payload.presence.invisible === false && f !== c65Frame, "c65 restore broadcast");
  c65Obs.close(); c65Actor.close();
  c65Actor.send({ type: "presence.set", payload: { invisible: false } });
  await c65Obs.waitFor((f) => f.type === "presence" && f.payload.presence.user_id === "u_free" && f.payload.presence.invisible === false && f !== c65Frame, "c65 restore broadcast");
  c65Obs.close(); c65Actor.close();
  c65Actor.send({ type: "presence.set", payload: { invisible: false } });
  await c65Obs.waitFor((f) => f.type === "presence" && f.payload.presence.user_id === "u_free" && f.payload.presence.invisible === false && f !== c65Frame, "c65 restore broadcast");
  c65Obs.close(); c65Actor.close();

  const c66Obs = await new WsTest().connect("u_host");
  const c66First = await new WsTest().connect("u_mod");
  c66First.close();
  await c66Obs.waitFor((f) => f.type === "presence" && f.payload.presence.user_id === "u_mod" && f.payload.presence.state === "offline", "c66 first disconnect dark");
  const c66Second = await new WsTest().connect("u_mod");
  await c66Obs.waitFor((f) => f.type === "presence" && f.payload.presence.user_id === "u_mod" && f.payload.presence.state === "online", "c66 promotion");
  c66Second.send({ type: "presence.set", payload: { invisible: true } });
  await c66Obs.waitFor((f) => f.type === "presence" && f.payload.presence.user_id === "u_mod" && f.payload.presence.invisible === true, "c66 invisible set");
  c66Second.close();
  await c66Obs.waitFor((f) => f.type === "presence" && f.payload.presence.user_id === "u_mod" && f.payload.presence.state === "offline" && f.payload.presence.invisible === true, "c66 disconnect dark");
  const c66Base = c66Obs.count((f) => f.type === "presence" && f.payload.presence.user_id === "u_mod");
  const c66Third = await new WsTest().connect("u_mod");
  await new Promise((r) => setTimeout(r, 300));
  ok(c66Obs.count((f) => f.type === "presence" && f.payload.presence.user_id === "u_mod") === c66Base,
    "presence edge: an invisible user's reconnect fires no presence broadcast");
  const c66Probe = await new WsTest().connect("u_op");
  c66Probe.send({ type: "subscribe", payload: { channel_ids: ["ch_general"] } });
  const c66Snap = await c66Probe.waitFor((f) => f.type === "presence" && f.payload.presence.user_id === "u_mod", "c66 snapshot read");
  ok(c66Snap.payload.presence.state === "offline" && c66Snap.payload.presence.invisible === true,
    "presence edge: the snapshot still shows the reconnected invisible user offline and invisible");
  c66Third.send({ type: "presence.set", payload: { invisible: false } });
  c66Third.close(); c66Probe.close(); c66Obs.close();
  c66Second.send({ type: "presence.set", payload: { invisible: false } });
  c66Second.close(); c66Probe.close(); c66Obs.close();

  const c67Head = await get("/api/history?channel_id=ch_help_desk&limit=1", "u_host");
  const c67HeadSeq = c67Head.body.messages[0].seq;
  const c67Sock = await new WsTest().connect("u_op");
  c67Sock.send({ type: "resume", payload: { last_seq: { ch_help_desk: c67HeadSeq - 500 } } });
  const c67Deadline = Date.now() + 5000;
  while (c67Sock.count((f) => f.type === "message" && f.channel_id === "ch_help_desk") < 500 && Date.now() < c67Deadline) await new Promise((r) => setTimeout(r, 25));
  ok(c67Sock.count((f) => f.type === "message" && f.channel_id === "ch_help_desk") === 500,
    "resume edge: a gap of exactly 500 replays all 500 messages");
  ok(c67Sock.count((f) => f.type === "resync") === 0, "resume edge: a gap of exactly 500 sends no resync frame");
  c67Sock.close();

  const t5f1Hist = await get("/api/history?channel_id=ch_introductions&limit=50", "u_host");
  const t5f1Msg = t5f1Hist.body.messages.find((m) => m.author_id === "u_free");
  await post("/api/reports", { target_kind: "message", target_id: t5f1Msg.id, reason: "spam", note: "t5f1 first" }, "u_host");
  const t5f1QueueA = await get("/api/mod/queue", "u_mod");
  const t5f1ItemA = t5f1QueueA.body.items.filter((i) => i.snapshot.body === t5f1Msg.body && i.state === "open" && !i.appeal).at(-1);
  await post("/api/mod/action", { report_id: t5f1ItemA.report_id, action: "mute", reason_code: "spam", note: "t5f1", override_claim: false, duration_sec: 777001 }, "u_mod");
  const t5f1SanctionsA = await get("/api/sanctions", "u_free");
  const t5f1SanctionA = t5f1SanctionsA.body.sanctions.filter((s) => s.rung === "mute" && s.duration_sec === 777001).at(-1);
  ok(!!t5f1SanctionA, "sanction id reuse: the first setup sanction exists");
  await post("/api/reports", { target_kind: "message", target_id: t5f1Msg.id, reason: "spam", note: "t5f1 second" }, "u_host");
  const t5f1QueueB = await get("/api/mod/queue", "u_mod");
  const t5f1ItemB = t5f1QueueB.body.items.filter((i) => i.snapshot.body === t5f1Msg.body && i.state === "open" && !i.appeal).at(-1);
  await post("/api/mod/action", { report_id: t5f1ItemB.report_id, action: "mute", reason_code: "spam", note: "t5f1", override_claim: false, duration_sec: 777002 }, "u_mod");
  const t5f1SanctionsB = await get("/api/sanctions", "u_free");
  const t5f1SanctionB = t5f1SanctionsB.body.sanctions.filter((s) => s.rung === "mute" && s.duration_sec === 777002).at(-1);
  ok(!!t5f1SanctionB, "sanction id reuse: the second setup sanction exists");
  await post("/api/appeals", { sanction_id: t5f1SanctionA.sanction_id, statement: "t5f1 appeal" }, "u_free");
  const t5f1QueueAppeal = await get("/api/mod/queue", "u_mod");
  const t5f1AppealItem = t5f1QueueAppeal.body.items.find((i) => i.appeal && i.snapshot.body === "t5f1 appeal" && i.state === "open");
  await post("/api/mod/appeal", { report_id: t5f1AppealItem.report_id, outcome: "overturned", reason_code: "other", note: "", override_claim: false }, "u_mod");
  await post("/api/reports", { target_kind: "message", target_id: t5f1Msg.id, reason: "spam", note: "t5f1 third" }, "u_host");
  const t5f1QueueC = await get("/api/mod/queue", "u_mod");
  const t5f1ItemC = t5f1QueueC.body.items.filter((i) => i.snapshot.body === t5f1Msg.body && i.state === "open" && !i.appeal).at(-1);
  await post("/api/mod/action", { report_id: t5f1ItemC.report_id, action: "mute", reason_code: "spam", note: "t5f1", override_claim: false, duration_sec: 777003 }, "u_mod");
  const t5f1SanctionsC = await get("/api/sanctions", "u_free");
  const t5f1SanctionC = t5f1SanctionsC.body.sanctions.filter((s) => s.rung === "mute" && s.duration_sec === 777003).at(-1);
  ok(!!t5f1SanctionC, "sanction id reuse: the post-removal sanction exists");
  ok(t5f1SanctionC.sanction_id !== t5f1SanctionB.sanction_id,
    "sanction id reuse: a sanction minted after an overturn removal never reuses a live id");
  const t5f1All = await get("/api/sanctions", "u_free");
  ok(new Set(t5f1All.body.sanctions.map((s) => s.sanction_id)).size === t5f1All.body.sanctions.length,
    "sanction id reuse: every live sanction id is unique");
  await post("/api/appeals", { sanction_id: t5f1SanctionC.sanction_id, statement: "t5f1 appeal on c" }, "u_free");
  const t5f1After = await get("/api/sanctions", "u_free");
  ok(t5f1After.body.sanctions.find((s) => s.duration_sec === 777003).appeal.state === "pending" &&
    t5f1After.body.sanctions.find((s) => s.duration_sec === 777002).appeal.state === "none",
    "sanction id reuse: an appeal on the newest sanction resolves to that sanction, not the older one");

  const t5f7Hist = await get("/api/history?channel_id=ch_strategy&limit=50", "u_op");
  const t5f7Msg = t5f7Hist.body.messages[0];
  const t5f7QueueBefore = await get("/api/mod/queue", "u_mod");
  const t5f7Before = t5f7QueueBefore.body.items.filter((i) => i.snapshot && i.snapshot.body === t5f7Msg.body).length;
  const t5f7Report = await post("/api/reports", { target_kind: "message", target_id: t5f7Msg.id, reason: "spam", note: "t5f7 unreadable" }, "u_free");
  const t5f7QueueAfter = await get("/api/mod/queue", "u_mod");
  const t5f7After = t5f7QueueAfter.body.items.filter((i) => i.snapshot && i.snapshot.body === t5f7Msg.body).length;
  ok(t5f7Report.status === 404 && t5f7After === t5f7Before,
    "report guard: reporting a message in a channel the reporter cannot read is 404 and queues nothing");

  // 21 — Phase 3 audit fix pins (2026-11-27): one block per finding
  // in runs/deepseek-audit-2026-11-27/AUDIT_REPORT.md. The bare boot
  // below runs WITHOUT KINGDOM_DEV_HOOKS: the default-boot surface is
  // what B-1/B-2 pin shut.
  async function bootBare(port) {
    const env = { ...process.env, KINGDOM_PORT: String(port), KINGDOM_HOST: "127.0.0.1" };
    delete env.KINGDOM_DEV_HOOKS;
    const srv = spawn("node", ["server.mjs"], {
      cwd: new URL(".", import.meta.url).pathname,
      env,
      stdio: ["ignore", "pipe", "inherit"],
    });
    await new Promise((resolve) => srv.stdout.once("data", resolve));
    return srv;
  }
  const bare = await bootBare(18801);
  const BARE = "http://127.0.0.1:18801";
  async function barePost(path, obj, headers = {}) {
    const r = await fetch(BARE + path, {
      method: "POST",
      headers: { "content-type": "application/json", ...headers },
      body: JSON.stringify(obj),
    });
    return { status: r.status, body: await r.json().catch(() => ({})) };
  }
  // B-1: the dev hooks must not exist in a default boot. The bare
  // boot answers 401 (B-2's identity gate sits in front of routing);
  // behind any identity the flag-gated routes themselves 404. The pin
  // is the property: the hooks never execute (never 200).
  const b1Lapse = await barePost("/api/dev/lapse", { user_id: "u_mod" });
  ok(b1Lapse.status === 401 || b1Lapse.status === 404, "B-1: POST /api/dev/lapse never executes in a default boot (no KINGDOM_DEV_HOOKS)");
  const b1Restore = await barePost("/api/dev/restore", { user_id: "u_spammer" });
  ok(b1Restore.status === 401 || b1Restore.status === 404, "B-1: POST /api/dev/restore never executes in a default boot");
  // B-2: no stub identity and no default user in a default boot —
  // every request without the (absent) production identity fails 401.
  async function bareGet(path, headers = {}) {
    const r = await fetch(BARE + path, { headers });
    return { status: r.status, body: await r.json().catch(() => ({})) };
  }
  const b2NoAuth = await bareGet("/api/me");
  ok(b2NoAuth.status === 401, "B-2: no credentials -> 401 in a default boot (no default user)");
  const b2Stub = await bareGet("/api/me", { authorization: "Bearer local:u_mod" });
  ok(b2Stub.status === 401, "B-2: Bearer local: stub identity is not honoured in a default boot");
  const b2Wallet = await bareGet("/api/entitlements", { "x-kingdom-wallet": "wallet_mod" });
  ok(b2Wallet.status === 401, "B-2: X-Kingdom-Wallet stub identity is not honoured in a default boot");
  const b2WsHead = await new Promise((resolve) => {
    const sock = net.connect(18801, "127.0.0.1", () => {
      const key = crypto.randomBytes(16).toString("base64");
      sock.write(`GET /ws HTTP/1.1\r\nHost: 127.0.0.1:18801\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: ${key}\r\nSec-WebSocket-Version: 13\r\nAuthorization: Bearer local:u_host\r\n\r\n`);
    });
    let head = "";
    sock.on("data", (c) => { head += c.toString(); if (head.includes("\r\n\r\n") || head.length > 512) { sock.destroy(); resolve(head); } });
    sock.on("error", () => resolve(head));
    sock.on("close", () => resolve(head));
    setTimeout(() => { sock.destroy(); resolve(head); }, 2000);
  });
  ok(!b2WsHead.includes("101"), "B-2: WS upgrade with stub identity is refused in a default boot (no 101)");
  bare.kill();

  // B-3: a deleted message's body is never served again — the
  // tombstone carries no body, in history or in resume replay.
  const b3Host = await new WsTest().connect("u_host");
  b3Host.send({ type: "subscribe", payload: { channel_ids: ["ch_general"] } });
  await b3Host.waitFor((f) => f.type === "presence", "b3 subscribed");
  b3Host.send({ type: "send", id: "c_b3_1", channel_id: "ch_general", payload: { body: "b3-secret-body-xyz" } });
  const b3Ack = await b3Host.waitFor((f) => f.type === "ack" && f.payload.for_id === "c_b3_1", "b3 ack");
  b3Host.send({ type: "delete", channel_id: "ch_general", payload: { message_id: b3Ack.payload.message_id } });
  await b3Host.waitFor((f) => f.type === "message.delete" && f.payload.message_id === b3Ack.payload.message_id, "b3 delete broadcast");
  const b3Hist = await get("/api/history?channel_id=ch_general&limit=200", "u_free");
  const b3Rec = b3Hist.body.messages.find((m) => m.id === b3Ack.payload.message_id);
  ok(!!b3Rec && b3Rec.deleted_at !== null && b3Rec.body !== "b3-secret-body-xyz" && !String(b3Rec.body ?? "").includes("b3-secret"),
    "B-3: history serves the tombstone without the deleted body");
  const b3Replay = await new WsTest().connect("u_free");
  b3Replay.send({ type: "resume", payload: { last_seq: { ch_general: b3Ack.payload.seq - 1 } } });
  const b3Frame = await b3Replay.waitFor((f) => f.type === "message" && f.payload.message.id === b3Ack.payload.message_id, "b3 resume replay");
  ok(b3Frame.payload.message.body !== "b3-secret-body-xyz" && !String(b3Frame.payload.message.body ?? "").includes("b3-secret"),
    "B-3: resume replay serves the tombstone without the deleted body");
  b3Host.close(); b3Replay.close();

  // A-2: content_removed removes the REPORTED message by identity,
  // never by body-text match — with two identical bodies, the
  // unreported one must survive (audit PoC P10).
  const a2Host = await new WsTest().connect("u_host");
  a2Host.send({ type: "subscribe", payload: { channel_ids: ["ch_general"] } });
  await a2Host.waitFor((f) => f.type === "presence", "a2 subscribed");
  const a2Ids = [];
  for (const fid of ["c_a2_1", "c_a2_2"]) {
    a2Host.send({ type: "send", id: fid, channel_id: "ch_general", payload: { body: "a2-dup-body" } });
    const ack = await a2Host.waitFor((f) => f.type === "ack" && f.payload.for_id === fid, "a2 ack " + fid);
    a2Ids.push(ack.payload.message_id);
  }
  await post("/api/reports", { target_kind: "message", target_id: a2Ids[1], reason: "spam" }, "u_free");
  const a2Queue = await get("/api/mod/queue", "u_mod");
  const a2Item = a2Queue.body.items.filter((i) => i.state === "open" && i.snapshot.body === "a2-dup-body").pop();
  ok(!!a2Item, "A-2: the report on the second duplicate is queued");
  await post("/api/mod/claim", { report_id: a2Item.report_id }, "u_mod");
  const a2Act = await post("/api/mod/action", { report_id: a2Item.report_id, action: "content_removed", reason_code: "spam" }, "u_mod");
  ok(a2Act.status === 200, "A-2: content_removed succeeds");
  const a2Hist = await get("/api/history?channel_id=ch_general&limit=200", "u_host");
  const a2First = a2Hist.body.messages.find((m) => m.id === a2Ids[0]);
  const a2Second = a2Hist.body.messages.find((m) => m.id === a2Ids[1]);
  ok(a2First.deleted_at === null && a2Second.deleted_at !== null,
    "A-2: content_removed deleted the reported message, not the identical-body earlier one");
  a2Host.close();

  // A-1: a repeat appeal on an already-appealed (pending) sanction
  // must not re-queue a duplicate mod-queue item (audit PoC P9). A
  // fresh appeal after a decision still opens a fresh item — that
  // behaviour is pinned in the appeals edge section above and must
  // keep working.
  const a1Spam = await new WsTest().connect("u_spammer");
  a1Spam.send({ type: "send", id: "c_a1_1", channel_id: "ch_general", payload: { body: "a1 appeal bait" } });
  const a1Ack = await a1Spam.waitFor((f) => f.type === "ack" && f.payload.for_id === "c_a1_1", "a1 ack");
  await post("/api/reports", { target_kind: "message", target_id: a1Ack.payload.message_id, reason: "spam" }, "u_free");
  const a1Queue0 = await get("/api/mod/queue", "u_mod");
  const a1Item = a1Queue0.body.items.filter((i) => i.state === "open" && i.snapshot.body === "a1 appeal bait").pop();
  await post("/api/mod/claim", { report_id: a1Item.report_id }, "u_mod");
  await post("/api/mod/action", { report_id: a1Item.report_id, action: "mute", reason_code: "spam", duration_sec: 424242 }, "u_mod");
  const a1Sanctions = await get("/api/sanctions", "u_spammer");
  const a1Sanction = a1Sanctions.body.sanctions.find((s) => s.duration_sec === 424242);
  ok(!!a1Sanction && a1Sanction.appeal.state === "none", "A-1: setup — a fresh mute sanction with no appeal");
  const a1First = await post("/api/appeals", { sanction_id: a1Sanction.sanction_id, statement: "a1 first appeal" }, "u_spammer");
  const a1Second = await post("/api/appeals", { sanction_id: a1Sanction.sanction_id, statement: "a1 duplicate appeal" }, "u_spammer");
  ok(a1First.status === 200 && a1Second.status === 200, "A-1: both appeal calls answer 200 (the repeat is idempotent, not an error)");
  const a1Queue1 = await get("/api/mod/queue", "u_mod");
  const a1AppealItems = a1Queue1.body.items.filter((i) => i.appeal && i.snapshot.body.startsWith("a1 ") && i.snapshot.body.includes("appeal"));
  ok(a1AppealItems.length === 1, "A-1: two appeals on one pending sanction queue exactly one mod-queue item");
  a1Spam.close();

  // B-9's pins live in section 12 (above), where sug_2 is still
  // live; by here the operator has legitimately dismissed it.

  // B-7: typing/edit/react frames are rate-limited per connection
  // (audit PoC P6: a 50-frame typing burst relayed in full). A burst
  // of 50 must land at the listener capped, not whole.
  const b7Listener = await new WsTest().connect("u_mod");
  b7Listener.send({ type: "subscribe", payload: { channel_ids: ["ch_general"] } });
  await b7Listener.waitFor((f) => f.type === "presence", "b7 listener ready");
  const b7Sender = await new WsTest().connect("u_op");
  await new Promise((r) => setTimeout(r, 200));
  for (let i = 0; i < 50; i++)
    b7Sender.send({ type: "typing", channel_id: "ch_general", payload: { state: "typing" } });
  await new Promise((r) => setTimeout(r, 900));
  const b7Got = b7Listener.count((f) => f.type === "typing" && f.payload.user_id === "u_op");
  ok(b7Got >= 1 && b7Got <= 20, `B-7: a 50-frame typing burst is relayed capped (got ${b7Got}, cap 20/s per connection)`);
  b7Listener.close(); b7Sender.close();

  a.close(); b.close(); c.close(); d.close(); aBad.close();
} finally {
  server.kill();
}
console.log(`\n${checks - failures}/${checks} checks passed`);
process.exit(failures === 0 ? 0 : 1);
