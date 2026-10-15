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
 * @param {object} args
 * @param {?string} args.role `'ADMIN' | 'PRO_USER' | 'LITE_USER'`
 * @param {boolean} [args.rewound] whether an admin rewind is in force
 * @param {?string} args.from first event date to read, `YYYY-MM-DD`
 * @param {?string} args.to last event date to read
 * @param {*} [args.refreshKey] any value whose change means "the forecasts refreshed"
 * @returns {{
 *   mine: Map<string, {rating: ?number, updatedAt: ?string}>,
 *   saving: Set<string>,
 *   saveError: Set<string>,
 *   loaded: boolean,
 *   covers: function(string): boolean,
 *   canRate: boolean,
 *   rate: function(number, string, string, ?number): Promise<boolean>,
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
  // The range a read has landed for. A date outside it has NOT been read: its absent keys mean
  // "not asked yet", never "unrated".
  const [loadedRange, setLoadedRange] = useState(null);

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
          { rating: row.rating ?? null, updatedAt: row.updatedAt ?? null },
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
    }).catch(() => {
      // A failed read says nothing about what was rated; the block stays usable, the PUT is an upsert.
      if (settle()) setLoadedRange({ from: readFrom, to: readTo });
    });
    return undefined;
  }, [rewound, readFrom, readTo, readRefresh, commitMine]);

  const rate = useCallback(async (locationId, eventDate, eventType, value) => {
    if (!canRateRef.current || rewoundRef.current) return false;
    const key = rowKey(locationId, eventDate, eventType);
    writeSeqRef.current += 1;
    const seq = writeSeqRef.current;
    latestWriteRef.current.set(key, seq);
    if (!restoreToRef.current.has(key)) restoreToRef.current.set(key, mineRef.current.get(key) ?? null);
    touch(key);
    flag(setSaving, key, true);
    const optimistic = new Map(mineRef.current);
    optimistic.set(key, {
      rating: value,
      updatedAt: mineRef.current.get(key)?.updatedAt ?? null,
    });
    commitMine(optimistic);

    const overtaken = () => !aliveRef.current || latestWriteRef.current.get(key) !== seq;
    try {
      const dto = await putRating({
        locationId, eventDate, eventType, rating: value,
      });
      if (overtaken()) return false;
      touch(key);
      restoreToRef.current.delete(key);
      latestWriteRef.current.delete(key);
      const stored = new Map(mineRef.current);
      stored.set(key, {
        rating: dto && 'rating' in dto ? (dto.rating ?? null) : value,
        updatedAt: dto?.updatedAt ?? null,
      });
      commitMine(stored);
      flag(setSaving, key, false);
      flag(setFailed, key, false);
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
      flag(setFailed, key, true);
      return false;
    }
  }, [commitMine, flag, touch]);

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

  const clearSaveError = useCallback((key) => flag(setFailed, key, false), [flag]);

  return useMemo(() => ({
    mine: rewound ? emptyMine : mine,
    saving,
    saveError: failed,
    loaded: !rewound && loadedRange != null,
    covers,
    canRate,
    rate,
    clearSaveError,
  }), [rewound, emptyMine, mine, saving, failed, loadedRange, covers, canRate, rate, clearSaveError]);
}
