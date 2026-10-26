import React, { useState } from 'react';
import PropTypes from 'prop-types';
import ProUpsellDoor, { AURORA_UPSELL_BODY, AURORA_UPSELL_HEADING } from './shared/ProUpsell.jsx';
import { SEVERITY, rootVars } from './AuroraBanner.jsx';
import { useIsMobile } from '../hooks/useIsMobile.js';
import { isLiteAlertShown } from '../utils/auroraLiteAlert.js';

const LEVEL_WORDS = { MODERATE: 'Moderate', STRONG: 'Strong' };
const DOOR_LABEL = 'Aurora forecast';
// Placeholder copy until the owner signs it off (aurora-lite-alert-plan.md §2.3), matching the aurora hot
// topic's "look due N, low".
const TITLE = 'Look north, low on the horizon, after dark.';
const STRIP_DETAIL = 'Look north, low';

/** The aurora "curtains" glyph of the strip, as the Pro strip draws it. */
function AuroraGlyph() {
  return (
    <svg viewBox="0 0 28 28" fill="none" aria-hidden="true">
      <g stroke="#fff" strokeWidth="1.5" strokeLinecap="round" opacity="0.92">
        <path d="M6 23c0-9 2-15 4-15" />
        <path d="M12 23c0-11 2-17 4-17" />
        <path d="M18 23c0-9 2-14 4-14" />
      </g>
      <circle cx="22" cy="5" r="1.6" fill="#fff" />
    </svg>
  );
}

/** The Pro door where the Pro banner shows its field, best site and viewline. */
function Door() {
  return (
    <ProUpsellDoor
      label={DOOR_LABEL}
      heading={AURORA_UPSELL_HEADING}
      body={AURORA_UPSELL_BODY}
      testIdPrefix="aurora-banner-upsell"
      className="wf-aurora-lite-door"
    />
  );
}

/** The dismiss button, shared by both layouts. */
function Dismiss({ onDismiss, strip = false }) {
  return (
    <button
      type="button"
      onClick={onDismiss}
      aria-label="Dismiss aurora banner"
      data-testid="aurora-banner-dismiss"
      className={strip ? 'wf-aurora-x wf-aurora-x-strip' : 'wf-aurora-x'}
    >
      ✕
    </button>
  );
}

Dismiss.propTypes = { onDismiss: PropTypes.func.isRequired, strip: PropTypes.bool };

/**
 * The aurora banner a LITE reader sees (docs/engineering/aurora-lite-alert-plan.md §2.3): the alert's level
 * and where to look, with a Pro door where Pro's field, best site and viewline are. It is built from
 * {@code GET /api/aurora/alert} alone, so it makes no score, location, viewline or Kp request, and it has
 * no activation (no Map door: a LITE reader has no aurora night to open; the window list's door is how
 * the Map upsells), so the door is its only control besides the dismiss.
 *
 * <p>{@code role="alert"} sits on a text-only wrapper (pill, when-line, title): an interactive door inside
 * a live region would be announced on every mount, so the door and the dismiss are outside it.
 *
 * <p>Dismissed per level for the session, as {@code AuroraBanner} is, and re-shown on escalation.
 *
 * @param {object} props
 * @param {object} props.liteAlert the alert from {@code GET /api/aurora/alert}
 */
export default function LiteAuroraBanner({ liteAlert }) {
  const isMobile = useIsMobile();
  const [dismissedLevel, setDismissedLevel] = useState(null);

  if (!isLiteAlertShown(liteAlert)) return null;
  if (dismissedLevel === liteAlert.level) return null;

  const sev = SEVERITY[liteAlert.level] ?? SEVERITY.MODERATE;
  const levelWord = LEVEL_WORDS[liteAlert.level] ?? 'Alert';
  const forecast = liteAlert.triggerType === 'forecast';
  const vars = rootVars({ sev });
  const onDismiss = () => setDismissedLevel(liteAlert.level);

  if (isMobile) {
    return (
      <div
        data-testid="aurora-banner"
        data-layout="strip"
        data-actionable="false"
        style={vars}
        className="wf-aurora-strip wf-aurora-strip--lite"
      >
        <span className="wf-aurora-glyph" aria-hidden="true"><AuroraGlyph /></span>
        <div role="alert" className="wf-aurora-strip-text" data-testid="aurora-banner-alert">
          <p className="wf-aurora-strip-l1" data-testid="aurora-banner-strip-headline">
            <span className="wf-aurora-strip-lvl">{levelWord}</span>{' '}
            {forecast ? 'Aurora tonight' : 'Aurora active now'}
          </p>
          <p className="wf-aurora-strip-l2" data-testid="aurora-banner-strip-detail">{STRIP_DETAIL}</p>
        </div>
        <Door />
        <Dismiss onDismiss={onDismiss} strip />
      </div>
    );
  }

  return (
    <div
      data-testid="aurora-banner"
      data-layout="card"
      data-actionable="false"
      style={vars}
      className="wf-aurora-card wf-aurora-card--lite"
    >
      <div className="wf-aurora-body">
        <div role="alert" className="wf-aurora-lite-alert" data-testid="aurora-banner-alert">
          <span className="wf-aurora-pill" data-testid="aurora-banner-pill">
            <span className="wf-aurora-dot" aria-hidden="true" />
            {levelWord}
          </span>
          <span className="wf-aurora-when" data-testid="aurora-banner-when">
            {forecast ? 'Forecast · tonight' : 'Active now'}
          </span>
          <p className="wf-aurora-title" data-testid="aurora-banner-title">{TITLE}</p>
        </div>
        <Door />
      </div>
      <Dismiss onDismiss={onDismiss} />
    </div>
  );
}

LiteAuroraBanner.propTypes = {
  liteAlert: PropTypes.shape({
    level: PropTypes.string,
    active: PropTypes.bool,
    triggerType: PropTypes.string,
    allOvercast: PropTypes.bool,
    currentNightDate: PropTypes.string,
    currentNightEndsAt: PropTypes.string,
  }).isRequired,
};
