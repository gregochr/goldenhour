/**
 * View-model helpers for the admin Users screen (Manage → Data → Users). Everything the server decides (a
 * reader's sub-region status and dates, whether an account is awaiting verification) arrives in the user
 * summary; this file only names, filters, sorts and spells it. No date is computed from another date.
 */
import { formatInstantUk, parseUtcInstant } from './conversions.js';
import { appNow } from './rewind.js';

/** The plain role labels (the values stay LITE_USER, PRO_USER and ADMIN on the wire). */
export const ROLE_LABELS = { LITE_USER: 'Lite', PRO_USER: 'Pro', ADMIN: 'Admin' };

/** The roles in the order the selects and chips list them. */
export const ROLE_OPTIONS = [
  { value: 'LITE_USER', label: 'Lite' },
  { value: 'PRO_USER', label: 'Pro' },
  { value: 'ADMIN', label: 'Admin' },
];

/**
 * The plain label for a role value.
 *
 * @param {string} role LITE_USER, PRO_USER or ADMIN
 * @returns {string} Lite, Pro or Admin (the value itself for an unknown role)
 */
export function roleLabel(role) {
  return ROLE_LABELS[role] ?? role ?? '';
}

/** Account status values. */
export const STATUS = { ACTIVE: 'active', AWAITING: 'awaiting', DISABLED: 'disabled' };

/**
 * The account's status: awaiting verification (a pending sign-up, its own state), disabled (switched off by an
 * admin) or active.
 *
 * @param {{enabled: boolean, awaitingVerification?: boolean}} user
 * @returns {'active'|'awaiting'|'disabled'}
 */
export function statusOf(user) {
  if (user.awaitingVerification) return STATUS.AWAITING;
  return user.enabled ? STATUS.ACTIVE : STATUS.DISABLED;
}

/** The status as the screen words it. */
export const STATUS_LABELS = {
  [STATUS.ACTIVE]: 'Active',
  [STATUS.AWAITING]: 'Awaiting verification',
  [STATUS.DISABLED]: 'Disabled',
};

/** Text and dot colours per status. */
export const STATUS_TONES = {
  [STATUS.ACTIVE]: { dot: 'bg-plex-ok', text: 'text-plex-text' },
  [STATUS.AWAITING]: { dot: 'bg-verdict-marginal', text: 'text-plex-text' },
  [STATUS.DISABLED]: { dot: 'bg-plex-text-muted', text: 'text-plex-text-muted' },
};

/** The empty filter set. */
export const NO_FILTERS = {
  search: '', role: 'ALL', awaiting: false, disabled: false, noSubRegions: false, locked: false, overLimit: false,
};

/**
 * Whether a user matches the search box: the text appears in the username OR the email, ignoring case.
 *
 * @param {{username: string, email?: string}} user
 * @param {string} search
 * @returns {boolean}
 */
export function matchesSearch(user, search) {
  const needle = search.trim().toLowerCase();
  if (!needle) return true;
  return (user.username ?? '').toLowerCase().includes(needle)
    || (user.email ?? '').toLowerCase().includes(needle);
}

/** Whether a user is a Lite reader holding no sub-regions. */
function hasNoSubRegions(user) {
  return user.role === 'LITE_USER' && user.subRegionStatus === 'FIRST_CHOICE';
}

/**
 * Applies the filters: the search and the single-select role, then every toggled chip, all ANDed.
 *
 * @param {Array<object>} users
 * @param {typeof NO_FILTERS} filters
 * @returns {Array<object>}
 */
export function applyUserFilters(users, filters) {
  return users.filter((user) => matchesSearch(user, filters.search)
    && (filters.role === 'ALL' || user.role === filters.role)
    && (!filters.awaiting || statusOf(user) === STATUS.AWAITING)
    && (!filters.disabled || statusOf(user) === STATUS.DISABLED)
    && (!filters.noSubRegions || hasNoSubRegions(user))
    && (!filters.locked || user.subRegionStatus === 'LOCKED')
    && (!filters.overLimit || user.overLimit === true));
}

/**
 * The counts the chips carry, over every user.
 *
 * @param {Array<object>} users
 * @returns {{all: number, LITE_USER: number, PRO_USER: number, ADMIN: number, awaiting: number,
 *   disabled: number, noSubRegions: number, locked: number, overLimit: number}}
 */
export function chipCounts(users) {
  const counts = {
    all: users.length, LITE_USER: 0, PRO_USER: 0, ADMIN: 0,
    awaiting: 0, disabled: 0, noSubRegions: 0, locked: 0, overLimit: 0,
  };
  for (const user of users) {
    if (counts[user.role] !== undefined) counts[user.role] += 1;
    const status = statusOf(user);
    if (status === STATUS.AWAITING) counts.awaiting += 1;
    if (status === STATUS.DISABLED) counts.disabled += 1;
    if (hasNoSubRegions(user)) counts.noSubRegions += 1;
    if (user.subRegionStatus === 'LOCKED') counts.locked += 1;
    if (user.overLimit === true) counts.overLimit += 1;
  }
  return counts;
}

/**
 * A civil date from the server ("2026-11-25") as "25 Nov", read as the UTC day it names so no zone moves it.
 *
 * @param {string|null|undefined} civilDate YYYY-MM-DD
 * @returns {string|null}
 */
export function formatCivilDayShort(civilDate) {
  if (typeof civilDate !== 'string') return null;
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(civilDate);
  if (!match) return null;
  const date = new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3])));
  if (Number.isNaN(date.getTime())) return null;
  return date.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', timeZone: 'UTC' });
}

/**
 * What the list's Sub-regions cell says on its first line, and the names for its second.
 *
 * @param {object} user a summary from GET /api/users
 * @returns {{kind: 'none'|'role'|'picks', text?: string, count?: number, detail?: string, names?: string}}
 */
export function subRegionsCell(user) {
  if (user.role === 'PRO_USER') return { kind: 'role', text: 'Pro: every sub-region' };
  if (user.role === 'ADMIN') return { kind: 'role', text: 'Admin: every sub-region' };
  const names = user.subRegions ?? [];
  if (names.length === 0) return { kind: 'none', text: 'None chosen' };
  let detail = 'can change';
  if (user.subRegionStatus === 'LOCKED') {
    const day = formatCivilDayShort(user.nextChangeOn);
    detail = day ? `locked to ${day}` : 'locked';
  } else if (user.subRegionStatus === 'FREE_UNTIL') {
    const day = formatCivilDayShort(user.freeUntil);
    detail = day ? `free to ${day}` : 'free to change';
  }
  if (user.overLimit) detail = `${detail} · over limit`;
  return { kind: 'picks', count: names.length, detail, names: names.join(', ') };
}

/** The UK calendar day of an instant as YYYY-MM-DD. */
function ukDayKey(date) {
  const parts = formatInstantUk(date, { day: '2-digit', month: '2-digit', year: 'numeric' });
  if (!parts) return null;
  const [d, m, y] = parts.split('/');
  return `${y}-${m}-${d}`;
}

/** The day before a YYYY-MM-DD key, as a key. */
function dayBefore(key) {
  const [y, m, d] = key.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d - 1)).toISOString().slice(0, 10);
}

/**
 * When an instant was, in the words the list uses: "Today, 09:14", "Yesterday", "21 Oct" (with the year when
 * it is not this year) or, for no instant, "Never". UK calendar and clock; "now" is the app's, so Rewind moves it.
 *
 * @param {string|null|undefined} value a backend instant (bare means UTC)
 * @param {Date} [now] the instant to measure from
 * @returns {string}
 */
export function formatLastActive(value, now = appNow()) {
  const instant = parseUtcInstant(value);
  if (!instant) return 'Never';
  const day = ukDayKey(instant);
  const today = ukDayKey(now);
  if (day === today) {
    return `Today, ${formatInstantUk(instant, { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })}`;
  }
  if (day === dayBefore(today)) return 'Yesterday';
  const sameYear = day.slice(0, 4) === today.slice(0, 4);
  return formatInstantUk(instant, sameYear
    ? { day: 'numeric', month: 'short' }
    : { day: 'numeric', month: 'short', year: 'numeric' });
}

/**
 * A date as "19 Oct 2026" on the UK calendar, or an em dash when there is none.
 *
 * @param {string|null|undefined} value a backend instant
 * @returns {string}
 */
export function formatJoined(value) {
  return formatInstantUk(value, { day: 'numeric', month: 'short', year: 'numeric' }) ?? '—';
}

/**
 * The joined date as the panel header words it: "today" for a UK-today join, else the date.
 *
 * @param {string|null|undefined} value a backend instant
 * @param {Date} [now]
 * @returns {string}
 */
export function formatJoinedPhrase(value, now = appNow()) {
  const instant = parseUtcInstant(value);
  if (instant && ukDayKey(instant) === ukDayKey(now)) return 'today';
  return formatJoined(value);
}

/**
 * When a verification link was sent, as a phrase for the awaiting-verification box: "today at 08:02" or
 * "27 Oct at 08:02".
 *
 * @param {string|null|undefined} value a backend instant
 * @param {Date} [now]
 * @returns {string|null} null when there is no time
 */
export function formatSentWhen(value, now = appNow()) {
  const instant = parseUtcInstant(value);
  if (!instant) return null;
  const time = formatInstantUk(instant, { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });
  if (ukDayKey(instant) === ukDayKey(now)) return `today at ${time}`;
  const day = formatInstantUk(instant, { day: 'numeric', month: 'short' });
  return `${day} at ${time}`;
}

/**
 * The sort keys' accessors: a missing value is null, so the sort puts it last.
 */
export const USER_SORT_ACCESSORS = {
  username: (u) => u.username ?? '',
  role: (u) => roleLabel(u.role),
  subRegions: (u) => (u.role === 'LITE_USER' ? (u.subRegions ?? []).length : null),
  status: (u) => STATUS_LABELS[statusOf(u)],
  lastActive: (u) => u.lastActiveAt || null,
  joined: (u) => u.createdAt || null,
};

/**
 * The note shown under the role select when the admin changes a role, saying what happens to sub-regions
 * (handoff B5). Nothing for an unchanged role or a move between Pro and Admin.
 *
 * @param {object} args
 * @param {string} args.from the current role value
 * @param {string} args.to the chosen role value
 * @param {string} args.username
 * @param {number} args.pickCount picks the account holds (kept picks while Pro or Admin)
 * @param {number} args.limit the Lite limit
 * @returns {string|null}
 */
export function roleChangeNote({ from, to, username, pickCount, limit }) {
  if (from === to) return null;
  if (to === 'LITE_USER') {
    if (pickCount === 0) return "They'll be asked to choose at their next sign-in.";
    return `Lite readers choose up to ${limit} sub-regions. ${username}'s ${pickCount} kept ${pickCount === 1 ? 'pick comes' : 'picks come'} back into use; the lock carries on from their last change.`;
  }
  if (from !== 'LITE_USER') return null;
  const who = roleLabel(to);
  if (pickCount === 0) return `${who} sees every sub-region.`;
  return `${who} sees every sub-region. ${username}'s ${pickCount} ${pickCount === 1 ? 'pick is' : 'picks are'} kept, and ${pickCount === 1 ? 'comes' : 'come'} back into use if the account returns to Lite.`;
}
