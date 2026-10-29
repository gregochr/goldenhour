import React from 'react';
import HealthIndicator from './HealthIndicator.jsx';
import { useHealthStatusValue } from '../context/HealthStatusContext.jsx';

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
