import React from 'react';
import PropTypes from 'prop-types';

/**
 * The origin chip — where the whole Plan tab is being planned from, and the way to move it.
 *
 * <p>It replaces the rail-footer's {@code Home · <place>} line (plan §4.8), which stated the same
 * fact and could not be acted on. Two states: home, and one region. The away state takes the
 * design's blue treatment and gains a {@code ⌂} control back — a separate button rather than a
 * second click on the chip, because the chip's job stays "open search" in both states and a
 * control whose meaning flips with the state is the one a reader has to think about.
 *
 * <h2>The home state is not a "no origin set" prompt</h2>
 *
 * <p>Home is the origin, not the absence of one, so the chip reads {@code ⌂ Home} even for a
 * reader with no postcode saved — the planning area degrades to the whole roster in that case and
 * the strip's footer already says what that means. Naming the place when there is one is the
 * design's own copy; naming nothing when there is not keeps the chip from becoming a second
 * settings nag beside the masthead's, which is the one this arm has already been careful about.
 *
 * @param {object}    props
 * @param {?object}   props.origin     the away origin ({@code {name, baseName}}), or null for home
 * @param {?string}   [props.homePlace] the reader's home place name; {@code undefined} while unknown
 * @param {Function}  props.onOpenSearch opens the search dialog
 * @param {Function}  props.onGoHome     returns the origin to home
 */
export default function PlanOriginChip({ origin, homePlace, onOpenSearch, onGoHome }) {
  const away = Boolean(origin);
  return (
    <span className="wf-origin" data-testid="window-first-origin">
      <button
        type="button"
        data-testid="window-first-origin-chip"
        data-away={away ? 'true' : 'false'}
        className={`wf-origin-chip${away ? ' away' : ''}`}
        // The glyph is decorative: the accessible name below spells the state out, and a reader on
        // a screen reader must not hear "house pin Keswick".
        onClick={onOpenSearch}
        // ⚠️ The name must CONTAIN the visible words (WCAG 2.5.3), and the visible words include
        // the place. Without the interpolation the chip drew `Home · Durham` under a name of
        // "Planning from home", so "Durham" appeared in no accessible name at all — a regression
        // against the plain-text line this chip replaced, and one a speech-input reader saying
        // "click Home Durham" hits immediately.
        aria-label={away
          ? `Planning from ${origin.baseName} in ${origin.name}. Search to change it.`
          : (homePlace
            ? `Planning from home · ${homePlace}. Search to change it.`
            : 'Planning from home. Search to change it.')}
      >
        <span aria-hidden="true" className="wf-origin-glyph">{away ? '◉' : '⌂'}</span>
        {/* `.wf-origin-place` carries the truncation, not the chip: `text-overflow` applies to
            block containers, and the chip is a flex container — see the stylesheet. */}
        <span aria-hidden="true" className="wf-origin-place">
          {away ? origin.baseName : (homePlace ? `Home · ${homePlace}` : 'Home')}
        </span>
      </button>
      {away && (
        <button
          type="button"
          data-testid="window-first-origin-home"
          className="wf-origin-home"
          onClick={onGoHome}
          // Named for what it does rather than for the glyph, and it names the destination because
          // "back" alone is meaningless out of context in a list of links.
          aria-label="Plan from home again"
        >
          <span aria-hidden="true">⌂</span>
        </button>
      )}
    </span>
  );
}

PlanOriginChip.propTypes = {
  origin: PropTypes.shape({
    name: PropTypes.string.isRequired,
    baseName: PropTypes.string.isRequired,
  }),
  homePlace: PropTypes.string,
  onOpenSearch: PropTypes.func.isRequired,
  onGoHome: PropTypes.func.isRequired,
};
