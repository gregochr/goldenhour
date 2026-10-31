import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, render, screen, within } from '@testing-library/react';

// The banner, its REAL lazy chunk and the REAL thumbnail, with only the heat kernel and the data hooks
// replaced (the AuroraBannerField.test.jsx chain). The viewline's spoken sentence must follow what the
// thumbnail actually drew, and the banner's own suite stubs the thumbnail out, so this file is where
// "the sentence is spoken only when the line is drawn" is proved end to end (plan B4).
vi.mock('../hooks/useAuroraStatus.js', () => ({ useAuroraStatus: vi.fn() }));
vi.mock('../hooks/useAuroraScores.js', () => ({ useAuroraScores: vi.fn() }));
vi.mock('../hooks/useAuroraViewline.js', () => ({ useAuroraViewline: vi.fn() }));
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => false }));
vi.mock('../utils/heatField.js', () => ({
  load: vi.fn(),
  drawGeo: vi.fn(),
}));

import AuroraBanner from '../components/AuroraBanner.jsx';
import { expandBanner } from './auroraBannerHarness.js';
import { useAuroraScores } from '../hooks/useAuroraScores.js';
import { useAuroraStatus } from '../hooks/useAuroraStatus.js';
import { useAuroraViewline } from '../hooks/useAuroraViewline.js';
import { drawGeo, load } from '../utils/heatField.js';

const LINE = {
  active: true,
  southernmostLatitude: 54.5,
  kp: 6,
  summary: 'Visible as far south as the Midlands',
  points: [{ lat: 54.5, lon: -2 }],
};
const SPOKEN = 'Visible as far south as the Midlands.';

let originalGetContext;

/** A 2d context with the calls the line draw makes. */
function makeContext() {
  return {
    strokeStyle: null,
    lineWidth: null,
    save: () => {},
    restore: () => {},
    beginPath: () => {},
    moveTo: () => {},
    lineTo: () => {},
    stroke: () => {},
    setLineDash: () => {},
  };
}

const sentence = () => screen.queryByTestId('aurora-banner-viewline-sr');
const label = () => screen.queryByTestId('aurora-banner-viewline-label');

async function mountBanner() {
  render(<AuroraBanner />);
  expandBanner();
  // The lazy chunk, then the kernel's load, then the draw.
  await screen.findByTestId('aurora-banner-field');
  await act(async () => {});
}

beforeEach(() => {
  vi.clearAllMocks();
  originalGetContext = HTMLCanvasElement.prototype.getContext;
  HTMLCanvasElement.prototype.getContext = () => makeContext();
  load.mockReset().mockResolvedValue({});
  drawGeo.mockReset().mockReturnValue(() => [48, 60]);
  useAuroraStatus.mockReturnValue({
    status: {
      level: 'MODERATE',
      description: 'Aurora likely',
      triggerType: 'forecast',
      forecastKp: 6,
      darkSkyLocationCount: 20,
      clearLocationCount: 12,
    },
    loading: false,
  });
  useAuroraScores.mockReturnValue({
    ready: true,
    spots: [{ id: 1, name: 'Kielder', lat: 55.2, lng: -2.6, rid: 'Kielder', stars: 5 }],
  });
  useAuroraViewline.mockReturnValue({ viewline: LINE, source: 'live' });
});

afterEach(() => {
  HTMLCanvasElement.prototype.getContext = originalGetContext;
});

describe('the aurora card speaks the viewline only when the real thumbnail has drawn it', () => {
  it('speaks the summary, inside the alert, once the line is drawn and labelled', async () => {
    await mountBanner();
    expect(label()).not.toBeNull();
    expect(within(screen.getByRole('alert')).getByTestId('aurora-banner-viewline-sr')).toHaveTextContent(SPOKEN);
  });

  it('says nothing while the kernel is still loading the land', async () => {
    let resolveLand;
    load.mockReturnValue(new Promise((resolve) => { resolveLand = resolve; }));
    render(<AuroraBanner />);
    expandBanner();
    await screen.findByTestId('aurora-banner-field');
    await act(async () => {});
    expect(label()).toBeNull();
    expect(sentence()).toBeNull();

    await act(async () => { resolveLand({}); });
    expect(sentence()).not.toBeNull();
  });

  it('says nothing when the land chunk fails to load, although the line is in hand', async () => {
    load.mockRejectedValue(new Error('chunk 404'));
    await mountBanner();
    expect(label()).toBeNull();
    expect(sentence()).toBeNull();
  });

  it('says nothing when the kernel declines to draw (no projection)', async () => {
    drawGeo.mockReturnValue(null);
    await mountBanner();
    expect(label()).toBeNull();
    expect(sentence()).toBeNull();
  });

  it('says nothing when the kernel throws mid-draw', async () => {
    drawGeo.mockImplementation(() => { throw new Error('bad geometry'); });
    await mountBanner();
    expect(sentence()).toBeNull();
  });

  it('says nothing when the browser gives no 2d context', async () => {
    HTMLCanvasElement.prototype.getContext = () => null;
    await mountBanner();
    expect(sentence()).toBeNull();
  });

  it('says nothing when the projection cannot place the latitude', async () => {
    drawGeo.mockReturnValue(() => null);
    await mountBanner();
    expect(sentence()).toBeNull();
  });

  it('says nothing when the line is not active, and when there is no summary to speak', async () => {
    useAuroraViewline.mockReturnValue({ viewline: { ...LINE, active: false }, source: 'live' });
    await mountBanner();
    expect(sentence()).toBeNull();
  });

  it('says nothing for a drawn line whose response carries no summary', async () => {
    useAuroraViewline.mockReturnValue({ viewline: { ...LINE, summary: '  ' }, source: 'live' });
    await mountBanner();
    expect(label()).not.toBeNull();
    expect(sentence()).toBeNull();
  });
});
