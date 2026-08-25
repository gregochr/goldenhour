/**
 * One relative-age formatter, shared by every "how old is this?" stamp.
 *
 * <p>There were two of these, and both stopped at an unbounded hours tier — one for the briefing's
 * age and {@code formatCalcTime} in {@code UserSettingsModal.jsx} for the drive-time stamp. That
 * was survivable for the briefing, which regenerates every 8–10 hours, and not for the drive-time
 * stamp, which only moved when a user pressed a button: left alone for a month it read
 * <em>"744h ago"</em>, a number nobody can convert at a glance.
 *
 * <p>They also differed cosmetically — {@code "just now"} vs {@code "Just now"}, {@code "5m ago"}
 * vs {@code "5 min ago"} — which is exactly how two copies of one idea drift. Both styles are
 * preserved here as an option rather than standardised away, because each is pinned by a test and
 * neither is wrong; what matters is that the *tiers* now live in one place.
 */

/** Minutes below which we say "just now" rather than a count. */
const JUST_NOW_MINUTES = 1;
const MINUTES_PER_HOUR = 60;
const HOURS_PER_DAY = 24;

/**
 * Formats how long ago an instant was, as a short relative string.
 *
 * <p>Tiers: under a minute → "just now"; under an hour → minutes; under a day → hours; beyond
 * that → days, unbounded. There is deliberately no weeks or months tier: "45d ago" needs no
 * conversion, whereas "1mo ago" quietly hides anything from 30 to 60 days, and this stamp exists
 * precisely to show that something has gone stale.
 *
 * <p>Days use {@code floor} rather than {@code round} — 35 hours is "1d ago", not "2d ago",
 * because an elapsed-time stamp should never claim more time has passed than actually has.
 * Minutes and hours keep {@code round}, matching the behaviour both call sites already had.
 *
 * @param {string|null|undefined} isoString UTC instant, with or without a trailing Z
 * @param {Object}  [options]
 * @param {boolean} [options.verbose=false] true for "Just now" / "5 min ago" (the Settings style),
 *                                          false for "just now" / "5m ago" (the briefing style)
 * @param {number}  [options.now] epoch ms to measure against; defaults to the current time.
 *                                Present so callers can drive it from a ticking clock in state.
 * @returns {string|null} the formatted age, or null when there is nothing to format
 */
export function formatRelativeAge(isoString, { verbose = false, now = null } = {}) {
  if (!isoString) return null;
  // Instants arrive from the backend both with and without the Z; treat both as UTC.
  const then = new Date(isoString.endsWith('Z') ? isoString : `${isoString}Z`);
  if (Number.isNaN(then.getTime())) return null;

  const nowMs = now ?? Date.now();
  const minutes = Math.round((nowMs - then.getTime()) / 60000);

  if (minutes < JUST_NOW_MINUTES) return verbose ? 'Just now' : 'just now';
  if (minutes < MINUTES_PER_HOUR) return verbose ? `${minutes} min ago` : `${minutes}m ago`;

  const hours = Math.round(minutes / MINUTES_PER_HOUR);
  if (hours < HOURS_PER_DAY) return `${hours}h ago`;

  const days = Math.floor(hours / HOURS_PER_DAY);
  return `${days}d ago`;
}
