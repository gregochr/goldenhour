import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import AuroraBanner, { SEVERITY, bzStatus, formatDetectedAt } from '../components/AuroraBanner.jsx';
import { auroraRampHex } from '../utils/auroraRamp.js';

// Mock the useAuroraStatus hook
vi.mock('../hooks/useAuroraStatus.js', () => ({
  useAuroraStatus: vi.fn(),
}));

// The banner no longer asks for the viewline at all (B3 brings it back behind a flag); the mock
// exists so a regression that re-adds the call fails a test instead of firing a real request.
vi.mock('../hooks/useAuroraViewline.js', () => ({
  useAuroraViewline: vi.fn(),
}));

// The banner picks its layout from useIsMobile (< 640px). The hook's own query — `(max-width: 639px)`
// — is pinned in useIsMobile.test.js, so here it is a switch: the pattern FiltersPopover.test.jsx uses.
let mockIsMobile = false;
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => mockIsMobile }));

import { useAuroraStatus } from '../hooks/useAuroraStatus.js';
import { useAuroraViewline } from '../hooks/useAuroraViewline.js';

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
    // The hook is never called today; answering the shape B3 will read keeps a regression that
    // re-adds the call failing on the `not.toHaveBeenCalled` below rather than on a destructure.
    useAuroraViewline.mockReturnValue({ viewline: null, source: null });
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

  it('never asks for the viewline: the banner draws no viewline sentence and makes no request for one', () => {
    renderBanner({ ...MODERATE });
    expect(useAuroraViewline).not.toHaveBeenCalled();
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
    });

    it('STRONG sets the Strong accent, ink and 92% fill although the API said amber', () => {
      renderBanner({ ...STRONG });
      const banner = screen.getByTestId('aurora-banner');
      expect(banner.style.getPropertyValue('--acc')).toBe('#76FFA0');
      expect(banner.style.getPropertyValue('--acc-ink')).toBe('#A8FFC4');
      expect(banner.style.getPropertyValue('--fill')).toBe('92%');
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

    it('offers "View on map →" for a real alert and "Generate scores →" for a simulated one', () => {
      const real = renderBanner({ ...MODERATE });
      expect(screen.getByText('View on map →')).toBeInTheDocument();
      real.unmount();
      renderBanner({ ...MODERATE, simulated: true });
      expect(screen.getByText('Generate scores →')).toBeInTheDocument();
      expect(screen.queryByText('View on map →')).not.toBeInTheDocument();
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

    it('offers "Map →", and "Scores →" for a simulated alert whose door is not the map', () => {
      const real = renderBanner({ ...MODERATE });
      expect(screen.getByText('Map →')).toBeInTheDocument();
      real.unmount();
      renderBanner({ ...MODERATE, simulated: true });
      expect(screen.getByText('Scores →')).toBeInTheDocument();
      expect(screen.queryByText('Map →')).not.toBeInTheDocument();
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

    it('falls back to a hash jump to the map when no onViewOnMap is supplied', () => {
      renderBanner({ ...MODERATE });

      fireEvent.click(screen.getByTestId('aurora-banner'));

      expect(window.location.hash).toBe('#map');
    });

    it('a simulated alert goes to the manage hash and never calls onViewOnMap', () => {
      const onViewOnMap = vi.fn();
      renderBanner({ ...MODERATE, simulated: true }, { onViewOnMap });

      fireEvent.click(screen.getByTestId('aurora-banner'));

      expect(onViewOnMap).not.toHaveBeenCalled();
      expect(window.location.hash).toBe('#manage');
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

    it('is a focusable role="alert"', () => {
      renderBanner({ ...MODERATE });
      const alert = screen.getByRole('alert');
      expect(alert).toBe(screen.getByTestId('aurora-banner'));
      expect(alert).toHaveAttribute('tabindex', '0');
    });

    it('has a dismiss button with an accessible name, that is a real button', () => {
      renderBanner({ ...MODERATE });
      expect(screen.getByRole('button', { name: 'Dismiss aurora banner' })).toHaveAttribute('type', 'button');
    });
  });

  it('hides decorative parts of the card from assistive technology and keeps the Kp figure readable', () => {
    renderBanner({ ...MODERATE, forecastKp: 6 });
    expect(screen.getByTestId('aurora-banner-meter')).toHaveAttribute('aria-hidden', 'true');
    expect(screen.getByTestId('aurora-banner-kp')).not.toHaveAttribute('aria-hidden');
  });

  // ---------------------------------------------------------------------------
  // bzStatus helper — unit tests (SkyRatingEvalView still imports it)
  // ---------------------------------------------------------------------------

  describe('bzStatus()', () => {
    it('returns ✅ and "favourable for aurora" for Bz < −1', () => {
      const result = bzStatus(-9.2);
      expect(result.emoji).toBe('✅');
      expect(result.label).toBe('Bz south (-9.2 nT)');
      expect(result.explanation).toBe('favourable for aurora');
    });

    it('returns ✅ for moderate negative Bz (−3.1)', () => {
      const result = bzStatus(-3.1);
      expect(result.emoji).toBe('✅');
      expect(result.label).toBe('Bz south (-3.1 nT)');
      expect(result.explanation).toBe('favourable for aurora');
    });

    it('returns ➖ and "neutral" for Bz near zero (−0.5)', () => {
      const result = bzStatus(-0.5);
      expect(result.emoji).toBe('➖');
      expect(result.label).toBe('Bz near zero (-0.5 nT)');
      expect(result.explanation).toBe('neutral');
    });

    it('returns ⚠️ and "poor for aurora" for Bz north (+3.2)', () => {
      const result = bzStatus(3.2);
      expect(result.emoji).toBe('⚠️');
      expect(result.label).toBe('Bz north (+3.2 nT)');
      expect(result.explanation).toBe('poor for aurora');
    });

    it('returns ⚠️ and "poor for aurora" for Bz firmly north (+7.8)', () => {
      const result = bzStatus(7.8);
      expect(result.emoji).toBe('⚠️');
      expect(result.label).toBe('Bz north (+7.8 nT)');
      expect(result.explanation).toBe('poor for aurora');
    });

    it('contains no interpretive severity language', () => {
      // Bz text should be factual only — severity comes from the Kp headline
      for (const bz of [-9.2, -3.1, -0.5, 3.2, 7.8]) {
        const result = bzStatus(bz);
        expect(result.explanation).not.toMatch(/faint|strong|should be visible|aurora possible|aurora unlikely|aurora expected|blocked|borderline/i);
      }
    });
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
