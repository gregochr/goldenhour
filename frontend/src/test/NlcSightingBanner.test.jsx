import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import NlcSightingBanner, { formatReportedAt } from '../components/NlcSightingBanner.jsx';

// Mock the useNlcSighting hook
vi.mock('../hooks/useNlcSighting.js', () => ({
  useNlcSighting: vi.fn(),
}));

import { useNlcSighting } from '../hooks/useNlcSighting.js';

function renderBanner(sighting, props = {}) {
  useNlcSighting.mockReturnValue({ sighting, loading: false });
  return render(<NlcSightingBanner {...props} />);
}

/** Minimal valid "active + clear" sighting; spread + override per test. */
function activeSighting(overrides = {}) {
  const twoHoursAgo = new Date();
  twoHoursAgo.setHours(twoHoursAgo.getHours() - 2);
  return {
    active: true,
    clearTonight: true,
    reportedAt: twoHoursAgo.toISOString(),
    observerLocation: 'Dumfries',
    source: 'NLCNET',
    lookDirection: 'N–NW',
    darkSkyLocationCount: 64,
    hexColour: '#8E86D6',
    description: 'Noctilucent cloud reported over southern Scotland',
    ...overrides,
  };
}

describe('NlcSightingBanner', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.location.hash = '';
  });

  // ---------------------------------------------------------------------------
  // Not rendered cases
  // ---------------------------------------------------------------------------

  it('returns null when sighting is null (free-tier / 403 / no data)', () => {
    const { container } = renderBanner(null);
    expect(container.firstChild).toBeNull();
  });

  it('returns null when there is no active sighting (active: false)', () => {
    const { container } = renderBanner(activeSighting({ active: false }));
    expect(container.firstChild).toBeNull();
  });

  it('returns null when skies are not clear (clearTonight: false)', () => {
    const { container } = renderBanner(activeSighting({ clearTonight: false }));
    expect(container.firstChild).toBeNull();
  });

  // ---------------------------------------------------------------------------
  // Rendered cases
  // ---------------------------------------------------------------------------

  it('renders when a sighting is active and skies are clear', () => {
    renderBanner(activeSighting());
    expect(screen.getByTestId('nlc-sighting-banner')).toBeInTheDocument();
  });

  it('renders when clearTonight is omitted (backend already gated)', () => {
    const s = activeSighting();
    delete s.clearTonight;
    renderBanner(s);
    expect(screen.getByTestId('nlc-sighting-banner')).toBeInTheDocument();
  });

  it('displays the sighting description', () => {
    renderBanner(activeSighting());
    expect(screen.getByText(/reported over southern Scotland/i)).toBeInTheDocument();
  });

  it('shows the relative "Xh ago" report label', () => {
    // Pin the clock. `activeSighting()` derives reportedAt as "two hours ago" from the real one,
    // so on any run within two hours of UK midnight that lands on the previous day and
    // formatReportedAt correctly returns "yesterday HH:MM" instead. The component was right and
    // the test was wrong: this assertion is about the same-day branch, so it has to own the time.
    //
    // An INSTANT, not the local literal this used to be. The same-day branch is now decided on the
    // UK calendar, so a wall-clock pin only names a UK midday on a runner that happens to be in the
    // UK — in Pacific/Auckland `'2026-07-15T12:00:00'` is 01:00 BST and the sighting falls on the
    // 14th, which is a real failure this file caught.
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-07-15T11:00:00Z')); // 12:00 BST
    try {
      renderBanner(activeSighting());
      expect(screen.getByText(/Sighting · 2h ago/i)).toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  it('shows the observer location and source', () => {
    renderBanner(activeSighting());
    expect(screen.getByText(/Dumfries via NLCNET/i)).toBeInTheDocument();
  });

  it('shows the look direction', () => {
    renderBanner(activeSighting());
    expect(screen.getByText(/Look N–NW, low/i)).toBeInTheDocument();
  });

  it('shows the dark-sky clear-count line when present', () => {
    renderBanner(activeSighting({ darkSkyLocationCount: 64 }));
    const el = screen.getByTestId('nlc-sighting-darksky');
    expect(el).toBeInTheDocument();
    expect(el.textContent).toMatch(/64 dark-sky sites clear/i);
  });

  it('uses singular "site" when the dark-sky count is 1', () => {
    renderBanner(activeSighting({ darkSkyLocationCount: 1 }));
    expect(screen.getByTestId('nlc-sighting-darksky').textContent).toMatch(/1 dark-sky site clear/i);
  });

  it('omits the dark-sky line when the count is zero', () => {
    renderBanner(activeSighting({ darkSkyLocationCount: 0 }));
    expect(screen.queryByTestId('nlc-sighting-darksky')).not.toBeInTheDocument();
  });

  it('drives the accent colour from the API hexColour', () => {
    renderBanner(activeSighting({ hexColour: '#a78bfa' }));
    const banner = screen.getByTestId('nlc-sighting-banner');
    expect(banner.style.getPropertyValue('--nlc-accent')).toBe('#a78bfa');
  });

  it('falls back to the default violet accent when hexColour is absent', () => {
    const s = activeSighting();
    delete s.hexColour;
    renderBanner(s);
    expect(screen.getByTestId('nlc-sighting-banner').style.getPropertyValue('--nlc-accent')).toBe('#8E86D6');
  });

  // ---------------------------------------------------------------------------
  // Dismiss behaviour
  // ---------------------------------------------------------------------------

  it('dismiss button hides the banner for the current report', () => {
    renderBanner(activeSighting());
    fireEvent.click(screen.getByTestId('nlc-sighting-dismiss'));
    expect(screen.queryByTestId('nlc-sighting-banner')).not.toBeInTheDocument();
  });

  it('dismiss click does not propagate to the banner (no hash navigation)', () => {
    const originalHash = window.location.hash;
    renderBanner(activeSighting());
    fireEvent.click(screen.getByTestId('nlc-sighting-dismiss'));
    expect(window.location.hash).toBe(originalHash);
  });

  it('stays hidden after dismiss when the same report is returned', () => {
    const s = activeSighting();
    const { rerender } = renderBanner(s);
    fireEvent.click(screen.getByTestId('nlc-sighting-dismiss'));

    useNlcSighting.mockReturnValue({ sighting: s, loading: false });
    rerender(<NlcSightingBanner />);
    expect(screen.queryByTestId('nlc-sighting-banner')).not.toBeInTheDocument();
  });

  it('re-shows after dismiss when a NEWER report arrives', () => {
    const { rerender } = renderBanner(activeSighting());
    fireEvent.click(screen.getByTestId('nlc-sighting-dismiss'));
    expect(screen.queryByTestId('nlc-sighting-banner')).not.toBeInTheDocument();

    const newer = new Date().toISOString();
    useNlcSighting.mockReturnValue({
      sighting: activeSighting({ reportedAt: newer }),
      loading: false,
    });
    rerender(<NlcSightingBanner />);
    expect(screen.getByTestId('nlc-sighting-banner')).toBeInTheDocument();
  });

  // ---------------------------------------------------------------------------
  // Navigation
  // ---------------------------------------------------------------------------

  it('navigates to the map when the banner is clicked', () => {
    renderBanner(activeSighting());
    fireEvent.click(screen.getByTestId('nlc-sighting-banner'));
    expect(window.location.hash).toBe('#map');
  });

  // ---------------------------------------------------------------------------
  // Accessibility
  // ---------------------------------------------------------------------------

  it('has role="alert" for screen reader support', () => {
    renderBanner(activeSighting());
    expect(screen.getByRole('alert')).toBeInTheDocument();
  });

  it('dismiss button has an accessible label', () => {
    renderBanner(activeSighting());
    expect(screen.getByLabelText(/dismiss noctilucent sighting banner/i)).toBeInTheDocument();
  });

  // ---------------------------------------------------------------------------
  // formatReportedAt helper — unit tests
  // ---------------------------------------------------------------------------

  // The window-first arm has no Map tab, so there is nothing for this banner to switch to. Unlike
  // the aurora banner beside it, there is also nothing to re-route it to — v1's action is a bare tab
  // switch carrying no event type, no filter and no location — so inert is the honest state. What
  // matters is that when it cannot act it also stops LOOKING like a control.
  describe('when it has no destination to offer', () => {
    it('is not dressed as a control', () => {
      renderBanner(activeSighting(), { interactive: false });
      const banner = screen.getByTestId('nlc-sighting-banner');
      expect(banner).not.toHaveAttribute('tabindex');
      expect(banner.className).not.toMatch(/cursor-pointer/);
      expect(banner.textContent).not.toMatch(/show on map/i);
    });

    it('does nothing when pressed, and leaves the tab where it was', () => {
      window.location.hash = '';
      renderBanner(activeSighting(), { interactive: false });
      fireEvent.click(screen.getByTestId('nlc-sighting-banner'));
      // The state change, not "the banner is still on screen" — that would pass with the handler
      // deleted or intact, since nothing about the banner depends on it.
      expect(window.location.hash).toBe('');
    });

    // The positive half. Without it every assertion above passes for a banner that is inert always,
    // which would silently break the arm this banner actually works in.
    it('is still a control in the arm that has a Map tab', () => {
      window.location.hash = '';
      renderBanner(activeSighting());
      const banner = screen.getByTestId('nlc-sighting-banner');
      expect(banner).toHaveAttribute('tabindex', '0');
      expect(banner.textContent).toMatch(/show on map/i);
      fireEvent.click(banner);
      expect(window.location.hash).toBe('#map');
      window.location.hash = '';
    });
  });

  describe('formatReportedAt()', () => {
    it('returns null for null input', () => {
      expect(formatReportedAt(null)).toBeNull();
    });

    it('returns null for undefined input', () => {
      expect(formatReportedAt(undefined)).toBeNull();
    });

    // ── A pinned clock, and the reason is not tidiness ──
    //
    // Every assertion below is about which BRANCH `formatReportedAt` takes, and the branches are
    // chosen by the distance between "now" and the timestamp. Against a real clock the test's
    // meaning therefore depends on the hour CI happens to run at, and two of these were broken by
    // that: the "yesterday" case failed for the first ~50 minutes of every day, because a report
    // stamped yesterday 23:10 is under an hour old at 00:07 and the sub-hour branch wins; and the
    // "Nh ago" case carried a midnight guard that made it assert NOTHING between 00:00 and 03:00,
    // passing vacuously for three hours out of every twenty-four.
    //
    // ── INSTANT literals, and the reason inverted with the fix ──
    //
    // These were local wall-clock literals (`new Date('2026-08-11T14:30:00')`, `setHours(23, 10)`)
    // on the argument that the formatter read local getters, so pinning the wall clock "removed the
    // timezone from the question entirely". That was true of the old code and is now false of the
    // new: `formatReportedAt` reads Europe/London for both the clock it prints and the day it
    // credits, so a local literal is the thing that moves with the runner. Under the suite's UTC pin
    // `setHours(23, 10)` on 10 August is 00:10 BST on the ELEVENTH — today, not yesterday — and the
    // two day-branch cases below failed for exactly that reason.
    //
    // So each literal is now an explicit instant, chosen so the UK wall clock is the one the
    // assertion names. August, so BST: UTC+1.
    /** 14:30 BST on 11 August 2026 — an ordinary UK afternoon. */
    const NOW = '2026-08-11T13:30:00Z';
    /** 23:10 BST on 10 August — last night, and comfortably more than an hour before NOW. */
    const YESTERDAY_2310 = '2026-08-10T22:10:00Z';
    /** 23:10 BST on 9 August — two nights back, past the "yesterday" band. */
    const TWO_NIGHTS_AGO_2310 = '2026-08-09T22:10:00Z';

    beforeEach(() => {
      vi.useFakeTimers({ toFake: ['Date'] });
      vi.setSystemTime(new Date(NOW));
    });
    afterEach(() => vi.useRealTimers());

    const minutesAgo = (n) => new Date(Date.now() - n * 60000).toISOString();

    it('returns null for an invalid date string', () => {
      expect(formatReportedAt('not-a-date')).toBeNull();
    });

    it('returns null when there is no timestamp at all', () => {
      expect(formatReportedAt(null)).toBeNull();
      expect(formatReportedAt('')).toBeNull();
    });

    it('returns "just now" for a report under a minute old', () => {
      expect(formatReportedAt(new Date(Date.now() - 20000).toISOString())).toBe('just now');
    });

    it('returns "Nm ago" for a report under an hour old', () => {
      expect(formatReportedAt(minutesAgo(45))).toBe('45m ago');
    });

    // The boundary, which nothing pinned before: 59 minutes is still minutes, 60 is an hour.
    it('switches from minutes to hours at the hour', () => {
      expect(formatReportedAt(minutesAgo(59))).toBe('59m ago');
      expect(formatReportedAt(minutesAgo(60))).toBe('1h ago');
    });

    it('returns "Nh ago" for a report earlier today', () => {
      // No midnight guard: the clock is pinned to the afternoon, so this can no longer skip itself.
      expect(formatReportedAt(minutesAgo(180))).toBe('3h ago');
    });

    it('returns "yesterday HH:MM" for a report from yesterday', () => {
      expect(formatReportedAt(YESTERDAY_2310)).toBe('yesterday 23:10');
    });

    // The precedence that caused the flake, now stated as a rule rather than discovered as a bug:
    // "under an hour" beats "yesterday". Just after midnight a report from 23:10 is 57 minutes old,
    // and how long ago it was is more useful than which calendar day it fell in.
    it('prefers minutes to "yesterday" when yesterday was under an hour ago', () => {
      vi.setSystemTime(new Date('2026-08-10T23:07:00Z')); // 00:07 BST on the 11th
      expect(formatReportedAt(YESTERDAY_2310)).toBe('57m ago');
    });

    it('returns "D Mon HH:MM" for a report two days ago', () => {
      expect(formatReportedAt(TWO_NIGHTS_AGO_2310)).toBe('9 Aug 23:10');
    });

    // The defect this fix is about, at the boundary where it bites: 00:30 BST on the 11th is
    // "today" in the UK and "yesterday" (19:30 on the 10th) in New York. The old code decided the
    // day from the device's calendar fields, so this rendered "yesterday 00:30" — a UK clock time
    // under a foreign day label, which neither basis would have produced on its own.
    it('credits a UK small-hours report to the UK day, not the device day', () => {
      vi.setSystemTime(new Date('2026-08-11T05:00:00Z')); // 06:00 BST, so 5.5h after the report
      expect(formatReportedAt('2026-08-10T23:30:00Z')).toBe('5h ago');
    });
  });
});
