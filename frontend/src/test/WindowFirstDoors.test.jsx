import React from 'react';
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import WindowFirstDoors from '../components/WindowFirstDoors.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';

// Only the regional panel is stubbed, and only because mounting it fires one astro request per
// visible date — this file is about the door, not about the grid, and the grid has its own suite.
vi.mock('../components/WindowFirstRegionalPanel.jsx', () => ({
  default: () => <div data-testid="stub-regional" />,
}));

const EVENTS = [{ date: '2026-08-04', targetType: 'SUNSET' }];

const ctx = (overrides = {}) => ({
  // The regional door gates on the TRAVEL-FILTERED set, which is what `windowCards` is — the grid
  // drops away columns itself, so a horizon that is entirely away has no grid behind the door.
  windowCards: EVENTS.map((e) => ({ key: `${e.date}:${e.targetType}`, ...e })),
  // Supplied and deliberately NEVER equal to `windowCards` in the travel test below, so a revert to
  // the pre-review gate is caught rather than passing on a fixture where both are the same length.
  upcomingEvents: EVENTS,
  ...overrides,
});

/**
 * jsdom has no layout, and `useIsMobile` reads `window.matchMedia` — which jsdom does not implement
 * at all, so it must be stubbed or the hook throws. Defaulting to desktop keeps every other test in
 * this file on the path it is about.
 */
function setViewport(mobile) {
  window.matchMedia = (query) => ({
    matches: mobile && query.includes('639px'),
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    onchange: null,
    dispatchEvent: () => false,
  });
}

const renderDoors = (overrides = {}, props = {}) => {
  vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx(overrides));
  const handlers = { onShowOnMap: vi.fn(), ...props };
  // `unmount` is returned alongside the handlers so a test can round-trip the component, which is
  // the only honest way to assert that state survives an unmount and remount (a tab switch away
  // and back, for instance).
  const { unmount } = render(<WindowFirstDoors locations={[]} {...handlers} />);
  return { ...handlers, unmount };
};

beforeEach(() => {
  setViewport(false);
  // Locally this is a PROCESS-level store that survives across files in a reused worker, so a leak
  // from another suite is invisible on CI and very real here.
  sessionStorage.clear();
});
afterEach(() => vi.restoreAllMocks());

describe('WindowFirstDoors', () => {
  describe('what the door claims', () => {
    it('names the door and what is behind it', () => {
      renderDoors();
      expect(screen.getByTestId('window-first-door-regional')).toHaveTextContent('Regional planner');
      expect(screen.getByTestId('window-first-door-regional')).toHaveTextContent('every region, every window');
    });

    it('counts no regions, because the roster is not a fact about tonight', () => {
      // The mock's "4 regions →" is the species §6 bans outright — the same charge that removed
      // P7's "61 coastal locations →". Asserted against the tile's whole text, not against one
      // element, because a count could reappear anywhere in it.
      renderDoors();
      expect(screen.getByTestId('window-first-door-regional').textContent).not.toMatch(/\d+\s*regions?/i);
    });
  });

  describe('a door with nothing behind it is not drawn', () => {
    it('drops the regional door when there are no windows to plan over', () => {
      renderDoors({ windowCards: [], upcomingEvents: [] });
      expect(screen.queryByTestId('window-first-door-regional')).toBeNull();
    });

    it('keeps the regional door on a phone, now that the grid has a phone layout', () => {
      // This assertion is INVERTED from what it pinned before, and deliberately so. The old rule
      // was "no door on a phone, because `HeatmapGrid` is `hidden sm:grid` and the tile would open
      // a ~26px empty bordered box". The grid now renders at every width (a scroller with the
      // region column pinned), so the gate it stood in for is gone and the owner gets the full plan
      // on the surface they actually read on.
      //
      // It also removes this file's side of the rem/px seam: the gate was `useIsMobile`
      // (`max-width: 639px`, px) standing in for Tailwind's `sm:` (40rem).
      setViewport(true);
      renderDoors();
      expect(screen.getByTestId('window-first-door-regional')).toBeInTheDocument();
    });

    it('drops the regional door when every window in the horizon is a travel day', () => {
      // Found by review. The gate read `upcomingEvents`, which is the list BEFORE the travel filter,
      // while the grid drops away columns itself — so a fortnight away drew a door promising "every
      // region, every window" over a panel holding one dashed band, whose own wording ("no forecast
      // generated") is the phrase the away row directly above it deliberately rejects.
      //
      // `upcomingEvents` is deliberately NON-empty here while `windowCards` is empty — that is
      // exactly the all-away state, and it is the only fixture in which the two candidate gates
      // disagree. A test where both were empty would pass under either implementation.
      renderDoors({ windowCards: [], upcomingEvents: EVENTS });
      expect(screen.queryByTestId('window-first-door-regional')).toBeNull();
    });

    it('renders nothing at all when there is nothing behind the door', () => {
      renderDoors({ windowCards: [] });
      expect(screen.queryByTestId('window-first-doors')).toBeNull();
    });
  });

  describe('the disclosure contract', () => {
    it('starts closed, and says so', () => {
      renderDoors();
      const door = screen.getByRole('button', { name: /Regional planner/ });
      expect(door).toHaveAttribute('aria-expanded', 'false');
      expect(door).toHaveTextContent('Open');
    });

    it('points at a panel element that exists while it is closed', () => {
      // `aria-controls` is an IDREF. A closed door whose panel is unmounted points at nothing.
      renderDoors();
      const target = screen.getByTestId('window-first-door-regional').getAttribute('aria-controls');
      expect(document.getElementById(target)).toBeInTheDocument();
      expect(document.getElementById(target)).toHaveAttribute('hidden');
    });

    it('mounts nothing behind the door until it is opened', () => {
      // The whole point of the door: the regional panel fires one astro request per visible date
      // on mount, and a closed door must cost nothing.
      renderDoors();
      expect(screen.queryByTestId('stub-regional')).toBeNull();
    });

    it('mounts the panel and unhides it on open', () => {
      renderDoors();
      fireEvent.click(screen.getByTestId('window-first-door-regional'));

      const door = screen.getByTestId('window-first-door-regional');
      expect(door).toHaveAttribute('aria-expanded', 'true');
      expect(door).toHaveTextContent('Collapse');
      expect(screen.getByTestId('stub-regional')).toBeInTheDocument();
      expect(document.getElementById(door.getAttribute('aria-controls'))).not.toHaveAttribute('hidden');
    });

    it('keeps the panel mounted once opened, hiding it instead of tearing it down', () => {
      // Unmounting would refire the astro wave on every reopen, and would leave `aria-controls`
      // dangling again. `hidden` is display:none — no layout, and out of the accessibility tree.
      renderDoors();
      const door = screen.getByTestId('window-first-door-regional');

      fireEvent.click(door);
      fireEvent.click(door);

      expect(door).toHaveAttribute('aria-expanded', 'false');
      expect(screen.getByTestId('stub-regional')).toBeInTheDocument();
      expect(document.getElementById(door.getAttribute('aria-controls'))).toHaveAttribute('hidden');
    });
  });

  // Without persistence, remounting the pane collapsed the door even when the reader had just
  // opened it — a working position lost to incidental React churn.
  describe('remembering whether the door was left open', () => {
    const doorPanel = () => screen.queryByTestId('window-first-panel-regional-body');

    // The behavioural round trip, and the only one here that fails for the right reason. Written
    // first for that reason: the storage assertions below all pass for an implementation that
    // writes correctly and restores nothing.
    it('reopens the door it was left with', () => {
      const { unmount } = renderDoors();
      expect(doorPanel()).toBeNull();

      fireEvent.click(screen.getByTestId('window-first-door-regional'));
      expect(doorPanel()).toBeInTheDocument();
      unmount();

      renderDoors();
      expect(doorPanel()).toBeInTheDocument();
      // The control must not claim a state the DOM lacks — a restored door whose panel was never
      // mounted would announce `aria-expanded="true"` over nothing.
      expect(screen.getByTestId('window-first-door-regional')).toHaveAttribute('aria-expanded', 'true');
    });

    it('starts closed on a fresh session', () => {
      renderDoors();
      expect(doorPanel()).toBeNull();
      expect(screen.getByTestId('window-first-door-regional')).toHaveAttribute('aria-expanded', 'false');
    });

    it('keeps a closed door closed across the round trip', () => {
      // The negative half. Without it, "reopens what was left" passes for a component that simply
      // opens everything on mount.
      const { unmount } = renderDoors();
      fireEvent.click(screen.getByTestId('window-first-door-regional'));
      fireEvent.click(screen.getByTestId('window-first-door-regional'));
      unmount();

      renderDoors();
      expect(doorPanel()).toBeNull();
    });

    // ⚠️ Never spy on storage in this suite. `setup.js` installs a plain-object substitute only when
    // jsdom does not supply one, which is true on this project's Macs and false on CI — so an
    // instance spy passes locally and records nothing on the runner, and a `Storage.prototype` spy
    // does the reverse. This project has already lost a CI round to exactly that. Observe through
    // `length`/`key`, which both implementations have.
    it('keeps the door state out of localStorage, where the settled preferences live', () => {
      const keysNow = (store) => Array.from({ length: store.length }, (_, i) => store.key(i));

      // Control: prove the observation can see a write at all in whichever storage this environment
      // supplied. Without it, "nothing was written" passes on a mechanism that sees nothing.
      localStorage.setItem('control', '1');
      expect(keysNow(localStorage)).toContain('control');
      localStorage.removeItem('control');

      // A snapshot rather than `[]`, so the assertion is about what THIS interaction wrote and
      // cannot be broken by an unrelated key the environment happens to carry.
      const before = keysNow(localStorage);

      renderDoors();
      fireEvent.click(screen.getByTestId('window-first-door-regional'));

      expect(keysNow(localStorage)).toEqual(before);
      expect(keysNow(sessionStorage)).toContain('photocast.planDoors');
    });
  });
});
