import { useCallback, useMemo, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import { rampHex } from '../../utils/scoreRamp.js';
import { calDow } from '../../utils/windowFirstStrip.js';
import { VERDICT_LABEL, badgeChannel } from '../../utils/windowFirstCards.js';
import { verdictRegionLabel } from '../../utils/mapVerdict.js';
import { useOutsideDismiss } from '../../hooks/useOutsideDismiss.js';

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
  verdicts = null, scopeIsArea = true, landingLabel = '', onReopenLanding = null,
  onOpenWindowPanel = null,
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
  /** The pill, so the reopen row can hand focus back to the control that opened the menu. */
  const pillRef = useRef(null);

  const active = activeIndex >= 0 && activeIndex < events.length ? events[activeIndex] : null;
  // Stepping from "nowhere" is ambiguous — the map is on a date/event the list has no row for.
  // The retired `wf-map-window` `<select>` this control absorbed put it best in its own comment:
  // "a real state and not an error", since `GET /api/forecast` reaches further than the
  // briefing's rendered horizon. Both ends are disabled rather than guessing which direction
  // "next" means.
  const atStart = !active || activeIndex <= 0;
  const atEnd = !active || activeIndex >= events.length - 1;

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
    return row ? (verdicts?.get(row.id) ?? null) : null;
  }
  const activeVerdict = active ? (verdicts?.get(active.id) ?? null) : null;

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

  // A press on the MAP never dismisses — `useOutsideDismiss` carries that rule for all four map
  // panels, so they cannot drift apart. No `enabled` gate here: this dropdown is not a
  // `BottomSheet` on any viewport, so it is never portalled outside `rootRef`.
  useOutsideDismiss({ open, rootRef, onDismiss: () => setOpen(false) });

  /** Grouped by date, in the list's own order — the list is already chronological. */
  const groups = useMemo(() => {
    const out = [];
    let lastDate = null;
    for (const row of events) {
      if (row.date !== lastDate) {
        out.push({ date: row.date, rows: [] });
        lastDate = row.date;
      }
      out[out.length - 1].rows.push(row);
    }
    return out;
  }, [events]);

  function selectRow(row) {
    setOpen(false);
    onSelect(row);
  }

  function step(delta) {
    const next = activeIndex + delta;
    if (next < 0 || next >= events.length) return;
    setOpen(false);
    onSelect(events[next]);
  }

  function onKeyDown(e) {
    if (e.key === 'ArrowLeft') {
      e.preventDefault();
      step(-1);
    } else if (e.key === 'ArrowRight') {
      e.preventDefault();
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
      onKeyDown={onKeyDown}
    >
      <button
        type="button"
        data-testid="wf-win-prev"
        aria-label={stepLabel('Previous event', atStart ? null : verdictAt(activeIndex - 1))}
        className="wf-win-step"
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
        aria-haspopup="listbox"
        aria-expanded={open}
        // ⚠️ Names the LISTBOX, not the popup box around it. Since L4 the popup holds the landing
        // card's reopen row beside the listbox, so `role="listbox"` moved to an inner element — and
        // a trigger declaring `aria-haspopup="listbox"` while `aria-controls` pointed at a generic
        // container left JAWS's "move to controlled element" landing on an unnamed div. The id and
        // the role belong on one element.
        aria-controls="wf-win-listbox"
        title={active?.rosterNote || undefined}
        onClick={() => setOpen((v) => !v)}
      >
        {active ? (
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
            {/* ⚠️ The bare spaces are load-bearing, not formatting. accname TRIMS each element's own
                contribution before concatenating, so sibling spans join with nothing between them —
                measured, this name read "20:28Also goodWorth iteverywhere in your area". With the
                stylesheet loaded the inline-flex boxes happen to insert spaces, which makes the name
                a side effect of a `display` value the phone rule already proves is volatile. */}
            {' '}
            <Medallion kind={active.pickKind} />
            {' '}
            <VerdictCell verdict={activeVerdict} scopeIsArea={scopeIsArea} />
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

      <button
        type="button"
        data-testid="wf-win-next"
        aria-label={stepLabel('Next event', atEnd ? null : verdictAt(activeIndex + 1))}
        className="wf-win-step"
        disabled={atEnd}
        onClick={() => step(1)}
      >
        &#x203A;
        <StepTick verdict={atEnd ? null : verdictAt(activeIndex + 1)} />
      </button>

      {open && (
        <div id="wf-win-menu" data-testid="wf-win-menu" className="wf-win-menu">
          {/* The way back into the landing card, above the windows it compares — dismissing it used
              to be irreversible, which quietly made closing it a risk (README §4 "Recoverable").
              `RegionsJump`'s own `wf-jump-reset` is the precedent: the way back lives in the
              control that caused it.

              ⚠️ It sits in the popup box but OUTSIDE the listbox below — see that element's note. */}
          {onReopenLanding && landingLabel && (
            <button
              type="button"
              data-testid="wf-win-landing"
              className="wf-win-row wf-win-landing"
              onClick={() => {
                setOpen(false);
                onReopenLanding();
                // ⚠️ This button unmounts itself (the row is withheld while the card is open), so
                // without this focus falls to `<body>` and a keyboard reader has to re-traverse the
                // masthead and the tab list — on the one control whose entire purpose is recovery.
                // Focus returns to the pill, which caused the menu and is still on screen.
                // `selectRow`'s identical pre-existing loss is untouched here.
                pillRef.current?.focus();
              }}
            >
              {/* ⚠️ ONE grid item, not two. `.wf-win-row` is `display: grid`, and CSS Grid wraps
                  each contiguous text run in an anonymous grid item — so a bare glyph span beside
                  bare text auto-placed onto two ROWS: measured `grid-template-rows: 18px 18px` and a
                  57px row against its siblings' 41px, with the glyph alone on a full-width line.
                  `RegionsJump`'s `.wf-jump-name` wraps glyph and text together for exactly this
                  reason; the first cut took that precedent's placement and not its markup.

                  ⚠️ **"Back to" is not decoration either.** The row shipped with the card's header
                  as its whole accessible name — a bare interrogative ("Tonight, or tomorrow?") in
                  NVDA's Elements List among "Previous event, Worth it" and the window rows, with
                  nothing saying it was a control or what it did. The precedent's own name is
                  "↺ Back to <region>"; this had taken the placement and dropped the verb.

                  ⚠️ The glyph is `↺`, matching that precedent, and deliberately NOT `◎` — which is
                  `PICK_TEXT.best`'s glyph, rendered on window rows a few pixels below in this same
                  popup. One glyph, two meanings, one menu. */}
              <span className="wf-win-landing-txt">
                <span aria-hidden="true">&#8634;{' '}</span>
                Back to
                {' '}
                {landingLabel}
              </span>
            </button>
          )}
          {/* ⚠️ The LISTBOX is this inner element, not the popup box above it. `role="listbox"`
              admits only `option` (and `group`) children, and the reopen row is neither — it
              chooses no window, so leaving it inside left one child a listbox-navigating screen
              reader could not reach by the roles the container promises. The popup box keeps the
              test-id and the class; the id moved here WITH the role, so the pill's `aria-controls`
              still names the listbox itself rather than a generic wrapper.

              ⚠️ **This does NOT make the listbox fully conforming, and an earlier revision of this
              comment implied it did.** The day-group wrappers below are `div`s with no role, each
              holding a text-bearing `.wf-win-day` heading — also neither `option` nor `group`. That
              is pre-existing, and it is left alone rather than quietly rolled into a landing-card
              commit; but it is the same class of problem, and this note must not be read as saying
              it was handled. */}
          <div role="listbox" id="wf-win-listbox" data-testid="wf-win-listbox" aria-label="Choose an event">
            {groups.map((group) => (
              <div key={group.date}>
                <div data-testid="wf-win-day" className="wf-win-day">
                  {dayHeading(group.date)}
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
              row and the landing card's reopen row are the same idiom at the other end of the list.

              ⚠️ Outside the listbox for the same reason the reopen row is: it selects no window. */}
          {onOpenWindowPanel && (
            <button
              type="button"
              data-testid="wf-win-more"
              className="wf-win-row wf-win-landing wf-win-more"
              onClick={() => {
                setOpen(false);
                onOpenWindowPanel();
                // ⚠️ **Not optional, and I made this exact mistake one row above before fixing it
                // there.** This button unmounts itself, so without this focus falls to `<body>` —
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
  /** Whether the scope segment reads "My area" — decides only the all-in-scope wording. */
  scopeIsArea: PropTypes.bool,
  /**
   * The landing card's own header text (map-landing-plan.md §3 L4 step 7). Derived ONCE by the
   * caller and handed to both surfaces, so this row can never name a different pair of windows from
   * the card it reopens. Empty while the card is open, or when it has nothing to show.
   */
  landingLabel: PropTypes.string,
  /** Reopens the landing card. Null withholds the row entirely — including on the overlay. */
  onReopenLanding: PropTypes.func,
  /** Opens the window panel on the row in force (map-landing-plan.md §3 L5). Null withholds it. */
  onOpenWindowPanel: PropTypes.func,
};

/**
 * The two picks' vocabulary — glyph and words, as SEPARATE elements (see {@link Medallion}).
 *
 * <p><b>Exported since map-landing L4</b>, because the landing card wears the same two picks and a
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
function VerdictCell({ verdict, scopeIsArea }) {
  if (!verdict) return null;
  const region = verdictRegionLabel(verdict, { scopeIsArea });
  return (
    <span className="wf-win-verdict" data-tier={verdict.tier} data-testid="wf-win-verdict">
      <b className="wf-win-verdict-word" data-testid="wf-win-verdict-word">{VERDICT_LABEL[verdict.tier] || VERDICT_LABEL.AWAITING}</b>
      {/* Same accname rule as the pill's own siblings: without this the word and the region read as
          one token ("Worth iteverywhere in your area"). */}
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

/** am / pm / night — the CSS colour-class key. Astro and Aurora share one colour (README table). */
function kindClass(row) {
  if (row.kind === 'solar') return row.eventType === 'SUNRISE' ? 'am' : 'pm';
  return 'night';
}

const KIND_TEXT = { SUNRISE: 'Sunrise', SUNSET: 'Sunset', ASTRO: 'Astro', AURORA: 'Aurora' };

function KindChip({ row }) {
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
function WindowRow({ row, active, onSelect }) {
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
        {icons.join('')}
      </span>
    </button>
  );
}

WindowRow.propTypes = {
  row: PropTypes.shape({
    id: PropTypes.string.isRequired,
    label: PropTypes.string.isRequired,
    /** See `events[].dayLabel` above — same optional-with-fallback contract. */
    dayLabel: PropTypes.string,
    time: PropTypes.string,
    bestRating: PropTypes.number,
    scored: PropTypes.bool,
    badges: PropTypes.array,
    rosterNote: PropTypes.string,
    /** `'best'` / `'also'` when this window is one of the forecast's two served picks. */
    pickKind: PropTypes.oneOf(['best', 'also']),
  }).isRequired,
  active: PropTypes.bool.isRequired,
  onSelect: PropTypes.func.isRequired,
};
