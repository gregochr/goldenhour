import React from 'react';
import PropTypes from 'prop-types';
import GuideLink from './guide/GuideLink.jsx';
import { useWarnings } from '../context/WarningsContext.jsx';
import {
  cardMarker, levelClasses, staleLead, warningLabel,
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

/**
 * The small source line beside warnings: the attribution the licence requires and a link to the Met Office page,
 * and, when the feed is stale, the plain admission that the warnings may be out of date (plan §8) so an empty
 * list never reads as an all-clear. Render it wherever chips are, and where the feed is stale even with none. It ends
 * with the guide's {@code ?} door (the same on every surface, so the lane, popup, sheet, Coming up and Map all open
 * the same page; with a stale feed it opens the freshness section).
 *
 * @param {object} props
 * @param {boolean} props.showAttribution whether warnings are shown here (attribution is owed with them)
 * @param {boolean} props.stale whether the feed is stale
 * @param {?string} props.lastCheckedAt the served last-checked instant
 * @param {string} props.attribution the served attribution line
 * @param {string} props.href the Met Office page
 * @param {string} [props.className] extra classes
 */
export function WarningFooter({
  showAttribution, stale, lastCheckedAt, attribution, href, className = '',
}) {
  if (!showAttribution && !stale) return null;
  return (
    <p
      data-testid="warning-footer"
      className={`font-mono text-[10px] leading-snug text-plex-text-secondary ${className}`}
    >
      {stale && (
        <span data-testid="warning-stale">
          {`${staleLead(lastCheckedAt)} — `}
          <a
            data-testid="warning-stale-link"
            className="underline"
            href={href}
            target="_blank"
            rel="noopener noreferrer"
          >
            check the Met Office
          </a>
          {showAttribution ? '. ' : ''}
        </span>
      )}
      {showAttribution && (
        <span data-testid="warning-attribution">
          {`${attribution} · `}
          <a
            data-testid="warning-source-link"
            className="underline"
            href={href}
            target="_blank"
            rel="noopener noreferrer"
          >
            Read it on the Met Office site
          </a>
        </span>
      )}
      <GuideLink
        section={stale ? 'warningsFresh' : 'warnings'}
        label={stale ? 'How fresh Met Office weather warnings are' : 'About Met Office weather warnings'}
        className="wf-warning-guide"
        testId="warning-guide-link"
      />
    </p>
  );
}

/**
 * {@link WarningFooter} over the served feed's own source line: the attribution, the Met Office link and the stale
 * admission, read from {@code useWarnings()} so a surface that draws a warning needs only to say whether it shows one.
 *
 * @param {object} props
 * @param {boolean} props.showAttribution whether warnings are shown here (attribution is owed with them)
 */
export function ServedWarningFooter({ showAttribution }) {
  const served = useWarnings();
  return (
    <WarningFooter
      showAttribution={showAttribution}
      stale={served.stale}
      lastCheckedAt={served.lastCheckedAt}
      attribution={served.attribution}
      href={served.metOfficeUrl}
    />
  );
}

ServedWarningFooter.propTypes = { showAttribution: PropTypes.bool.isRequired };

WarningFooter.propTypes = {
  showAttribution: PropTypes.bool.isRequired,
  stale: PropTypes.bool.isRequired,
  lastCheckedAt: PropTypes.string,
  attribution: PropTypes.string.isRequired,
  href: PropTypes.string.isRequired,
  className: PropTypes.string,
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
