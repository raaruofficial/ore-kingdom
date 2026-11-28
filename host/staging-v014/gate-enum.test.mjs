// B-4 enumeration test (Phase 3 audit): every action in the custody
// bundle is probed against a locally booted wrapper (throwaway copy
// of the custody DB) and classified by outcome. The EXPECTED table
// pins the dispatch contract: the B-4 gate flip (default-deny) must
// change NO current action's outcome — an action dispatches exactly
// as before, or refuses exactly as before. Any future drift (a new
// bundle action silently inheriting dispatch, a set edit changing
// an outcome) fails this test.
//
// Run: bun run gate-enum.test.mjs [--print]
//   --print writes the observed table instead of asserting (used to
//   capture the baseline before the gate flip; the committed table
//   below is that baseline, captured 2026-11-27 pre-flip).
import { spawn } from "node:child_process";
import { copyFileSync, mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Actions } from "./actions.bundle.js";

const PORT = 18391;
const HERE = new URL(".", import.meta.url).pathname;
const tmp = mkdtempSync(join(tmpdir(), "ok-gate-enum-"));
const dbPath = join(tmp, "app.db");
copyFileSync(join(HERE, "custody-app.db"), dbPath);

const EXPECTED = {
  "authenticateSiws": "DISPATCHED",
  "cancelSubscription": "REFUSED_NO_SUBSCRIPTION",
  "claimFoundingSpot": "SIWS_REQUIRED",
  "completeCheckout": "REFUSED_PAYMENTS",
  "completeFoundingBadgeCheckout": "REFUSED_PAYMENTS",
  "completePaidPinCheckout": "SIWS_REQUIRED",
  "completeTipCheckout": "SIWS_REQUIRED",
  "createCheckout": "REFUSED_PAYMENTS",
  "createFoundingBadgeCheckout": "REFUSED_PAYMENTS",
  "createPaidPinCheckout": "SIWS_REQUIRED",
  "createReply": "SIWS_REQUIRED",
  "createThread": "SIWS_REQUIRED",
  "createTipCheckout": "SIWS_REQUIRED",
  "getOwnerOverview": "REFUSED_OPERATOR",
  "getPublicConfig": "DISPATCHED",
  "getSubscriptionStatus": "DISPATCHED",
  "getThread": "DISPATCHED",
  "listForum": "DISPATCHED",
  "listModerationFeed": "DISPATCHED",
  "listMotherlode": "DISPATCHED",
  "listNotifications": "DISPATCHED",
  "markNotificationsRead": "SIWS_REQUIRED",
  "registerProspector": "DISPATCHED",
  "reportContent": "SIWS_REQUIRED",
  "simulateBillingEvent": "REFUSED_PAYMENTS",
  "toggleLike": "SIWS_REQUIRED",
  "updateOwnerSettings": "REFUSED_OPERATOR",
  "voteFounderPoll": "SIWS_REQUIRED",
  "voteRestore": "SIWS_REQUIRED",
  "__unknown__": "REFUSED_NOT_AVAILABLE",
};

function classify(p) {
  const e = p?.error;
  if (typeof e === "string") {
    if (e.startsWith("A verified sign-in proof is required")) return "SIWS_REQUIRED";
    if (e.startsWith("Payments are disabled")) return "REFUSED_PAYMENTS";
    if (e.startsWith("Operator actions are unavailable")) return "REFUSED_OPERATOR";
    if (e.startsWith("No paid subscription")) return "REFUSED_NO_SUBSCRIPTION";
    if (e.includes("is not available in this build")) return "REFUSED_NOT_AVAILABLE";
    if (e.startsWith("Unknown action")) return "REFUSED_UNKNOWN";
    return "DISPATCHED"; // handler reached; its own validation/business error
  }
  return "DISPATCHED";
}

const srv = spawn("bun", ["run", "server.ts"], {
  cwd: HERE,
  env: { ...process.env, OK_PORT: String(PORT), OK_DB_PATH: dbPath, OK_CLIENT_DIR: tmp },
  stdio: ["ignore", "pipe", "pipe"],
});
let bootLog = "";
srv.stdout.on("data", (c) => { bootLog += c; });
srv.stderr.on("data", (c) => { bootLog += c; });
let up = false;
for (let i = 0; i < 40 && !up; i++) {
  try { const r = await fetch(`http://127.0.0.1:${PORT}/healthz`); up = r.ok; } catch {}
  if (!up) await new Promise((r) => setTimeout(r, 250));
}
if (!up) { console.error("wrapper did not boot\n" + bootLog); srv.kill(); process.exit(2); }

async function act(action) {
  const r = await fetch(`http://127.0.0.1:${PORT}/actions`, {
    method: "POST", headers: { "content-type": "application/json" },
    body: JSON.stringify({ action, args: {} }),
  });
  return r.json().catch(() => ({}));
}

const names = Object.keys(Actions).sort();
const observed = {};
for (const n of names) observed[n] = classify(await act(n));
observed["__unknown__"] = classify(await act("definitelyNotAnAction"));
srv.kill();

if (process.argv.includes("--print")) {
  console.log(JSON.stringify(observed, null, 2));
  process.exit(0);
}

let failures = 0;
if (names.length !== 29) { failures++; console.error(`FAIL: bundle enumerates ${names.length} actions, expected 29`); }
for (const [name, want] of Object.entries(EXPECTED)) {
  const got = observed[name];
  if (got !== want) { failures++; console.error(`FAIL: ${name}: expected ${want}, got ${got}`); }
  else console.log(`ok: ${name} -> ${got}`);
}
for (const name of Object.keys(observed)) {
  if (!(name in EXPECTED)) { failures++; console.error(`FAIL: ${name} observed (${observed[name]}) but absent from the EXPECTED table`); }
}
console.log(failures === 0 ? `\n${Object.keys(EXPECTED).length}/${Object.keys(EXPECTED).length} enumeration outcomes match` : `\n${failures} enumeration failure(s)`);
process.exit(failures === 0 ? 0 : 1);
