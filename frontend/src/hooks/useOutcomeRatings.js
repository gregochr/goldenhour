import {
  useCallback, useEffect, useMemo, useRef, useState,
} from 'react';
import { fetchMyRatings, putRating } from '../api/outcomeRatingApi.js';

/**
 * The key a rating lives under: `"<locationId>|<date>|<SUNRISE|SUNSET>"`.
 *
 * @param {number|string} locationId
 * @param {string} date `YYYY-MM-DD`
 * @param {string} eventType
 * @returns {string}
 */
export function rowKey(locationId, date, eventType) {
  return `${locationId}|${date}|${eventType}`;
}

/**
 * A `mine` entry. The `seen` tag is carried only when there is one, so an untagged rating is exactly
 * the `{rating, updatedAt}` it has always been.
 *
 * @param {?number} rating
 * @param {?string} updatedAt
 * @param {?string} seen
 * @returns {{rating: ?number, updatedAt: ?string, seen?: string}}
 */
function entry(rating, updatedAt, seen) {
  return seen ? { rating, updatedAt, seen } : { rating, updatedAt };
}

/**
 * The tag the server will hold after a write, so the optimistic value matches what comes back:
 * a tag sent (or an explicit null) is taken as it stands; with none sent the earlier tag is KEPT, unless
 * it cannot stand beside the new rating (`NOT_SEEN` goes with a null rating only, a sighting with a star
 * only), in which case it goes. The same rule as `OutcomeRatingService`.
 *
 * @param {?string} previous the tag held before
 * @param {?number} rating the rating being written
 * @param {string|null|undefined} requested the tag sent: undefined = not sent
 * @returns {?string}
 */
export function settledSeen(previous, rating, requested) {
  if (requested !== undefined) return requested;
  if (!previous) return null;
  return (previous === 'NOT_SEEN') === (rating == null) ? previous : null;
}

/**
 * The reader's own outcome ratings for the Map tab's past windows (`docs/engineering/outcome-rating-
 * plan.md` §2 #5, "The data").
 *
 * <p>Mounted in `WindowFirstMapPane`, beside the forecasts the ratings join to. Held in component
 * state ALONE — nothing in `swrCache`, `localStorage` or module scope — so a logout, which unmounts
 * the tree, cannot carry one user's ratings to the next.
 *
 * <h2>The map</h2>
 * <p>{@code mine}: {@code Map<"locationId|date|type", {rating: ?number, updatedAt: ?string}>}. A row's
 * existence is the answer: {@code rating: null} is "didn't see it", an absent key is unrated.
 *
 * <h2>Optimistic, with a rollback</h2>
 * <p>{@link rate} writes {@code mine} first, then PUTs. On failure it restores the value the key held
 * before — or deletes the key when there was none — and reports {@code saveError} for that key. The
 * error stays through the retry's own saving and clears on the next success or when
 * {@link clearSaveError} is called (the block's "Change", and its unmount: a re-selection). A response
 * that has been overtaken (a newer {@code rate} for the same key started after it) is dropped whole,
 * the Ask `send` rule, so a slow answer can never repaint what a later tap decided.
 *
 * <h2>A read cannot clobber a write</h2>
 * <p>The range is re-read on mount, when the oldest known past date changes (an older day landed) and
 * when {@code refreshKey} changes (the forecasts refreshed). A key written, or still being written,
 * since a read began keeps its local value whatever that read says: the GET may have been served
 * before the PUT committed. A read overtaken by a newer read is dropped.
 *
 * <h2>Roles and the rewind</h2>
 * <p>Only ADMIN and PRO_USER ever call the PUT (the server refuses LITE with 403). A LITE account
 * makes the initial read only, once — it has no rows, a downgraded one may — and never re-reads.
 * While {@code rewound} nothing is asked for at all and {@code mine} is empty: a rewind is a
 * screenshot of the forecast as it stood, and a rating recorded later would contradict the picture
 * (plan §5 #6).
 *
 * <h2>The seen tag</h2>
 * <p>An optional "what did you see" tag (valley-mist-plan M3) rides each record as {@code seen}. {@link
 * rateTag} saves it by PUTting the SAME rating again with the tag, and it has its OWN in-flight and
 * failed sets ({@code tagSaving}, {@code tagSaveError}) so a tag save never flips the block out of its
 * "You rated" layout the way a star save does. A failed tag save rolls the record back to what it was
 * before the tag — the star was never in question — and the failure shows beside the chips.
 *
 * @param {object} args
 * @param {?string} args.role `'ADMIN' | 'PRO_USER' | 'LITE_USER'`
 * @param {boolean} [args.rewound] whether an admin rewind is in force
 * @param {?string} args.from first event date to read, `YYYY-MM-DD`
 * @param {?string} args.to last event date to read
 * @param {*} [args.refreshKey] any value whose change means "the forecasts refreshed"
 * @returns {{
 *   mine: Map<string, {rating: ?number, updatedAt: ?string, seen?: string}>,
 *   saving: Set<string>,
 *   saveError: Set<string>,
 *   tagSaving: Set<string>,
 *   tagSaveError: Set<string>,
 *   loaded: boolean,
 *   readFailed: boolean,
 *   covers: function(string): boolean,
 *   wasRead: function(string): boolean,
 *   canRate: boolean,
 *   rate: function(number, string, string, ?number, (string|null)=): Promise<boolean>,
 *   rateTag: function(number, string, string, ?string): Promise<boolean>,
 *   clearSaveError: function(string): void
 * }}
 */
export function useOutcomeRatings({
  role, rewound = false, from, to, refreshKey = null,
}) {
  const canRate = role === 'ADMIN' || role === 'PRO_USER';
  const isLite = role === 'LITE_USER';

  // One empty map per hook instance (never shared module state), returned while rewound.
  const [emptyMine] = useState(() => new Map());
  const [mine, setMine] = useState(() => new Map());
  const [saving, setSaving] = useState(() => new Set());
  const [failed, setFailed] = useState(() => new Set());
  const [tagSaving, setTagSaving] = useState(() => new Set());
  const [tagFailed, setTagFailed] = useState(() => new Set());
  // The range a read has landed for. A date outside it has NOT been read: its absent keys mean
  // "not asked yet", never "unrated".
  const [loadedRange, setLoadedRange] = useState(null);
  // Whether the NEWEST read that settled failed. {@code loaded} and {@code covers} count a failed read
  // as read (the block stays usable, the PUT is an upsert), but a COUNT or a BADGE states a fact about
  // what the reader has rated, and an empty {@code mine} after a failed read says nothing of the kind
  // — so a reader of those two (outcome-rating-plan.md §2 #6) stays silent while this is true.
  const [readFailed, setReadFailed] = useState(false);

  // The refs hold what an async continuation must read fresh; the state is what renders.
  const mineRef = useRef(mine);
  const aliveRef = useRef(true);
  const canRateRef = useRef(canRate);
  const rewoundRef = useRef(rewound);
  const writeSeqRef = useRef(0);
  const readSeqRef = useRef(0);
  /** Per key, the sequence of the newest `rate` — a response carrying an older one is dropped. */
  const latestWriteRef = useRef(new Map());
  /** Per key, the value to restore if the in-flight write(s) fail — the one held BEFORE the first. */
  const restoreToRef = useRef(new Map());
  /** A counter bumped when any write starts or settles; a key's last touch, compared with a read's start. */
  const touchClockRef = useRef(0);
  const touchedAtRef = useRef(new Map());
  /** Per key, the newest write queued or in flight: the tail the next write waits behind. */
  const queueRef = useRef(new Map());

  useEffect(() => {
    canRateRef.current = canRate;
    rewoundRef.current = rewound;
  }, [canRate, rewound]);
  useEffect(() => {
    aliveRef.current = true;
    return () => { aliveRef.current = false; };
  }, []);

  const commitMine = useCallback((next) => {
    mineRef.current = next;
    setMine(next);
  }, []);
  const touch = useCallback((key) => {
    touchClockRef.current += 1;
    touchedAtRef.current.set(key, touchClockRef.current);
  }, []);
  const flag = useCallback((setter, key, on) => {
    setter((current) => {
      if (current.has(key) === on) return current;
      const next = new Set(current);
      if (on) next.add(key); else next.delete(key);
      return next;
    });
  }, []);

  // A LITE account reads once, with the range and refresh key it mounted with — never again.
  const [initial] = useState(() => ({ from, to, refreshKey }));
  const readFrom = isLite ? initial.from : from;
  const readTo = isLite ? initial.to : to;
  const readRefresh = isLite ? initial.refreshKey : refreshKey;

  useEffect(() => {
    if (rewound || !readFrom || !readTo) return undefined;
    readSeqRef.current += 1;
    const token = readSeqRef.current;
    const startedAt = touchClockRef.current;
    let settled = false;
    const settle = () => {
      if (settled || !aliveRef.current || token !== readSeqRef.current) return false;
      settled = true;
      return true;
    };
    fetchMyRatings(readFrom, readTo).then((rows) => {
      if (!settle()) return;
      const fresh = new Map();
      for (const row of Array.isArray(rows) ? rows : []) {
        fresh.set(
          rowKey(row.locationId, row.eventDate, row.eventType),
          entry(row.rating ?? null, row.updatedAt ?? null, row.seen),
        );
      }
      // A key written since this read began keeps what the writes decided.
      for (const [key, at] of touchedAtRef.current) {
        if (at <= startedAt && !latestWriteRef.current.has(key)) continue;
        const local = mineRef.current.get(key);
        if (local) fresh.set(key, local); else fresh.delete(key);
      }
      commitMine(fresh);
      setLoadedRange({ from: readFrom, to: readTo });
      setReadFailed(false);
    }).catch(() => {
      // A failed read says nothing about what was rated; the block stays usable, the PUT is an upsert.
      // `readFailed` is how the counts and the badge, which DO state what was rated, know to stay quiet.
      if (settle()) {
        setLoadedRange({ from: readFrom, to: readTo });
        setReadFailed(true);
      }
    });
    return undefined;
  }, [rewound, readFrom, readTo, readRefresh, commitMine]);

  /**
   * The one write path. {@code kind} picks which in-flight and failed sets the write reports on: a
   * star save is {@code 'rating'} (the block shows its saving layout), a tag save {@code 'tag'} (the
   * block stays where it is). {@code seen} is the tag to send: undefined leaves it out of the PUT.
   */
  const execute = useCallback(async (kind, locationId, eventDate, eventType, requested, seen) => {
    if (!canRateRef.current || rewoundRef.current) return false;
    const key = rowKey(locationId, eventDate, eventType);
    // A tag tap carries the star the slot holds NOW, read when its turn comes: it may have queued behind a
    // star save, and the star it was pressed against is no longer the one to send.
    const value = kind === 'tag' ? mineRef.current.get(key)?.rating : requested;
    if (kind === 'tag' && value == null) return false;
    const setBusy = kind === 'tag' ? setTagSaving : setSaving;
    const setFail = kind === 'tag' ? setTagFailed : setFailed;
    writeSeqRef.current += 1;
    const seq = writeSeqRef.current;
    latestWriteRef.current.set(key, seq);
    if (!restoreToRef.current.has(key)) restoreToRef.current.set(key, mineRef.current.get(key) ?? null);
    touch(key);
    flag(setBusy, key, true);
    const before = mineRef.current.get(key);
    const optimistic = new Map(mineRef.current);
    optimistic.set(key, entry(
      value,
      before?.updatedAt ?? null,
      settledSeen(before?.seen ?? null, value, seen),
    ));
    commitMine(optimistic);

    const overtaken = () => !aliveRef.current || latestWriteRef.current.get(key) !== seq;
    try {
      const dto = await putRating({
        locationId, eventDate, eventType, rating: value, seen,
      });
      if (overtaken()) return false;
      touch(key);
      restoreToRef.current.delete(key);
      latestWriteRef.current.delete(key);
      const stored = new Map(mineRef.current);
      stored.set(key, entry(
        dto && 'rating' in dto ? (dto.rating ?? null) : value,
        dto?.updatedAt ?? null,
        // The server's word on the tag; a response that carries none (an older server, a test double)
        // falls back to what this write meant.
        dto && 'rating' in dto ? dto.seen : settledSeen(before?.seen ?? null, value, seen),
      ));
      commitMine(stored);
      // The newest write for the slot has settled, so NOTHING is in flight for it: clear both busy flags.
      // A write that was overtaken returns without clearing its own, so clearing only this kind's would
      // leave a tag save overtaken by a star stuck "saving" for good.
      flag(setSaving, key, false);
      flag(setTagSaving, key, false);
      flag(setFail, key, false);
      // A save that lands clears the other kind's stale failure too: the record is current again.
      flag(kind === 'tag' ? setFailed : setTagFailed, key, false);
      return true;
    } catch {
      if (overtaken()) return false;
      touch(key);
      const previous = restoreToRef.current.get(key) ?? null;
      restoreToRef.current.delete(key);
      latestWriteRef.current.delete(key);
      const rolledBack = new Map(mineRef.current);
      if (previous) rolledBack.set(key, previous); else rolledBack.delete(key);
      commitMine(rolledBack);
      flag(setSaving, key, false);
      flag(setTagSaving, key, false);
      flag(setFail, key, true);
      return false;
    }
  }, [commitMine, flag, touch]);

  /**
   * Writes are SERIALISED per slot: a tag tap while a star save is in flight (or the reverse) waits for
   * it and then runs, so two PUTs for one slot are never in the air together and the server can never
   * see them cross. With nothing pending the write starts at once (synchronously as far as its first
   * await), so the optimistic value shows without a tick's delay. {@link execute} never rejects, so the
   * chain never breaks.
   */
  const write = useCallback((kind, locationId, eventDate, eventType, requested, seen) => {
    const key = rowKey(locationId, eventDate, eventType);
    const pending = queueRef.current.get(key);
    const run = pending
      ? pending.then(() => execute(kind, locationId, eventDate, eventType, requested, seen))
      : execute(kind, locationId, eventDate, eventType, requested, seen);
    queueRef.current.set(key, run);
    run.then(() => {
      if (queueRef.current.get(key) === run) queueRef.current.delete(key);
    });
    return run;
  }, [execute]);

  /**
   * Saves a star (or "didn't see it" with null) — and, for "didn't see it", the NOT_SEEN tag that goes
   * with it, so the record says so in its own words. {@code seen} left undefined keeps the earlier tag
   * (the server drops one the new rating contradicts).
   */
  const rate = useCallback((locationId, eventDate, eventType, value, seen) => write(
    'rating',
    locationId,
    eventDate,
    eventType,
    value,
    seen === undefined && value == null ? 'NOT_SEEN' : seen,
  ), [write]);

  /**
   * Saves the "what did you see" tag: the SAME rating again, with {@code tag} (a name, or null to
   * clear). Refuses — resolving false, asking nothing — for a slot that holds no star: a tag needs
   * one, and "didn't see it" carries NOT_SEEN on its own.
   */
  const rateTag = useCallback(async (locationId, eventDate, eventType, tag) => {
    const held = mineRef.current.get(rowKey(locationId, eventDate, eventType));
    if (held?.rating == null) return false;
    return write('tag', locationId, eventDate, eventType, held.rating, tag ?? null);
  }, [write]);

  /**
   * Whether the reader's ratings for an event date have been read. Not the same as {@code loaded}: an
   * older day that lands after the first read widens the range, and until that range's own read
   * returns, a slot there has no record HERE only because nobody has asked — a block offered for it
   * would show an empty grid over a rating the reader may already have made, and a tap would
   * overwrite it. A failed read counts as read (the PUT is an upsert and nothing better is known); a
   * LITE reader, who cannot write, needs only the first read.
   */
  const covers = useCallback((date) => {
    if (rewound || loadedRange == null) return false;
    if (isLite) return true;
    return date >= loadedRange.from && date <= loadedRange.to;
  }, [rewound, isLite, loadedRange]);

  /**
   * Whether the server was actually ASKED about an event date: the date lies in the range a read
   * has landed for, for every role. Stricter than {@link covers}, which says "a block may be offered"
   * and so is true for a LITE reader's every date after the first read (a LITE reader cannot write
   * and re-reads nothing). A count or a badge states a fact about what was rated, so it needs this:
   * an older day a LITE reader has since loaded was never read, and "Not rated" there would be a guess.
   */
  const wasRead = useCallback((date) => {
    if (rewound || loadedRange == null) return false;
    return date >= loadedRange.from && date <= loadedRange.to;
  }, [rewound, loadedRange]);

  const clearSaveError = useCallback((key) => {
    flag(setFailed, key, false);
    flag(setTagFailed, key, false);
  }, [flag]);

  return useMemo(() => ({
    mine: rewound ? emptyMine : mine,
    saving,
    saveError: failed,
    tagSaving,
    tagSaveError: tagFailed,
    loaded: !rewound && loadedRange != null,
    readFailed: !rewound && readFailed,
    covers,
    wasRead,
    canRate,
    rate,
    rateTag,
    clearSaveError,
  }), [rewound, emptyMine, mine, saving, failed, tagSaving, tagFailed, loadedRange, readFailed, covers, wasRead,
    canRate, rate, rateTag, clearSaveError]);
}
