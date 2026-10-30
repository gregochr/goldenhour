import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, render, screen, fireEvent, within } from '@testing-library/react';
import AuroraBanner, { SEVERITY, formatDetectedAt } from '../components/AuroraBanner.jsx';
import { auroraRampHex } from '../utils/auroraRamp.js';

// Mock the useAuroraStatus hook
vi.mock('../hooks/useAuroraStatus.js', () => ({
  useAuroraStatus: vi.fn(),
}));

// The viewline hook is a value the test hands the banner; what the banner passes it as `enabled` is
// what a real request would hinge on (aurora-banner-plan.md B3, §4 D4/D9).
vi.mock('../hooks/useAuroraViewline.js', () => ({
  useAuroraViewline: vi.fn(),
}));

// The banner picks its layout from useIsMobile (< 640px). The hook's own query — `(max-width: 639px)`
// — is pinned in useIsMobile.test.js, so here it is a switch: the pattern FiltersPopover.test.jsx uses.
let mockIsMobile = false;
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => mockIsMobile }));

// The per-location scores are the hook's own suite's business (useAuroraScores.test.jsx); here they are
// a value the test hands the banner, so each rule about WHICH site it draws and names can be failed
// without a request in sight.
vi.mock('../hooks/useAuroraScores.js', () => ({
  useAuroraScores: vi.fn(),
}));

// The planning area and the HOME reach come from the briefing provider (aurora-banner-plan.md §4 D1).
// A mutable value rather than a rendered provider: the banner reads three fields of it. The mock also
// carries `effectiveReachById` (the Plan origin's drive) with CONTRADICTING numbers, so a banner that
// breaks a tie by it instead of the HOME `reachById` names the wrong site.
let mockBriefing;
vi.mock('../context/WindowFirstBriefingContext.jsx', () => ({
  useWindowFirstBriefing: () => mockBriefing,
}));

// The lazy field thumbnail, replaced by a stub that shows what it was handed. Its throwing is switched
// by `mockThumbThrows`, standing in for a chunk that fails to load (a lazy component rethrows the
// rejection at render, the same path the boundary under test catches).
let mockThumbThrows = false;
let mockThumbThrown = 0;
// Every render of the stub, so a test can see whether the thumbnail was handed the SAME spots and
// frame (the redraw keys) across a re-render that only changed the viewline.
let mockThumbRenders = [];
// What the stub tells the banner about the line: the REAL thumbnail reports it from its draw (its own
// suite and AuroraBannerField.test.jsx pin that), so here it is a switch.
let mockLineDrawn = true;
vi.mock('../components/AuroraFieldThumb.jsx', () => ({
  default: function ThumbStub({ spots, fit, viewline, kp, onViewlineDrawn }) {
    React.useEffect(() => {
      if (!onViewlineDrawn) return undefined;
      onViewlineDrawn(mockLineDrawn && Boolean(viewline));
      return () => onViewlineDrawn(false);
    }, [onViewlineDrawn, viewline]);
    if (mockThumbThrows) {
      mockThumbThrown += 1;
      throw new Error('chunk failed to load');
    }
    mockThumbRenders.push({ spots, fit });
    return (
      <div
        data-testid="aurora-field-stub"
        data-names={spots.map((s) => s.name).join('|')}
        data-fit={JSON.stringify(fit)}
        data-viewline={viewline ? JSON.stringify(viewline) : ''}
        data-kp={kp ?? ''}
      />
    );
  },
}));

import { useAuroraScores } from '../hooks/useAuroraScores.js';
import { useAuroraStatus } from '../hooks/useAuroraStatus.js';
import { useAuroraViewline } from '../hooks/useAuroraViewline.js';
import { bbox } from '../utils/heatGeometry.js';

// ── Fixtures ────────────────────────────────────────────────────────────────────────────────────
// Every fixture's `hexColour` CONTRADICTS its level (the API's old amber/red, or worse): severity is
// chosen from `level`, so a regression that reads the API colour fails a test that checks `--acc`.
const MODERATE = {
  level: 'MODERATE',
  hexColour: '#ff0000',
  description: 'Possible aurora on the northern horizon',
  active: true,
  eligibleLocations: 5,
};
const STRONG = {
  level: 'STRONG',
  hexColour: '#ff9900',
  description: 'Aurora likely across northern England',
  active: true,
  eligibleLocations: 12,
};

function renderBanner(status, props = {}) {
  useAuroraStatus.mockReturnValue({ status, loading: false });
  return render(<AuroraBanner {...props} />);
}

/**
 * The nine meter bars in DOM order (k = 0..8, bottom-up on screen). Each bar's `data-k` is what the
 * stylesheet reads to pick its `--bar-k` colour, so the index is asserted here rather than trusted.
 */
function bars() {
  const found = within(screen.getByTestId('aurora-banner-meter')).queryAllByTestId('aurora-banner-bar');
  found.forEach((bar, i) => expect(bar).toHaveAttribute('data-k', String(i)));
  return found;
}

describe('AuroraBanner', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useAuroraViewline.mockReturnValue({ viewline: null, source: null });
    useAuroraScores.mockReturnValue({ ready: false, spots: [] });
    mockBriefing = { heatSpots: [], reachById: new Map(), effectiveReachById: new Map(), origin: null };
    mockThumbThrows = false;
    mockThumbThrown = 0;
    mockThumbRenders = [];
    mockLineDrawn = true;
    mockIsMobile = false;
    // Pin the clock to a fixed noon instant so the relative "today / yesterday / N days ago"
    // detection formatting is deterministic — previously flaked near local midnight. Only Date is
    // faked, so testing-library's real setTimeout-based waits keep working.
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-01-15T12:00:00Z'));
    // Reset hash
    window.location.hash = '';
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  // ---------------------------------------------------------------------------
  // Not rendered cases
  // ---------------------------------------------------------------------------

  it('returns null when status is null (free-tier / 403)', () => {
    const { container } = renderBanner(null);
    expect(container.firstChild).toBeNull();
  });

  it('returns null when level is QUIET', () => {
    const { container } = renderBanner({ level: 'QUIET', active: false, eligibleLocations: 0 });
    expect(container.firstChild).toBeNull();
  });

  it('returns null when level is MINOR', () => {
    const { container } = renderBanner({ level: 'MINOR', active: false, eligibleLocations: 0 });
    expect(container.firstChild).toBeNull();
  });

  it('draws no viewline SENTENCE: the line lives on the thumbnail, as a label', () => {
    renderBanner({ ...MODERATE });
    expect(screen.queryByTestId('aurora-banner-viewline')).not.toBeInTheDocument();
  });

  // ---------------------------------------------------------------------------
  // Severity rides the aurora ramp, chosen from `level` and never from `hexColour`
  // ---------------------------------------------------------------------------

  describe('severity', () => {
    it('MODERATE sets the Moderate accent, ink and 62% fill although the API said red', () => {
      renderBanner({ ...MODERATE });
      const banner = screen.getByTestId('aurora-banner');
      expect(banner.style.getPropertyValue('--acc')).toBe('#3AC884');
      expect(banner.style.getPropertyValue('--acc-ink')).toBe('#8CE6B6');
      expect(banner.style.getPropertyValue('--fill')).toBe('62%');
      expect(banner.style.getPropertyValue('--fill-ratio')).toBe('0.62');
    });

    it('STRONG sets the Strong accent, ink and 92% fill although the API said amber', () => {
      renderBanner({ ...STRONG });
      const banner = screen.getByTestId('aurora-banner');
      expect(banner.style.getPropertyValue('--acc')).toBe('#76FFA0');
      expect(banner.style.getPropertyValue('--acc-ink')).toBe('#A8FFC4');
      expect(banner.style.getPropertyValue('--fill')).toBe('92%');
      expect(banner.style.getPropertyValue('--fill-ratio')).toBe('0.92');
    });

    it('does not depend on hexColour being present at all', () => {
      const withoutHex = { ...MODERATE };
      delete withoutHex.hexColour;
      renderBanner(withoutHex);
      expect(screen.getByTestId('aurora-banner').style.getPropertyValue('--acc')).toBe('#3AC884');
    });

    it('carries the colours on the strip too, and nothing else inline', () => {
      mockIsMobile = true;
      renderBanner({ ...STRONG });
      const strip = screen.getByTestId('aurora-banner');
      expect(strip.style.getPropertyValue('--acc')).toBe('#76FFA0');
      expect(strip.style.getPropertyValue('--fill')).toBe('92%');
      expect(strip.style.getPropertyValue('--fill-ratio')).toBe('0.92');
      expect(strip.style.backgroundColor).toBe('');
      expect(strip.style.animation).toBe('');
    });

    it('maps SEVERE and EXTREME onto STRONG\'s row, so the API can grow a level without a new lookup', () => {
      expect(SEVERITY.SEVERE).toEqual(SEVERITY.STRONG);
      expect(SEVERITY.EXTREME).toEqual(SEVERITY.STRONG);
      expect(SEVERITY.STRONG).toEqual({ acc: '#76FFA0', ink: '#A8FFC4', fill: 92, breathes: true });
      expect(SEVERITY.MODERATE).toEqual({ acc: '#3AC884', ink: '#8CE6B6', fill: 62, breathes: false });
    });
  });

  // ---------------------------------------------------------------------------
  // The card (>= 640px)
  // ---------------------------------------------------------------------------

  describe('the card', () => {
    it('renders the card layout with the alert description', () => {
      renderBanner({ ...MODERATE });
      const banner = screen.getByTestId('aurora-banner');
      expect(banner).toHaveAttribute('data-layout', 'card');
      expect(screen.getByText('Possible aurora on the northern horizon')).toBeInTheDocument();
    });

    it('shows the NOAA G-scale beside the level word when gScale is present', () => {
      renderBanner({ ...STRONG, gScale: 'G4' });
      expect(screen.getByTestId('aurora-banner-pill').textContent).toBe('Strong · G4');
    });

    it('shows the level word alone when gScale is absent', () => {
      renderBanner({ ...STRONG });
      expect(screen.getByTestId('aurora-banner-pill').textContent).toBe('Strong');
    });

    it('words a simulated alert\'s pill "simulated"', () => {
      renderBanner({ ...MODERATE, simulated: true });
      expect(screen.getByTestId('aurora-banner-pill').textContent).toBe('Moderate · simulated');
    });

    describe('the when-line', () => {
      it('reads "Forecast · tonight" for a forecast alert', () => {
        renderBanner({ ...MODERATE, forecastKp: 6, triggerType: 'forecast' });
        expect(screen.getByTestId('aurora-banner-when').textContent).toBe('Forecast · tonight');
      });

      it('reads "Active now · detected HH:MM" for a live alert', () => {
        renderBanner({ ...MODERATE, triggerType: 'realtime', detectedAt: '2026-01-15T11:30:00Z' });
        expect(screen.getByTestId('aurora-banner-when').textContent).toBe('Active now · detected 11:30');
        expect(screen.getByTestId('aurora-banner-detected').textContent).toBe('detected 11:30');
      });

      it('reads "Active now" alone when a live alert carries no detection time', () => {
        renderBanner({ ...MODERATE, triggerType: 'realtime', detectedAt: null });
        expect(screen.getByTestId('aurora-banner-when').textContent).toBe('Active now');
        expect(screen.queryByTestId('aurora-banner-detected')).not.toBeInTheDocument();
      });

      it('says "detected yesterday HH:MM" when the alert persisted from yesterday', () => {
        renderBanner({ ...STRONG, detectedAt: '2026-01-14T21:34:00Z' });
        expect(screen.getByTestId('aurora-banner-detected').textContent).toBe('detected yesterday 21:34');
      });

      it('reads "Simulated" and never a detection time for a simulated alert', () => {
        renderBanner({ ...MODERATE, simulated: true, detectedAt: '2026-01-15T11:30:00Z' });
        expect(screen.getByTestId('aurora-banner-when').textContent).toBe('Simulated');
        expect(screen.queryByTestId('aurora-banner-detected')).not.toBeInTheDocument();
      });

      it('shows no detection time on a forecast alert even when one is on the payload', () => {
        renderBanner({
          ...MODERATE, forecastKp: 6, triggerType: 'forecast', detectedAt: '2026-01-15T11:30:00Z',
        });
        expect(screen.queryByTestId('aurora-banner-detected')).not.toBeInTheDocument();
      });
    });

    describe('the pending thumbnail slot (Kp and the nine-step meter)', () => {
      it('shows the Kp figure and exactly the lower six of nine bars lit for Kp 6', () => {
        renderBanner({ ...MODERATE, forecastKp: 6, triggerType: 'forecast' });
        expect(screen.getByTestId('aurora-banner-kp').textContent).toBe('Kp 6');
        const lit = bars().map((b) => b.getAttribute('data-lit'));
        expect(lit).toEqual(['true', 'true', 'true', 'true', 'true', 'true', 'false', 'false', 'false']);
      });

      it('glows only the lit bars from the seventh up: Kp 6 glows none, Kp 8 glows bars 6 and 7', () => {
        const { unmount } = renderBanner({ ...MODERATE, forecastKp: 6 });
        expect(bars().map((b) => b.getAttribute('data-glow'))).toEqual(Array(9).fill('false'));
        unmount();

        renderBanner({ ...STRONG, forecastKp: 8 });
        expect(bars().map((b) => b.getAttribute('data-glow'))).toEqual([
          'false', 'false', 'false', 'false', 'false', 'false', 'true', 'true', 'false',
        ]);
        expect(bars().map((b) => b.getAttribute('data-lit')).filter((v) => v === 'true')).toHaveLength(8);
      });

      it('lights all nine bars for Kp 9', () => {
        renderBanner({ ...STRONG, forecastKp: 9 });
        expect(bars().every((b) => b.getAttribute('data-lit') === 'true')).toBe(true);
        expect(bars().map((b) => b.getAttribute('data-glow')).filter((v) => v === 'true')).toHaveLength(3);
      });

      it('rounds a fractional Kp to the whole step the figure and the bars both show', () => {
        const { unmount } = renderBanner({ ...MODERATE, forecastKp: 6.4 });
        expect(screen.getByTestId('aurora-banner-kp').textContent).toBe('Kp 6');
        unmount();
        renderBanner({ ...MODERATE, forecastKp: 6.5 });
        expect(screen.getByTestId('aurora-banner-kp').textContent).toBe('Kp 7');
        expect(bars().filter((b) => b.getAttribute('data-lit') === 'true')).toHaveLength(7);
      });

      it('prefers the forecast Kp, and falls back to the live kp field when forecastKp is absent', () => {
        const { unmount } = renderBanner({ ...MODERATE, forecastKp: 6, kp: 3 });
        expect(screen.getByTestId('aurora-banner-kp').textContent).toBe('Kp 6');
        unmount();
        renderBanner({ ...MODERATE, forecastKp: null, kp: 5, triggerType: 'realtime' });
        expect(screen.getByTestId('aurora-banner-kp').textContent).toBe('Kp 5');
      });

      it('draws "Kp 0" and an unlit meter for a Kp of zero, which is a reading and not a missing one', () => {
        renderBanner({ ...MODERATE, forecastKp: 0, kp: 5 });
        expect(screen.getByTestId('aurora-banner-kp').textContent).toBe('Kp 0');
        expect(bars().every((b) => b.getAttribute('data-lit') === 'false')).toBe(true);
      });

      it('draws an unlit meter and no figure when there is no Kp at all', () => {
        renderBanner({ ...MODERATE });
        expect(screen.queryByTestId('aurora-banner-kp')).not.toBeInTheDocument();
        expect(bars()).toHaveLength(9);
        expect(bars().every((b) => b.getAttribute('data-lit') === 'false')).toBe(true);
        expect(screen.getByTestId('aurora-banner-slot')).toHaveTextContent(/scoring \/ locations/i);
      });

      it('keeps the placeholder wording out of the accessibility tree: the Kp figure is the answer', () => {
        renderBanner({ ...MODERATE, forecastKp: 6 });
        expect(screen.getByText('Scoring / locations')).toHaveAttribute('aria-hidden', 'true');
      });

      it('colours the bars from the aurora ramp module, including its #1F7F78 teal (not the spec\'s #22867E)', () => {
        renderBanner({ ...MODERATE, forecastKp: 6 });
        const banner = screen.getByTestId('aurora-banner');
        for (let k = 0; k < 9; k += 1) {
          expect(banner.style.getPropertyValue(`--bar-${k}`)).toBe(auroraRampHex(1 + (4 * k) / 8));
        }
        // Bar 4 is the ramp's third stop: pinned as a literal so a retyped spec hex cannot pass.
        expect(banner.style.getPropertyValue('--bar-4')).toBe('#1F7F78');
        expect(banner.style.getPropertyValue('--bar-0')).toBe('#384262');
        expect(banner.style.getPropertyValue('--bar-8')).toBe('#76FFA0');
      });

      it('puts a test tube, no Kp figure and no meter in the slot for a simulated alert', () => {
        renderBanner({ ...MODERATE, simulated: true, forecastKp: 6 });
        expect(screen.getByTestId('aurora-banner-slot')).toHaveTextContent('🧪');
        expect(screen.queryByTestId('aurora-banner-kp')).not.toBeInTheDocument();
        expect(screen.queryByTestId('aurora-banner-meter')).not.toBeInTheDocument();
      });
    });

    describe('the sites-clear line', () => {
      it('shows "N dark-sky sites clear" when triage has run', () => {
        renderBanner({ ...MODERATE, darkSkyLocationCount: 45, clearLocationCount: 12 });
        expect(screen.getByTestId('aurora-banner-best').textContent).toBe('12 dark-sky sites clear');
      });

      it('shows the singular "1 dark-sky site clear"', () => {
        renderBanner({ ...STRONG, darkSkyLocationCount: 10, clearLocationCount: 1 });
        expect(screen.getByTestId('aurora-banner-best').textContent).toBe('1 dark-sky site clear');
      });

      it('shows the total, with no "clear", when triage has not run yet', () => {
        renderBanner({ ...MODERATE, darkSkyLocationCount: 45, clearLocationCount: null });
        expect(screen.getByTestId('aurora-banner-best').textContent).toBe('45 dark-sky sites');
      });

      it('draws no line at all when there are no Bortle-enriched locations and no Bz', () => {
        renderBanner({ ...MODERATE, darkSkyLocationCount: 0, clearLocationCount: null });
        expect(screen.queryByTestId('aurora-banner-best')).not.toBeInTheDocument();
        expect(screen.queryByText(/dark-sky/i)).not.toBeInTheDocument();
      });

      it('appends the live Bz with a true minus sign, never a hyphen', () => {
        renderBanner({
          ...MODERATE, darkSkyLocationCount: 45, clearLocationCount: 12, bzNanoTesla: -9.2, triggerType: 'realtime',
        });
        const text = screen.getByTestId('aurora-banner-best').textContent;
        expect(text).toBe('12 dark-sky sites clear · Bz −9.2 nT');
        expect(text).toContain('−');
        expect(text).not.toContain('-9.2');
      });

      it('shows a northward Bz with a plus sign, and a near-zero one without a sign', () => {
        const { unmount } = renderBanner({ ...MODERATE, bzNanoTesla: 3.24, triggerType: 'realtime' });
        expect(screen.getByTestId('aurora-banner-best').textContent).toBe('Bz +3.2 nT');
        unmount();
        renderBanner({ ...MODERATE, bzNanoTesla: -0.04, triggerType: 'realtime' });
        expect(screen.getByTestId('aurora-banner-best').textContent).toBe('Bz 0.0 nT');
      });

      it('shows Bz alone when there is no site count', () => {
        renderBanner({ ...MODERATE, darkSkyLocationCount: 0, bzNanoTesla: -6, triggerType: 'realtime' });
        expect(screen.getByTestId('aurora-banner-best').textContent).toBe('Bz −6.0 nT');
      });

      it('omits Bz for a forecast alert, for a simulated alert and when it is null', () => {
        const forecast = renderBanner({
          ...MODERATE, darkSkyLocationCount: 10, clearLocationCount: 4, bzNanoTesla: -6, triggerType: 'forecast',
        });
        expect(screen.getByTestId('aurora-banner-best').textContent).toBe('4 dark-sky sites clear');
        forecast.unmount();

        const simulated = renderBanner({
          ...MODERATE, darkSkyLocationCount: 10, clearLocationCount: 4, bzNanoTesla: -6, simulated: true,
        });
        expect(screen.getByTestId('aurora-banner-best').textContent).toBe('4 dark-sky sites clear');
        simulated.unmount();

        renderBanner({
          ...MODERATE, darkSkyLocationCount: 10, clearLocationCount: 4, bzNanoTesla: null, triggerType: 'realtime',
        });
        expect(screen.getByTestId('aurora-banner-best').textContent).toBe('4 dark-sky sites clear');
      });

      it('no longer renders the Bz sentence, the Kp meta line or the viewline sentence', () => {
        renderBanner({
          ...MODERATE, forecastKp: 6, triggerType: 'forecast', bzNanoTesla: -9.2, darkSkyLocationCount: 5, clearLocationCount: 3,
        });
        expect(screen.queryByTestId('aurora-banner-bz')).not.toBeInTheDocument();
        expect(screen.queryByTestId('aurora-banner-viewline')).not.toBeInTheDocument();
        expect(screen.queryByText(/favourable for aurora/i)).not.toBeInTheDocument();
        expect(screen.queryByText(/Kp 6 forecast/i)).not.toBeInTheDocument();
      });
    });

    it('offers "View on map →" for a real alert and "Operations →" for a simulated one', () => {
      const doors = { onViewOnMap: vi.fn(), onOpenSimulated: vi.fn() };
      const real = renderBanner({ ...MODERATE }, doors);
      expect(screen.getByText('View on map →')).toBeInTheDocument();
      real.unmount();
      renderBanner({ ...MODERATE, simulated: true }, doors);
      expect(screen.getByText('Operations →')).toBeInTheDocument();
      expect(screen.queryByText('View on map →')).not.toBeInTheDocument();
    });

    it('withholds the call to action when the door is withheld, and words a simulation plainly (C4)', () => {
      const real = renderBanner({ ...MODERATE });
      expect(screen.queryByText('View on map →')).not.toBeInTheDocument();
      real.unmount();
      renderBanner({ ...MODERATE, simulated: true }, { onViewOnMap: vi.fn() });
      expect(screen.getByTestId('aurora-banner-cta').textContent).toBe('Test alert');
      expect(screen.queryByText('Operations →')).not.toBeInTheDocument();
    });
  });

  // ---------------------------------------------------------------------------
  // The overcast gate (unchanged)
  // ---------------------------------------------------------------------------

  describe('the clear-sky gate', () => {
    it('suppresses a MODERATE banner when all locations are overcast', () => {
      // A MODERATE alert nobody can see anywhere is noise in the top slot — gate it out.
      renderBanner({ ...MODERATE, darkSkyLocationCount: 20, clearLocationCount: 0 });
      expect(screen.queryByTestId('aurora-banner')).not.toBeInTheDocument();
    });

    it('suppresses it on the strip as well', () => {
      mockIsMobile = true;
      renderBanner({ ...MODERATE, darkSkyLocationCount: 20, clearLocationCount: 0 });
      expect(screen.queryByTestId('aurora-banner')).not.toBeInTheDocument();
    });

    it('keeps a simulated banner visible under full overcast (admin preview always renders)', () => {
      renderBanner({ ...MODERATE, simulated: true, darkSkyLocationCount: 20, clearLocationCount: 0 });
      expect(screen.getByTestId('aurora-banner')).toBeInTheDocument();
    });

    it('keeps a STRONG banner under full overcast, with the honest overcast note in the best line\'s place', () => {
      // A major storm stays surfaced even under cloud — gaps open, people travel.
      renderBanner({
        ...STRONG, darkSkyLocationCount: 20, clearLocationCount: 0, bzNanoTesla: -8, triggerType: 'realtime',
      });
      expect(screen.getByTestId('aurora-banner')).toBeInTheDocument();
      expect(screen.getByTestId('aurora-banner-overcast').textContent)
        .toBe('All locations overcast — no clear skies forecast tonight');
      // The note REPLACES the whole line: no site count and no Bz beside it.
      expect(screen.queryByTestId('aurora-banner-best')).not.toBeInTheDocument();
      expect(screen.queryByText(/Bz/)).not.toBeInTheDocument();
    });

    it('fails open when the clear count is unknown: missing data is not overcast', () => {
      renderBanner({ ...MODERATE, darkSkyLocationCount: 20, clearLocationCount: null });
      expect(screen.getByTestId('aurora-banner')).toBeInTheDocument();
      expect(screen.queryByTestId('aurora-banner-overcast')).not.toBeInTheDocument();
    });

    it('does not call it overcast when there are no dark-sky locations to be overcast', () => {
      renderBanner({ ...MODERATE, darkSkyLocationCount: 0, clearLocationCount: 0 });
      expect(screen.getByTestId('aurora-banner')).toBeInTheDocument();
      expect(screen.queryByTestId('aurora-banner-overcast')).not.toBeInTheDocument();
    });
  });

  // ---------------------------------------------------------------------------
  // Layout switch
  // ---------------------------------------------------------------------------

  describe('layout', () => {
    it('renders the card, with the thumbnail slot, when the viewport is not mobile', () => {
      mockIsMobile = false;
      renderBanner({ ...MODERATE, forecastKp: 6 });
      expect(screen.getByTestId('aurora-banner')).toHaveAttribute('data-layout', 'card');
      expect(screen.getByTestId('aurora-banner-slot')).toBeInTheDocument();
      // The 96x118 box the field will fill is the wrapper; the pending content sits inside it.
      expect(within(screen.getByTestId('aurora-banner-thumb')).getByTestId('aurora-banner-slot')).toBeInTheDocument();
    });

    it('renders the strip, with no thumbnail slot, when the viewport is mobile', () => {
      mockIsMobile = true;
      renderBanner({ ...MODERATE, forecastKp: 6 });
      expect(screen.getByTestId('aurora-banner')).toHaveAttribute('data-layout', 'strip');
      expect(screen.queryByTestId('aurora-banner-slot')).not.toBeInTheDocument();
      expect(screen.queryByTestId('aurora-banner-thumb')).not.toBeInTheDocument();
      expect(screen.queryByTestId('aurora-banner-pill')).not.toBeInTheDocument();
    });

    it('swaps from the card to the strip when the viewport crosses the breakpoint', () => {
      mockIsMobile = false;
      const { rerender } = renderBanner({ ...MODERATE, forecastKp: 6 });
      expect(screen.getByTestId('aurora-banner')).toHaveAttribute('data-layout', 'card');
      mockIsMobile = true;
      rerender(<AuroraBanner />);
      expect(screen.getByTestId('aurora-banner')).toHaveAttribute('data-layout', 'strip');
      expect(screen.getAllByTestId('aurora-banner')).toHaveLength(1);
    });

    it('keeps a dismissal across that swap, in either direction', () => {
      mockIsMobile = false;
      const { rerender } = renderBanner({ ...MODERATE });
      fireEvent.click(screen.getByTestId('aurora-banner-dismiss'));
      mockIsMobile = true;
      rerender(<AuroraBanner />);
      expect(screen.queryByTestId('aurora-banner')).not.toBeInTheDocument();
      mockIsMobile = false;
      rerender(<AuroraBanner />);
      expect(screen.queryByTestId('aurora-banner')).not.toBeInTheDocument();
    });
  });

  // ---------------------------------------------------------------------------
  // The strip (< 640px)
  // ---------------------------------------------------------------------------

  describe('the strip', () => {
    beforeEach(() => {
      mockIsMobile = true;
    });

    it('names the level and "Aurora tonight" for a forecast alert, "Aurora active now" for a live one', () => {
      const forecast = renderBanner({ ...MODERATE, forecastKp: 6, triggerType: 'forecast' });
      // One sentence with a real space between the level word and the headline: the gap on screen is
      // CSS, so without the text separator a reader of the DOM gets "ModerateAurora tonight".
      expect(screen.getByTestId('aurora-banner-strip-headline').textContent).toBe('Moderate Aurora tonight');
      forecast.unmount();

      renderBanner({ ...STRONG, triggerType: 'realtime' });
      expect(screen.getByTestId('aurora-banner-strip-headline').textContent).toBe('Strong Aurora active now');
      expect(screen.queryByText(/Aurora tonight/)).not.toBeInTheDocument();
    });

    it('reads "Aurora simulated" for a simulated alert', () => {
      renderBanner({ ...MODERATE, simulated: true });
      expect(screen.getByTestId('aurora-banner-strip-headline').textContent).toBe('Moderate Aurora simulated');
      expect(screen.getByTestId('aurora-banner')).toHaveTextContent('🧪');
    });

    it('puts Kp and the clear-site count on line two while scores are pending', () => {
      renderBanner({ ...MODERATE, forecastKp: 6, darkSkyLocationCount: 150, clearLocationCount: 147 });
      expect(screen.getByTestId('aurora-banner-strip-detail').textContent).toBe('Kp 6 · 147 dark sites clear');
    });

    it('uses the singular for one site, and drops "clear" when triage has not run', () => {
      const one = renderBanner({ ...MODERATE, forecastKp: 6, darkSkyLocationCount: 4, clearLocationCount: 1 });
      expect(screen.getByTestId('aurora-banner-strip-detail').textContent).toBe('Kp 6 · 1 dark site clear');
      one.unmount();
      renderBanner({ ...MODERATE, forecastKp: 6, darkSkyLocationCount: 4, clearLocationCount: null });
      expect(screen.getByTestId('aurora-banner-strip-detail').textContent).toBe('Kp 6 · 4 dark sites');
    });

    it('says "all sites overcast" for a STRONG alert under full overcast, where the card gives the whole sentence', () => {
      renderBanner({ ...STRONG, forecastKp: 8, darkSkyLocationCount: 20, clearLocationCount: 0 });
      expect(screen.getByTestId('aurora-banner-strip-detail').textContent).toBe('Kp 8 · all sites overcast');
      expect(screen.queryByTestId('aurora-banner-overcast')).not.toBeInTheDocument();
    });

    it('shows line two with Kp only, or sites only, or not at all', () => {
      const kpOnly = renderBanner({ ...MODERATE, forecastKp: 6 });
      expect(screen.getByTestId('aurora-banner-strip-detail').textContent).toBe('Kp 6');
      kpOnly.unmount();

      const sitesOnly = renderBanner({ ...MODERATE, darkSkyLocationCount: 9, clearLocationCount: 9 });
      expect(screen.getByTestId('aurora-banner-strip-detail').textContent).toBe('9 dark sites clear');
      sitesOnly.unmount();

      renderBanner({ ...MODERATE });
      expect(screen.queryByTestId('aurora-banner-strip-detail')).not.toBeInTheDocument();
    });

    it('shows no Bz, no overcast sentence and no viewline: they live on the Map tab', () => {
      renderBanner({
        ...STRONG, triggerType: 'realtime', bzNanoTesla: -9.2, darkSkyLocationCount: 20, clearLocationCount: 0,
      });
      expect(screen.queryByText(/Bz/)).not.toBeInTheDocument();
      expect(screen.queryByTestId('aurora-banner-overcast')).not.toBeInTheDocument();
      expect(screen.queryByTestId('aurora-banner-viewline')).not.toBeInTheDocument();
    });

    it('offers "Map →", and "Operations →" for a simulated alert whose door is not the map', () => {
      const doors = { onViewOnMap: vi.fn(), onOpenSimulated: vi.fn() };
      const real = renderBanner({ ...MODERATE }, doors);
      expect(screen.getByText('Map →')).toBeInTheDocument();
      real.unmount();
      renderBanner({ ...MODERATE, simulated: true }, doors);
      expect(screen.getByText('Operations →')).toBeInTheDocument();
      expect(screen.queryByText('Map →')).not.toBeInTheDocument();
    });

    it('withholds the call to action when the door is withheld, and words a simulation plainly (C4)', () => {
      const real = renderBanner({ ...MODERATE });
      expect(screen.queryByText('Map →')).not.toBeInTheDocument();
      real.unmount();
      renderBanner({ ...MODERATE, simulated: true }, { onViewOnMap: vi.fn() });
      expect(screen.getByTestId('aurora-banner-cta').textContent).toBe('Test');
    });
  });

  // ---------------------------------------------------------------------------
  // breathe — live and southward Bz only
  // ---------------------------------------------------------------------------

  describe('breathe', () => {
    // The spec's severity table: MODERATE has no motion; STRONG breathes only while live and Bz is
    // southward. (Plan section 1 item 10 said "keep the old pulse predicate", which had no level term.)
    it.each([
      ['card', false],
      ['strip', true],
    ])('a STRONG alert breathes on the %s when it is live and Bz is south (< −1 nT)', (layout, mobile) => {
      mockIsMobile = mobile;
      renderBanner({ ...STRONG, bzNanoTesla: -6.0, triggerType: 'realtime' });
      const banner = screen.getByTestId('aurora-banner');
      expect(banner).toHaveAttribute('data-layout', layout);
      expect(banner).toHaveClass('wf-aurora-breathe');
    });

    it('a STRONG alert breathes when triggerType is null and Bz is favourable (backwards compat)', () => {
      renderBanner({ ...STRONG, bzNanoTesla: -6.0, triggerType: null });
      expect(screen.getByTestId('aurora-banner')).toHaveClass('wf-aurora-breathe');
    });

    it('a MODERATE alert never breathes, however live and southward', () => {
      renderBanner({ ...MODERATE, bzNanoTesla: -9.0, triggerType: 'realtime' });
      expect(screen.getByTestId('aurora-banner')).not.toHaveClass('wf-aurora-breathe');
    });

    it('does not breathe for a forecast alert, however southward the last Bz was', () => {
      renderBanner({ ...STRONG, bzNanoTesla: -6.0, triggerType: 'forecast', forecastKp: 6 });
      expect(screen.getByTestId('aurora-banner')).not.toHaveClass('wf-aurora-breathe');
    });

    it('does not breathe for a simulated alert', () => {
      renderBanner({ ...STRONG, bzNanoTesla: -6.0, simulated: true });
      expect(screen.getByTestId('aurora-banner')).not.toHaveClass('wf-aurora-breathe');
    });

    it('does not breathe at Bz exactly −1, north of it or unknown', () => {
      const edge = renderBanner({ ...STRONG, bzNanoTesla: -1, triggerType: 'realtime' });
      expect(screen.getByTestId('aurora-banner')).not.toHaveClass('wf-aurora-breathe');
      edge.unmount();
      const north = renderBanner({ ...STRONG, bzNanoTesla: 3.0, triggerType: 'realtime' });
      expect(screen.getByTestId('aurora-banner')).not.toHaveClass('wf-aurora-breathe');
      north.unmount();
      renderBanner({ ...STRONG, bzNanoTesla: null, triggerType: 'realtime' });
      expect(screen.getByTestId('aurora-banner')).not.toHaveClass('wf-aurora-breathe');
    });

    it('breathes just south of −1 (−1.1)', () => {
      renderBanner({ ...STRONG, bzNanoTesla: -1.1, triggerType: 'realtime' });
      expect(screen.getByTestId('aurora-banner')).toHaveClass('wf-aurora-breathe');
    });
  });

  // ---------------------------------------------------------------------------
  // Simulated treatment
  // ---------------------------------------------------------------------------

  describe('simulated alerts', () => {
    it.each([
      ['card', false],
      ['strip', true],
    ])('wear the hatched, dashed treatment on the %s', (_layout, mobile) => {
      mockIsMobile = mobile;
      renderBanner({ ...MODERATE, simulated: true });
      expect(screen.getByTestId('aurora-banner')).toHaveClass('wf-aurora-simulated');
    });

    it('a real alert does not', () => {
      renderBanner({ ...MODERATE });
      expect(screen.getByTestId('aurora-banner')).not.toHaveClass('wf-aurora-simulated');
    });
  });

  // ---------------------------------------------------------------------------
  // Activation — the whole surface, both layouts
  // ---------------------------------------------------------------------------

  describe.each([
    ['card', false],
    ['strip', true],
  ])('activation on the %s', (_layout, mobile) => {
    beforeEach(() => {
      mockIsMobile = mobile;
    });

    it('clicking a live alert calls onViewOnMap instead of a plain hash jump', () => {
      const onViewOnMap = vi.fn();
      renderBanner({ ...MODERATE }, { onViewOnMap });

      fireEvent.click(screen.getByTestId('aurora-banner'));

      expect(onViewOnMap).toHaveBeenCalledTimes(1);
      // The callback owns navigation now, so the banner must not touch the hash itself.
      expect(window.location.hash).toBe('');
    });

    it('Enter and Space on the banner also call onViewOnMap, and prevent the page scroll Space would cause', () => {
      const onViewOnMap = vi.fn();
      renderBanner({ ...STRONG }, { onViewOnMap });
      const banner = screen.getByTestId('aurora-banner');

      expect(fireEvent.keyDown(banner, { key: 'Enter' })).toBe(false);
      expect(fireEvent.keyDown(banner, { key: ' ' })).toBe(false);
      expect(onViewOnMap).toHaveBeenCalledTimes(2);
    });

    it('other keys do nothing', () => {
      const onViewOnMap = vi.fn();
      renderBanner({ ...STRONG }, { onViewOnMap });

      expect(fireEvent.keyDown(screen.getByTestId('aurora-banner'), { key: 'a' })).toBe(true);
      expect(onViewOnMap).not.toHaveBeenCalled();
    });

    it('a key pressed on the dismiss button does NOT also open the map', () => {
      // The button is inside the activating surface: its Enter bubbles to the root's key handler.
      const onViewOnMap = vi.fn();
      renderBanner({ ...MODERATE }, { onViewOnMap });
      const dismiss = screen.getByRole('button', { name: /dismiss aurora banner/i });

      fireEvent.keyDown(dismiss, { key: 'Enter' });
      fireEvent.keyDown(dismiss, { key: ' ' });

      expect(onViewOnMap).not.toHaveBeenCalled();
    });

    it('hands the Plan\'s live lens to onViewOnMap (C4)', () => {
      mockBriefing = {
        ...mockBriefing, ratingLens: { minRating: 4 }, reachLens: { tier: { limitMinutes: 60 } },
      };
      const onViewOnMap = vi.fn();
      renderBanner({ ...MODERATE }, { onViewOnMap });

      fireEvent.click(screen.getByTestId('aurora-banner'));

      expect(onViewOnMap).toHaveBeenCalledExactlyOnceWith({ minRating: 4, limitMinutes: 60 });
    });

    it('hands null lens values when the briefing holds no lens (the degrade path)', () => {
      const onViewOnMap = vi.fn();
      renderBanner({ ...MODERATE }, { onViewOnMap });

      fireEvent.click(screen.getByTestId('aurora-banner'));

      expect(onViewOnMap).toHaveBeenCalledExactlyOnceWith({ minRating: null, limitMinutes: null });
    });

    it('with no onViewOnMap a real alert has no door: nothing happens and the hash is untouched', () => {
      renderBanner({ ...MODERATE });

      fireEvent.click(screen.getByTestId('aurora-banner'));
      fireEvent.keyDown(screen.getByTestId('aurora-banner'), { key: 'Enter' });

      expect(window.location.hash).toBe('');
    });

    it('a simulated alert calls onOpenSimulated and never onViewOnMap or the hash', () => {
      const onViewOnMap = vi.fn();
      const onOpenSimulated = vi.fn();
      renderBanner({ ...MODERATE, simulated: true }, { onViewOnMap, onOpenSimulated });

      fireEvent.click(screen.getByTestId('aurora-banner'));

      expect(onViewOnMap).not.toHaveBeenCalled();
      expect(onOpenSimulated).toHaveBeenCalledTimes(1);
      expect(window.location.hash).toBe('');
    });

    it('a simulated alert with no onOpenSimulated has no door (a non-admin)', () => {
      const onViewOnMap = vi.fn();
      renderBanner({ ...MODERATE, simulated: true }, { onViewOnMap });

      fireEvent.click(screen.getByTestId('aurora-banner'));

      expect(onViewOnMap).not.toHaveBeenCalled();
      expect(screen.getByTestId('aurora-banner')).toHaveAttribute('data-actionable', 'false');
    });
  });

  // ---------------------------------------------------------------------------
  // The Map door needs a night row to select (overlay convergence C4 review)
  // ---------------------------------------------------------------------------

  describe('the Map door is offered only when the Map tab has a night row for it', () => {
    // `ready` is all the door reads. No spots: a rated field would mount the lazy thumbnail chunk and
    // leave it loaded for the file, breaking the test that asserts its pending slot.
    it('is offered for a live night (active, real, not over)', () => {
      renderBanner({ ...MODERATE, currentNightEndsAt: '2026-01-16T05:00:00Z' }, { onViewOnMap: vi.fn() });
      expect(screen.getByTestId('aurora-banner')).toHaveAttribute('data-actionable', 'true');
    });

    it('is withheld when the status is alert-worthy but NOT active, with no rated run to select', () => {
      renderBanner({ ...MODERATE, active: false }, { onViewOnMap: vi.fn() });
      expect(screen.getByTestId('aurora-banner')).toHaveAttribute('data-actionable', 'false');
      expect(screen.queryByTestId('aurora-banner-cta')).toBeNull();
    });

    it('is withheld when the status\' night has ENDED (the stale-night-with-a-forecast case)', () => {
      renderBanner(
        { ...MODERATE, currentNightEndsAt: '2026-01-15T05:00:00Z' },
        { onViewOnMap: vi.fn() },
      );
      expect(screen.getByTestId('aurora-banner')).toHaveAttribute('data-actionable', 'false');
    });

    it('is kept for an inactive (forecast) alert when a rated run exists for the night to select', () => {
      useAuroraScores.mockReturnValue({ ready: true, spots: [] });
      const onViewOnMap = vi.fn();
      renderBanner({ ...MODERATE, active: false }, { onViewOnMap });
      fireEvent.click(screen.getByTestId('aurora-banner'));
      expect(onViewOnMap).toHaveBeenCalledTimes(1);
    });

    it('a withheld door never calls onViewOnMap', () => {
      const onViewOnMap = vi.fn();
      renderBanner({ ...MODERATE, active: false }, { onViewOnMap });
      fireEvent.click(screen.getByTestId('aurora-banner'));
      fireEvent.keyDown(screen.getByTestId('aurora-banner'), { key: 'Enter' });
      expect(onViewOnMap).not.toHaveBeenCalled();
    });
  });

  describe('a banner with no door looks like a statement (C4 CSS)', () => {
    const css = readFileSync(resolve(__dirname, '../index.css'), 'utf8');
    const rule = (selectorStart) => {
      const at = css.indexOf(selectorStart);
      expect(at, `${selectorStart} rule`).toBeGreaterThan(-1);
      return css.slice(at, css.indexOf('}', at));
    };

    it('drops the pointer cursor on both layouts when data-actionable is false', () => {
      const body = rule(".wf-aurora-card[data-actionable='false'],");
      expect(body).toContain(".wf-aurora-strip[data-actionable='false']");
      expect(body).toMatch(/cursor:\s*default/);
    });

    it('keeps its call-to-action word off the accent weight', () => {
      const body = rule(".wf-aurora-card[data-actionable='false'] .wf-aurora-cta,");
      expect(body).toContain(".wf-aurora-strip[data-actionable='false'] .wf-aurora-strip-cta");
      expect(body).toMatch(/font-weight:\s*400/);
      expect(body).not.toMatch(/--acc-ink/);
    });

    it('the markup carries the attribute the rules key on', () => {
      renderBanner({ ...MODERATE });
      expect(screen.getByTestId('aurora-banner')).toHaveAttribute('data-actionable', 'false');
    });
  });

  // ---------------------------------------------------------------------------
  // Dismiss behaviour
  // ---------------------------------------------------------------------------

  describe.each([
    ['card', false],
    ['strip', true],
  ])('dismissing the %s', (_layout, mobile) => {
    beforeEach(() => {
      mockIsMobile = mobile;
    });

    it('hides the banner at the current level', () => {
      renderBanner({ ...MODERATE });

      fireEvent.click(screen.getByRole('button', { name: /dismiss aurora banner/i }));

      expect(screen.queryByTestId('aurora-banner')).not.toBeInTheDocument();
    });

    it('does not propagate to the banner (no hash navigation, no map)', () => {
      const onViewOnMap = vi.fn();
      renderBanner({ ...MODERATE }, { onViewOnMap });

      fireEvent.click(screen.getByTestId('aurora-banner-dismiss'));

      expect(onViewOnMap).not.toHaveBeenCalled();
      expect(window.location.hash).toBe('');
    });

    it('re-shows after dismissal when the level escalates (MODERATE → STRONG)', () => {
      const { rerender } = renderBanner({ ...MODERATE });

      fireEvent.click(screen.getByTestId('aurora-banner-dismiss'));
      expect(screen.queryByTestId('aurora-banner')).not.toBeInTheDocument();

      useAuroraStatus.mockReturnValue({ status: { ...STRONG }, loading: false });
      rerender(<AuroraBanner />);

      expect(screen.getByTestId('aurora-banner')).toBeInTheDocument();
      expect(screen.getByTestId('aurora-banner').style.getPropertyValue('--acc')).toBe('#76FFA0');
    });

    it('stays hidden after dismiss when the level remains the same', () => {
      const { rerender } = renderBanner({ ...MODERATE });

      fireEvent.click(screen.getByTestId('aurora-banner-dismiss'));

      useAuroraStatus.mockReturnValue({ status: { ...MODERATE, description: 'Updated text' }, loading: false });
      rerender(<AuroraBanner />);

      expect(screen.queryByTestId('aurora-banner')).not.toBeInTheDocument();
    });
  });

  // ---------------------------------------------------------------------------
  // Accessibility
  // ---------------------------------------------------------------------------

  describe.each([
    ['card', false],
    ['strip', true],
  ])('accessibility of the %s', (_layout, mobile) => {
    beforeEach(() => {
      mockIsMobile = mobile;
    });

    it('is a focusable role="alert" while it has a door', () => {
      renderBanner({ ...MODERATE }, { onViewOnMap: vi.fn() });
      const alert = screen.getByRole('alert');
      expect(alert).toBe(screen.getByTestId('aurora-banner'));
      expect(alert).toHaveAttribute('tabindex', '0');
    });

    it('is a role="alert" with NO tab stop and no call to action when it has no door (C4)', () => {
      renderBanner({ ...MODERATE });
      const alert = screen.getByRole('alert');
      expect(alert).not.toHaveAttribute('tabindex');
      expect(alert).toHaveAttribute('data-actionable', 'false');
      expect(screen.queryByTestId('aurora-banner-cta')).not.toBeInTheDocument();
    });

    it('has a dismiss button with an accessible name, that is a real button', () => {
      renderBanner({ ...MODERATE });
      expect(screen.getByRole('button', { name: 'Dismiss aurora banner' })).toHaveAttribute('type', 'button');
    });
  });

  // ---------------------------------------------------------------------------
  // Once per-location scores exist (aurora-banner-plan.md B2): the field and the best line
  // ---------------------------------------------------------------------------

  describe('per-location scores', () => {
    const rated = (id, name, stars) => ({ id, name, lat: 55 + id / 100, lng: -2, rid: name, stars });
    const heat = (id, name, regionName) => ({ id, name, lat: 55 + id / 100, lng: -2, regionName, rid: regionName });
    const driveMap = (entries) => new Map(entries.map(([id, driveMinutes]) => [id, { driveMinutes }]));
    const scored = (...spots) => useAuroraScores.mockReturnValue({ ready: spots.length > 0, spots });
    const FORECAST = { ...MODERATE, forecastKp: 6, triggerType: 'forecast', darkSkyLocationCount: 150, clearLocationCount: 147 };
    const best = () => screen.getByTestId('aurora-banner-best');

    it('asks the score hook about the status it is showing', () => {
      renderBanner(FORECAST);
      expect(useAuroraScores).toHaveBeenCalledWith(FORECAST);
    });

    it('draws the lazy field in the thumbnail slot, and shows the pending slot until it has loaded', async () => {
      scored(rated(1, 'Kielder', 5));
      renderBanner(FORECAST);
      // The Suspense fallback IS the pending slot — the swap is invisible in layout.
      const thumb = screen.getByTestId('aurora-banner-thumb');
      expect(within(thumb).getByTestId('aurora-banner-slot')).toBeInTheDocument();

      const field = await within(thumb).findByTestId('aurora-field-stub');
      expect(field).toHaveAttribute('data-names', 'Kielder');
      expect(within(thumb).queryByTestId('aurora-banner-slot')).not.toBeInTheDocument();
    });

    it('stays on the pending slot while no site is rated', () => {
      renderBanner(FORECAST);
      const thumb = screen.getByTestId('aurora-banner-thumb');
      expect(within(thumb).getByTestId('aurora-banner-slot')).toBeInTheDocument();
      expect(within(thumb).queryByTestId('aurora-field-stub')).not.toBeInTheDocument();
      expect(best()).toHaveTextContent('147 dark-sky sites clear');
      expect(best()).not.toHaveTextContent('Best:');
    });

    it('falls back to the pending slot, banner intact, when the field\'s chunk fails to load', async () => {
      mockThumbThrows = true;
      const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
      try {
        scored(rated(1, 'Kielder', 5));
        renderBanner(FORECAST);
        // Wait for the failure to HAPPEN: the Suspense fallback is the same pending slot, so a check
        // made before the lazy module has loaded and thrown would pass with the boundary deleted.
        await vi.waitFor(() => expect(mockThumbThrown).toBeGreaterThan(0));
        await act(async () => {});
        // A boundary above the thumbnail, not the shell's: the banner is still here, in its pending slot.
        expect(screen.getByTestId('aurora-banner')).toBeInTheDocument();
        expect(within(screen.getByTestId('aurora-banner-thumb')).getByTestId('aurora-banner-slot')).toBeInTheDocument();
        expect(screen.queryByTestId('aurora-field-stub')).not.toBeInTheDocument();
        // The best line is words, not the picture: it still names the site.
        expect(best()).toHaveTextContent('Best: Kielder 5★');
      } finally {
        spy.mockRestore();
      }
    });

    it('speaks a rating as a number of stars, with the glyph hidden from assistive technology', () => {
      scored(rated(1, 'Kielder', 4));
      const four = renderBanner(FORECAST);
      const stars = best().querySelector('.wf-aurora-stars');
      expect(stars.querySelector('[aria-hidden="true"]').textContent).toBe('★');
      expect(stars.querySelector('.sr-only').textContent).toBe(' stars');
      four.unmount();

      scored(rated(1, 'Kielder', 1));
      renderBanner(FORECAST);
      expect(best().querySelector('.sr-only').textContent).toBe(' star');
    });

    it('stops asking for scores once the banner is dismissed, and asks again on escalation', () => {
      const view = renderBanner(FORECAST);
      expect(useAuroraScores).toHaveBeenLastCalledWith(FORECAST);
      fireEvent.click(screen.getByTestId('aurora-banner-dismiss'));
      expect(useAuroraScores).toHaveBeenLastCalledWith(null);

      const escalated = { ...FORECAST, level: 'STRONG' };
      useAuroraStatus.mockReturnValue({ status: escalated, loading: false });
      view.rerender(<AuroraBanner />);
      expect(useAuroraScores).toHaveBeenLastCalledWith(escalated);
      expect(screen.getByTestId('aurora-banner')).toBeInTheDocument();
    });

    describe('the southern viewline (B3)', () => {
      const LINE = { active: true, southernmostLatitude: 54.5, kp: 6, points: [{ lat: 54.5, lon: -2 }] };
      /** The `enabled` argument of the hook's most recent call. */
      const asked = () => useAuroraViewline.mock.calls.at(-1)[0];
      const everAsked = () => useAuroraViewline.mock.calls.some(([enabled]) => enabled === true);

      it('does not ask while the slot shows the Kp meter, because no field is drawn to carry a line', () => {
        renderBanner(FORECAST);
        expect(asked()).toBe(false);
        expect(everAsked()).toBe(false);
      });

      it('asks once the planning area has a rated field', () => {
        scored(rated(1, 'Kielder', 5));
        renderBanner(FORECAST);
        expect(asked()).toBe(true);
      });

      it('does not ask while the only rated sites are outside the planning area', () => {
        mockBriefing.heatSpots = [heat(1, 'Kielder', 'Northumberland'), heat(3, 'Skye', 'Highlands')];
        mockBriefing.reachById = driveMap([[1, 60], [3, 400]]);
        scored(rated(3, 'Skye', 5));
        renderBanner(FORECAST);
        expect(asked()).toBe(false);
      });

      it('never asks for a simulated alert, whatever scores the hook holds', () => {
        scored(rated(1, 'Kielder', 5));
        renderBanner({ ...FORECAST, simulated: true });
        expect(everAsked()).toBe(false);
      });

      it('stops asking once the banner is dismissed, and asks again on escalation', () => {
        scored(rated(1, 'Kielder', 5));
        const view = renderBanner(FORECAST);
        expect(asked()).toBe(true);
        fireEvent.click(screen.getByTestId('aurora-banner-dismiss'));
        expect(asked()).toBe(false);

        useAuroraStatus.mockReturnValue({ status: { ...FORECAST, level: 'STRONG' }, loading: false });
        view.rerender(<AuroraBanner />);
        expect(asked()).toBe(true);
      });

      it('does not ask for a status that is not alert-worthy, whatever scores are held', () => {
        scored(rated(1, 'Kielder', 5));
        renderBanner({ ...MODERATE, level: 'QUIET', active: false });
        expect(everAsked()).toBe(false);
      });

      it('hands the thumbnail the same sites and frame when only the viewline changes, so a poll repaints nothing', async () => {
        useAuroraViewline.mockReturnValue({ viewline: LINE, source: 'live' });
        scored(rated(1, 'Kielder', 5));
        const view = renderBanner(FORECAST);
        await screen.findByTestId('aurora-field-stub');
        const first = mockThumbRenders.at(-1);

        // The 5-minute poll: the hook hands back a fresh object for the same line.
        useAuroraViewline.mockReturnValue({ viewline: { ...LINE, points: [...LINE.points] }, source: 'live' });
        view.rerender(<AuroraBanner />);
        const last = mockThumbRenders.at(-1);
        expect(mockThumbRenders.length).toBeGreaterThan(1);
        expect(last.spots).toBe(first.spots);
        expect(last.fit).toBe(first.fit);
      });

      it('does not ask for a banner the overcast gate suppresses, but does for a STRONG one', () => {
        scored(rated(1, 'Kielder', 5));
        const moderate = renderBanner({ ...FORECAST, darkSkyLocationCount: 20, clearLocationCount: 0 });
        expect(moderate.container.firstChild).toBeNull();
        expect(everAsked()).toBe(false);
        moderate.unmount();

        renderBanner({ ...STRONG, darkSkyLocationCount: 20, clearLocationCount: 0 });
        expect(asked()).toBe(true);
      });

      it('does not ask on the phone: the strip has no thumbnail to draw a line on', () => {
        mockIsMobile = true;
        scored(rated(1, 'Kielder', 5));
        renderBanner(FORECAST);
        expect(everAsked()).toBe(false);
      });

      it('does not ask when there is no status at all', () => {
        scored(rated(1, 'Kielder', 5));
        renderBanner(null);
        expect(everAsked()).toBe(false);
      });

      it('hands the viewline and the alert\'s Kp to the thumbnail', async () => {
        useAuroraViewline.mockReturnValue({ viewline: LINE, source: 'live' });
        scored(rated(1, 'Kielder', 5));
        renderBanner(FORECAST);
        const field = await screen.findByTestId('aurora-field-stub');
        expect(JSON.parse(field.dataset.viewline)).toEqual(LINE);
        expect(field).toHaveAttribute('data-kp', '6');
      });

      describe('the spoken sentence', () => {
        const SPOKEN = { ...LINE, summary: 'Visible as far south as the Midlands' };
        const sentence = () => screen.queryByTestId('aurora-banner-viewline-sr');

        it('puts the response\'s summary inside the alert\'s own text once the line is drawn', () => {
          useAuroraViewline.mockReturnValue({ viewline: SPOKEN, source: 'live' });
          scored(rated(1, 'Kielder', 5));
          renderBanner(FORECAST);
          const alert = screen.getByRole('alert');
          expect(within(alert).getByTestId('aurora-banner-viewline-sr')).toHaveTextContent(
            'Visible as far south as the Midlands.',
          );
          expect(alert).toHaveTextContent('Visible as far south as the Midlands.');
        });

        it('is visually hidden but not hidden from assistive technology, and sits outside the aria-hidden thumbnail', () => {
          useAuroraViewline.mockReturnValue({ viewline: SPOKEN, source: 'live' });
          scored(rated(1, 'Kielder', 5));
          renderBanner(FORECAST);
          expect(sentence()).toHaveClass('sr-only');
          expect(sentence().closest('[aria-hidden="true"]')).toBeNull();
          expect(within(screen.getByTestId('aurora-banner-thumb')).queryByTestId('aurora-banner-viewline-sr')).toBeNull();
        });

        it('reads between the best line and the call to action', () => {
          useAuroraViewline.mockReturnValue({ viewline: SPOKEN, source: 'live' });
          scored(rated(1, 'Kielder', 5));
          renderBanner(FORECAST, { onViewOnMap: vi.fn() });
          const best = screen.getByTestId('aurora-banner-best');
          const cta = screen.getByText('View on map →');
          expect(best.compareDocumentPosition(sentence()) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
          expect(sentence().compareDocumentPosition(cta) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
        });

        it('speaks the forecast line\'s summary as well, and never doubles a full stop the server wrote', () => {
          useAuroraViewline.mockReturnValue({
            viewline: { ...SPOKEN, summary: 'Visible as far south as northern England.' },
            source: 'forecast',
          });
          scored(rated(1, 'Kielder', 5));
          renderBanner(FORECAST);
          expect(sentence().textContent).toBe('Visible as far south as northern England.');
        });

        it('says nothing while the thumbnail reports that it did not draw the line, whatever data is in hand', async () => {
          mockLineDrawn = false;
          useAuroraViewline.mockReturnValue({ viewline: SPOKEN, source: 'live' });
          scored(rated(1, 'Kielder', 5));
          renderBanner(FORECAST);
          expect(await screen.findByTestId('aurora-field-stub')).toBeInTheDocument();
          expect(sentence()).toBeNull();
        });

        it('stops speaking when the thumbnail unmounts, as it does on the swap to the phone strip and back', async () => {
          useAuroraViewline.mockReturnValue({ viewline: SPOKEN, source: 'live' });
          scored(rated(1, 'Kielder', 5));
          const view = renderBanner(FORECAST);
          await screen.findByTestId('aurora-field-stub');
          expect(sentence()).not.toBeNull();
          mockIsMobile = true;
          view.rerender(<AuroraBanner />);
          expect(sentence()).toBeNull();
          mockIsMobile = false;
          view.rerender(<AuroraBanner />);
          await screen.findByTestId('aurora-field-stub');
          expect(sentence()).not.toBeNull();
        });

        it.each([
          ['a question mark', 'Is it visible as far south as Leeds?', 'Is it visible as far south as Leeds?'],
          ['an exclamation mark', 'Visible as far south as Leeds!', 'Visible as far south as Leeds!'],
          ['a stop inside a closing bracket', 'Visible as far south as Leeds (maybe.)', 'Visible as far south as Leeds (maybe.)'],
          ['a stop inside a closing quote', 'Visible as "far south."', 'Visible as "far south."'],
          ['surrounding whitespace', '  Visible as far south as Leeds.  ', 'Visible as far south as Leeds.'],
          ['an interior stop only', 'Visible as far south as St. Bees', 'Visible as far south as St. Bees.'],
        ])('keeps %s as the terminal punctuation and adds a stop only when there is none', async (_name, summary, spoken) => {
          useAuroraViewline.mockReturnValue({ viewline: { ...SPOKEN, summary }, source: 'live' });
          scored(rated(1, 'Kielder', 5));
          renderBanner(FORECAST);
          await screen.findByTestId('aurora-field-stub');
          expect(sentence().textContent).toBe(spoken);
        });

        it('says nothing while the slot shows the Kp meter, however good the line the hook holds', () => {
          useAuroraViewline.mockReturnValue({ viewline: SPOKEN, source: 'live' });
          renderBanner(FORECAST);
          expect(screen.getByTestId('aurora-banner-meter')).toBeInTheDocument();
          expect(sentence()).toBeNull();
        });

        it('says nothing for a simulated alert', () => {
          useAuroraViewline.mockReturnValue({ viewline: SPOKEN, source: 'live' });
          scored(rated(1, 'Kielder', 5));
          renderBanner({ ...FORECAST, simulated: true });
          expect(sentence()).toBeNull();
        });

        it('says nothing on the phone strip, which has no thumbnail', () => {
          mockIsMobile = true;
          useAuroraViewline.mockReturnValue({ viewline: SPOKEN, source: 'live' });
          scored(rated(1, 'Kielder', 5));
          renderBanner(FORECAST);
          expect(screen.getByTestId('aurora-banner')).toHaveAttribute('data-layout', 'strip');
          expect(sentence()).toBeNull();
          expect(screen.getByTestId('aurora-banner')).not.toHaveTextContent('Midlands');
        });

        it('says nothing when the viewline is inactive, whatever summary it still carries', () => {
          useAuroraViewline.mockReturnValue({ viewline: { ...SPOKEN, active: false }, source: 'live' });
          scored(rated(1, 'Kielder', 5));
          renderBanner(FORECAST);
          expect(sentence()).toBeNull();
        });

        it('says nothing when there is no line at all', () => {
          scored(rated(1, 'Kielder', 5));
          renderBanner(FORECAST);
          expect(sentence()).toBeNull();
        });

        it.each([
          ['absent', undefined],
          ['null', null],
          ['empty', ''],
          ['blank', '   '],
        ])('says nothing when the summary is %s', (_name, summary) => {
          useAuroraViewline.mockReturnValue({ viewline: { ...LINE, summary }, source: 'live' });
          scored(rated(1, 'Kielder', 5));
          renderBanner(FORECAST);
          expect(sentence()).toBeNull();
        });

        it('says nothing once the banner is dismissed, and again after an escalation', () => {
          useAuroraViewline.mockReturnValue({ viewline: SPOKEN, source: 'live' });
          scored(rated(1, 'Kielder', 5));
          const view = renderBanner(FORECAST);
          expect(sentence()).not.toBeNull();
          fireEvent.click(screen.getByTestId('aurora-banner-dismiss'));
          expect(sentence()).toBeNull();
          useAuroraStatus.mockReturnValue({ status: { ...FORECAST, level: 'STRONG' }, loading: false });
          view.rerender(<AuroraBanner />);
          expect(sentence()).not.toBeNull();
        });
      });

      it('hands the thumbnail no line when the hook has none', async () => {
        scored(rated(1, 'Kielder', 5));
        renderBanner(FORECAST);
        const field = await screen.findByTestId('aurora-field-stub');
        expect(field.dataset.viewline).toBe('');
      });
    });

    describe('the best line', () => {
      it('names the best site, its stars and how many sites are clear', () => {
        scored(rated(1, 'Kielder', 4), rated(2, 'Hackfall Wood', 5), rated(3, 'Bamburgh', 3));
        renderBanner(FORECAST);
        expect(best().textContent).toBe('Best: Hackfall Wood 5★ stars · 147 sites clear');
        expect(best().querySelector('b').textContent).toBe('Hackfall Wood');
        expect(best().querySelector('.wf-aurora-stars').textContent).toBe('5★ stars');
      });

      it('uses the singular for one clear site', () => {
        scored(rated(1, 'Kielder', 4));
        renderBanner({ ...FORECAST, darkSkyLocationCount: 4, clearLocationCount: 1 });
        expect(best().textContent).toBe('Best: Kielder 4★ stars · 1 site clear');
      });

      it('says "dark-sky sites", not "clear", when triage has not run', () => {
        scored(rated(1, 'Kielder', 4));
        renderBanner({ ...FORECAST, darkSkyLocationCount: 20, clearLocationCount: null });
        expect(best().textContent).toBe('Best: Kielder 4★ stars · 20 dark-sky sites');
      });

      it('names the site alone when the status counts no sites', () => {
        scored(rated(1, 'Kielder', 4));
        renderBanner({ ...FORECAST, darkSkyLocationCount: 0, clearLocationCount: null });
        expect(best().textContent).toBe('Best: Kielder 4★ stars');
      });

      it('appends the live Bz reading, with a true minus sign', () => {
        scored(rated(1, 'Kielder', 5));
        renderBanner({ ...FORECAST, triggerType: 'realtime', bzNanoTesla: -9.2 });
        expect(best().textContent).toBe('Best: Kielder 5★ stars · 147 sites clear · Bz −9.2 nT');
      });

      it('gives way to the overcast sentence when every site is overcast', () => {
        scored(rated(1, 'Kielder', 5));
        renderBanner({ ...STRONG, darkSkyLocationCount: 20, clearLocationCount: 0 });
        expect(screen.getByTestId('aurora-banner-overcast')).toBeInTheDocument();
        expect(screen.queryByTestId('aurora-banner-best')).not.toBeInTheDocument();
      });

      it('breaks a tie by the shorter HOME drive', () => {
        scored(rated(1, 'Far Hide', 5), rated(2, 'Near Hide', 5));
        mockBriefing.reachById = driveMap([[1, 120], [2, 30]]);
        renderBanner(FORECAST);
        expect(best()).toHaveTextContent('Best: Near Hide 5★');
      });

      it('lets a measured drive beat an unknown one', () => {
        scored(rated(1, 'Aaa Unmeasured', 5), rated(2, 'Zzz Measured', 5));
        mockBriefing.reachById = driveMap([[2, 170]]);
        renderBanner(FORECAST);
        expect(best()).toHaveTextContent('Best: Zzz Measured 5★');
      });

      it('reads the HOME drive, never the Plan origin\'s', () => {
        scored(rated(1, 'Home Near', 5), rated(2, 'Origin Near', 5));
        mockBriefing.reachById = driveMap([[1, 20], [2, 90]]);
        mockBriefing.effectiveReachById = driveMap([[1, 200], [2, 10]]);
        renderBanner(FORECAST);
        expect(best()).toHaveTextContent('Best: Home Near 5★');
      });

      it('breaks a full tie by name', () => {
        scored(rated(1, 'Whitby', 4), rated(2, 'Alnmouth', 4));
        renderBanner(FORECAST);
        expect(best()).toHaveTextContent('Best: Alnmouth 4★');
      });
    });

    describe('the planning area', () => {
      // Northumberland is an hour away; the Highlands are measured beyond the 180-minute glance limit.
      const catalogue = [
        heat(1, 'Kielder', 'Northumberland'),
        heat(2, 'Bamburgh', 'Northumberland'),
        heat(3, 'Skye', 'Highlands'),
      ];
      const reach = driveMap([[1, 60], [2, 70], [3, 400]]);

      beforeEach(() => {
        mockBriefing.heatSpots = catalogue;
        mockBriefing.reachById = reach;
      });

      it('names the best site inside the area, although a better-rated one lies outside', () => {
        scored(rated(3, 'Skye', 5), rated(1, 'Kielder', 4));
        renderBanner(FORECAST);
        expect(best()).toHaveTextContent('Best: Kielder 4★');
        expect(best()).not.toHaveTextContent('Skye');
      });

      it('draws only the in-area sites, framed to the area', async () => {
        scored(rated(3, 'Skye', 5), rated(1, 'Kielder', 4), rated(2, 'Bamburgh', 3));
        renderBanner(FORECAST);
        const field = await screen.findByTestId('aurora-field-stub');
        expect(field).toHaveAttribute('data-names', 'Kielder|Bamburgh');
        expect(JSON.parse(field.dataset.fit)).toEqual(bbox([catalogue[0], catalogue[1]], 0.25));
      });

      it('stays pending — no field, no Best line — when every rated site is outside the area', () => {
        scored(rated(3, 'Skye', 5));
        renderBanner(FORECAST);
        expect(screen.queryByTestId('aurora-field-stub')).not.toBeInTheDocument();
        expect(within(screen.getByTestId('aurora-banner-thumb')).getByTestId('aurora-banner-slot')).toBeInTheDocument();
        expect(best().textContent).toBe('147 dark-sky sites clear');
      });

      it('counts every rated site, and frames them, when the area cannot be known', async () => {
        mockBriefing.heatSpots = [];
        scored(rated(3, 'Skye', 5), rated(1, 'Kielder', 4));
        renderBanner(FORECAST);
        expect(best()).toHaveTextContent('Best: Skye 5★');
        const field = await screen.findByTestId('aurora-field-stub');
        expect(field).toHaveAttribute('data-names', 'Skye|Kielder');
        expect(JSON.parse(field.dataset.fit)).toEqual(bbox([rated(3, 'Skye', 5), rated(1, 'Kielder', 4)], 0.25));
      });

      it('follows an away Plan origin to that origin\'s own region', () => {
        mockBriefing.origin = { name: 'Highlands' };
        scored(rated(3, 'Skye', 3), rated(1, 'Kielder', 5));
        renderBanner(FORECAST);
        expect(best()).toHaveTextContent('Best: Skye 3★');
      });
    });

    describe('a simulated alert', () => {
      it('never shows the field or a best line, whatever scores the hook holds', () => {
        scored(rated(1, 'Kielder', 5));
        renderBanner({ ...MODERATE, simulated: true, darkSkyLocationCount: 20, clearLocationCount: 12 });
        const thumb = screen.getByTestId('aurora-banner-thumb');
        expect(thumb).toHaveTextContent('🧪');
        expect(within(thumb).queryByTestId('aurora-field-stub')).not.toBeInTheDocument();
        expect(best()).not.toHaveTextContent('Best:');
      });
    });

    describe('the strip', () => {
      beforeEach(() => {
        mockIsMobile = true;
      });

      it('names the best site on line two once scores exist', () => {
        scored(rated(1, 'Hackfall Wood', 5));
        renderBanner({ ...STRONG, forecastKp: 8, darkSkyLocationCount: 20, clearLocationCount: 12 });
        const detail = screen.getByTestId('aurora-banner-strip-detail');
        expect(detail.textContent).toBe('Kp 8 · best Hackfall Wood 5★ stars');
        // The name is its own shrinking, ellipsising span and the rating a separate one that never
        // is: on a 320px phone the tail of the line is the answer (index.css, .wf-aurora-strip-l2-best).
        expect(detail).toHaveClass('wf-aurora-strip-l2-best');
        expect(detail.querySelector('.wf-aurora-strip-name').textContent).toBe('Hackfall Wood');
        expect(detail.querySelector('.wf-aurora-stars')).not.toBeNull();
      });

      it('leaves line two as it was while no site is rated', () => {
        renderBanner({ ...STRONG, forecastKp: 8, darkSkyLocationCount: 20, clearLocationCount: 12 });
        expect(screen.getByTestId('aurora-banner-strip-detail').textContent).toBe('Kp 8 · 12 dark sites clear');
      });

      it('keeps "all sites overcast" ahead of the best site', () => {
        scored(rated(1, 'Kielder', 5));
        renderBanner({ ...STRONG, forecastKp: 8, darkSkyLocationCount: 20, clearLocationCount: 0 });
        expect(screen.getByTestId('aurora-banner-strip-detail').textContent).toBe('Kp 8 · all sites overcast');
      });

      it('has no thumbnail to draw into, so it never mounts the field', () => {
        scored(rated(1, 'Kielder', 5));
        renderBanner({ ...STRONG, forecastKp: 8 });
        expect(screen.queryByTestId('aurora-banner-thumb')).not.toBeInTheDocument();
        expect(screen.queryByTestId('aurora-field-stub')).not.toBeInTheDocument();
      });

      it('applies the area restriction to the strip\'s best site too', () => {
        mockBriefing.heatSpots = [heat(1, 'Kielder', 'Northumberland'), heat(3, 'Skye', 'Highlands')];
        mockBriefing.reachById = driveMap([[1, 60], [3, 400]]);
        scored(rated(3, 'Skye', 5), rated(1, 'Kielder', 4));
        renderBanner({ ...STRONG, forecastKp: 8 });
        expect(screen.getByTestId('aurora-banner-strip-detail').textContent).toBe('Kp 8 · best Kielder 4★ stars');
      });
    });
  });

  it('hides decorative parts of the card from assistive technology and keeps the Kp figure readable', () => {
    renderBanner({ ...MODERATE, forecastKp: 6 });
    expect(screen.getByTestId('aurora-banner-meter')).toHaveAttribute('aria-hidden', 'true');
    expect(screen.getByTestId('aurora-banner-kp')).not.toHaveAttribute('aria-hidden');
  });

  // ---------------------------------------------------------------------------
  // formatDetectedAt helper — unit tests
  // ---------------------------------------------------------------------------

  describe('formatDetectedAt()', () => {
    it('returns null for null input', () => {
      expect(formatDetectedAt(null)).toBeNull();
    });

    it('returns null for undefined input', () => {
      expect(formatDetectedAt(undefined)).toBeNull();
    });

    it('returns null for invalid date string', () => {
      expect(formatDetectedAt('not-a-date')).toBeNull();
    });

    it('returns time-only for a detection today', () => {
      expect(formatDetectedAt('2026-01-15T11:30:00Z')).toBe('11:30');
    });

    // ── Explicit instants, not `new Date()` + local setters ──
    //
    // The clock above is pinned, but these fixtures used to step it with `setDate`/`setHours`,
    // which read and write the RUNNER's calendar fields. `formatDetectedAt` now decides the day on
    // the UK one, so the two disagree off Greenwich: in America/New_York, "yesterday 21:34" local
    // is 02:34Z the same UK day, and the assertion below is about the branch that is no longer
    // taken. January, so the UK is on GMT and these instants are also their own UK wall clock.
    it('returns "yesterday HH:MM" for a detection yesterday', () => {
      expect(formatDetectedAt('2026-01-14T21:34:00Z')).toBe('yesterday 21:34');
    });

    it('returns "D Mon HH:MM" for a detection two days ago', () => {
      expect(formatDetectedAt('2026-01-13T14:22:00Z')).toBe('13 Jan 14:22');
    });
  });
});
