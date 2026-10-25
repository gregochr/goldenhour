import { useCallback, useLayoutEffect, useMemo, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import { foreignModalOverPaneOf } from '../../utils/mapForeignModal.js';
import { rampHex } from '../../utils/scoreRamp.js';
import { calDow } from '../../utils/windowFirstStrip.js';
import BoltIcon from './BoltIcon.jsx';
import { VERDICT_LABEL, badgeChannel } from '../../utils/windowFirstCards.js';
import VerdictWord from '../shared/VerdictWord.jsx';
import { verdictRegionLabel } from '../../utils/mapVerdict.js';
import { useOutsideDismiss } from '../../hooks/useOutsideDismiss.js';
import { useRowFocusRescue } from '../../hooks/useRowFocusRescue.js';
import { useFitToFrame } from '../../hooks/useFitToFrame.js';
import { noStoredForecast } from './outcomeRatingCopy.js';
import ProUpsellDoor, { AURORA_UPSELL_BODY, AURORA_UPSELL_HEADING } from '../shared/ProUpsell.jsx';

/** Breathing room kept above/below the active row when the opened menu scrolls it into view. */
const MENU_SCROLL_PAD_PX = 6;

/**
 * The Map tab's single chronological window control — map-tab-v2-plan.md §3 P6,
 * docs/design/map-tab-v2/README.md "1. The window control".
 *
 * <p>One pill (kind chip · label · time · caret), flanked by `‹ ›` steppers, opening a day-grouped
 * dropdown that states each event's best achievable score — "choosing a window is then an informed
 * act rather than a guess" (README). This is the whole reason the menu computes a figure at all:
 * it is not decoration, it is what makes the choice informed.
 *
 * <p>Pure presentation over `utils/mapEvents.js`'s built EV list — this component owns no fetching
 * and derives no score; every row it draws is exactly what it was handed.
 *
 * <h2>Keyboard is scoped to this control, not the document</h2>
 *
 * <p>`←`/`→` step and `Esc` closes the dropdown, via a local `onKeyDown` on this component's own
 * wrapper — bubbling from whichever descendant has focus, never a `document` listener. The Map
 * pane is never unmounted (only hidden), so a global key listener would keep firing for a control
 * that is not on screen; a listener scoped to this subtree cannot.
 *
 * <p>The dropdown's own outside-click dismissal (a `document.mousedown` listener, matching this
 * app's existing disclosure idiom in `HealthIndicator.jsx`/`InfoTip.jsx`) is a different case and
 * does not carry the same risk: it can only ever close state that is not visible when it fires
 * from another tab, never act on anything.
 *
 * <h2>Optionally controlled, for menu exclusivity (map-tab-v2-plan.md §3 P7)</h2>
 *
 * <p>P7 adds a second popover to the same map pane (`FiltersPopover`), and "opening one closes the
 * others" needs one caller-owned source of truth. Passing both {@code open} and
 * {@code onOpenChange} puts this component in CONTROLLED mode — the caller's boolean becomes the
 * only truth and every internal open/close (pill click, row selection, a stepper closing the menu,
 * outside click, `Escape`) is reported upward instead of applied to local state. Omitting both
 * keeps the original uncontrolled behaviour byte-for-byte, which is what every test written before
 * P7 already exercises and what the Plan-tab overlay would fall back to if it ever mounted this
 * control (it does not, today).
 */
export default function WindowControl({
  events, activeIndex, onSelect, open: openProp, onOpenChange = null,
  verdicts = null, scopeIsArea = true,
  onOpenWindowPanel = null, pillRef: pillRefProp = null, pillOverride = null,
  canLoadOlder = false, olderDayLabel = null, olderDayLoadingLabel = null, onLoadOlderDay = null,
  emptyOlderDayLabel = null, ratedCounts = null, thinCounts = null, betterNext = null, auroraDoor = null,
}) {
  const isControlled = openProp !== undefined;
  const [openState, setOpenState] = useState(false);
  const open = isControlled ? openProp : openState;
  // `useCallback`, closing over the CURRENT `open` (controlled or not) so a functional update
  // (`setOpen(v => !v)`, the pill's own toggle) always flips the value actually on screen rather
  // than a possibly-stale internal `openState` the controlled caller has since overridden.
  // Recreated whenever `open` changes, which is exactly when the outside-click effect below needs
  // to re-subscribe anyway — listing it as a dependency costs nothing extra.
  const setOpen = useCallback((next) => {
    const value = typeof next === 'function' ? next(open) : next;
    if (!isControlled) setOpenState(value);
    onOpenChange?.(value);
  }, [open, isControlled, onOpenChange]);
  const rootRef = useRef(null);
  const menuRef = useRef(null);
  // Opens down from the pill, so the frame's bottom edge is the one it can run past.
  useFitToFrame(menuRef, rootRef, { direction: 'down', open });
  // ⚠️ The window on screen must be in view when the menu opens (outcome-rating-plan.md §2 #2, O3b).
  // The EARLIER group sits ABOVE the future days, so with a few past days and the Load row the window
  // the reader is on — usually a future one — would open below the fold of a 420px menu. Scrolls the
  // MENU only (never an ancestor, which `scrollIntoView` can drag the page by), after
  // `useFitToFrame`'s own layout effect has set the room it fits in, and only as far as it must: a
  // menu whose rows all fit, or whose active row is already visible, is left exactly as it was.
  // jsdom has no layout (every offset is 0), where this is a no-op.
  useLayoutEffect(() => {
    if (!open) return;
    const menu = menuRef.current;
    const row = menu?.querySelector('[role="option"][aria-selected="true"]');
    if (!menu || !row) return;
    const top = row.offsetTop;
    const bottom = top + row.offsetHeight;
    if (bottom > menu.scrollTop + menu.clientHeight) {
      menu.scrollTop = bottom - menu.clientHeight + MENU_SCROLL_PAD_PX;
    } else if (top < menu.scrollTop) {
      menu.scrollTop = Math.max(top - MENU_SCROLL_PAD_PX, 0);
    }
  }, [open]);
  /** The pill, so the menu's rows can hand focus back to the control that opened the menu. */
  /**
   * ⚠️ **The pill is this tab's return address, and `MapView` needs to reach it.** Two routes it
   * owns destroy their own trigger and must leave focus somewhere stable inside the pane, or it
   * falls to `<body>` where the pane's React `onKeyDown` never fires and `useDialogFocus` records
   * nothing to restore to: closing a drilldown panel with its ✕, and handing off to the four-day
   * sheet from a region row. The caller may therefore supply the ref; the internal uses below are
   * unchanged (map-landing-plan.md §4 #40).
   */
  const ownPillRef = useRef(null);
  const pillRef = pillRefProp ?? ownPillRef;
  /** The "is better" line, so a key pressed ON it can be told apart from one pressed on the pill. */
  const betterRef = useRef(null);

  const active = activeIndex >= 0 && activeIndex < events.length ? events[activeIndex] : null;
  // Stepping from "nowhere" is ambiguous — the map is on a date/event the list has no row for.
  // The retired `wf-map-window` `<select>` this control absorbed put it best in its own comment:
  // "a real state and not an error", since `GET /api/forecast` reaches further than the
  // briefing's rendered horizon. Both ends are disabled rather than guessing which direction
  // "next" means.
  //
  // ⚠️ Three more terms since the older-day fetch (outcome-rating-plan.md §2 #2, O3b). `loadable` is
  // "one more stored day can be fetched": while it is true the control is NOT at its start even on
  // the oldest window, because `‹` there fetches rather than stepping. `loading` is the fetch in
  // flight — the pill says so, and both steppers (and the Load row) are disabled for it (§4 #10: the
  // prototype's silently swallowed press is replaced by a visible state).
  const loadable = Boolean(canLoadOlder && onLoadOlderDay && olderDayLabel);
  const loading = Boolean(olderDayLoadingLabel);
  const atStart = !active || loading || (activeIndex <= 0 && !loadable);
  const atEnd = !active || loading || activeIndex >= events.length - 1;
  /**
   * One window and nothing to load: both steppers are disabled, and a disabled button says nothing
   * about WHY. They point at a shared sr-only reason (C3: the aurora-only tab holds exactly one row,
   * so its two dead steppers would otherwise be unexplained).
   */
  const onlyOneWindow = events.length === 1 && !loadable && !loading;
  /**
   * A NIGHT row's accessible name. The pill's content gives a solar row its name (kind, day, time,
   * verdict), but a night row has no verdict cell, so by content alone it reads "Aurora Tonight" with
   * neither a star nor a time - and on the aurora-only tab this is the only control there is. So a
   * night row states its best star (or that it is not scored) and its time when one is known. Solar
   * rows keep the name computed from their content, untouched.
   */
  function nightPillName(row) {
    // The daylight High-water row has its own words in its content (kind, day, time, "Big seas"): it
    // is neither a night row ("not scored yet" would be a false claim) nor a solar one.
    if (!row || row.kind === 'solar' || row.kind === 'hw' || row.past === true) return undefined;
    const kind = KIND_TEXT[row.eventType] || row.eventType;
    const best = Number.isFinite(row.bestRating)
      ? `best ${row.bestRating} star${row.bestRating === 1 ? '' : 's'}` : 'not scored yet';
    return [kind, row.dayLabel ?? row.label, row.time || null, best].filter(Boolean).join(', ');
  }
  /** `‹` on the oldest window fetches the next older day instead of stepping. */
  const prevLoads = loadable && !loading && Boolean(active) && activeIndex <= 0;

  /**
   * One EV row's tier, or null — the stepper ticks' whole source, and the pill's.
   *
   * <p>A miss is the honest answer for three different rows and this control draws all three the
   * same way (no tick, no word): a NIGHT row, which has no per-region rollup at all
   * (map-tab-v2-plan.md O-16, and §6 Q1 decided its cell renders empty); an unscored solar row; and
   * a row beyond the briefing's horizon. Telling them apart is `row.kind`'s job, not this map's —
   * see `utils/mapVerdict.js`'s own note on how overloaded a null is here.
   */
  function verdictAt(index) {
    const row = index >= 0 && index < events.length ? events[index] : null;
    // A PAST row has no verdict whatever the map holds (outcome-rating-plan.md §2 #2): no tint, no tick.
    return row && !row.past ? (verdicts?.get(row.id) ?? null) : null;
  }
  const activeVerdict = active && !active.past ? (verdicts?.get(active.id) ?? null) : null;

  /**
   * A stepper's accessible name, carrying the neighbour's verdict when there is one.
   *
   * <p>⚠️ Without this the tick is a colour-only channel with NO text alternative: it is
   * {@code aria-hidden}, and `aria-label` REPLACES a button's subtree, so nothing about the
   * neighbouring window reached a screen reader at all. The whole point of the tick — "`‹ ›` stop
   * being blind", so the reader can see whether the night either side is better before spending a
   * tap — was delivered to sighted users only, and at 11×3px the three tier colours are also the
   * canonical red/green confusion for a dichromat (WCAG 1.4.1). The word is the second channel.
   */
  function stepLabel(base, verdict) {
    const word = verdict ? VERDICT_LABEL[verdict.tier] : null;
    return word ? `${base}, ${word}` : base;
  }
  /**
   * One stepper's accessible name for the neighbour at {@code index}: the bare base when the
   * stepper is disabled, the neighbour's verdict word when it has one, and "past" when it is a
   * PAST window — which has no verdict (it is `served: false`) and whose word, on the pill and
   * here, is the same one (outcome-rating-plan.md §2 #2).
   */
  function stepName(base, index, disabled) {
    if (disabled) return base;
    const row = index >= 0 && index < events.length ? events[index] : null;
    return row?.past === true ? `${base}, past` : stepLabel(base, verdictAt(index));
  }

  // A press on the MAP never dismisses — `useOutsideDismiss` carries that rule for all four map
  // panels, so they cannot drift apart. No `enabled` gate here: this dropdown is not a
  // `BottomSheet` on any viewport, so it is never portalled outside `rootRef`.
  useOutsideDismiss({ open, rootRef, onDismiss: () => setOpen(false) });

  /**
   * ⚠️ **The "is better" line shows only if it fits** (map-landing-nocard-plan.md D1, owner 2026-10-25).
   * The control is a constant 504px, so the line is paid for by the pill (432px down to about 245px), and
   * measured in Chromium the verdict word clips on a first window that carries a pick and on a weekday
   * label, at every viewport width; and the frame is not the viewport (`<main>`'s `sm:px-4`, the 1080px
   * cap, the Ask dock), so no media query could name the width. The fit is therefore asked of the layout.
   *
   * <p>A ResizeObserver keeps the control's width in state. The key joins window, sentence, width, fonts
   * and a signature of what the pill SHOWS (tier, region wording, pick, thin count, loading), because at a
   * fixed 504px the same window and width can still carry different content (a scope flip, a refresh, a
   * late webfont). A layout effect runs on EVERY commit while the line is a candidate (so the verdict is
   * reached before paint) and withholds it when, with it drawn, the pill or the verdict cell overflows.
   * The cell, not the word: the word is its own box and never overflows itself, it overflows the squeezed
   * cell. The region line is allowed to ellipsise and is not tested.
   *
   * <p>⚠️ The verdict is stored AS A KEY and state is set only when the line is drawn AND overflows, so it
   * cannot oscillate: withholding the line leaves the key unchanged and the effect inert (the control's
   * width is fixed by its own rule, never by the line). A new width, window or content makes a new key and
   * the line is tried again. No observer (jsdom) means no width; the effect still measures, reading zeros.
   */
  const [controlWidth, setControlWidth] = useState(null);
  const [misfitKey, setMisfitKey] = useState(null);
  const [fontsReady, setFontsReady] = useState(
    () => !globalThis.document?.fonts || document.fonts.status === 'loaded',
  );
  const noEvents = events.length === 0;
  useLayoutEffect(() => {
    const el = rootRef.current;
    if (!el || typeof ResizeObserver === 'undefined') return undefined;
    const observer = new ResizeObserver((entries) => {
      const width = entries?.[0]?.contentRect?.width;
      if (Number.isFinite(width)) setControlWidth(Math.round(width));
    });
    observer.observe(el);
    return () => observer.disconnect();
  }, [noEvents]);
  // A webfont arriving after first paint changes every glyph width: one more try, once.
  useLayoutEffect(() => {
    if (fontsReady) return undefined;
    let live = true;
    document.fonts.ready.then(() => { if (live) setFontsReady(true); });
    return () => { live = false; };
  }, [fontsReady]);
  const fitSig = [
    activeVerdict?.tier, activeVerdict ? verdictRegionLabel(activeVerdict, { scopeIsArea }) : '',
    active?.pickKind, thinCounts?.get(active?.id), loading, scopeIsArea,
  ].join('~');
  const fitKey = betterNext
    ? `${active?.id}|${betterNext.text}|${controlWidth}|${fontsReady}|${fitSig}` : null;
  const showBetter = Boolean(betterNext) && misfitKey !== fitKey;
  /** Set by the line's own focus/blur: did it hold focus when it left the DOM? */
  const betterHadFocus = useRef(false);
  // On every commit, by design (see above); it sets state only for a drawn line that overflows.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useLayoutEffect(() => {
    // ⚠️ The PARENT withdrew a line that held focus (a refresh, thin counts arriving, the clock rebuilding
    // the list): the element is gone and focus fell to <body>, where the pane's keys never fire. Same
    // rescue as `useRowFocusRescue`: only when focus is truly nowhere.
    if (betterRef.current == null && betterHadFocus.current) {
      betterHadFocus.current = false;
      const at = document.activeElement;
      if (!at || at === document.body) pillRef.current?.focus();
    }
    if (!showBetter) return;
    const pill = pillRef.current;
    const cell = pill?.querySelector('.wf-win-verdict');
    const overflows = (node) => node != null && node.scrollWidth > node.clientWidth + 1;
    if (!overflows(pill) && !overflows(cell)) return;
    // Withheld with focus on the line: it is about to unmount, so the pill takes focus first.
    if (betterRef.current && document.activeElement === betterRef.current) {
      betterHadFocus.current = false;
      pillRef.current?.focus();
    }
    setMisfitKey(fitKey);
  });

  // A row can leave this open menu with nobody pressing anything — the EV list is rebuilt against
  // the clock, so last night's rows go at dawn (D-14) and yesterday's filler solar rows at UK
  // midnight. The pill is where focus goes if the row holding it does; see the hook for why it only
  // ever acts on that transition.
  const rowFocus = useRowFocusRescue({
    active: open, rowIds: events.map((row) => row.id), fallbackRef: pillRef,
  });

  /**
   * Two sets of day groups, each grouped by date in the list's own order (the list is already
   * chronological): the EARLIER group (every PAST row) and the future groups (everything else).
   *
   * <p>⚠️ <b>One date can be in BOTH, and must not be deduped.</b> At Thu 08:15 today's elapsed
   * sunrise is a past row (under the EARLIER sub-header {@code Today}) and its sunset is not (under
   * the future group {@code THU 15 · today}), so a key of {@code group.date} alone would collide;
   * the keys carry their set. A night row of the SAME date as a past solar row (yesterday's astro
   * night, kept on screen until dawn) sits in the future set the same way.
   */
  const { earlierGroups, laterGroups } = useMemo(() => {
    const groupByDate = (rows) => {
      const out = [];
      let lastDate = null;
      for (const row of rows) {
        if (row.date !== lastDate) {
          out.push({ date: row.date, rows: [] });
          lastDate = row.date;
        }
        out[out.length - 1].rows.push(row);
      }
      return out;
    };
    return {
      earlierGroups: groupByDate(events.filter((row) => row.past === true)),
      laterGroups: groupByDate(events.filter((row) => row.past !== true)),
    };
  }, [events]);
  const earlierDates = new Set(earlierGroups.map((group) => group.date));
  // The EARLIER header is drawn when there is anything under it: a past row, or a day to fetch.
  // So does a day that loaded empty: its line is the group's content when nothing else is (the T-4 floor).
  const noStoredLine = emptyOlderDayLabel ? noStoredForecast(emptyOlderDayLabel) : null;
  const showEarlier = earlierGroups.length > 0 || loadable || Boolean(noStoredLine);

  function selectRow(row) {
    setOpen(false);
    onSelect(row);
  }

  /**
   * Fetches the next older day, from any of its three routes — `‹`, `←` and the Load row.
   *
   * <p>⚠️ Focus goes to the pill HERE, before the state change, and not in an effect. `‹` is about to
   * be disabled (a focused control that becomes disabled drops focus to {@code <body>}, where this
   * control's keyboard handler never fires) and the Load row unmounts with the menu; and
   * `useRowFocusRescue` records only `[data-ev-id]` rows and clears on pointerdown, so it will NOT
   * rescue the Load row — the same reason the drilldown row does this.
   */
  function loadOlder() {
    if (!loadable || loading) return;
    setOpen(false);
    pillRef.current?.focus();
    onLoadOlderDay();
  }

  /**
   * Presses the "is better" line: the second solar window, exactly as if it had been chosen from the
   * menu (so `selectEvRow` resets the filters on a sunset to sunrise change, on purpose).
   *
   * <p>⚠️ Focus goes to the pill HERE, before the state change. The line unmounts itself the moment the
   * window moves off the first one, and a focused control that unmounts drops focus to
   * {@code <body>}, where this control's keyboard handler and the pane's Escape never fire (the defect
   * map-tab.md records five times). Same order as {@link loadOlder}.
   */
  function pressBetter() {
    if (!betterNext || loading) return;
    setOpen(false);
    pillRef.current?.focus();
    onSelect(betterNext.row);
  }

  function step(delta) {
    if (loading) return;
    if (delta < 0 && prevLoads) {
      loadOlder();
      return;
    }
    const next = activeIndex + delta;
    if (next < 0 || next >= events.length) return;
    setOpen(false);
    onSelect(events[next]);
  }

  function onKeyDown(e) {
    // ⚠️ Stand down entirely while a dialog from OUTSIDE the map pane is over it — the same
    // predicate `MapView`'s pane handler and the drilldown's two panels read. Without it this
    // operates the map BEHIND an open sheet: `map-landing-plan.md`
    // §4 #37 recorded that for the two panels, and an accessibility lens then found this control
    // is on the very route that reaches them — a keyboard reader gets here by Tabbing out of the
    // non-trapping sheet onto the pill, so the drilldown cannot be opened behind a sheet without
    // opening THIS first.
    if (foreignModalOverPaneOf(rootRef.current)) return;
    // ⚠️ The second route that moves the window off the first while the line holds focus (the press is
    // the first): the line is about to unmount, so focus moves to the pill before `step` changes state.
    const onBetter = betterRef.current != null && e.target === betterRef.current;
    // ⚠️ The Lite aurora door lives inside the menu, so its keys bubble here. Left/Right there would step
    // the window, close the menu and unmount the door with focus inside it; the door owns its own keys.
    if ((e.key === 'ArrowLeft' || e.key === 'ArrowRight') && e.target?.closest?.('.wf-win-aurora-door')) return;
    if (e.key === 'ArrowLeft') {
      e.preventDefault();
      if (onBetter && !atStart) pillRef.current?.focus();
      step(-1);
    } else if (e.key === 'ArrowRight') {
      e.preventDefault();
      if (onBetter && !atEnd) pillRef.current?.focus();
      step(1);
    } else if (e.key === 'Escape' && open) {
      e.preventDefault();
      setOpen(false);
    }
  }

  // Genuinely nothing to show — no briefing, no forecast domain, nothing fetched yet. Distinct
  // from `!active` below: an EMPTY list has no dropdown to offer either, where a non-empty list
  // with no matching row still has somewhere useful for the pill to send the reader.
  if (events.length === 0) return null;

  return (
    // The div itself is not interactive — every real affordance inside it (the pill, the two
    // steppers, each dropdown row) is a native `<button>`. `onKeyDown` here exists purely to
    // catch `ArrowLeft`/`ArrowRight`/`Escape` bubbling up from whichever of those has focus, which
    // is what "scoped to the map pane, never document-global" (map-tab-v2-plan.md §3 P6) means in
    // practice — a `document` listener would keep firing for a control that is not on screen,
    // since the Map pane is never unmounted (only hidden).
    // eslint-disable-next-line jsx-a11y/no-static-element-interactions
    <div
      ref={rootRef}
      data-testid="wf-win-control"
      className="wf-win-control"
      data-better={showBetter ? '' : undefined}
      onKeyDown={onKeyDown}
    >
      {/* The fetch's one announcement. The pill keeps focus while its text changes to `Loading …`, and a
          content change under focus is not reliably spoken, so this region (mounted ALWAYS, since a
          live region must exist before its text changes) says it. It also says an older day that loaded
          with no rows (`No stored forecast for Fri 16`), the one other thing the press does. */}
      <span role="status" id="wf-win-status" className="sr-only" data-testid="wf-win-status">
        {loading ? `Loading ${olderDayLoadingLabel}\u2026` : (noStoredLine ?? '')}
      </span>
      {onlyOneWindow && (
        <span id="wf-win-only" className="sr-only" data-testid="wf-win-only">Only one window</span>
      )}
      <button
        type="button"
        data-testid="wf-win-prev"
        /* On the oldest window with a day to fetch, `‹` does a different thing and says so: the name
           is what it will fetch, not "Previous event" (outcome-rating-plan.md §2 #2, §4 #17). */
        aria-label={prevLoads ? `Load ${olderDayLabel}` : stepName('Previous event', activeIndex - 1, atStart)}
        className="wf-win-step"
        /* At the T-4 floor the disabled `‹` is explained by the status line (the same sentence the menu
           shows), so a reader who lands on it with the menu closed is told why. */
        aria-describedby={atStart && noStoredLine && !loading
          ? 'wf-win-status' : (onlyOneWindow ? 'wf-win-only' : undefined)}
        disabled={atStart}
        onClick={() => step(-1)}
      >
        &#x2039;
        <StepTick verdict={atStart ? null : verdictAt(activeIndex - 1)} />
      </button>

      <button
        ref={pillRef}
        type="button"
        data-testid="wf-win-pill"
        /* The tint is an inset left bar plus a matching border, keyed off the tier. A night row and
           an unscored one both carry no tier and so no tint, which is the design's own rule. */
        data-tier={activeVerdict?.tier || undefined}
        className="wf-win-pill"
        aria-label={loading ? undefined : nightPillName(active)}
        title={active?.rosterNote || undefined}
        /* The older-day fetch in flight: the pill says so (below) and is the one busy element. */
        aria-busy={loading || undefined}
        /* ⚠️ **The phone override carries the pill's popup semantics with it**
           (map-mobile-sheet-plan.md §3 M2 task 4) — on the phone the pill body opens the peek
           sheet's Windows section instead of this dropdown, so `aria-haspopup="listbox"`,
           `aria-controls="wf-win-listbox"` and an `aria-expanded` derived from THIS component's own
           `open` would all be false claims: there is no listbox to open, nothing here controls
           `wf-win-listbox`, and `open` never turns true on the phone (nothing sets `openMapMenu` to
           `'window'` there). `pillOverride` is the small contract that replaces all three at once
           rather than leaving two of them stale beside a correct third. */
        {...(pillOverride ? {
          'aria-expanded': pillOverride.expanded,
          'aria-controls': pillOverride.controlsId,
          onClick: pillOverride.onPress,
        } : {
          'aria-haspopup': 'listbox',
          'aria-expanded': open,
          // ⚠️ Names the LISTBOX, not the popup box around it. The popup holds rows that choose no
          // window (the EARLIER header and Load row, the drilldown row) beside the listbox, so
          // `role="listbox"` sits on an inner element — and a trigger declaring `aria-haspopup="listbox"` while `aria-controls` pointed at a generic
          // container left JAWS's "move to controlled element" landing on an unnamed div. The id and
          // the role belong on one element.
          'aria-controls': 'wf-win-listbox',
          onClick: () => setOpen((v) => !v),
        })}
      >
        {loading ? (
          // The fetch in flight (§2 #2, §4 #10). Not timed: it lasts as long as the request does.
          // The label class keeps the pill's flex/overflow behaviour; `wf-win-loading` is the
          // state's own hook.
          <span className="wf-win-label wf-win-loading" data-testid="wf-win-loading">
            {`Loading ${olderDayLoadingLabel}…`}
          </span>
        ) : active ? (
          <>
            <KindChip row={active} />
            {' '}
            {/* The kind chip already reads SUNRISE/SUNSET, so the pill text is the day-only
                form — `dayLabel`, never `label` (map-tab-v2-plan.md's kind-chip dedup). Falls
                back to `label` for a caller that predates the field, rather than rendering
                nothing. */}
            <span className="wf-win-label">{active.dayLabel ?? active.label}</span>
            {' '}
            {active.time && <span className="wf-win-time">{active.time}</span>}
            {/* ⚠️ Defensive, not load-bearing in a browser. The glued reading "20:28Also goodWorth
                iteverywhere in your area" is jsdom's: it computes no layout, so these flex items
                read as `display: inline` to it. In a browser `.wf-win-pill` is `display: flex` at
                every width — no media query changes it — so these are flex items, blockified
                whatever their own display, and every engine spaces them itself: removing each of
                these separators changed no accessible name (measured 2026-09-11 at 1280px and
                375px by Playwright's accessible-name algorithm over Chromium's, WebKit's and
                Firefox's layout; Chromium's native accessibility tree agreed on 2026-09-14, at
                1400px and 375px). The breakpoint rules that do change a
                `display` or `position` in here (hiding the region at ≤639px and the pick at
                ≤389px, taking the pick's words out of flow at ≤811px) leave the pill flex.
                They stay because they state the boundary in the DOM and jsdom's reading needs
                them. Rule and method: `WindowComingUpFourDays`'s class doc. */}
            {' '}
            <Medallion kind={active.pickKind} />
            {' '}
            {/* A PAST window (outcome-rating-plan.md §2 #2) wears this tag where a verdict would be:
                it is `served: false`, so it has no verdict, tint or tick, and the pill states what
                it IS. The DOM text is "Past" — the stylesheet uppercases it — so the accessible name
                says "past" in words. */}
            {active.past
              ? <span className="wf-win-past" data-testid="wf-win-past">Past</span>
              : active.kind === 'hw' ? <HighWaterVerdict /> : (
                <VerdictCell
                  verdict={activeVerdict}
                  scopeIsArea={scopeIsArea}
                  ratedCount={thinCounts?.get(active.id) ?? null}
                />
              )}
          </>
        ) : (
          // The map is on a date/event this list has no row for — an ordinary state (the map's
          // own domain can reach further than the briefing, or further than any night on record),
          // not an error. Still opens the dropdown, exactly like the retired `<select>`'s own
          // `No forecast window` option did, so the reader is never stuck with no way back.
          <span className="wf-win-label" data-testid="wf-win-no-match">No forecast</span>
        )}
        <span aria-hidden="true" className="wf-win-caret">&#9662;</span>
      </button>

      {showBetter && (
        // A SIBLING of the pill, never inside it: the pill is itself a `<button>`. Its accessible name is
        // its text (the `›` is aria-hidden; no `aria-label`, which would replace the content), and the
        // tier word rides `aria-describedby` so a screen reader hears the reason the stepper tick gives
        // a sighted reader. `data-tier` is the ACTIVE window's, so the border matches the pill's tint.
        // The description span is OUTSIDE the button: inside it, it would join the button's name.
        <>
          <button
            ref={betterRef}
            type="button"
            data-testid="wf-win-better"
            className="wf-win-better"
            data-tier={activeVerdict?.tier || undefined}
            aria-describedby="wf-win-better-why"
            onClick={pressBetter}
            onFocus={() => { betterHadFocus.current = true; }}
            // Focus moving to another element clears it; a blur to nowhere (the node leaving the DOM)
            // does not, which is exactly the case the rescue above is for.
            onBlur={(e) => { if (e.relatedTarget) betterHadFocus.current = false; }}
          >
            {betterNext.text}
            <span aria-hidden="true">{' ›'}</span>
          </button>
          <span id="wf-win-better-why" data-testid="wf-win-better-why" className="sr-only">
            {VERDICT_LABEL[betterNext.tier]}
          </span>
        </>
      )}

      <button
        type="button"
        data-testid="wf-win-next"
        aria-label={stepName('Next event', activeIndex + 1, atEnd)}
        className="wf-win-step"
        aria-describedby={onlyOneWindow ? 'wf-win-only' : undefined}
        disabled={atEnd}
        onClick={() => step(1)}
      >
        &#x203A;
        <StepTick verdict={atEnd ? null : verdictAt(activeIndex + 1)} />
      </button>

      {open && (
        <div ref={menuRef} id="wf-win-menu" data-testid="wf-win-menu" className="wf-win-menu">
          {/* The EARLIER group's header and Load row (outcome-rating-plan.md §2 #2, O3b), above the
              windows they precede. ⚠️ Outside the listbox because the Load row
              chooses no window, and `role="listbox"` admits only options. It is the first row under
              the header, so the listbox starts right after it and the menu still reads, and scrolls,
              as one list: header, Load row, past days, future days. */}
          {showEarlier && (
            <div data-testid="wf-win-earlier" className="wf-win-earlier">
              EARLIER
              <span className="wf-win-earlier-hint">rate how it went</span>
            </div>
          )}
          {noStoredLine && (
            <div data-testid="wf-win-none" className="wf-win-none">{noStoredLine}</div>
          )}
          {showEarlier && loadable && (
            <button
              type="button"
              data-testid="wf-win-load"
              className="wf-win-row wf-win-load"
              disabled={loading}
              onClick={loadOlder}
            >
              {/* The kind-chip slot, `.wf-win-load-chip` (the prototype's `.wk.lo`). The glyph is
                  decoration; the row's accessible name is its text, which already says what it fetches. */}
              <span className="wf-hc-sun wf-win-load-chip" aria-hidden="true">&#8593;</span>
              <span className="wf-win-row-main">
                <b className="wf-win-load-title">{`Load ${olderDayLabel}`}</b>
                {/* Defensive, like the pill's separators: the column is flex, so a browser spaces
                    the two lines itself; jsdom reads them as inline and glued the name otherwise. */}
                {' '}
                <span className="wf-win-load-sub">
                  {`or press ‹ past the oldest window · fetches ${olderDayLabel}`}
                </span>
              </span>
            </button>
          )}
          {/* ⚠️ The LISTBOX is this inner element, not the popup box above it. `role="listbox"`
              admits only `option` (and `group`) children, and the EARLIER header, the Load row and the
              drilldown row are none of them — they choose no window, so leaving them inside left
              children a listbox-navigating screen reader could not reach by the roles the container
              promises. The popup box keeps the
              test-id and the class; the id moved here WITH the role, so the pill's `aria-controls`
              still names the listbox itself rather than a generic wrapper.

              ⚠️ **This does NOT make the listbox fully conforming, and an earlier revision of this
              comment implied it did.** The day-group wrappers below are `div`s with no role, each
              holding a text-bearing `.wf-win-day` heading — also neither `option` nor `group`. That
              is pre-existing, and it is left alone rather than quietly rolled into an unrelated
              commit; but it is the same class of problem, and this note must not be read as saying
              it was handled. */}
          <div
            role="listbox"
            id="wf-win-listbox"
            data-testid="wf-win-listbox"
            aria-label="Choose an event"
            onFocus={rowFocus.onFocus}
            onBlur={rowFocus.onBlur}
            onPointerDown={rowFocus.onPointerDown}
          >
            {/* The EARLIER group's days: a sub-header per past day, then its rows (sunrise first).
                ⚠️ Keys carry the set — a date can be in both (see `earlierGroups`). */}
            {earlierGroups.map((group) => (
              <div key={`earlier:${group.date}`}>
                <div data-testid="wf-win-sub" className="wf-win-sub">
                  {group.rows[0].dayLabel ?? group.date}
                </div>
                {group.rows.map((row) => (
                  <WindowRow
                    key={row.id}
                    row={row}
                    active={row.id === active?.id}
                    onSelect={() => selectRow(row)}
                    ratedCount={ratedCounts?.get(row.id) ?? null}
                  />
                ))}
              </div>
            ))}
            {laterGroups.map((group, index) => (
              <div key={`day:${group.date}`}>
                <div
                  data-testid="wf-win-day"
                  // The first future day under an EARLIER group wears the rule the group's own
                  // `:first-child` would otherwise take off it.
                  className={`wf-win-day${index === 0 && earlierGroups.length > 0 ? ' wf-win-day-after-earlier' : ''}`}
                >
                  {dayHeading(group.date)}
                  {/* The prototype's `· today` — drawn only when the SAME date also heads an EARLIER
                      sub-header (today's elapsed sunrise), the one place two headings would
                      otherwise read as two different days. */}
                  {earlierDates.has(group.date) && group.rows.some((row) => row.dayLabel === 'Today')
                    ? ' · today' : ''}
                </div>
                {group.rows.map((row) => (
                  <WindowRow
                    key={row.id}
                    row={row}
                    active={row.id === active?.id}
                    onSelect={() => selectRow(row)}
                  />
                ))}
              </div>
            ))}
          </div>

          {/* The drilldown's entry (map-landing-plan.md §3 L5) — below the windows, because it is
              about the one already chosen rather than a way to choose another. `RegionsJump`'s reset
              row is the same idiom at the other end of its list.

              ⚠️ Outside the listbox for the same reason as the Load row: it selects no window. */}
          {onOpenWindowPanel && (
            <button
              type="button"
              data-testid="wf-win-more"
              className="wf-win-row wf-win-landing wf-win-more"
              // The app's own convention for a control that opens a dialog — eight siblings in
              // `components/map/` carry it, including both panels this row leads to. It was the one
              // route into the drilldown without it (review, #792).
              aria-haspopup="dialog"
              onClick={() => {
                setOpen(false);
                onOpenWindowPanel();
                // ⚠️ **Not optional.** This button unmounts itself, so without this focus falls to
                // `<body>` —
                // and `MapView`'s Escape handler is a React `onKeyDown` on the map pane's root,
                // which a press on `<body>` never reaches. The panel would then be un-closable by
                // keyboard on the ONLY route that opens it (plan §3 L5 step 5 asks for Escape by
                // name). Focus returns to the pill, which is inside the pane and still on screen.
                pillRef.current?.focus();
              }}
            >
              <span className="wf-win-landing-txt">
                <span aria-hidden="true">&#9636;{' '}</span>
                This window, region by region
              </span>
            </button>
          )}

          {/* The Lite aurora door (docs/engineering/aurora-pro-door-plan.md §2.2): a footer AFTER the listbox and
              the drilldown row, never inside the listbox (its children must be options), and not an
              event: it is no row, so no stepper, count or selection ever reaches it. */}
          {auroraDoor && (
            <div data-testid="wf-win-aurora-door" className="wf-win-aurora-door">
              <ProUpsellDoor
                label={auroraDoor.label}
                heading={AURORA_UPSELL_HEADING}
                body={AURORA_UPSELL_BODY}
                testIdPrefix="wf-aurora-upsell"
                className="wf-pro-upsell-door--inline"
                unmountFocusRef={pillRef}
              />
            </div>
          )}
        </div>
      )}
    </div>
  );
}

WindowControl.propTypes = {
  events: PropTypes.arrayOf(PropTypes.shape({
    id: PropTypes.string.isRequired,
    kind: PropTypes.string.isRequired,
    eventType: PropTypes.string.isRequired,
    date: PropTypes.string.isRequired,
    label: PropTypes.string.isRequired,
    /**
     * Day-only form of `label` with the trailing SUNRISE/SUNSET word stripped (kind-chip dedup).
     * Optional rather than required: every row `utils/mapEvents.js` builds carries it, but the
     * component falls back to `label` for a caller that predates the field.
     */
    dayLabel: PropTypes.string,
    time: PropTypes.string,
    bestRating: PropTypes.number,
    scored: PropTypes.bool,
    /**
     * A solar window that has gone (outcome-rating-plan.md §2 #2): the pill wears a `Past` tag where
     * a verdict would be, and the steppers name it "past".
     */
    past: PropTypes.bool,
    badges: PropTypes.array,
    rosterNote: PropTypes.string,
    /** `'best'` / `'also'` when this window is one of the forecast's two served picks. */
    pickKind: PropTypes.oneOf(['best', 'also']),
  })).isRequired,
  /** Index into `events` of the currently-shown row; -1 when nothing matches yet. */
  activeIndex: PropTypes.number.isRequired,
  /** Called with the chosen row — never an index, so the caller never has to re-look it up. */
  onSelect: PropTypes.func.isRequired,
  /**
   * Controlled dropdown state (map-tab-v2-plan.md §3 P7's menu exclusivity). Omit both this and
   * `onOpenChange` for the original uncontrolled behaviour.
   */
  open: PropTypes.bool,
  /** Fired on every open/close this component would otherwise have applied to local state. */
  onOpenChange: PropTypes.func,
  /**
   * Row id → that window's verdict, from `utils/mapVerdict.buildEvVerdicts`. Optional: omitting it
   * renders the control exactly as it did before this phase, which is what the frozen Plan-tab
   * overlay would get if it ever mounted this control (it does not).
   */
  verdicts: PropTypes.instanceOf(Map),
  /**
   * Row id to the thin-sample `· N rated` count (`utils/mapThinSample.buildThinCounts`). Optional:
   * absent or a missing row draws nothing, which is every window but a thinly-rated one.
   */
  thinCounts: PropTypes.instanceOf(Map),
  /**
   * The "<day> <event> is better" line (`utils/mapLanding.betterNextWindow`, map-landing-nocard-plan.md
   * P2): the second solar window to select on a press, the sentence, and its tier word. Null (the
   * default, and what the phone passes) draws nothing.
   */
  betterNext: PropTypes.shape({
    row: PropTypes.object.isRequired,
    text: PropTypes.string.isRequired,
    tier: PropTypes.string.isRequired,
  }),
  /**
   * The Lite reader's aurora Pro door (aurora-pro-door-plan.md §2.2): the trigger's label, drawn as a footer
   * of the open menu. Null (the default, and what Pro and Admin pass) draws nothing.
   */
  auroraDoor: PropTypes.shape({ label: PropTypes.string.isRequired }),
  /** Whether the scope segment reads "My area" — decides only the all-in-scope wording. */
  scopeIsArea: PropTypes.bool,
  /** Opens the window panel on the row in force (map-landing-plan.md §3 L5). Null withholds it. */
  onOpenWindowPanel: PropTypes.func,
  /** A ref the caller attaches to the pill, to return focus to it from a route it owns. */
  pillRef: PropTypes.object,
  /**
   * Whether one more stored past day can be fetched (outcome-rating-plan.md §2 #2, O3b) — derived in
   * `WindowFirstMapPane` from the dates it holds against the T-4 floor, and false under a rewind.
   * Turns `‹` on the oldest window into the fetch and draws the Load row.
   */
  canLoadOlder: PropTypes.bool,
  /** The day {@code canLoadOlder} would fetch, as the control names it: {@code Thu 13}. */
  olderDayLabel: PropTypes.string,
  /** The day being fetched right now ({@code Thu 13}), or null. Non-null puts the control in its loading state. */
  olderDayLoadingLabel: PropTypes.string,
  /**
   * The most recently loaded older day, as {@code Fri 16}, when it came back with no rows
   * (outcome-rating-plan.md §4 #15, amended 2026-10-17). Draws the quiet `No stored forecast for …` line
   * in the Load row's place and has the status region say it. It stays at the T-4 floor, where no Load
   * row follows, so the disabled `‹` is explained.
   */
  emptyOlderDayLabel: PropTypes.string,
  /** Fetches the next older day. The caller owns the fetch and what selection does when it lands. */
  onLoadOlderDay: PropTypes.func,
  /**
   * Row id to the number of locations the signed-in reader has rated for that PAST window
   * (outcome-rating-plan.md §2 #6, O4b; built in the pane by {@code utils/outcomeRatingJoin}). A
   * zero prints {@code Not rated}; an id the map LACKS — and a null map — is unknown (not read yet, a
   * failed read, a rewind) and prints nothing in the cell.
   */
  ratedCounts: PropTypes.instanceOf(Map),
  /**
   * Phone-only: replaces the pill body's dropdown-opening behaviour and popup semantics with the
   * peek sheet's Windows section (map-mobile-sheet-plan.md §3 M2 task 4). Null keeps the original
   * listbox dropdown, unchanged — every desktop/tablet caller omits this.
   */
  pillOverride: PropTypes.shape({
    /** Called on a pill press instead of toggling the local/controlled dropdown. */
    onPress: PropTypes.func.isRequired,
    /** Whether the peek sheet's Windows section is open — replaces the dropdown's own `open`. */
    expanded: PropTypes.bool.isRequired,
    /** The element the pill's `aria-controls` should name instead of `wf-win-listbox`. */
    controlsId: PropTypes.string.isRequired,
  }),
};

/**
 * The two picks' vocabulary — glyph and words, as SEPARATE elements (see {@link Medallion}).
 *
 * <p><b>Exported since map-landing L4</b>, because the window and region panels wear the same two picks and a
 * second copy would be a second vocabulary: L4's first cut minted `PICK_WORDS` plus an inline glyph
 * ternary, giving three spellings of `◎` across two files in one commit — in a component whose own
 * kind-chip comment nine lines away says "never a second one". L5 and L6 each want the pair again.
 */
export const PICK_TEXT = { best: { glyph: '\u25CE', words: 'Best bet' }, also: { glyph: '\u25CB', words: 'Also good' } };

/**
 * The pick medallion — an outline chip, never a filled one.
 *
 * <p>A filled chip became the loudest thing on a control whose job is the verdict; it was built and
 * cut, and the rejection is part of the spec. Drawn only where the window it names is on screen: on
 * the pill when the current window is a pick, and on every menu row that is one. ⚠️ <b>No chip
 * pointing at OTHER windows</b> — also built, also cut, because it put a second navigation control
 * on the map to answer a question the reader arrives from the Plan tab already having answered.
 *
 * <p>⚠️ <b>The glyph and the words are separate elements, and that is load-bearing.</b> The phone
 * rule drops the words and keeps the glyph; doing it with {@code font-size: 0} plus
 * {@code ::first-letter} does not work, because these glyphs are symbols rather than letters and the
 * chip renders as an empty bordered box.
 *
 * <p><b>The colours are this arm's own pick channel, not the bundle's.</b> The design gives
 * ALSO GOOD {@code #EFC377} — which in this app is {@code --color-badge-maybe}, the <em>Maybe
 * verdict</em> colour, and it would sit six pixels from a verdict word drawn in exactly that amber
 * on exactly the windows where the runner-up is a Maybe. The Plan matrix already answered this for
 * its own BEST BET / ALSO GOOD legend — one green channel at two strengths — so this reuses that
 * pair rather than minting a second vocabulary (map-landing-plan.md §4 #12).
 */
function Medallion({ kind }) {
  const pick = kind ? PICK_TEXT[kind] : null;
  if (!pick) return null;
  return (
    <span className="wf-win-pick" data-pick={kind} data-testid="wf-win-pick">
      <i aria-hidden="true" data-testid="wf-win-pick-glyph" className="wf-win-pick-glyph">{pick.glyph}</i>
      <span data-testid="wf-win-pick-words" className="wf-win-pick-words">{pick.words}</span>
    </span>
  );
}

Medallion.propTypes = { kind: PropTypes.oneOf(['best', 'also']) };

/**
 * The verdict word, with the region it is true of stacked under it.
 *
 * <p><b>Stacked, not inline.</b> The design measured the inline form at about 120px, which pushed
 * the stepper under the right-hand nav cluster — so the word sits over the region, and the region
 * is the part that yields at phone width.
 *
 * <p>Renders nothing at all when there is no verdict. That covers a night row (§6 Q1: the cell is
 * empty, and nothing is borrowed from the solar vocabulary or synthesised from the night's stars),
 * an unscored window, and a date beyond the briefing.
 */
function VerdictCell({ verdict, scopeIsArea, ratedCount = null }) {
  if (!verdict) return null;
  const region = verdictRegionLabel(verdict, { scopeIsArea });
  return (
    <span className="wf-win-verdict" data-tier={verdict.tier} data-testid="wf-win-verdict">
      {/* ⚠️ `mode="swap"`: "TOO FEW RATED" (91px) clipped the day label at 320–430px and itself at
          640–700px (measured), so the pill swaps to the short form at 811px and below on one media
          query (`.wf-vw-short` / `.wf-vw-full` in index.css) and the full word stays in the
          accessible name (unrated-verdict-plan.md U2, review F3). From 812px the full word is drawn. */}
      <span className="wf-win-verdict-line">
        <b className="wf-win-verdict-word" data-testid="wf-win-verdict-word"><VerdictWord tier={verdict.tier} mode="swap" /></b>
        {/* thin-sample plan F4: rated sky places in the SCOPE pool, only on a thin sample
            (`utils/mapThinSample.js`). Drawn aria-hidden and spoken in words beside it, so the name
            reads "Worth it, 6 places rated" whatever the width hides. */}
        {ratedCount != null && (
          <>
            <span aria-hidden="true" className="wf-win-verdict-count" data-testid="wf-win-verdict-count">
              <span className="wf-win-verdict-count-sep">&middot;</span>
              {`${ratedCount} rated`}
            </span>
            <span className="sr-only">{`, ${ratedCount} ${ratedCount === 1 ? 'place' : 'places'} rated`}</span>
          </>
        )}
      </span>
      {/* Defensive, like the pill's own separators: `.wf-win-verdict` is `display: inline-flex`, so
          the word and the region are flex items and browsers space them themselves (measured
          2026-09-11 by Playwright's accessible-name algorithm over Chromium's, WebKit's and
          Firefox's layout; Chromium's native accessibility tree agreed on 2026-09-14). "Worth
          iteverywhere in your area" is jsdom's reading — it computes no layout, so they read as
          inline to it (see `WindowComingUpFourDays`'s class doc). */}
      {' '}
      {/* A `<span>`, not an `<em>`: `<em>` means stress emphasis, some AT announces it, and the
          stylesheet immediately sets `font-style: normal` on it — the tell that none is intended. */}
      {region && <span className="wf-win-verdict-region" data-testid="wf-win-verdict-region">{region}</span>}
    </span>
  );
}

VerdictCell.propTypes = {
  verdict: PropTypes.shape({
    tier: PropTypes.string.isRequired,
    regionName: PropTypes.string,
    sharingCount: PropTypes.number,
    allInScope: PropTypes.bool,
  }),
  scopeIsArea: PropTypes.bool,
  ratedCount: PropTypes.number,
};

/**
 * The stepper's verdict tick — the tier of the window one step back or forward.
 *
 * <p>"`‹ ›` stop being blind": the map draws one window at a time, so without this the reader
 * cannot see whether the night either side is better before spending a tap. Hidden when the stepper
 * is disabled (there is no neighbour to describe) and when the neighbour carries no tier.
 */
function StepTick({ verdict }) {
  if (!verdict?.tier) return null;
  return <i aria-hidden="true" className="wf-win-tick" data-tier={verdict.tier} data-testid="wf-win-tick" />;
}

StepTick.propTypes = {
  verdict: PropTypes.shape({ tier: PropTypes.string }),
};

/** "SAT 12" — the dropdown's day heading, from the app's own weekday abbreviation. */
function dayHeading(dateStr) {
  const dayNum = Number(dateStr.split('-')[2]);
  return `${calDow(dateStr)} ${dayNum}`.toUpperCase();
}

/**
 * am / pm / night / hw — the CSS colour-class key. Astro and Aurora share one colour (README table);
 * the daylight High-water row has its own, in the tide light (hot-topics-by-day-plan §3 M3).
 */
function kindClass(row) {
  if (row.kind === 'solar') return row.eventType === 'SUNRISE' ? 'am' : 'pm';
  if (row.kind === 'hw') return 'hw';
  return 'night';
}

const KIND_TEXT = {
  SUNRISE: 'Sunrise', SUNSET: 'Sunset', ASTRO: 'Astro', AURORA: 'Aurora', HIGH_WATER: 'High water',
};

/**
 * The neutral verdict word on the daylight High-water row: it is not a light window, so it carries no
 * tier, no tint and no region, only the fact that this is for big seas.
 */
function HighWaterVerdict() {
  return (
    <span className="wf-win-verdict wf-win-verdict-hw" data-testid="wf-win-verdict-hw">
      <span className="wf-win-verdict-line">
        <b className="wf-win-verdict-word">Big seas</b>
      </span>
    </span>
  );
}

/**
 * Exported since the map-mobile-sheet increment (§3 M2 task 5): the peek sheet's Windows section
 * draws the identical roster this dropdown does and must not mint a second kind-chip markup — the
 * kind-chip dedup rule this file already states for `dayLabel` applies to the chip itself too.
 */
export function KindChip({ row }) {
  // Reuses `.wf-hc-sun` — the day rail's own kind-chip class (matrix-axis plan D14) — rather than
  // minting a second chip vocabulary. `.am`/`.pm` already exist there; `.night` is this phase's one
  // addition (index.css), sharing the same `color-mix` idiom against the new astro tokens.
  return (
    <span className={`wf-hc-sun ${kindClass(row)}`}>
      {KIND_TEXT[row.eventType] || row.eventType}
    </span>
  );
}

KindChip.propTypes = {
  row: PropTypes.shape({
    kind: PropTypes.string,
    eventType: PropTypes.string,
  }).isRequired,
};

/** A handful of topic channels get a glyph in the dropdown; anything else is a plain dot. */
const CHANNEL_ICON = {
  tide: '\u{1F30A}',
  aurora: '\u{1F30C}',
  nlc: '✨',
  snow: '❄️',
  eclipse: '●',
  plain: '•',
};

/** One dropdown row — kind chip · label+time · `N★ best` with swatch · topic icons. */
function WindowRow({ row, active, onSelect, ratedCount = null }) {
  const icons = useMemo(() => {
    const seen = new Set();
    const out = [];
    for (const badge of row.badges || []) {
      const channel = badgeChannel(badge?.type);
      if (seen.has(channel)) continue;
      seen.add(channel);
      out.push(CHANNEL_ICON[channel] || CHANNEL_ICON.plain);
    }
    return out;
  }, [row.badges]);

  if (row.past) {
    return <PastWindowRow row={row} active={active} onSelect={onSelect} ratedCount={ratedCount} />;
  }

  return (
    <button
      type="button"
      data-testid="wf-win-row"
      data-ev-id={row.id}
      role="option"
      aria-selected={active}
      className={`wf-win-row${active ? ' on' : ''}`}
      // The bortle-only-roster caveat (README OPEN 1: "if the real astro score only exists for
      // dark-sky locations, the event row should say so") — a native tooltip rather than a second
      // line, since the row's four-column grid has no spare space for one.
      title={row.rosterNote || undefined}
      onClick={onSelect}
    >
      <KindChip row={row} />
      {' '}
      <span className="wf-win-row-main">
        {/* Day-only form beside the kind chip — see the collapsed pill above, including the
            same back-compat fallback. */}
        <b>{row.dayLabel ?? row.label}</b>
        {' '}
        <span className="wf-win-row-sub">
          {row.time && <span className="wf-win-row-time">{row.time}</span>}
          {' '}
          {/* Inside the label cell rather than as a fifth grid child: the row's
              `grid-template-columns` is a fixed four, and a conditional fifth child would leave the
              score and topic columns landing in different places on rows that have a pick and rows
              that do not. */}
          <Medallion kind={row.pickKind} />
        </span>
        {/* big-seas plan F1: the sr-only twin of the ⚡ in the topics cell below, which is
            aria-hidden like every topic icon — the row's name must carry the fact itself (the
            verdict-count pattern above). */}
        {/* The High-water row's chip already says "High water", so its clause is the bolt's own fact. */}
        {(row.bigSeasAligned || row.kind === 'hw') && <span className="sr-only">, big seas</span>}
      </span>
      <span className="wf-win-row-score">
        {row.scored ? (
          <>
            <i aria-hidden="true" style={{ background: rampHex(row.bestRating) }} />
            {row.bestRating}&#9733; best
          </>
        ) : (
          <span className="wf-win-row-unscored">&mdash;</span>
        )}
      </span>
      <span className="wf-win-row-topics" aria-hidden="true">
        {(row.bigSeasAligned || row.kind === 'hw') && <span className="wf-win-row-bigseas" data-testid="wf-win-row-bigseas"><BoltIcon /></span>}
        {icons.join('')}
      </span>
    </button>
  );
}

/**
 * What a PAST window says where a verdict would be: {@code Not rated} (ink-3) or {@code {n} rated}
 * (star gold), n being the number of locations the signed-in reader has rated for it, "didn't see
 * it" included (outcome-rating-plan.md §2 #2, §2 #6). ⚠️ <b>It cannot say "unknown", so it is not
 * drawn for unknown</b>: a null {@code ratedCount} (the date's ratings not read yet, a failed read,
 * a rewind, no ratings source at all) renders NOTHING, never a false {@code Not rated} — the caller's
 * {@code ratedCounts} map simply lacks the row's id. A zero is a fact and reads {@code Not rated}.
 * Shared with the phone peek sheet's Windows section.
 */
export function RatedCell({ ratedCount = null }) {
  if (ratedCount == null) return null;
  return ratedCount > 0
    ? <span className="wf-win-rated" data-testid="wf-win-rated">{`${ratedCount} rated`}</span>
    : <span className="wf-win-notrated" data-testid="wf-win-notrated">Not rated</span>;
}

RatedCell.propTypes = { ratedCount: PropTypes.number };

/**
 * One PAST row in the EARLIER group (outcome-rating-plan.md §2 #2): kind chip, the time in bold
 * ink-2 (the prototype's {@code .wr.past .wm b}), and {@code Not rated} / {@code {n} rated} on the
 * right. The day is the sub-header's, so the row carries it only in words for a screen reader
 * (an option's name is its content, and the sub-header is not inside it). No pick, no topics, no
 * score: a past window is `served: false` and states none of those.
 */
function PastWindowRow({ row, active, onSelect, ratedCount }) {
  return (
    <button
      type="button"
      data-testid="wf-win-row"
      data-ev-id={row.id}
      data-past="true"
      role="option"
      aria-selected={active}
      className={`wf-win-row wf-win-row-past${active ? ' on' : ''}`}
      onClick={onSelect}
    >
      <KindChip row={row} />
      {' '}
      <span className="wf-win-row-main">
        <b className="wf-win-row-pasttime">
          <span className="sr-only">{`${row.dayLabel ?? row.label} `}</span>
          {row.time}
        </b>
      </span>
      <span className="wf-win-row-score">
        <RatedCell ratedCount={ratedCount} />
      </span>
      <span className="wf-win-row-topics" aria-hidden="true" />
    </button>
  );
}

PastWindowRow.propTypes = {
  row: PropTypes.shape({
    id: PropTypes.string.isRequired,
    label: PropTypes.string.isRequired,
    dayLabel: PropTypes.string,
    time: PropTypes.string,
  }).isRequired,
  active: PropTypes.bool.isRequired,
  onSelect: PropTypes.func.isRequired,
  ratedCount: PropTypes.number,
};

WindowRow.propTypes = {
  row: PropTypes.shape({
    id: PropTypes.string.isRequired,
    label: PropTypes.string.isRequired,
    /** See `events[].dayLabel` above — same optional-with-fallback contract. */
    dayLabel: PropTypes.string,
    time: PropTypes.string,
    bestRating: PropTypes.number,
    scored: PropTypes.bool,
    /** A window that has gone — drawn by {@link PastWindowRow}, in the EARLIER group. */
    past: PropTypes.bool,
    badges: PropTypes.array,
    rosterNote: PropTypes.string,
    /** `'best'` / `'also'` when this window is one of the forecast's two served picks. */
    pickKind: PropTypes.oneOf(['best', 'also']),
    /** The served big-seas high water lands on this event (big-seas plan F1): draws the ⚡. */
    bigSeasAligned: PropTypes.bool,
    /** `'hw'` for the daylight High-water row (draws the ⚡ and says "big seas, high water"). */
    kind: PropTypes.string,
  }).isRequired,
  active: PropTypes.bool.isRequired,
  onSelect: PropTypes.func.isRequired,
  /** The count a PAST row's `{n} rated` reads: 0 prints `Not rated`, null (unknown) prints nothing. */
  ratedCount: PropTypes.number,
};
