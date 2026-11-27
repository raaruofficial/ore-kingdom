// Message-echo bench for the local Kingdom backend.
// Measures, over N sends: (a) send -> ack latency, (b) send -> own
// broadcast (`message` frame) latency, on a loopback connection.
// SURFACE: this machine, loopback TCP, Node client — a protocol-level
// number, NOT an app or device measurement. Run: node bench.mjs [n]

import { spawn } from "node:child_process";
import net from "node:net";
import crypto from "node:crypto";

const PORT = 18801;
const N = Number(process.argv[2] ?? 50);

const server = spawn("node", ["server.mjs"], {
  cwd: new URL(".", import.meta.url).pathname,
  env: { ...process.env, KINGDOM_PORT: String(PORT), KINGDOM_HOST: "127.0.0.1" },
  stdio: ["ignore", "pipe", "inherit"],
});
await new Promise((resolve) => server.stdout.once("data", resolve));

function connect(user) {
  return new Promise((resolve) => {
    const key = crypto.randomBytes(16).toString("base64");
    const socket = net.connect(PORT, "127.0.0.1", () => {
      socket.write(
        `GET /ws HTTP/1.1\r\nHost: 127.0.0.1:${PORT}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n` +
        `Sec-WebSocket-Key: ${key}\r\nSec-WebSocket-Version: 13\r\nAuthorization: Bearer local:${user}\r\n\r\n`,
      );
    });
    let buf = Buffer.alloc(0);
    let handshook = false;
    const frames = [];
    const waiters = [];
    socket.on("data", (chunk) => {
      buf = Buffer.concat([buf, chunk]);
      if (!handshook) {
        const idx = buf.indexOf("\r\n\r\n");
        if (idx === -1) return;
        handshook = true;
        buf = buf.subarray(idx + 4);
        resolve(api);
      }
      for (;;) {
        if (buf.length < 2) return;
        let len = buf[1] & 0x7f;
        let off = 2;
        if (len === 126) { if (buf.length < 4) return; len = buf.readUInt16BE(2); off = 4; }
        else if (len === 127) { if (buf.length < 10) return; len = Number(buf.readBigUInt64BE(2)); off = 10; }
        if (buf.length < off + len) return;
        const obj = JSON.parse(buf.subarray(off, off + len).toString("utf8"));
        buf = buf.subarray(off + len);
        frames.push(obj);
        for (let i = waiters.length - 1; i >= 0; i--) if (waiters[i](obj)) waiters.splice(i, 1);
      }
    });
    const api = {
      send(obj) {
        const payload = Buffer.from(JSON.stringify(obj), "utf8");
        const mask = crypto.randomBytes(4);
        const header = Buffer.alloc(4);
        header[0] = 0x81; header[1] = 0x80 | 126; header.writeUInt16BE(payload.length, 2);
        const masked = Buffer.from(payload.map((b, i) => b ^ mask[i % 4]));
        socket.write(Buffer.concat([header, mask, masked]));
      },
      waitFor(pred) {
        const hit = frames.find(pred);
        if (hit) return Promise.resolve(hit);
        return new Promise((res) => waiters.push((o) => (pred(o) ? (res(o), true) : false)));
      },
      close() { socket.end(); },
    };
  });
}

const pct = (sorted, p) => sorted[Math.min(sorted.length - 1, Math.floor((p / 100) * sorted.length))];
const stats = (xs) => {
  const s = [...xs].sort((a, b) => a - b);
  return { n: s.length, median_ms: pct(s, 50), p95_ms: pct(s, 95), max_ms: s[s.length - 1] };
};

try {
  const ws = await connect("u_host");
  ws.send({ type: "subscribe", payload: { channel_ids: ["ch_general"] } });
  await ws.waitFor((f) => f.type === "presence");
  const ackLat = [];
  const echoLat = [];
  for (let i = 0; i < N; i++) {
    const ref = `bench_${i}`;
    const t0 = performance.now();
    let ackAt = 0;
    const ackP = ws.waitFor((f) => f.type === "ack" && f.payload.for_id === ref).then(() => { ackAt = performance.now(); });
    const echoP = ws.waitFor((f) => f.type === "message" && f.payload.message.body === `bench ${i}`);
    ws.send({ type: "send", id: ref, channel_id: "ch_general", payload: { body: `bench ${i}` } });
    await ackP;
    ackLat.push(ackAt - t0);
    await echoP;
    echoLat.push(performance.now() - t0);
  }
  ws.close();
  console.log(JSON.stringify({
    surface: "loopback on this VM (Node protocol client -> local backend); NOT a device or app measurement",
    sends: N,
    send_to_ack: stats(ackLat),
    send_to_broadcast_echo: stats(echoLat),
  }, null, 2));
} finally {
  server.kill();
}
