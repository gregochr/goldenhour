import React from 'react';
import PropTypes from 'prop-types';
import GuideLink from './guide/GuideLink.jsx';
import { useWarnings } from '../context/WarningsContext.jsx';
import {
  cardMarker, levelClasses, staleLine, warningLabel,
} from '../utils/warnings.js';

/**
 * One Met Office weather warning as a text chip (weather-warnings-plan.md §12, §13).
 *
 * <p>Style guide v1.4: the official level colour with the guide's ink, the level word always printed (never colour
 * alone), no Met Office logo, no triangle or weather glyph. An ISSUED warning at version 2.0 or later carries the
 * server's {@code updated} flag and is labelled "Updated". The chip is a link to the Met Office warnings page and
 * opens nothing of ours (the detail popup is a later phase); with no URL it is plain text.
 *
 * <p>⚠️ It is never drawn in the language of a rating: no stars, no verdict colours, no ramp. A warning never changes
 * a rating and a rating never agrees or disagrees with one, so the two sit on separate lines and the chip is the only
 * thing in the level colours.
 *
 * @param {object} props
 * @param {object} props.warning a served {@code WarningDto}
 * @param {?string} [props.href] the Met Office page the chip links to
 */
export default function WarningChip({ warning, href = null }) {
  const label = warningLabel(warning);
  if (label == null) return null;
  const classes = `inline-flex flex-wrap items-baseline gap-x-1.5 rounded px-1.5 py-0.5 font-mono text-[11px] `
    + `font-semibold leading-tight no-underline ${levelClasses(warning.level)}`;
  const body = (
    <>
      <span>{label}</span>
      {warning.updated === true && (
        <span data-testid="warning-chip-updated" className="font-bold uppercase tracking-wide">
          <span className="sr-only">, </span>
          Updated
        </span>
      )}
    </>
  );
  if (!href) {
    return (
      <span data-testid="warning-chip" data-level={warning.level} className={classes}>{body}</span>
    );
  }
  return (
    <a
      data-testid="warning-chip"
      data-level={warning.level}
      className={`${classes} hover:underline focus-visible:outline focus-visible:outline-2 `
        + 'focus-visible:outline-offset-2 focus-visible:outline-plex-gold'}
      href={href}
      target="_blank"
      rel="noopener noreferrer"
    >
      {body}
      <span className="sr-only">, Met Office Weather Warnings (opens in a new tab)</span>
    </a>
  );
}

WarningChip.propTypes = {
  warning: PropTypes.shape({
    warningId: PropTypes.string,
    level: PropTypes.string,
    weatherTypes: PropTypes.arrayOf(PropTypes.string),
    updated: PropTypes.bool,
  }).isRequired,
  href: PropTypes.string,
};

/**
 * The chips for one row or card, most severe first. Renders nothing for no warnings.
 *
 * @param {object} props
 * @param {object[]} props.warnings served warnings, already in the style guide's order
 * @param {?string} [props.href] the Met Office page each chip links to
 * @param {string} [props.testId] the group's test id
 * @param {string} [props.className] extra classes for the group
 */
export function WarningChips({
  warnings, href = null, testId = 'warning-chips', className = '',
}) {
  if (!Array.isArray(warnings) || warnings.length === 0) return null;
  return (
    <div
      data-testid={testId}
      role="group"
      aria-label="Met Office weather warnings"
      className={`flex flex-wrap gap-1.5 ${className}`}
    >
      {warnings.map((warning) => (
        <WarningChip key={warning.warningId} warning={warning} href={href} />
      ))}
    </div>
  );
}

WarningChips.propTypes = {
  warnings: PropTypes.arrayOf(PropTypes.object),
  href: PropTypes.string,
  testId: PropTypes.string,
  className: PropTypes.string,
};

/** U+2060: forbids a line break on either side, so the {@code ?} door cannot be stranded from the link before it. */
const WORD_JOINER = '⁠';

/** A non-breaking space and the new-tab arrow, so the arrow never leaves the last word of the link. */
const NBSP_ARROW = ' ↗';

/**
 * The small source line beside warnings (map-warning-row-plan.md M3): the attribution the licence requires, which is
 * itself the link to the Met Office warnings page (new tab, followed by an arrow), then the guide's {@code ?} door.
 * When the feed is stale, a line of its own above says when it was last checked and that the warnings may be out of
 * date (plan section 8), so an empty list never reads as an all-clear. Render it wherever chips are, and where the feed
 * is stale even with none. The {@code ?} is the same on every surface, so the lane, popup, sheet, Coming up and Map
 * all open the same page; with a stale feed it opens the freshness section.
 *
 * <p>The attribution's last word, the arrow and the {@code ?} never part across a line (a word joiner holds the
 * {@code ?} to the link), so nothing is ever stranded. {@code guideAtEnd} instead pushes the {@code ?} to the far
 * right of a box-wide footer (the Map's).
 *
 * @param {object} props
 * @param {boolean} props.showAttribution whether warnings are shown here (attribution is owed with them)
 * @param {boolean} props.stale whether the feed is stale
 * @param {?string} props.lastCheckedAt the served last-checked instant
 * @param {string} props.attribution the served attribution line
 * @param {string} props.href the Met Office page
 * @param {string} [props.className] extra classes
 * @param {boolean} [props.guideAtEnd] pin the {@code ?} to the far right of a full-width footer
 */
export function WarningFooter({
  showAttribution, stale, lastCheckedAt, attribution, href, className = '', guideAtEnd = false,
}) {
  if (!showAttribution && !stale) return null;
  const guide = (
    <GuideLink
      section={stale ? 'warningsFresh' : 'warnings'}
      label={stale ? 'How fresh Met Office weather warnings are' : 'About Met Office weather warnings'}
      className="wf-warning-guide"
      testId="warning-guide-link"
    />
  );
  const cut = attribution.lastIndexOf(' ');
  const head = cut > 0 ? attribution.slice(0, cut + 1) : '';
  const last = cut > 0 ? attribution.slice(cut + 1) : attribution;
  return (
    <div
      data-testid="warning-footer"
      className={`font-mono text-[10px] leading-snug text-plex-text-secondary ${className}`}
    >
      {stale && (
        <p data-testid="warning-stale">
          {staleLine(lastCheckedAt)}
          {!showAttribution && guide}
        </p>
      )}
      {showAttribution && (
        <p
          data-testid="warning-attribution-line"
          className={guideAtEnd ? 'flex items-baseline justify-between gap-2' : undefined}
        >
          <span data-testid="warning-attribution">
            <a
              data-testid="warning-source-link"
              className="underline"
              href={href}
              target="_blank"
              rel="noopener noreferrer"
            >
              {head}
              <span className="whitespace-nowrap">
                {last}
                <span aria-hidden="true">{NBSP_ARROW}</span>
              </span>
              <span className="sr-only">, Met Office warnings page, opens in a new tab</span>
            </a>
            {!guideAtEnd && WORD_JOINER}
          </span>
          {guide}
        </p>
      )}
    </div>
  );
}

/**
 * {@link WarningFooter} over the served feed's own source line: the attribution, the Met Office link and the stale
 * admission, read from {@code useWarnings()} so a surface that draws a warning needs only to say whether it shows one.
 *
 * @param {object} props
 * @param {boolean} props.showAttribution whether warnings are shown here (attribution is owed with them)
 */
export function ServedWarningFooter({ showAttribution, guideAtEnd = false, className = '' }) {
  const served = useWarnings();
  return (
    <WarningFooter
      showAttribution={showAttribution}
      stale={served.stale}
      lastCheckedAt={served.lastCheckedAt}
      attribution={served.attribution}
      href={served.metOfficeUrl}
      guideAtEnd={guideAtEnd}
      className={className}
    />
  );
}

ServedWarningFooter.propTypes = {
  showAttribution: PropTypes.bool.isRequired,
  guideAtEnd: PropTypes.bool,
  className: PropTypes.string,
};

WarningFooter.propTypes = {
  showAttribution: PropTypes.bool.isRequired,
  stale: PropTypes.bool.isRequired,
  lastCheckedAt: PropTypes.string,
  attribution: PropTypes.string.isRequired,
  href: PropTypes.string.isRequired,
  className: PropTypes.string,
  guideAtEnd: PropTypes.bool,
};

/**
 * The text marker on a Plan window card (weather-warnings-plan.md W3; the plan's "card triangle" is text because the
 * style guide forbids a Met Office icon or triangle without agreement): the most severe level word in its official
 * colour, "+N" for more. It is purely visual: the card's one spoken sentence carries every warning's full phrase, so
 * the marker is {@code aria-hidden}, a link to nothing and never inside a rating.
 *
 * @param {object} props
 * @param {object[]} props.warnings the card's warnings in the style guide's order
 */
export function WarningCardMarker({ warnings }) {
  const marker = cardMarker(warnings);
  if (marker == null) return null;
  return (
    <span
      data-testid="wf-heat-warning"
      data-level={marker.level}
      aria-hidden="true"
      title={marker.name}
      className={`wf-hc-wm ${levelClasses(marker.level)}`}
    >
      <span className="wf-hc-wm-t">{marker.text}</span>
      {marker.count && <span className="wf-hc-wm-n">{marker.count}</span>}
    </span>
  );
}

WarningCardMarker.propTypes = {
  warnings: PropTypes.arrayOf(PropTypes.object).isRequired,
};
