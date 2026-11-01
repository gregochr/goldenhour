import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';

// The one chain the banner's own suite stubs out: the banner, its REAL lazy chunk and the REAL
// thumbnail, with only the heat kernel (and the data hooks) replaced. A change to the thumbnail's
// default export or to the props the banner hands it fails here and nowhere else.
vi.mock('../hooks/useAuroraStatus.js', () => ({ useAuroraStatus: vi.fn() }));
vi.mock('../hooks/useAuroraScores.js', () => ({ useAuroraScores: vi.fn() }));
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => false }));
vi.mock('../utils/heatField.js', () => ({
  load: vi.fn(() => Promise.resolve({})),
  drawGeo: vi.fn(() => () => [0, 0]),
}));

import AuroraBanner from '../components/AuroraBanner.jsx';
import { expandBanner } from './auroraBannerHarness.js';
import { useAuroraScores } from '../hooks/useAuroraScores.js';
import { useAuroraStatus } from '../hooks/useAuroraStatus.js';
import { drawGeo } from '../utils/heatField.js';

let originalGetContext;

beforeEach(() => {
  vi.clearAllMocks();
  originalGetContext = HTMLCanvasElement.prototype.getContext;
  HTMLCanvasElement.prototype.getContext = () => ({});
  useAuroraStatus.mockReturnValue({
    status: { level: 'MODERATE', description: 'Aurora likely', darkSkyLocationCount: 20, clearLocationCount: 12 },
    loading: false,
  });
});

afterEach(() => {
  HTMLCanvasElement.prototype.getContext = originalGetContext;
});

describe('AuroraBanner with the real field thumbnail', () => {
  it('swaps the pending slot for a canvas drawn with the rated sites once the lazy chunk has loaded', async () => {
    useAuroraScores.mockReturnValue({
      ready: true,
      spots: [{ id: 1, name: 'Kielder', lat: 55.2, lng: -2.6, rid: 'Kielder', stars: 5 }],
    });
    render(<AuroraBanner />);
    expandBanner();

    const thumb = screen.getByTestId('aurora-banner-thumb');
    expect(within(thumb).getByTestId('aurora-banner-slot')).toBeInTheDocument();

    const canvas = await within(thumb).findByTestId('aurora-banner-field');
    expect(canvas.tagName).toBe('CANVAS');
    expect(within(thumb).queryByTestId('aurora-banner-slot')).not.toBeInTheDocument();
    await vi.waitFor(() => expect(drawGeo).toHaveBeenCalledTimes(1));
    const [cv, w, h, points] = drawGeo.mock.calls[0];
    expect(cv).toBe(canvas);
    expect([w, h]).toEqual([96, 118]);
    expect(points).toEqual([{ lat: 55.2, lng: -2.6, rid: 'Kielder', r: [5] }]);
  });
});
