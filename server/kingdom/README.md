# Kingdom local backend

A zero-dependency Node implementation of the pinned Ore Kingdom
client contract, for local development and proof only.

- Contract: `~/workspace/goals/ore-kingdom-dapp-store-submission/files/fable-build/CONTRACT.md`
  (frames §2–§4, shapes §6, history/search §7, exclusions §8) plus
  the INTEGRATION_MAP §3 TODO-CONTRACT read models, in exactly the
  paths and shapes the Android host layer calls
  (`android/app/src/main/java/com/raaru/orekingdom/kingdom/KingdomTransport.kt`).
- Run: `node server.mjs` — listens on `KINGDOM_HOST:KINGDOM_PORT`
  (default `0.0.0.0:8799`, matching the client's default
  `KINGDOM_API_URL=http://10.0.2.2:8799` from the Android emulator).
- Test: `node test.mjs` — boots a scratch instance and runs the
  contract-surface checks (44 assertions: REST shapes, channel
  filtering, history cursors, WS ack/idempotency/slowmode/resume,
  moderation flow, sanctions/appeals, and the SW2-03 lapse push).

## Hard limits (by design)

- **Local only.** In-memory seeded state; nothing persists;
  nothing is deployed anywhere.
- **Identity is a stub:** `Authorization: Bearer local:<user_id>`
  (seed users `u_host` member, `u_mod` moderator, `u_op` operator,
  `u_free` free, `u_spammer` member) or an `X-Kingdom-Wallet`
  header matching a seed wallet; default `u_host`. This is NOT a
  production auth shape — production binds the connection to the
  wallet identity per CONTRACT §1.
- **No money surface:** no payment, purchase, bid, tip, gift, or
  signing shape exists (CONTRACT §8). Pass state changes happen
  only through the local test hooks below.
- **Test hooks (not contract surface):** `POST /api/dev/lapse`
  and `POST /api/dev/restore` with `{ "user_id": "…" }` flip a
  user to the lapsed (free, read-only) entitlement shape and push
  `role.update` — the push carries the server-filtered channel
  list alongside the entitlements, which is the SW2-03 server
  half. Suspension/ban mod actions push the same frame.
- **Typing relay** is broadcast to other subscribers as an
  extension beyond CONTRACT §4; the current client's inbound
  typing path is a stub (`applyTypingStub`), so it is ignored
  there today.
- Plain `ws://` locally; the contract's WSS requirement is a
  production transport property, unchanged.
