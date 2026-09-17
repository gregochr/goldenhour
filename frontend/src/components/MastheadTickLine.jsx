import React, { useCallback, useLayoutEffect, useRef } from 'react';
import PropTypes from 'prop-types';
import { GOLDEN } from './shared/MastheadLight.jsx';
import { watchDeparture } from '../utils/watchDeparture.js';

/**
 * The same amber as a Tailwind class, for the nudge link's hover.
 *
 * <p>Imported beside {@link GOLDEN} rather than re-declared. Tailwind's scanner reads raw source
 * text and cannot follow a template literal, so an arbitrary-value class has to contain the hex —
 * the duplication is
 * forced by the toolchain, not chosen. Keeping the two literals one line apart is what stops them
 * drifting, and `mastheadColours.test.js` asserts they are equal ACROSS the two files.
 */
const GOLDEN_HOVER = 'hover:text-[#E0A542]';

/**
 * One clock time in the row, carrying two different words for two different readers.
 *
 * <p><b>Assistive technology gets the EVENT; sighted readers get the KIND.</b> The rule above is
 * `aria-hidden`, so this row is the entire accessible answer — and the kind alone does not answer
 * it: `golden` is the same word for sunrise and for sunset, so a screen reader heard
 * "05:32 blue, 06:04 golden, 19:58 golden, 20:31 blue" and the only thing separating morning from
 * evening was DOM order, which is exactly the positional cue the hidden gradient was carrying. The
 * event name is announced at every width; the kind stays the visible label, because on screen the
 * amber and the left-to-right order already say which is which.
 */
function LightTime({ time, kind, event, className = '' }) {
  const isGolden = kind === 'golden';
  return (
    <span
      data-testid={`masthead-light-${kind}`}
      className={`whitespace-nowrap ${className} ${isGolden ? 'font-medium' : ''}`}
      style={isGolden ? { color: GOLDEN } : undefined}
    >
      {time}
      <span className="sr-only">{` ${event}`}</span>
      {/* Visible from tablet up; the phone drops it for room. `aria-hidden` so it does not stack a
          second, vaguer word behind the event name above. */}
      <span aria-hidden="true" className="hidden sm:inline">{` ${kind}`}</span>
    </span>
  );
}

LightTime.propTypes = {
  time: PropTypes.string.isRequired,
  kind: PropTypes.oneOf(['blue', 'golden']).isRequired,
  event: PropTypes.oneOf(['dawn', 'sunrise', 'sunset', 'dusk']).isRequired,
  className: PropTypes.string,
};

/**
 * The two pins, drawn rather than typed.
 *
 * <p>The bundle's own asset note — "every glyph is either a small inline SVG (home pin, map pin,
 * search) or a text character" — and the pins are the two it names first. The chip this line
 * replaces used `⌂` and `◉`, which is a house and a fisheye: at 9px the second reads as a bullet
 * and carried the whole away/home distinction on a character most fonts draw differently. Both are
 * {@code aria-hidden} and {@code focusable="false"} (IE/Edge legacy still tab-stops an SVG without
 * it): the button's accessible name spells the state out in words, and a reader must not hear
 * "house pin Keswick".
 */
function Pin({ away }) {
  return away ? (
    <svg className="wf-tick-pin" viewBox="0 0 24 24" width="13" height="13"
      aria-hidden="true" focusable="false">
      <path
        fill="currentColor"
        d="M12 2a7 7 0 0 0-7 7c0 5.2 7 13 7 13s7-7.8 7-13a7 7 0 0 0-7-7Zm0 9.6A2.6 2.6 0 1 1 12 6.4a2.6 2.6 0 0 1 0 5.2Z"
      />
    </svg>
  ) : (
    <svg className="wf-tick-pin" viewBox="0 0 24 24" width="13" height="13"
      aria-hidden="true" focusable="false">
      <path fill="currentColor" d="M12 3 3 10.6V21h6.2v-5.8h5.6V21H21V10.6Z" />
    </svg>
  );
}

Pin.propTypes = { away: PropTypes.bool.isRequired };

/**
 * The masthead's tick line — where the plan is computed from, how to change it, and today's light.
 *
 * <p>The bundle calls this "the <b>only</b> statement of where the plan is computed from; there is
 * no separate origin chip or breadcrumb anywhere in the tab", and that is what it replaces: the
 * rail footer's {@code PlanOriginChip}, its "Home not set" line, its "Edit reach" link and its
 * forecast age all went in the same commit (plan-matrix §6 M3.5). Three of those four are
 * relocations rather than deletions — Edit reach onto the ⚙ path it already opened, the age beside
 * the change line so the page states one, and the home prompt into this line's own empty state.
 *
 * <h2>Three states, and they are the light's, not this line's</h2>
 *
 * <p>{@code light} still carries the whole of {@link MastheadLight}'s three-way distinction and
 * this component honours it unchanged: {@code undefined} is "no answer yet" and says nothing;
 * {@code null} is "answered, no home saved" and nudges; an object is the day. ⚠️ <b>A failed light
 * fetch is {@code undefined}, not {@code null}</b> — {@code useTodaysLight} maps it that way on
 * purpose, because "you have not set a postcode" is a claim about the reader's account that a 502
 * is no evidence for. Nothing here may collapse the two.
 *
 * <h2>Search is offered only where a handler is — the Plan tab</h2>
 *
 * <p>Two controls here open search: the origin button and the ⌕ beside it. Both render only when
 * {@code onOpenSearch} is handed over, and the shell hands it over on the Plan tab alone. Everything
 * search finds is a Plan object (a window, a region to plan from, a place's four-day sheet), and
 * until 2026-09-16 a pick made on Coming up opened a Plan dialog over the feed. With no handler the
 * origin is drawn as a non-interactive STATEMENT in the button's place and the ⌕ is not drawn at all:
 * withheld, never rendered with nothing behind it, because plan-matrix §3 rule 14 bans a control with
 * no visible effect.
 *
 * <p>The statement was the Map tab's first (map-tab-v2-plan.md §3 P11: "on a map, panning IS the
 * search"), and {@code isMapTab} keeps that rule as its own — it withholds search even from a caller
 * that hands a handler over — and adds the one thing the other tabs' statement does not draw, the
 * "drive times from here" caption. The shell never hands the Map tab a handler, so in the app the
 * two reasons agree; they are kept apart because they are two decisions, and the Map's must not
 * start depending on which tabs the shell happens to search from.
 *
 * <h2>The nudge is the origin button's empty state, and it is a different control</h2>
 *
 * <p>In every other state the origin button opens search (on the Plan tab; elsewhere it is the
 * statement). With no home saved it opens the postcode field instead, on every tab, because that is
 * the one thing a reader in that state needs and a button labelled "set a postcode" that opened a
 * search box would be a control whose label lies. Search does not become unreachable on the Plan tab:
 * the ⌕ beside it and the {@code /} key both still open it, which is why the two are separate buttons
 * here rather than one.
 *
 * <p>⚠️ <b>{@code homePlace} is the authority and {@code light} is only consulted while it is
 * unknown</b>, which is narrower than the OR this shipped with and had to be. The two arrive on
 * separate requests and {@code useTodaysLight} never resets {@code light} to {@code undefined}
 * while it refetches — so on the round trip after a reader SAVES a postcode, settings resolve first
 * and the light is still holding its previous {@code null}. Under an OR the tick line replaced
 * their new home with "set a postcode" at the moment they acted on it. Worse permanently: a saved
 * postcode that failed to geocode leaves {@code homePlace} non-null and {@code /light} answering
 * 204 forever, and the origin control would never render for that account. {@code undefined} from
 * either source is still unknown, so neither arm can turn a dropped request into a nag.
 *
 * <h2>Whose light the times are</h2>
 *
 * <p>The times are always the reader's <b>home</b> light — {@code /api/user/settings/light} is
 * keyed on the saved postcode and an away origin does not move it. At home the origin button says
 * the place two elements to the left, so the label is spoken and not drawn; <b>away it is drawn</b>,
 * because otherwise a row reading "The Lake District · from Keswick   05:40 golden" attributes
 * Durham's sunrise to Cumbria, and the 20–30 minute spread across this country is exactly the size
 * that makes the claim wrong rather than merely imprecise. This is the same rule
 * {@link MastheadLight} has always carried ("an unlabelled gradient is a guess wearing data's
 * clothes"), with the drawn half now conditional because the line names the place elsewhere.
 *
 * <p>⚠️ It is {@code label}, never {@code shortLabel}, in <b>both</b> channels. The backend
 * documents the short form as the label "reduced to what fits a phone" — a bare postcode — and the
 * word it drops is "Home", which is the entire content of the attribution. Drawing the short form
 * away would have put `NE66 1NG` beside a Cumbrian origin and said nothing about whose it was.
 *
 * <h2>⚠️ The origin slot keeps the reader's focus when its element is swapped, or ⌂ goes</h2>
 *
 * <p>The slot holds one of three elements — the nudge, the Map tab's statement, the origin button
 * — and on the Map tab the move from the first to the second is a DIFFERENT element: a
 * {@code <button>} replaced by a {@code <span>}. Elsewhere React reuses the button node and focus
 * rides it; here the focused node is destroyed and focus falls to {@code <body>}, where no ring
 * shows and a screen reader can lose its place. The route is the nudge's own purpose: press "set a
 * postcode" and save one in the settings dialog. The page takes the new home from the save's own
 * response, so the nudge is replaced while the dialog is still open, and the dialog's recorded
 * opener is gone by the time it closes (the last paragraph below). The same swap can also land
 * while the nudge itself holds focus — the dialog closed before its own settings read answered, and
 * the answer names a home saved elsewhere — which is the handoff this section describes first.
 *
 * <p>So the statement is a programmatic focus target ({@code tabIndex={-1}}: focusable, never a tab
 * stop, so "panning IS the search" still holds for the Tab order), and a swap hands focus from the
 * departing element to its replacement — but only when the departing one HELD focus. That is
 * decided in the ref's cleanup by `utils/watchDeparture.js`, whose doc says why a ref cleanup and
 * never focus events. A "focus is nowhere after a swap" test was the obvious alternative and is
 * wrong here: a tab switch swaps this slot too, and the shell moves focus deliberately around one
 * (its `tabRequest` handoff, which arrives with focus wherever a closing dialog left it).
 *
 * <p><b>⌂ is the same defect from outside the slot, and ends in the same place.</b> It renders
 * only while away, and its press returns the origin home — so the press destroys the very node it
 * was made on, on every tab, and the reader landed on {@code <body>}. It is watched for leaving
 * exactly as the slot's elements are — recorded only when it HELD focus, and undone on StrictMode's
 * re-run — and the handoff puts the reader on whatever the slot holds once home: the origin button,
 * the statement, or the nudge when no home is saved. That element sits beside ⌂ in the same flex
 * item, and it is the one that now says what the press did ("Home · Durham").
 *
 * <p>⚠️ <b>It lands BEFORE a closing dialog's own restore, and that is deliberate.</b> The shell's
 * {@code onGoHome} also closes the window popup, and ⌂ is reachable from inside it: the popup holds
 * no trap and leaves this row in the Tab order. On that route the popup's {@code useDialogFocus}
 * cleanup, a passive effect, used to find focus nowhere and put the reader back on the card that
 * opened the popup. The handoff is a layout effect, so it runs first; the restore then finds focus
 * somewhere real, with no layer left claiming modality, and stands down. An owner decision
 * (2026-09-16): the reader had left the dialog and acted in the masthead, so the popup route ends
 * on the origin control too. Moving the handoff to a passive effect would reverse it, and
 * `planOriginShell.test.jsx`'s popup test is what says so. ⚠️ <b>Not while `App`'s map overlay or
 * settings dialog is ALSO open</b> — the overlay opened over the popup, or the popup opened behind
 * settings, both routes CLAUDE.md lists as open. That layer still claims modality, so the restore
 * reads the origin control as stranded and returns the reader to the card, exactly as before this
 * handoff existed (measured in jsdom through the real `App`, and in Chromium, WebKit and Firefox on
 * a harness with the real `MapOverlay` and `Modal`).
 *
 * <p>⚠️ <b>It lands on a control the next key can press, and no gate on the input device is
 * safe.</b> Chromium and Firefox focus a button on a mouse click, so a pointer press on ⌂ hands
 * focus on too, with no ring drawn: a Space or Enter straight after it opens search (or, from the
 * nudge, settings), where it used to scroll the page or do nothing. Holding Enter does the same by
 * key repeat, in every engine. Both are a class the app already ships — a popup closed by a mouse
 * click returns focus to its card, and Space reopens it. The tells for "that was a mouse" are
 * refused: Chromium reports a screen-reader or voice-control press as a click with {@code detail}
 * 1 and {@code pointerType} "mouse", Firefox gives it {@code detail} 1, a touch tap has
 * {@code detail} 1 too, and Chromium focuses that press as a mouse focus, so {@code :focus-visible}
 * would likely miss it as well — engine source read for the first two, the tap measured, the last
 * inferred (jsdom cannot check it: its {@code :focus-visible} is its selector engine's heuristic
 * over the key, mouse and focus events it has recorded, not a browser's). A gate on any of them
 * would put the readers this handoff exists for back on {@code <body>}.
 *
 * <p>The nudge also hands its caller a way to FIND the slot later ({@code onSetPostcode}'s
 * argument), for the ordinary order: a save moves the home while the dialog is still open, the
 * nudge is replaced under the dialog, the dialog's recorded opener is detached by the time it
 * closes, and it needs somewhere else to put the reader. See `App`'s settings mount.
 *
 * @param {object}    props
 * @param {object|null|undefined} [props.light] the day's light — see the three states above
 * @param {?object}   props.origin      the away origin ({@code {name, baseName}}), or null for home
 * @param {?string}   [props.homePlace] the reader's home place; {@code undefined} while unknown
 * @param {Function}  [props.onOpenSearch] opens the search dialog; absent on every tab but Plan,
 *        which withholds both search controls (see the class comment)
 * @param {Function}  props.onGoHome      returns the origin to home
 * @param {Function}  props.onSetPostcode opens settings on the home-postcode field; called with a
 *        function that returns the origin slot's CURRENT element — the nudge's successor, once the
 *        home is saved — for the dialog to restore focus to if the nudge itself is gone
 */
export default function MastheadTickLine({
  light, origin, homePlace, onOpenSearch, onGoHome, onSetPostcode, searchOpen = false,
  isMapTab = false,
}) {
  // Whichever of the three elements the origin slot holds now. See the class comment's last
  // section: the handoff below and the nudge's resolver both read it.
  const originSlot = useRef(null);
  // The element that left the line while holding focus — the slot's departing element, or ⌂ —
  // set by a ref cleanup (`watchDeparture`), spent by the layout effect below.
  const departed = useRef(null);
  const trackOriginSlot = useCallback((node) => {
    if (!node) return undefined;
    originSlot.current = node;
    const leave = watchDeparture(departed, node);
    return () => {
      leave();
      if (originSlot.current === node) originSlot.current = null;
    };
  }, []);
  // ⌂ is not in the slot, and never a target: it only departs. It hands focus to the same place as
  // a swapped slot element, and only on the same condition — see the class comment.
  const trackHome = useCallback((node) => (node ? watchDeparture(departed, node) : undefined), []);
  // Every commit, but it acts only in the one that took a focused element out — a swapped slot
  // element or ⌂ — and spends the record either way. The slot's element is attached by then — refs
  // attach before layout effects run. And only while focus is still nowhere: nothing in this commit
  // should have placed it, but a reader's position is never taken. ⚠️ A LAYOUT effect, and for ⌂
  // that is load-bearing: see the class comment on the popup's passive restore.
  useLayoutEffect(() => {
    if (!departed.current) return;
    departed.current = null;
    const focused = document.activeElement;
    const nowhere = !focused || focused === document.body || focused === document.documentElement;
    if (nowhere) originSlot.current?.focus();
  });
  const originSlotNow = useCallback(() => originSlot.current, []);

  const away = Boolean(origin);
  // See the class comment: either positive answer, never an absent one, and never while away —
  // a reader planning from a region is not planning from a postcode, and the prompt would be
  // about nothing they can see. (The rail footer's "Home not set" line withheld it identically.)
  const noHome = !away && (homePlace === null || (homePlace === undefined && light === null));
  // Whether this row offers search at all — the one question both search controls answer from. Two
  // reasons it may not, and either is enough: no handler was handed over (every tab but Plan — see
  // the class comment), or this is the Map tab, where panning IS the search whatever a caller passes.
  const searchable = typeof onOpenSearch === 'function' && !isMapTab;
  // The statement, drawn INSTEAD of the interactive origin button wherever there is no search to
  // open — never instead of the empty-state nudge, which stays exactly as it is on every tab
  // (CLAUDE.md's do-not-re-gate-the-postcode rule: the band's empty state nudges the reader to this
  // field, and a dead statement there would make the nudge a dead end).
  const statement = !searchable && !noHome;
  const originLabel = away
    ? `${origin.name} · from ${origin.baseName}`
    : (homePlace ? `Home · ${homePlace}` : 'Home');

  return (
    <div data-testid="window-first-tickline" className="wf-tick">
      {/* ⚠️ The bordered group and the ⌂ beside it are ONE flex item, which is a wrap fix rather
          than a wrapper for its own sake. `.wf-tick` wraps (it must — an away label names two
          places and a phone has 330px), and a flex line breaks on hypothetical main sizes, so as
          siblings the ⌂ was pushed to the next line on its own and landed beside the clock times,
          detached from the origin it undoes. `PlanOriginChip` held the same pair inside one
          `inline-flex` for the same reason; the port flattened it and the guarantee went with it. */}
      <span className="wf-tick-origin-set">
        <span
          className="wf-tick-group"
          data-away={away ? 'true' : 'false'}
          data-statement={statement ? 'true' : 'false'}
        >
          {noHome ? (
            <button
              type="button"
              ref={trackOriginSlot}
              onClick={() => onSetPostcode(originSlotNow)}
              data-testid="masthead-set-postcode"
              tabIndex={searchOpen ? -1 : undefined}
              // ⚠️ The name is the LONG visible form, and it must stay a superstring of the short one
              // (WCAG 2.5.3). This shipped as "Set postcode" — carried over from the row this
              // replaced, whose visible words genuinely were "Set postcode"/"Set" — and the copy
              // changed underneath it, so neither rendered string appeared in the name and a
              // speech-input reader saying what they could see hit nothing.
              aria-label="Set a postcode for light and drive times"
              className={`wf-tick-origin wf-tick-nudge ${GOLDEN_HOVER}`}
            >
              <Pin away={false} />
              <span aria-hidden="true" className="wf-tick-place">
                <span className="hidden sm:inline">Set a postcode for light and drive times</span>
                <span className="sm:hidden">Set a postcode</span>
              </span>
            </button>
          ) : statement ? (
            // The statement (map-tab-v2-plan.md §3 P11, README "Masthead change", and every tab but
            // Plan since 2026-09-16 — see the class comment). A `<span>`, never a `<button>`, so it
            // needs none of WCAG 2.5.3's accname machinery the interactive arm below carries: a
            // non-interactive element's accessible name is just its rendered text, which is already
            // exactly what a reader sees. The caption is real content, not decoration, so it is a
            // plain visible text node rather than `aria-hidden` — only the SVG pin glyph is hidden.
            //
            // `tabIndex={-1}` makes it a place focus can be PUT, never one Tab reaches: it is where
            // the reader lands when the nudge they pressed is replaced by this (see the class
            // comment). It carries `.wf-tick-origin`, so a keyboard landing draws that rule's ring.
            <span
              ref={trackOriginSlot}
              tabIndex={-1}
              data-testid="window-first-origin-statement"
              className="wf-tick-origin"
            >
              <Pin away={away} />
              <span className="wf-tick-place">{originLabel}</span>
              {/* The Map tab's alone: every drive time and leave-by on the map is measured from this
                  place. Coming up and Operations show no drive time, so there it would be a claim
                  about nothing. */}
              {isMapTab && (
                <span data-testid="masthead-origin-caption" className="wf-tick-caption">
                  drive times from here
                </span>
              )}
            </span>
          ) : (
            <button
              type="button"
              ref={trackOriginSlot}
              onClick={onOpenSearch}
              data-testid="window-first-origin-chip"
              data-away={away ? 'true' : 'false'}
              className="wf-tick-origin"
              tabIndex={searchOpen ? -1 : undefined}
              // ⚠️ The name must contain the visible words IN THE ORDER THEY ARE DRAWN (WCAG 2.5.3).
              // The away arm was inherited from the chip this replaces, where the visible text was
              // the base town alone; M3 draws `<Region> · from <base>`, so a name reading "Planning
              // from Keswick in The Lake District" transposed the two and a speech-input reader
              // dictating what they could see matched nothing. Without the interpolation at all the
              // home arm had the same defect one step worse — it drew `Home · Durham` under a name of
              // "Planning from home", so "Durham" appeared in no accessible name anywhere.
              aria-label={away
                ? `Planning from ${origin.name}, from ${origin.baseName}. Search to change it.`
                : (homePlace
                  ? `Planning from home · ${homePlace}. Search to change it.`
                  : 'Planning from home. Search to change it.')}
            >
              <Pin away={away} />
              {/* `.wf-tick-place` carries the truncation, not the button: `text-overflow` applies to
                  block containers and the button is a flex container — the span blockifies as a flex
                  item, which is what makes the idiom work here and not one level up. */}
              <span aria-hidden="true" className="wf-tick-place">{originLabel}</span>
            </button>
          )}
          {/* Full-height hairline between the two controls, exactly as the bundle draws it. Purely
              visual: the two buttons are already separate tab stops with separate names.
              ⚠️ Omitted with the ⌕ wherever this row offers no search — every tab but Plan. The
              Map tab withheld it first, by its own rule (README "Masthead change": "the ⌕ search
              button is absent", because panning IS the search there); Coming up and Operations
              followed on 2026-09-16 through a missing handler, because a pick from a search opened
              there put a Plan dialog over that tab. */}
          {searchable && (
            <>
              <span aria-hidden="true" className="wf-tick-sep" />
              <button
                type="button"
                onClick={onOpenSearch}
                data-testid="window-first-search"
                className="wf-tick-search"
                tabIndex={searchOpen ? -1 : undefined}
                // Named for what it searches, matching the dialog's own accessible name, so a reader
                // who opens it hears the same words the button promised.
                aria-label="Search days, regions and places"
              >
                <span aria-hidden="true" className="wf-tick-glyph">⌕</span>
                {/* Hidden on a phone, which has no keyboard to press it with. `aria-hidden` because
                    the shortcut is an affordance for sighted pointer users; a screen-reader user is
                    told nothing useful by hearing "slash". */}
                <kbd aria-hidden="true" className="wf-tick-kbd">/</kbd>
              </button>
            </>
          )}
        </span>

        {/* ⚠️ Its own press removes it — going home ends "away" — so it is watched for leaving with
            focus, and the reader is handed to the origin slot rather than dropped on `<body>` (the
            class comment's last section). */}
        {away && (
          <button
            type="button"
            ref={trackHome}
            data-testid="window-first-origin-home"
            className="wf-tick-home"
            onClick={onGoHome}
            tabIndex={searchOpen ? -1 : undefined}
            // Named for what it does rather than for the glyph, and it names the destination because
            // "back" alone is meaningless out of context in a list of links.
            aria-label="Plan from home again"
          >
            <Pin away={false} />
          </button>
        )}
      </span>

      {light && (
        <span data-testid="masthead-light-times" className="wf-tick-times">
          {/* Drawn only when away — see the class comment. `sr-only` rather than absent at home,
              because the rule above is `aria-hidden` and this row is still the entire accessible
              answer to "whose light is this". */}
          <span
            data-testid="masthead-light-label"
            className={away ? 'wf-tick-lbl' : 'sr-only'}
          >
            {light.label}
          </span>
          {/* The blue hours are the pair that goes first when the row runs out of room: they
              bracket the goldens, so dropping them narrows the row without losing its shape. The
              boundary is `md` rather than the `lg` this row used before the origin button joined
              it — the bundle asks for all four on iPad (834px) and two on a phone, and 768px is
              the breakpoint that gives exactly that with the widest safety margin at 640–767. */}
          <LightTime time={light.civilDawn} kind="blue" event="dawn" className="hidden md:inline" />
          <LightTime time={light.sunrise} kind="golden" event="sunrise" />
          <LightTime time={light.sunset} kind="golden" event="sunset" />
          <LightTime time={light.civilDusk} kind="blue" event="dusk" className="hidden md:inline" />
        </span>
      )}
    </div>
  );
}

MastheadTickLine.propTypes = {
  light: PropTypes.shape({
    label: PropTypes.string.isRequired,
    shortLabel: PropTypes.string.isRequired,
    civilDawn: PropTypes.string.isRequired,
    sunrise: PropTypes.string.isRequired,
    sunset: PropTypes.string.isRequired,
    civilDusk: PropTypes.string.isRequired,
  }),
  origin: PropTypes.shape({
    name: PropTypes.string.isRequired,
    baseName: PropTypes.string.isRequired,
  }),
  homePlace: PropTypes.string,
  /**
   * Opens search. Absent means this row offers none: the origin is a statement and the ⌕ is not
   * drawn. The shell hands it over on the Plan tab only; see the class comment. (`isMapTab` withholds
   * search on its own too.)
   */
  onOpenSearch: PropTypes.func,
  onGoHome: PropTypes.func.isRequired,
  onSetPostcode: PropTypes.func.isRequired,
  /**
   * Whether the search panel is open OVER this row (M3.3).
   *
   * <p>⚠️ It takes the row's three controls out of the TAB ORDER and nothing else. The anchored
   * panel is opaque and covers this row exactly, so a keyboard reader who tabbed past the search
   * input landed on a control they could not see — WCAG 2.4.11 (Focus Not Obscured), which the
   * centred box this replaced did not breach because it merely dimmed them. `tabIndex={-1}` rather
   * than `aria-hidden` or `inert`: the first would hide focusable content from assistive tech
   * without stopping focus reaching it, and the second is absent from this project's jsdom (see
   * `useDialogFocus`), so it would fail as a silent no-op.
   */
  searchOpen: PropTypes.bool,
  /**
   * Whether the Map tab is the active tab (map-tab-v2-plan.md §3 P11) — a per-tab STATE of this
   * component, not a fork: the origin control renders as a non-interactive statement (pin, place,
   * caption) and the `⌕` search button is withheld, because on a map panning IS the search — whatever
   * `onOpenSearch` says. ⚠️ Since 2026-09-16 the statement is not the Map's alone: a missing
   * `onOpenSearch` draws it on every other tab but Plan too, without the caption, which stays this
   * prop's. The empty-state nudge (`noHome`) is unaffected on every tab — see `statement`'s derivation.
   */
  isMapTab: PropTypes.bool,
};
