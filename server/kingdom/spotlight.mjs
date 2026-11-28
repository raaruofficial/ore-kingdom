/*
 * This is the spotlight auction state model for the local contract backend.
 * Pins are auctioned under the price model of record 2026-11-27 (not sold flat);
 * the contract carries no bid shape, so this module is the state model the REST spotlight read serves;
 * wins are recorded elsewhere and passed in.
 */

// Interim values — the price model of record carries 2 slots and leaves the auction floor open,
// while FRAMEWORK_PIVOT_DISCORD_2026-11-26.md specifies one slot at a $50/12h floor;
// the owner confirms both before the auction ships.
export const PIN_SLOT_COUNT = 2; // OWNER-DETAIL-OPEN: confirm slot count and auction floor
export const PIN_AUCTION_FLOOR_CENTS = 5000; // OWNER-DETAIL-OPEN: confirm slot count and auction floor
export const PIN_WINDOW_MS = 12 * 3600_000;

export function scheduleWin(wins, win, nowMs) {
  const active = wins.filter((entry) => entry.starts_at <= nowMs && nowMs < entry.ends_at);

  let starts_at;
  if (active.length < PIN_SLOT_COUNT) {
    starts_at = nowMs;
  } else {
    starts_at = active.reduce(
      (earliest, entry) => Math.min(earliest, entry.ends_at),
      Number.POSITIVE_INFINITY,
    );
  }

  const ends_at = starts_at + PIN_WINDOW_MS;

  return { ...win, starts_at, ends_at };
}

export function computeSpotlightState(wins, nowMs) {
  const active = wins
    .filter((entry) => entry.starts_at <= nowMs && nowMs < entry.ends_at)
    .sort((a, b) => a.ends_at - b.ends_at);

  if (active.length === 0) {
    return {
      occupied: false,
      post: null,
      window_ends_at: null,
      floor_usd: PIN_AUCTION_FLOOR_CENTS / 100,
    };
  }

  const first = active[0];

  return {
    occupied: true,
    post: {
      author_id: first.author_id,
      body: first.body,
      commercial: first.commercial,
    },
    window_ends_at: new Date(first.ends_at).toISOString(),
    floor_usd: PIN_AUCTION_FLOOR_CENTS / 100,
  };
}
