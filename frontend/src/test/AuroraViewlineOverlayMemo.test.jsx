import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render } from '@testing-library/react';

const seen = { polygon: [], polyline: [] };
vi.mock('react-leaflet', () => ({
  Polygon: ({ positions }) => { seen.polygon.push(positions); return null; },
  Polyline: ({ positions, children }) => { seen.polyline.push(positions); return <>{children}</>; },
  Tooltip: ({ children }) => <>{children}</>,
}));

import AuroraViewlineOverlay from '../components/AuroraViewlineOverlay.jsx';

const line = (lat) => ({
  points: [{ longitude: -5, latitude: lat }, { longitude: 3, latitude: lat }],
  southernmostLatitude: lat,
  active: true,
  isForecast: false,
});

describe('AuroraViewlineOverlay lat-lng memo (P3d)', () => {
  beforeEach(() => { seen.polygon = []; seen.polyline = []; });

  it('hands Leaflet the identical arrays when re-rendered with the same viewline object', () => {
    const v = line(54);
    const view = render(<AuroraViewlineOverlay viewline={v} />);
    view.rerender(<AuroraViewlineOverlay viewline={v} />);
    expect(seen.polyline).toHaveLength(2);
    expect(seen.polyline[1]).toBe(seen.polyline[0]);
    expect(seen.polygon[1]).toBe(seen.polygon[0]);
  });

  it('builds new arrays for a new viewline object', () => {
    const view = render(<AuroraViewlineOverlay viewline={line(54)} />);
    view.rerender(<AuroraViewlineOverlay viewline={line(52)} />);
    expect(seen.polyline[1]).not.toBe(seen.polyline[0]);
    expect(seen.polygon[1]).not.toBe(seen.polygon[0]);
    expect(seen.polyline[1][0]).toEqual([52, -5]);
  });

  it('survives going from no line to a line (hook order is stable)', () => {
    const view = render(<AuroraViewlineOverlay viewline={null} />);
    view.rerender(<AuroraViewlineOverlay viewline={line(54)} />);
    expect(seen.polyline).toHaveLength(1);
  });
});
