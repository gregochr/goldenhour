import React from 'react';
import PropTypes from 'prop-types';
import { useWarnings } from '../context/WarningsContext.jsx';
import { WarningChips, WarningFooter } from './WarningChip.jsx';

/**
 * A window's warning chips with the source line under them: what the map callout shows. Prints nothing while the
 * feature is off, and nothing for a window with no warning unless the feed is stale (then only the stale line).
 *
 * @param {object} props
 * @param {object[]} props.warnings the window's warnings, from {@code warningsForWindow}
 * @param {string} [props.testId] the group's test id
 * @param {string} [props.className] extra classes for the wrapper
 */
export default function WarningStrip({ warnings, testId = 'warning-chips', className = '' }) {
  const served = useWarnings();
  if (!served.enabled) return null;
  const showAttribution = warnings.length > 0;
  if (!showAttribution && !served.stale) return null;
  return (
    <div data-testid="warning-strip" className={`flex flex-col gap-1 ${className}`}>
      <WarningChips warnings={warnings} href={served.metOfficeUrl} testId={testId} />
      <WarningFooter
        showAttribution={showAttribution}
        stale={served.stale}
        lastCheckedAt={served.lastCheckedAt}
        attribution={served.attribution}
        href={served.metOfficeUrl}
      />
    </div>
  );
}

WarningStrip.propTypes = {
  warnings: PropTypes.arrayOf(PropTypes.object).isRequired,
  testId: PropTypes.string,
  className: PropTypes.string,
};
