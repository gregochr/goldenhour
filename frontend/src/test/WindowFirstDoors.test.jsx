import React from 'react';
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import WindowFirstDoors from '../components/WindowFirstDoors.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';

// Only the regional panel is stubbed, and only because mounting it fires one astro request per
// visible date — this file is about the door, not about the grid, and the grid has its own suite.
// `HotTopicStrip` is rendered FOR REAL: the one thing P9 decided about it is what it is handed for
// a LITE user, and a stub that echoed the prop back would assert the test's own fixture.
vi.mock('../components/WindowFirstRegionalPanel.jsx', () => ({
  default: () => <div data-testid="stub-regional" />,
}));

const TOPIC = {
  type: 'SPRING_TIDE', label: 'Spring tides', date: '2026-08-05', filterAction: 'TIDE',
};

const EVENTS = [{ date: '2026-08-04', targetType: 'SUNSET' }];

const ctx = (overrides = {}) => ({
  briefing: { generatedAt: '2026-08-04T12:00:00', hotTopics: [TOPIC] },
  // The regional door gates on the TRAVEL-FILTERED set, which is what `windowCards` is — the grid
  // drops away columns itself, so a horizon that is entirely away has no grid behind the door.
  windowCards: EVENTS.map((e) => ({ key: `${e.date}:${e.targetType}`, ...e })),
  // Supplied and deliberately NEVER equal to `windowCards` in the travel test below, so a revert to
  // the pre-review gate is caught rather than passing on a fixture where both are the same length.
  upcomingEvents: EVENTS,
  isLiteUser: false,
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
  // the only honest way to assert that state survives the arm being swapped out.
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
  describe('what each door claims', () => {
    it('names both doors and what is behind them', () => {
      renderDoors();
      expect(screen.getByTestId('window-first-door-regional')).toHaveTextContent('Regional planner');
      expect(screen.getByTestId('window-first-door-regional')).toHaveTextContent('every region, every window');
      expect(screen.getByTestId('window-first-door-topics')).toHaveTextContent('Hot topics');
      expect(screen.getByTestId('window-first-door-topics')).toHaveTextContent('the detail behind the badges');
    });

    it('counts no regions, because the roster is not a fact about tonight', () => {
      // The mock's "4 regions →" is the species §6 bans outright — the same charge that removed
      // P7's "61 coastal locations →". Asserted against the tile's whole text, not against one
      // element, because a count could reappear anywhere in it.
      renderDoors();
      expect(screen.getByTestId('window-first-door-regional').textContent).not.toMatch(/\d+\s*regions?/i);
    });

    it('counts no live topics either, though that one was arguable', () => {
      // Dropped for a reason about the PAIR: two tiles of identical construction where one carries
      // a number and the other cannot reads as a defect in the one that does not.
      renderDoors({ briefing: { hotTopics: [TOPIC, { ...TOPIC, type: 'AURORA' }] } });
      expect(screen.getByTestId('window-first-door-topics').textContent).not.toMatch(/\d+\s*live/i);
      expect(screen.getByTestId('window-first-door-topics').textContent).not.toMatch(/\b\d+\b/);
    });
  });

  describe('a door with nothing behind it is not drawn', () => {
    it('drops the hot-topics door when there are no topics', () => {
      // This is the honest form of "3 live": the reader learns whether it is worth opening by
      // whether it is there, and never opens a door onto an empty room.
      renderDoors({ briefing: { hotTopics: [] } });
      expect(screen.queryByTestId('window-first-door-topics')).toBeNull();
      expect(screen.getByTestId('window-first-door-regional')).toBeInTheDocument();
    });

    it('drops it when the payload carries no hotTopics field at all', () => {
      // A legacy cached briefing. Distinct input from an empty array.
      renderDoors({ briefing: { generatedAt: '2026-08-04T12:00:00' } });
      expect(screen.queryByTestId('window-first-door-topics')).toBeNull();
    });

    it('drops the regional door when there are no windows to plan over', () => {
      renderDoors({ windowCards: [], upcomingEvents: [] });
      expect(screen.queryByTestId('window-first-door-regional')).toBeNull();
      expect(screen.getByTestId('window-first-door-topics')).toBeInTheDocument();
    });

    it('drops the regional door on a phone, where the grid renders nothing at all', () => {
      // Found by review. `HeatmapGrid`'s whole output is `hidden sm:grid` / `hidden sm:flex`, and
      // the v1 arm wraps the same disclosure in `hidden sm:block` (DailyBriefing.jsx:1526) — a guard
      // the re-parenting dropped. Without this the tile opened a ~26px empty bordered box and fired
      // one astro request per date for content that cannot paint.
      setViewport(true);
      renderDoors();
      expect(screen.queryByTestId('window-first-door-regional')).toBeNull();
      expect(screen.getByTestId('window-first-door-topics')).toBeInTheDocument();
    });

    it('keeps the hot-topics door on a phone, because that strip has no breakpoint gate', () => {
      // The two tiles look identical, so hiding both would be as wrong as hiding neither.
      setViewport(true);
      renderDoors();
      fireEvent.click(screen.getByTestId('window-first-door-topics'));
      expect(screen.getByTestId('hot-topic-strip')).toBeInTheDocument();
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

    it('renders nothing at all when neither has anything behind it', () => {
      renderDoors({ windowCards: [], briefing: { hotTopics: [] } });
      expect(screen.queryByTestId('window-first-doors')).toBeNull();
    });

    it('renders nothing when there is no briefing yet', () => {
      renderDoors({ briefing: null, windowCards: [] });
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

    it('mounts nothing behind a door until it is opened', () => {
      // The whole point of the door: the regional panel fires one astro request per visible date
      // on mount, and a closed door must cost nothing.
      renderDoors();
      expect(screen.queryByTestId('stub-regional')).toBeNull();
      expect(screen.queryByTestId('hot-topic-strip')).toBeNull();
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

    it('leaves one door alone when the other is opened', () => {
      // Both panels are tall and both sit at the foot of a long pane, so a radio pair would
      // collapse one under the reader with no cause they can see.
      renderDoors();
      fireEvent.click(screen.getByTestId('window-first-door-regional'));
      fireEvent.click(screen.getByTestId('window-first-door-topics'));

      expect(screen.getByTestId('window-first-door-regional')).toHaveAttribute('aria-expanded', 'true');
      expect(screen.getByTestId('window-first-door-topics')).toHaveAttribute('aria-expanded', 'true');
      expect(screen.getByTestId('stub-regional')).toBeInTheDocument();
      expect(screen.getByTestId('hot-topic-strip')).toBeInTheDocument();
    });
  });

  describe('what the hot-topics door hands the strip', () => {
    it('gives it the briefing\'s own topics', () => {
      renderDoors();
      fireEvent.click(screen.getByTestId('window-first-door-topics'));
      expect(screen.getByTestId('hot-topic-pill-SPRING_TIDE')).toHaveTextContent('Spring tides');
    });

    it('leaves the LITE treatment exactly as the v1 arm has it, which is P9\'s recorded decision', () => {
      // Plan §5b assigned P9 a reconvergence on the blanket fact blur. It is NOT made: the blur is
      // one of five LITE treatments in that component, so editing it alone would leave a greyed,
      // inert pill carrying sharp numbers — strictly more incoherent than today — and editing all
      // of it is a freemium-policy change, not a layout fix. Handed to P15 with the evidence.
      // Asserted through the strip's OWN upsell rather than through the prop, so it cannot pass by
      // echoing this test's fixture back at it.
      renderDoors({ isLiteUser: true });
      fireEvent.click(screen.getByTestId('window-first-door-topics'));
      expect(screen.getByTestId('hot-topic-upsell')).toHaveTextContent('Upgrade to Pro');
    });

    it('shows no upsell to a user who is not on LITE', () => {
      renderDoors({ isLiteUser: false });
      fireEvent.click(screen.getByTestId('window-first-door-topics'));
      expect(screen.queryByTestId('hot-topic-upsell')).toBeNull();
    });

    it('routes a topic tap to the map as a filter, the way the v1 arm does', () => {
      // Two lines reproduced rather than imported, so `DailyBriefing` stays untouched for §4's
      // comparison — which also means nothing else would catch them being dropped.
      const { onShowOnMap } = renderDoors();
      fireEvent.click(screen.getByTestId('window-first-door-topics'));
      fireEvent.click(screen.getByTestId('hot-topic-pill-SPRING_TIDE'));

      expect(onShowOnMap).toHaveBeenCalledWith({ filterAction: 'TIDE', date: '2026-08-05' });
    });

    it('hands over the briefing\'s aurora summary, which the strip cannot fetch itself', () => {
      // Without it an AURORA pill never expands — `resolveAuroraData` returns null and so
      // `canExpandRich` is false — and the topic loses its whole rich card silently.
      renderDoors({
        briefing: {
          hotTopics: [{ type: 'AURORA', label: 'Aurora', date: '2026-08-05', detail: 'tonight' }],
          auroraTonight: { alertLevel: 'MODERATE', kpIndex: 5, locations: [] },
        },
      });
      fireEvent.click(screen.getByTestId('window-first-door-topics'));
      fireEvent.click(screen.getByTestId('hot-topic-pill-AURORA'));

      expect(screen.getByTestId('aurora-expanded-card')).toBeInTheDocument();
    });
  });

  // Both Plan arms are alive at once and the reader flips between them to compare the same night.
  // The v1 arm has always remembered its briefing grid across such a round trip; this one forgot on
  // every unmount, so a flip landed on collapsed doors beside an arm that had stayed open.
  describe('remembering which doors were left open', () => {
    const doorPanel = () => screen.queryByTestId('window-first-panel-topics-body');

    // The behavioural round trip, and the only one here that fails for the right reason. Written
    // first for that reason: the storage assertions below all pass for an implementation that
    // writes correctly and restores nothing.
    it('reopens the doors it was left with', () => {
      const { unmount } = renderDoors();
      expect(doorPanel()).toBeNull();

      fireEvent.click(screen.getByTestId('window-first-door-topics'));
      expect(doorPanel()).toBeInTheDocument();
      unmount();

      renderDoors();
      expect(doorPanel()).toBeInTheDocument();
      // The control must not claim a state the DOM lacks — a restored door whose panel was never
      // mounted would announce `aria-expanded="true"` over nothing.
      expect(screen.getByTestId('window-first-door-topics')).toHaveAttribute('aria-expanded', 'true');
    });

    it('starts closed on a fresh session, which is the property the current Plan keeps too', () => {
      renderDoors();
      expect(doorPanel()).toBeNull();
      expect(screen.getByTestId('window-first-door-topics')).toHaveAttribute('aria-expanded', 'false');
    });

    it('keeps a closed door closed across the round trip', () => {
      // The negative half. Without it, "reopens what was left" passes for a component that simply
      // opens everything on mount.
      const { unmount } = renderDoors();
      fireEvent.click(screen.getByTestId('window-first-door-topics'));
      fireEvent.click(screen.getByTestId('window-first-door-topics'));
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
      fireEvent.click(screen.getByTestId('window-first-door-topics'));

      expect(keysNow(localStorage)).toEqual(before);
      expect(keysNow(sessionStorage)).toContain('photocast.planDoors');
    });
  });
});
