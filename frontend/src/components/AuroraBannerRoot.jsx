import React from 'react';
import PropTypes from 'prop-types';
import AuroraBanner from './AuroraBanner.jsx';
import LiteAuroraBanner from './LiteAuroraBanner.jsx';
import { useAuroraStatus } from '../hooks/useAuroraStatus.js';

/**
 * Chooses the aurora banner for the reader's role (docs/engineering/aurora-lite-alert-plan.md §2.3). A LITE
 * reader has no {@code status} (the Pro-shaped status is Pro and Admin only) but has a {@code liteAlert},
 * and gets {@link LiteAuroraBanner}; everyone else gets {@link AuroraBanner}, untouched.
 *
 * <p>The choice is made HERE, above {@code AuroraBanner}, because that component calls its score, viewline
 * and briefing hooks before its early returns: a branch inside it would be a conditional hook. Once a
 * LITE alert has answered, the Lite banner stays mounted (it applies the gate itself) so its per-level
 * dismissal survives the alert going quiet and returning.
 *
 * @param {object} props
 * @param {function} [props.onViewOnMap] - passed to {@link AuroraBanner}
 * @param {function} [props.onOpenSimulated] - passed to {@link AuroraBanner}
 */
export default function AuroraBannerRoot({ onViewOnMap = undefined, onOpenSimulated = undefined }) {
  const { status, liteAlert } = useAuroraStatus();
  if (!status && liteAlert) return <LiteAuroraBanner liteAlert={liteAlert} />;
  return <AuroraBanner onViewOnMap={onViewOnMap} onOpenSimulated={onOpenSimulated} />;
}

AuroraBannerRoot.propTypes = {
  onViewOnMap: PropTypes.func,
  onOpenSimulated: PropTypes.func,
};
