import React, { useMemo } from 'react';
import PropTypes from 'prop-types';
import { useWarnings } from '../context/WarningsContext.jsx';
import { placeCountLines, validityText, warningsForCard } from '../utils/warnings.js';
import WarningChip, { WarningFooter } from './WarningChip.jsx';
import WarningMatrix from './WarningMatrix.jsx';

/** The Met Office's own advice page, the only target the style guide allows for the optional link. */
export const WHAT_SHOULD_I_DO_URL = 'https://www.metoffice.gov.uk/weather/warnings-and-advice/seasonal-advice';

const BODY = 'whitespace-pre-line text-[12.5px] leading-snug text-plex-text';

/** A labelled block of Met Office text, shown whole and never truncated; nothing when the text is absent. */
function TextBlock({ title, testId, text = null, children = null }) {
  if (!text && !children) return null;
  return (
    <div data-testid={testId} className="flex flex-col gap-0.5">
      <h4 className="font-mono text-[10px] font-semibold uppercase tracking-wider text-plex-text-secondary">
        {title}
      </h4>
      {children ?? <p className={BODY}>{text}</p>}
    </div>
  );
}

TextBlock.propTypes = {
  title: PropTypes.string.isRequired,
  testId: PropTypes.string.isRequired,
  text: PropTypes.string,
  children: PropTypes.node,
};

/** One warning in full: the style guide's required text, status, validity, places and the matrix. */
export function WarningDetail({ warning, counts = null, showChip = true }) {
  const lines = placeCountLines(counts, warning.warningId);
  const expect = Array.isArray(warning.whatToExpect) ? warning.whatToExpect.filter((t) => t && t.trim()) : [];
  const validity = validityText(warning);
  return (
    <article
      data-testid="window-warning"
      data-level={warning.level}
      data-warning-id={warning.warningId}
      className="flex flex-wrap items-start gap-x-4 gap-y-2 rounded border border-plex-border-light p-2.5"
    >
      <div className="flex min-w-[14rem] flex-1 flex-col gap-2">
        {showChip && <div><WarningChip warning={warning} /></div>}
        {warning.headline && (
          <p data-testid="window-warning-headline" className="text-[13px] font-semibold leading-snug text-plex-text">
            {warning.headline}
          </p>
        )}
        <TextBlock
          title="Update"
          testId="window-warning-update"
          text={warning.updated === true ? warning.updateDescription : null}
        />
        {expect.length > 0 && (
          <TextBlock title="What to expect" testId="window-warning-expect">
            <ul className="list-disc pl-4 text-[12.5px] leading-snug text-plex-text">
              {expect.map((line, index) => <li key={`${index}:${line}`}>{line}</li>)}
            </ul>
          </TextBlock>
        )}
        <TextBlock title="What should I do" testId="window-warning-advice" text={warning.whatShouldIDo} />
        <TextBlock title="Further details" testId="window-warning-further" text={warning.furtherDetails} />
        {validity && (
          <p data-testid="window-warning-validity" className="font-mono text-[11px] text-plex-text-secondary">
            {validity}
          </p>
        )}
        {lines.length > 0 && (
          <ul data-testid="window-warning-places" className="font-mono text-[11px] text-plex-text-secondary">
            {lines.map((line) => <li key={line.region}>{`${line.region}: ${line.text}`}</li>)}
          </ul>
        )}
      </div>
      <WarningMatrix likelihood={warning.likelihood} impact={warning.impact} />
    </article>
  );
}

WarningDetail.propTypes = {
  warning: PropTypes.object.isRequired,
  counts: PropTypes.arrayOf(PropTypes.object),
  showChip: PropTypes.bool,
};

/** The style guide's optional "What should I do" link, to the Met Office's own advice page. */
export function WarningAdviceLink() {
  return (
    <p className="font-mono text-[10px] leading-snug text-plex-text-secondary">
      <a
        data-testid="window-warning-advice-link"
        className="underline"
        href={WHAT_SHOULD_I_DO_URL}
        target="_blank"
        rel="noopener noreferrer"
      >
        What should I do: if you would like further advice and guidance on what to do in severe weather, click here
      </a>
    </p>
  );
}

/**
 * The body every warning surface that shows a warning in full draws under its own heading: each warning in the style
 * guide's order ({@link WarningDetail}), the style guide's advice link when there is any warning, and the
 * attribution with its Met Office link (and the stale admission) that the licence requires wherever a warning shows.
 * It takes the warnings themselves, never a window card, so the window popup's row ({@link WindowWarningRow}, the
 * card adapter) and the Map's expanded warning (glance-to-map-plan.md G1, which has the polled list and no card)
 * draw one body and can never word a warning differently.
 *
 * @param {object} props
 * @param {object[]} props.warnings the served warnings to show in full, in the style guide's order
 * @param {object[]} [props.counts] the card's {@code warningPlaceCounts}, when the surface has a card
 * @param {boolean} [props.showFooter] false where the surface already draws the footer beside the warning (the Map's
 *        chips do), so the attribution is printed once
 */
export function WindowWarningsBody({ warnings, counts = null, showFooter = true }) {
  const served = useWarnings();
  const showAttribution = warnings.length > 0;
  return (
    <>
      {warnings.map((warning) => (
        <WarningDetail key={warning.warningId} warning={warning} counts={counts} />
      ))}
      {showAttribution && <WarningAdviceLink />}
      {showFooter && (
        <WarningFooter
          showAttribution={showAttribution}
          stale={served.stale}
          lastCheckedAt={served.lastCheckedAt}
          attribution={served.attribution}
          href={served.metOfficeUrl}
        />
      )}
    </>
  );
}

WindowWarningsBody.propTypes = {
  warnings: PropTypes.arrayOf(PropTypes.object).isRequired,
  counts: PropTypes.arrayOf(PropTypes.object),
  showFooter: PropTypes.bool,
};

/**
 * The window popup's WARNING row (weather-warnings-plan.md W3): every Met Office warning that applies to the window,
 * in full, in the style guide's order, with the attribution and the Met Office link. Visually and structurally apart
 * from every rating: no stars, no verdict colour, and a warning never changes either. Prints nothing while the
 * feature is off, and nothing for a window with no warning on a fresh feed. A stale feed prints its admission, never
 * an "all clear". The card adapter over {@link WindowWarningsBody}.
 *
 * @param {object} props
 * @param {object} props.card the window card the popup is about
 */
export default function WindowWarningRow({ card }) {
  const served = useWarnings();
  const warnings = useMemo(() => warningsForCard(served, card), [served, card]);
  if (!served.enabled) return null;
  if (warnings.length === 0 && !served.stale) return null;
  return (
    <section
      data-testid="window-sheet-warnings"
      aria-label="Met Office weather warnings"
      className="mt-3 flex flex-col gap-2 border-t border-plex-border-light pt-3"
    >
      <h3 className="font-mono text-[10px] font-semibold uppercase tracking-widest text-plex-text-secondary">
        Warning
      </h3>
      <WindowWarningsBody warnings={warnings} counts={card.warningPlaceCounts} />
    </section>
  );
}

WindowWarningRow.propTypes = {
  card: PropTypes.object.isRequired,
};
