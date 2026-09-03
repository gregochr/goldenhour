import React from 'react';
import PropTypes from 'prop-types';
import { GOLDEN } from './shared/MastheadLight.jsx';

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
 * <h2>The nudge is the origin button's empty state, and it is a different control</h2>
 *
 * <p>In every other state the origin button opens search. With no home saved it opens the postcode
 * field instead, because that is the one thing a reader in that state needs and a button labelled
 * "set a postcode" that opened a search box would be a control whose label lies. Search does not
 * become unreachable: the ⌕ beside it and the {@code /} key both still open it, which is why the
 * two are separate buttons here rather than one.
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
 * @param {object}    props
 * @param {object|null|undefined} [props.light] the day's light — see the three states above
 * @param {?object}   props.origin      the away origin ({@code {name, baseName}}), or null for home
 * @param {?string}   [props.homePlace] the reader's home place; {@code undefined} while unknown
 * @param {Function}  props.onOpenSearch  opens the search dialog
 * @param {Function}  props.onGoHome      returns the origin to home
 * @param {Function}  props.onSetPostcode opens settings on the home-postcode field
 */
export default function MastheadTickLine({
  light, origin, homePlace, onOpenSearch, onGoHome, onSetPostcode, searchOpen = false,
  isMapTab = false,
}) {
  const away = Boolean(origin);
  // See the class comment: either positive answer, never an absent one, and never while away —
  // a reader planning from a region is not planning from a postcode, and the prompt would be
  // about nothing they can see. (The rail footer's "Home not set" line withheld it identically.)
  const noHome = !away && (homePlace === null || (homePlace === undefined && light === null));
  // The map tab's own statement, drawn INSTEAD of the interactive origin button — never instead of
  // the empty-state nudge, which stays exactly as it is on every tab (CLAUDE.md's do-not-re-gate-
  // the-postcode rule: the band's empty state nudges the reader to this field, and a dead statement
  // there would make the nudge a dead end).
  const statement = isMapTab && !noHome;
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
              onClick={onSetPostcode}
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
            // The map tab's own statement (map-tab-v2-plan.md §3 P11, README "Masthead change"):
            // "on a map, panning IS the search" — so this is a `<span>`, never a `<button>`, and
            // needs none of WCAG 2.5.3's accname machinery the interactive arm below carries: a
            // non-interactive element's accessible name is just its rendered text, which is already
            // exactly what a reader sees. The caption is real content, not decoration, so it is a
            // plain visible text node rather than `aria-hidden` — only the SVG pin glyph is hidden.
            <span data-testid="window-first-origin-statement" className="wf-tick-origin">
              <Pin away={away} />
              <span className="wf-tick-place">{originLabel}</span>
              <span data-testid="masthead-origin-caption" className="wf-tick-caption">
                drive times from here
              </span>
            </span>
          ) : (
            <button
              type="button"
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
              ⚠️ Map tab only omits — README "Masthead change": "the ⌕ search button is absent" —
              because panning IS the search there; every other tab keeps both untouched. */}
          {!isMapTab && (
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
                aria-label="Search windows, regions and locations"
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

        {away && (
          <button
            type="button"
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
  onOpenSearch: PropTypes.func.isRequired,
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
   * caption) and the `⌕` search button is withheld, because on a map panning IS the search. The
   * empty-state nudge (`noHome`) is unaffected on every tab — see `statement`'s own derivation.
   */
  isMapTab: PropTypes.bool,
};
