import React from 'react';
import HealthIndicator, { HealthStatusLine } from './HealthIndicator.jsx';
import { useHealthPanel, useHealthStatusValue } from '../context/HealthStatusContext.jsx';

/**
 * The admin health pill, subscribed to the full health value so the app tree is not.
 *
 * @returns {JSX.Element} the pill
 */
export default function HealthStatusPill() {
  const h = useHealthStatusValue();
  return (
    <HealthIndicator
      status={h.status}
      degraded={h.degraded}
      checkedAt={h.checkedAt}
      build={h.build}
      services={h.services}
      database={h.database}
      session={h.session}
      appVersion={h.appVersion}
      startedAt={h.startedAt}
    />
  );
}

/**
 * The phone masthead's status dot: the same indicator, drawn as an 8px dot, opening the same panel
 * through the shared open state (compact-chrome plan R1). Admin-only by the caller, like the pill.
 *
 * @returns {JSX.Element} the dot
 */
export function HealthStatusDot() {
  const h = useHealthStatusValue();
  const panel = useHealthPanel();
  return (
    <HealthIndicator
      trigger="dot"
      open={panel.open}
      onOpenChange={panel.setOpen}
      status={h.status}
      degraded={h.degraded}
      checkedAt={h.checkedAt}
      build={h.build}
      services={h.services}
      database={h.database}
      session={h.session}
      appVersion={h.appVersion}
      startedAt={h.startedAt}
    />
  );
}

/**
 * The ⋯ menu's status line: `● UP · v2.37.0`, pressed to open the diagnostic panel the dot owns.
 *
 * @returns {JSX.Element|null} the line, or null while the status is unknown
 */
export function HealthStatusMenuLine() {
  const h = useHealthStatusValue();
  const panel = useHealthPanel();
  return (
    <HealthStatusLine
      status={h.status}
      appVersion={h.appVersion}
      onPress={() => panel.setOpen(true)}
    />
  );
}
