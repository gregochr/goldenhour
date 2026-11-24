import React, {
  useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState,
} from 'react';
import PropTypes from 'prop-types';
import WindowComingUpEntry from './WindowComingUpEntry.jsx';
import WindowComingUpSinceLine from './WindowComingUpSinceLine.jsx';
import GuideLink from './guide/GuideLink.jsx';
import {
  buildChronology, buildEntryView, chipCounts, chipCountsOf, FOOTER,
} from '../utils/comingUpFeed.js';
import { unlistedWatches, watchKeysMatching } from '../utils/watchedDates.js';
import { readFurtherAheadFilter, writeFurtherAheadFilter } from '../utils/furtherAheadFilter.js';
import { CHIP_GLYPHS } from '../utils/comingUpGlyphs.js';
import { ALMANAC_DAYS } from '../api/almanacApi.js';
import { comingUpOutsideFacts, entrySubject, outsideLineText } from '../utils/comingUpOutside.js';
import { scrollToEntry } from '../utils/scrollToEntry.js';
import { isEntryOpen, readOpenOverrides, writeOpenOverrides } from '../utils/comingUpOpen.js';
import { useWindowFirstBriefing } from '../context/WindowFirstBriefingContext.jsx';
import { useWarnings } from '../context/WarningsContext.jsx';
import { useFavourites } from '../context/FavouritesContext.jsx';
import BookmarkMark from './shared/BookmarkMark.jsx';
import { WarningFooter } from './WarningChip.jsx';
import useListedFeed from '../hooks/useListedFeed.js';

/** Served counts default to zero, matching a payload that has not arrived yet or a degraded
 * legacy shape ({@code {entries: []}}, which carries no {@code counts} key at all). */
const EMPTY_COUNTS = { fixed: 0, forecast: 0, byFamily: {} };

/** No graduated entries: the default, so a caller that passes none lists everything the feed served. */
const NO_GRADUATED = new Set();

/** The heading's words. The screen's own name for "dates known in advance", where the tab used to say "Coming up". */
export const FURTHER_AHEAD_HEADING = 'Further ahead';
export const FURTHER_AHEAD_SUBHEADING = 'dates known in advance';

/**
 * Further ahead — the 90-day almanac chronology (coming-up-plan.md §6, P3a), as the bottom section of the forecast
 * screen ({@code docs/engineering/one-screen-plan.md} O2). It was the Coming up tab's pane ({@code WindowFirstComingUp});
 * the chips, the remembered filter, the month rules, the entries, the since-line and the warning footer all moved
 * with it.
 *
 * <h2>A section, not a pane</h2>
 *
 * <p>A {@code <section aria-labelledby>} under a sticky {@code <h2>} "Further ahead · dates known in advance". The
 * heading is the section's focus target ({@code tabIndex=-1}): the "Further ahead ↓" jump, a lane jump and a retry
 * all land focus there, where the tab's panel used to take it. The heading carries the NEW badge as an
 * {@code aria-hidden} span, so its accessible name stays clean (the jump on This week's heading speaks the count).
 *
 * <h2>What it lists (D5, "graduation")</h2>
 *
 * <p>An entry is left out only when This week renders a lane that carries its jump, or a watched date's lane
 * ({@code graduatedIds}, which the heat strip reports from the lanes it drew). In-forecast entries with no lane stay,
 * in date order, and everything past the forecast days is listed; nothing shows twice and nothing vanishes from both.
 * A graduated entry carries no mark here or there, except that a watched date's lane wears the bookmark mark
 * (owner, 2026-11-23; screen-spec.md D2). The chips' counts follow what is listed.
 *
 * <h2>Watching (screen-spec.md D2)</h2>
 *
 * <p>The reader's watched dates are a group at the TOP of this section, before the filter chips: an {@code <h3>}
 * {@code [mark] Watching} and its own {@code role="list"} of the SAME {@code WindowComingUpEntry} cards (the same open
 * panel, with {@code WatchDateControl} inside), drawn only when it has a member. A watched entry MOVES there from its
 * month (it is never in both), and unwatching returns it. Members are the listed entries with
 * {@code watchKeysMatching(dates, entry)} non-empty; the filter chips and their counts describe the month lists only.
 * A watch whose key matches no entry in the SERVED feed is a quiet row, {@code [mark] <title> · no longer listed}, with a
 * real Stop watching button. (An entry This week draws as a watched lane is served and not listed, so it is neither.)
 *
 * <p><b>Focus on a move.</b> The pressed toggle unmounts when its card changes list, so focus never falls to
 * {@code <body>}: pressing Watch or Watching hands focus to that entry's toggle in its new place (found by entry id,
 * scrolled into view); removing an unlisted row focuses the next row, or the Watching heading, or this section's
 * heading when the group is gone. The Undo toast resolves its own target at Undo time (utils/focusReturn.js).
 *
 * <h2>The remembered filter (D7)</h2>
 *
 * <p>The chosen chip is kept in {@code localStorage['furtherAheadFilter']} ({@code utils/furtherAheadFilter.js}),
 * read once at mount and validated against the chips, so a stale or foreign value is simply {@code all}.
 *
 * <p>Original design notes, kept because they still hold. The 90-day almanac chronology (plan §6, P3a).
 *
 * <p>Recreates {@code docs/design/coming-up/Coming Up.html} §4/§5 against the P2 payload: month
 * rules, the two-column date-rail/card entry grid, the filter chips, the legend, and the replaced
 * header/footer copy. {@code docs/engineering/coming-up-plan.md} §6 is the brief; §13 is the wire
 * schema this component reads.
 *
 * <h2>Everything here is presentation over an already-decided payload</h2>
 *
 * <p>P2's {@code ComingUpAssembler} computed every fact, tag, threshold and action this pane shows.
 * This component and {@code WindowComingUpEntry} place that payload; {@code utils/comingUpFeed.js}
 * does the one kind of derivation that is still legitimately the client's — presentation arithmetic
 * over served fields (the date rail, month grouping, filter membership), never a new fact.
 *
 * <h2>The vocabulary now lives on the card, not the footer</h2>
 *
 * <p>Before P2, every entry was {@code ALMANAC} and the footer stated that once so a chip did not
 * have to repeat it on every row. P2 gives every entry a real {@code kindTag} ("Almanac" or
 * "Forecast · peak"), so the card now carries its own word and the footer's old job — see plan §6:
 * "the old vocabulary job now lives on the per-card kind tag, so delete it rather than ship both".
 *
 * <h2>Still no ROW count on the tab — the fetch went eager, the reasoning did not (D13's other
 * half, P5)</h2>
 *
 * <p>The fetch that used to justify this refusal is gone: {@code useComingUpFeed} now fires eagerly
 * for every reader (plan D13), so the tab's badge no longer waits for a reader to have already
 * looked. But a row count and the badge are different claims, and only one of them earned the
 * eager fetch's cost. A row count restates how much is already in the feed — eleven entries
 * and eight are the same answer to "is there anything coming up" — which is not a decision-changing
 * signal no matter how fresh the number is. The badge counts something else entirely: NEW arrivals
 * since the reader last looked that clear a rarity band (plan D3/D4), which is exactly the kind of
 * signal worth interrupting for. So the tab gains a badge (below, on the tab button itself) and
 * stays without a plain count — the eager fetch paid for the one that changes what a reader does
 * next, not for a second, cheaper number beside it.
 *
 * <h2>No role gate</h2>
 *
 * <p>Unchanged from P1: {@code GET /api/almanac} has no role gate and no DTO mapper, so this pane
 * is identical for LITE, PRO and ADMIN.
 *
 * @param {object}   props
 * @param {string}   props.id       the section's element id
 * @param {string}   props.headingId the {@code <h2>}'s element id, the section's {@code aria-labelledby}
 * @param {?Set<string>} [props.graduatedIds] ids of the entries This week already shows as lanes (D5); not listed
 * @param {function} [props.onHeadingSeen] called when the heading reaches the viewport (the NEW badge's one-off
 *                                    bootstrap write, D6); never called without an {@code IntersectionObserver}
 * @param {string}   props.status   `idle` | `loading` | `ready` | `error`
 * @param {?object}  props.events   the wrapped wire payload once it has arrived — see
 *                                  {@code ComingUpResponse}
 * @param {string}   props.todayStr the reader's today, `YYYY-MM-DD`
 * @param {function} props.onRetry  re-runs the fetch after a failure
 * @param {function} props.onGoToPlan takes the reader up to This week and moves focus there; takes an
 *                                    optional date — a `plan`-action card passes its own date
 * @param {function} props.onShowOnMap opens the map view (the structured door) for a `coastal-spots`/`dark-sky-spots`
 *                                     card action (D8, plan §6b) — forwarded to every entry
 * @param {(string|null)} [props.comingUpLastSeenDate] the reader's stored `comingUpLastSeenDate`
 *                                     (D3), or null/undefined before it is known — drives NEW flags,
 *                                     the fresh box-shadow, and the since-line (plan §6/P5)
 * @param {?Array<string>} [props.forecastDates] the dates (`YYYY-MM-DD`) the reader holds a colour
 *                                     forecast for — a `coastal-spots`/`dark-sky-spots` door onto
 *                                     any other date is withheld, with its reason printed (plan
 *                                     §11.24). Three-valued: null/absent is "not known yet" and
 *                                     withholds nothing; an array, even empty, is known
 * @param {function} props.onMarkSeen  clears the badge and every NEW flag (plan §6/P5)
 * @param {?{entryId: ?string, seq: number}} [props.focusRequest] a request from This week's topic pills to
 *                                     open the tab at one entry (glance-topic-strip-plan.md R4); `entryId` null
 *                                     is the door, which asks for nothing in particular
 * @param {function} [props.onFocusHandled] `(found: boolean)` — called exactly once per request, once it has
 *                                     been honoured (`true`) or could not be (`false`: the entry is not in the
 *                                     feed, or the feed is not ready), so the shell can fall back
 * @param {Array} [props.locations] the served enabled roster (`/api/locations`): a Lite reader on a shaped
 *                                     body gets the outside-your-sub-regions line from its coastal and
 *                                     dark-sky facts (S5b-2); unused on every other body
 */
export default function FurtherAhead({
  id, headingId, graduatedIds = NO_GRADUATED, onHeadingSeen = undefined, status, events: wireEvents, todayStr,
  onRetry, onGoToPlan, onShowOnMap, comingUpLastSeenDate, onMarkSeen, forecastDates = null, locations = undefined,
  focusRequest = null, onFocusHandled = undefined,
}) {
  // The remembered chip (D7), read once; a chip press writes it back (and the reset a deep link makes below does not).
  const favourites = useFavourites();
  const [activeFilter, setActiveFilter] = useState(readFurtherAheadFilter);
  const chooseFilter = (filterId) => {
    setActiveFilter(filterId);
    writeFurtherAheadFilter(filterId);
  };
  // Met Office warning entries (W3b) are reconciled against the polled warnings, and the entries This week draws as
  // lanes are taken out, in ONE place shared with the "Further ahead ↓" jump (`hooks/useListedFeed.js`), so the badge on
  // the jump and on this heading are the same number and neither counts an entry this section does not list.
  const {
    events, listedEntries, badge, sinceEntry,
  } = useListedFeed(wireEvents, graduatedIds, comingUpLastSeenDate);
  const served = useWarnings();
  // The reader's own open and close presses, by entry id, kept for the session and overriding the default
  // (collapse-and-topic-pips-plan.md §5; utils/comingUpOpen.js).
  const [openOverrides, setOpenOverrides] = useState(readOpenOverrides);
  useEffect(() => { writeOpenOverrides(openOverrides); }, [openOverrides]);
  const { allowedLocationIds } = useWindowFirstBriefing();

  // Watching: the listed entries the reader watches, soonest first. Only for a reader whose list has landed.
  const watchesReady = favourites.status === 'ready';
  const watchingEntries = useMemo(
    () => (watchesReady && Array.isArray(listedEntries)
      ? listedEntries.filter((entry) => watchKeysMatching(favourites.dates, entry).length > 0)
        .sort((a, b) => a.startDate.localeCompare(b.startDate))
      : []),
    [watchesReady, listedEntries, favourites.dates],
  );
  const watchingViews = useMemo(
    () => watchingEntries.map((entry) => buildEntryView(entry, todayStr, comingUpLastSeenDate, forecastDates)),
    [watchingEntries, todayStr, comingUpLastSeenDate, forecastDates],
  );
  // The watches the SERVED feed does not name: a quiet row each, so they can still be taken away.
  const unlisted = useMemo(
    () => (watchesReady && status === 'ready'
      ? unlistedWatches({ dates: favourites.dates, entries: events?.entries, todayStr }) : []),
    [watchesReady, status, favourites.dates, events, todayStr],
  );
  const hasWatching = status === 'ready' && (watchingViews.length > 0 || unlisted.length > 0);
  // The month lists hold everything listed that is not watched, so each entry appears once.
  const monthEntries = useMemo(() => {
    if (watchingEntries.length === 0) return listedEntries;
    const watchingIds = new Set(watchingEntries.map((entry) => entry.id));
    return listedEntries.filter((entry) => !watchingIds.has(entry.id));
  }, [listedEntries, watchingEntries]);

  // With nothing taken out the listed array is the served one, and the chips read the served counts as they always did.
  const trimmedAny = Array.isArray(events?.entries) && monthEntries.length !== events.entries.length;
  const counts = events?.counts ?? EMPTY_COUNTS;
  const totalEntries = listedEntries?.length ?? 0;
  const chips = useMemo(
    () => (trimmedAny ? chipCountsOf(monthEntries) : chipCounts(counts)),
    [trimmedAny, monthEntries, counts],
  );
  const activeChipLabel = chips.find((chip) => chip.id === activeFilter)?.label ?? 'active';
  const monthGroups = useMemo(
    () => buildChronology(
      monthEntries, todayStr, activeFilter, comingUpLastSeenDate, forecastDates,
    ),
    [monthEntries, todayStr, activeFilter, comingUpLastSeenDate, forecastDates],
  );
  // Open all and the focus request read every card on screen, Watching's included.
  const renderedEntries = useMemo(
    () => [...watchingViews, ...monthGroups.flatMap((group) => group.entries)],
    [watchingViews, monthGroups],
  );
  const entryOpen = (entry) => isEntryOpen(entry, openOverrides, todayStr);
  // "Close all" while every rendered entry is open, otherwise "Open all"; either sets every rendered entry.
  const allOpen = renderedEntries.length > 0 && renderedEntries.every(entryOpen);
  const setEntries = (ids, open) => setOpenOverrides((prev) => ({
    ...prev, ...Object.fromEntries(ids.map((entryId) => [entryId, open])),
  }));
  // S5b-2: a Lite reader whose body the server shaped by their sub-region picks. `outsideFacts` is null on every
  // other body (Pro, Admin, enforcement off), so nothing below draws and the pane is unchanged. The line sits on
  // every entry whose door row finds no matching allowed place; the ONE door goes on the first such entry,
  // skipping entries that carry the Lite locked tide row (falling back to the first when every one does).
  const outsideFacts = useMemo(
    () => comingUpOutsideFacts(locations, allowedLocationIds),
    [locations, allowedLocationIds],
  );
  const outsideByEntry = useMemo(() => {
    const byId = new Map();
    if (!outsideFacts || status !== 'ready') return { byId, doorId: null };
    let doorId = null;
    let firstId = null;
    renderedEntries.forEach((entry) => {
      const subject = entrySubject(entry);
      const text = subject ? outsideLineText(subject, outsideFacts, entry.dayChips?.length === 1) : null;
      if (!text) return;
      byId.set(entry.id, { subject, text });
      if (firstId === null) firstId = entry.id;
      if (doorId === null && !entry.lightRowsWithheld) doorId = entry.id;
    });
    return { byId, doorId: doorId ?? firstId };
  }, [outsideFacts, status, renderedEntries]);
  // The heading is the section's focus target, and what the NEW badge's bootstrap watches for (D6): every time it
  // reaches the viewport the shell is told, and the shell's own guard decides whether that is the one write. A browser
  // that cannot observe never tells it (the bootstrap is a write, so it does not fail open).
  const headingRef = useRef(null);
  const [headingNode, setHeadingNodeState] = useState(null);
  const setHeadingNode = useCallback((node) => {
    headingRef.current = node;
    setHeadingNodeState(node);
  }, []);
  useEffect(() => {
    if (!headingNode || !onHeadingSeen || typeof IntersectionObserver === 'undefined') return undefined;
    const observer = new IntersectionObserver((observed) => {
      if (observed.some((item) => item.isIntersecting)) onHeadingSeen();
    });
    observer.observe(headingNode);
    return () => observer.disconnect();
  }, [headingNode, onHeadingSeen]);
  const watchingHeadingRef = useRef(null);
  /** Where focus is owed after a press moved or removed what the reader pressed (see "Focus on a move" above). */
  const moveIntent = useRef(null);
  /** A press on an entry's Watch toggle (capture phase, before the toggle's own handler): the entry that will move. */
  const noteWatchPress = (event) => {
    const toggle = event.target.closest?.('[data-testid="coming-up-watch-toggle"]');
    const entryId = toggle?.closest('[data-entry-id]')?.getAttribute('data-entry-id') ?? null;
    if (entryId !== null) moveIntent.current = { kind: 'entry', entryId };
  };
  /** Stop watching an unlisted row: the next row (or the one before it) takes focus, else a heading. */
  const stopUnlisted = (row, index) => {
    const rest = unlisted.filter((each) => each.key !== row.key);
    const next = rest[index] ?? rest[index - 1] ?? null;
    moveIntent.current = next ? { kind: 'row', watchKey: next.key } : { kind: 'heading' };
    void favourites.setStar('date', row.key, row.name, false);
  };
  useLayoutEffect(() => {
    const intent = moveIntent.current;
    if (!intent) return;
    moveIntent.current = null;
    const within = (attribute, value) => [...document.querySelectorAll(`[${attribute}]`)]
      .find((node) => node.getAttribute(attribute) === value);
    let target = null;
    if (intent.kind === 'entry') {
      target = within('data-entry-id', intent.entryId)?.querySelector('[data-testid="coming-up-watch-toggle"]');
    } else if (intent.kind === 'row') {
      target = within('data-watch-key', intent.watchKey)?.querySelector('[data-testid="watching-unlisted-stop"]');
    }
    // A card the filter now hides, or the last row of a group that has gone: the nearest heading that is still there.
    const landing = target ?? watchingHeadingRef.current ?? headingRef.current;
    if (!landing) return;
    landing.focus();
    landing.scrollIntoView?.({ block: 'nearest' });
  });
  const retryRef = useRef(null);
  const retriedRef = useRef(false);
  /** The last focus request this pane answered, so a re-run of the effect below never answers it twice. */
  const handledFocusSeq = useRef(null);
  /** Marks the press, so the effect below knows the focus move is owed to a reader action. */
  const handleRetry = () => {
    retriedRef.current = true;
    onRetry();
  };
  /**
   * Puts focus somewhere real after a retry settles.
   *
   * <p>Pressing the button unmounts it — the whole error paragraph is replaced by the rows, or by
   * a freshly mounted copy of itself — and focus on a removed element falls to {@code <body>},
   * which drops a keyboard reader at the top of the document with no idea whether anything
   * happened. On success focus goes to the section's heading (its focus target); on a second failure it goes
   * back to the new button, which is where they were.
   */
  useEffect(() => {
    if (!retriedRef.current || status === 'loading') return;
    retriedRef.current = false;
    (retryRef.current || headingRef.current)?.focus();
  }, [status]);

  /**
   * Honours This week's pill press (glance-topic-strip-plan.md R4): opens the tab at one entry, in two phases.
   *
   * <p><b>Phase one, during render.</b> The entry may be filtered out. On the render that first sees a request,
   * when the entry is in the feed but not rendered under the active chip, the chip is reset to {@code all} (every
   * entry renders there, and the reader pressed a topic, not a filter). It is the React-documented "adjust state
   * on a prop change" shape, keyed on the request's {@code seq}, rather than a state write inside an effect.
   */
  const [seenFocusSeq, setSeenFocusSeq] = useState(null);
  if (focusRequest && focusRequest.seq !== seenFocusSeq) {
    setSeenFocusSeq(focusRequest.seq);
    const wanted = focusRequest.entryId;
    if (wanted != null && status === 'ready' && activeFilter !== 'all'
        && !renderedEntries.some((entry) => entry.id === wanted)
        && listedEntries?.some((entry) => entry.id === wanted)) {
      // Not persisted: the reader pressed a topic, not a filter, so their remembered chip is left alone.
      setActiveFilter('all');
    }
    // A deep link to a closed entry opens it first: the scroll below then centres the open card.
    if (wanted != null && status === 'ready' && listedEntries?.some((entry) => entry.id === wanted)) {
      setOpenOverrides((prev) => (prev[wanted] === true ? prev : { ...prev, [wanted]: true }));
    }
  }
  /**
   * <b>Phase two, an effect.</b> {@code scrollToEntry} scrolls, rings and focuses the card. The shell makes the
   * request in the same batch that shows the Plan pane, so the section is on screen by the time this runs. A null
   * entry, a feed that is not ready, an entry not listed even under {@code all} (a graduated one, which This week
   * shows as a lane), or a {@code scrollToEntry} that finds nothing, all fall back through
   * {@code onFocusHandled(false)}; every path calls it exactly once, so the request cannot loop.
   */
  useEffect(() => {
    if (!focusRequest) return;
    if (handledFocusSeq.current === focusRequest.seq) return;
    const { entryId, seq } = focusRequest;
    handledFocusSeq.current = seq;
    const rendered = entryId != null && status === 'ready'
      && renderedEntries.some((entry) => entry.id === entryId);
    onFocusHandled?.(rendered ? scrollToEntry(entryId) : false);
  }, [focusRequest, status, renderedEntries, onFocusHandled]);

  /** One entry card: the month lists and Watching draw the very same component with the very same props. */
  const renderEntry = (entry) => {
    const outside = outsideByEntry.byId.get(entry.id);
    return (
      <WindowComingUpEntry
        key={entry.id}
        entry={entry}
        open={entryOpen(entry)}
        onToggleOpen={() => setEntries([entry.id], !entryOpen(entry))}
        onGoToPlan={onGoToPlan}
        onShowOnMap={onShowOnMap}
        outsideLine={outside?.text ?? null}
        outsideDoor={outside && entry.id === outsideByEntry.doorId ? {
          subject: outside.subject,
          count: outsideFacts[outside.subject].roster,
          zeroPicks: outsideFacts.zeroPicks,
        } : null}
      />
    );
  };

  return (
    <section
      id={id}
      aria-labelledby={headingId}
      className="wf-sec wf-further-ahead"
      data-testid="further-ahead"
      onClickCapture={noteWatchPress}
    >
      {/* The sticky heading (D5/D11): an `<h2>` inside a sticky bar, so the bar can stick under the lens while the
          heading's own accessible name stays its text. The badge is `aria-hidden`; the jump on This week's heading
          is what speaks the count. `tabIndex=-1` makes the heading the section's focus target, and `scroll-margin`
          (index.css) keeps it clear of the sticky stack. */}
      <div className="wf-sec-bar wf-sec-bar-ahead" data-testid="further-ahead-bar">
        <h2
          id={headingId}
          ref={setHeadingNode}
          tabIndex={-1}
          className="wf-sec-h"
          data-testid="further-ahead-heading"
        >
          <span>{FURTHER_AHEAD_HEADING}</span>
          {' '}
          <span className="wf-sec-h-sub">{`· ${FURTHER_AHEAD_SUBHEADING}`}</span>
          {badge && (
            <span
              aria-hidden="true"
              data-testid="further-ahead-heading-badge"
              className={badge.band === 'interrupt' ? 'wf-jump-badge wf-jump-badge-rare' : 'wf-jump-badge'}
            >
              {badge.band === 'interrupt' ? '◆' : badge.count}
            </span>
          )}
        </h2>
      </div>
      <div className="wf-cu">
        <div className="wf-cu-head">
          {/* Rendered unconditionally (plan §6) — not gated on any entry actually using a dashed
              rule. Without it a reader who happens to filter down to an all-solid subset would see
              the legend appear and disappear as they click chips, which is worse than a legend
              that occasionally explains a distinction not currently on screen. */}
          <span className="wf-cu-legend" data-testid="coming-up-legend">
            <span className="wf-cu-legend-item">
              <span className="wf-cu-legend-swatch wf-cu-legend-swatch-fixed" aria-hidden="true" />
              fixed
            </span>
            <span className="wf-cu-legend-item">
              <span
                className="wf-cu-legend-swatch wf-cu-legend-swatch-forecast"
                aria-hidden="true"
              />
              could still move
            </span>
          </span>
          <GuideLink section="fixed" label="What fixed and could still move mean" />
          {status === 'ready' && renderedEntries.length > 0 && (
            <button
              type="button"
              className="wf-tide-strip-toggle wf-cu-toggle-all"
              data-testid="coming-up-toggle-all"
              onClick={() => setEntries(renderedEntries.map((entry) => entry.id), !allOpen)}
            >
              {allOpen ? 'Close all' : 'Open all'}
            </button>
          )}
        </div>

        {/* One always-mounted live region holding whichever of the three notes applies.
            ALWAYS mounted, and that is the load-bearing half:
            `docs/engineering/window-first-redesign-plan.md` §5f records that a live region
            inserted in the same commit as its content is unreliably announced, which is why
            `WindowSpotSheet` puts `role="status"` on the element that is there whatever happens
            rather than on the conditional paragraph. Selection follows focus on this bar, so a
            reader arriving by arrow key gets no other signal that the pane is still loading or
            that it failed.

            The empty line is gated on `ready`, not on entry count. "Nothing in the next 90 days"
            rendered while the request is still in flight is a false claim about the sky, and it is
            the state a reader sees for the whole of the first round-trip. */}
        <div role="status" data-testid="coming-up-status">
          {status === 'loading' && (
            <p className="wf-cu-note" data-testid="coming-up-loading">Looking ahead…</p>
          )}

          {status === 'error' && (
            <p className="wf-cu-note" data-testid="coming-up-error">
              {'Could not load what is coming up. '}
              <button
                type="button"
                className="wf-cu-retry"
                ref={retryRef}
                onClick={handleRetry}
              >
                Try again
              </button>
            </p>
          )}

          {/* ⚠️ SCOPED TO THE DATED LIST — "No dates", never a bare "Nothing coming up".
              `totalEntries` counts `events.entries` alone, while weather-driven topics (which have no
              row here) are shown on This week's day headers, so a broader sentence would deny content
              the app does show — which is what the pre-copy-pass wording avoided with the word "dated",
              and what a Codex review of #748 caught when the copy pass dropped it. */}
          {status === 'ready' && totalEntries === 0 && (
            <p className="wf-cu-note" data-testid="coming-up-empty">
              {`No dates coming up in the next ${ALMANAC_DAYS} days.`}
            </p>
          )}

          {/* Distinct from the empty state above: `totalEntries` is the UNFILTERED count, so a
              chip whose family genuinely has nothing today (dust and air are near-unreachable at
              first ship, D9) would otherwise leave the chips on screen, the empty note absent
              (because the feed itself is not empty) and no rows — a silent blank pane with no clue
              why. Naming the filter, not just "nothing here", is what tells a reader to check the
              chip rather than assume the feed broke. */}
          {status === 'ready' && monthEntries.length > 0 && monthGroups.length === 0 && (
            <p className="wf-cu-note" data-testid="coming-up-filter-empty">
              {`Nothing matches the ${activeChipLabel} filter.`}
            </p>
          )}
        </div>

        {/* The since-line (design §6's "the badge must land somewhere"; plan D3/D12/P5) — the
            design of record's own DOM order, between the header and the chips. Gated on `ready`
            alongside the chips below: `badge`/`sinceEntry` both read `events.entries`/`events.bands`,
            neither of which exists before the fetch has answered. Renders nothing of its own when
            `badge` is null (component-internal), which covers the overwhelmingly common case —
            "silence is the normal state" (design §6) — without a second gate here. */}
        {status === 'ready' && (
          <WindowComingUpSinceLine badge={badge} entry={sinceEntry} onMarkSeen={onMarkSeen} />
        )}

        {/* The stale admission for Met Office warnings (W3b): when the warnings feed has not been checked lately,
            the list may be missing or still holding a warning, so say so. There is deliberately no "no warnings"
            state anywhere in this pane, and nothing at all while the feature is off. */}
        {status === 'ready' && served.enabled && served.stale && (
          <WarningFooter
            showAttribution={false}
            stale
            lastCheckedAt={served.lastCheckedAt}
            attribution={served.attribution}
            href={served.metOfficeUrl}
            className="mb-2"
          />
        )}

        {/* WATCHING (screen-spec.md D2): the reader's watched dates, pinned above the chips and untouched by them, as the
            same entry cards. Only when it has a member. */}
        {hasWatching && (
          <div className="wf-cu-watching" data-testid="further-ahead-watching">
            <h3
              id="further-ahead-watching-h"
              ref={watchingHeadingRef}
              tabIndex={-1}
              className="wf-fav-section-h wf-cu-watching-h"
              data-testid="further-ahead-watching-heading"
            >
              <BookmarkMark className="wf-yp-mark" />
              Watching
            </h3>
            <div
              role="list"
              data-testid="watching-list"
              aria-labelledby="further-ahead-watching-h"
            >
              {watchingViews.map(renderEntry)}
              {unlisted.map((row, index) => (
                <div
                  key={row.key}
                  role="listitem"
                  className="wf-cu-unlisted"
                  data-testid="watching-unlisted"
                  data-watch-key={row.key}
                >
                  <span className="wf-cu-unlisted-text">
                    <BookmarkMark className="wf-yp-mark" />
                    {row.title}
                    <span aria-hidden="true">{' · '}</span>
                    <span className="sr-only">,</span>
                    {' '}
                    no longer listed
                  </span>
                  <button
                    type="button"
                    className="wf-cu-unlisted-stop"
                    data-testid="watching-unlisted-stop"
                    onClick={() => stopUnlisted(row, index)}
                  >
                    Stop watching
                    {' '}
                    <span className="sr-only">{row.name}</span>
                  </button>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* Chips and the chronology itself are gated on `ready`, unlike the head/handoff/footer
            above and below: their counts and rows come from data that does not exist yet during
            `idle`/`loading`/`error`, and a row of chips reading zero everywhere would understate
            the sky rather than describe it. */}
        {status === 'ready' && (
          <div className="wf-cu-chips" data-testid="coming-up-chips">
            {chips.map((chip) => (
              <button
                key={chip.id}
                type="button"
                className="wf-cu-chip"
                data-chip={chip.id}
                data-testid="coming-up-chip"
                // `aria-pressed`, not `aria-current`: this is a filter toggle and exactly one chip
                // is on at a time — the same convention the since-deleted `WindowRegionRail` already uses for the
                // same shape of control.
                aria-pressed={activeFilter === chip.id}
                onClick={() => chooseFilter(chip.id)}
              >
                {chip.id !== 'all' && (
                  <>
                    <span className="wf-cu-chip-dot" aria-hidden="true" />
                    {CHIP_GLYPHS[chip.id] && (
                      <span className="wf-cu-gi wf-cu-gi-chip" aria-hidden="true" data-testid="coming-up-chip-glyph">
                        {CHIP_GLYPHS[chip.id]}
                      </span>
                    )}
                  </>
                )}
                {chip.label}
                <span className="wf-cu-chip-count">{chip.count}</span>
              </button>
            ))}
            <GuideLink section="furtherAheadTopics" label="Every topic explained" />
          </div>
        )}

        {status === 'ready' && monthGroups.map((group, i) => (
          <div key={group.key}>
            <div
              className="wf-cu-month"
              data-testid="coming-up-month"
              data-first={i === 0 ? 'true' : undefined}
            >
              <span className="wf-cu-month-name">{group.monthLabel}</span>
              <span className="wf-cu-month-year">{group.year}</span>
              <span className="wf-cu-month-rule" aria-hidden="true" />
            </div>
            {/* One list per month, not one list interrupted by month-rule divs: a `role="list"`
                may only own `listitem` children, and a heading between two runs of entries would
                break that contract. Grouped-list-with-a-preceding-heading is the standard pattern
                for exactly this shape (a contacts list grouped by initial letter is the textbook
                example). */}
            <div
              role="list"
              data-testid="coming-up-list"
              aria-label={`${group.monthLabel} ${group.year}`}
            >
              {group.entries.map(renderEntry)}
            </div>
          </div>
        ))}

        <p className="wf-cu-foot" data-testid="coming-up-footer">
          {FOOTER}
        </p>
      </div>
    </section>
  );
}

FurtherAhead.propTypes = {
  id: PropTypes.string.isRequired,
  headingId: PropTypes.string.isRequired,
  graduatedIds: PropTypes.instanceOf(Set),
  onHeadingSeen: PropTypes.func,
  status: PropTypes.oneOf(['idle', 'loading', 'ready', 'error']).isRequired,
  events: PropTypes.shape({
    entries: PropTypes.array,
    counts: PropTypes.shape({
      fixed: PropTypes.number,
      forecast: PropTypes.number,
      byFamily: PropTypes.object,
    }),
    bands: PropTypes.shape({
      list: PropTypes.number,
      announce: PropTypes.number,
      interrupt: PropTypes.number,
    }),
  }),
  todayStr: PropTypes.string,
  onRetry: PropTypes.func.isRequired,
  onGoToPlan: PropTypes.func.isRequired,
  onShowOnMap: PropTypes.func.isRequired,
  comingUpLastSeenDate: PropTypes.string,
  forecastDates: PropTypes.arrayOf(PropTypes.string),
  onMarkSeen: PropTypes.func.isRequired,
  locations: PropTypes.array,
  focusRequest: PropTypes.shape({
    entryId: PropTypes.string,
    seq: PropTypes.number.isRequired,
  }),
  onFocusHandled: PropTypes.func,
};
