import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import MapBreadcrumb from '../components/map/MapBreadcrumb.jsx';

function renderCrumb(props = {}) {
  return render(
    <MapBreadcrumb
      carried={{}}
      minStars={3}
      driveTimeFilter={0}
      onBack={vi.fn()}
      onClearRating={vi.fn()}
      onClearReach={vi.fn()}
      onClearScope={vi.fn()}
      onClearOrigin={vi.fn()}
      {...props}
    />,
  );
}

describe('MapBreadcrumb reach clause', () => {
  it('names no reach when the carried limit is not one of the map\'s tiers', () => {
    renderCrumb({ carried: { limitMinutes: 17 }, driveTimeFilter: 17 });

    expect(screen.queryByTestId('wf-map-breadcrumb-carrying')).toBeNull();
  });
});

describe('MapBreadcrumb window clause for an event it has no word for', () => {
  it('prints the lower-cased event type in the strip and the announcement', () => {
    renderCrumb({ activeRow: { dayLabel: 'Sat', eventType: 'AURORA' } });

    expect(screen.getByTestId('wf-map-breadcrumb-window')).toHaveTextContent('Sat aurora');
    expect(screen.getByTestId('wf-map-breadcrumb-announce')).toHaveTextContent('Sat aurora');
  });

  it('prints just the day when the row has no event type at all', () => {
    renderCrumb({ activeRow: { dayLabel: 'Sat' } });

    expect(screen.getByTestId('wf-map-breadcrumb-window').textContent.trim()).toBe('Sat');
    expect(screen.getByTestId('wf-map-breadcrumb-announce').textContent).toBe('Sat');
  });
});
