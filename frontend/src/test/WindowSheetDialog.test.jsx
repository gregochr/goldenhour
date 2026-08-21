import React from 'react';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  cleanup, fireEvent, render, screen, within,
} from '@testing-library/react';
import WindowSheetDialog from '../components/WindowSheetDialog.jsx';
import { buildTopicIndex } from '../utils/windowFirstTopics.js';
import { drawGeo } from '../utils/heatField.js';

/**
 * The window popup — one window's whole drill-down, as a dialog over the plan.
 *
 * <h2>What this file owns, and what it does not</h2>
 *
 * <p>The pieces inside were transplanted rather than rewritten, so their own files still own them:
 * {@code WindowRowFieldMap.test.jsx} the field and its chips, {@code WindowRegionRail.test.jsx} the
 * cells, {@code WindowProseSlot.test.jsx} the prose, {@code WindowTopicRows.test.jsx} the topic
 * rows, {@code WindowSpotStrip.test.jsx} the ranked list. What is asserted here is what only the
 * DIALOG can be wrong about: its semantics and its name, what the header may claim, that picking a
 * region swaps words without moving furniture, that the map can never name a spot the list has
 * excluded, and the quiet sentence's two variants.
 *
 * <p>The kernel is mocked at its boundary and returns a linear stub projection, for the reason
 * {@code WindowRowFieldMap.test.jsx} records: jsdom paints no canvas, and a real Mercator would make
 * every expected pixel a magic number nobody could check.
 */

vi.mock('../utils/heatField.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    load: vi.fn(() => Promise.resolve({ type: 'FeatureCollection', features: [] })),
    land: vi.fn(() => ({ type: 'FeatureCollection', features: [] })),
    drawGeo: vi.fn(() => ([lng, lat]) => [lng * 10, lat * 10]),
  };
});

const TODAY = '2026-08-20';
const KEY = `${TODAY}:SUNSET`;

/**
 * Two named locations per region, and the second of each is what separates the region LABEL from the
 * location CHIP under the stub projection.
 *
 * <p>`centroid` is the mean of a region's projected points, so with one spot per region the label
 * and the chip land on the same pixel — the greedy placer's own overlap rule then drops every chip,
 * and a test asserting their absence would be pinning the placer rather than the placement. The
 * extra spots put each centroid 100px below its chip.
 */
const HEAT_SPOTS = [
  { id: 1, name: 'Bamburgh Beach', lat: 6, lng: 4, regionName: 'Coast', rid: 'Coast', skySubject: true, scores: [5] },
  { id: 3, name: 'Craster', lat: 26, lng: 4, regionName: 'Coast', rid: 'Coast', skySubject: true, scores: [3] },
  { id: 2, name: 'Malham Cove', lat: 6, lng: 24, regionName: 'Dales', rid: 'Dales', skySubject: true, scores: [4] },
  { id: 4, name: 'Gordale', lat: 26, lng: 24, regionName: 'Dales', rid: 'Dales', skySubject: true, scores: [3] },
];

function spot(overrides = {}) {
  return {
    key: '1',
    locationId: 1,
    locationName: 'Bamburgh Beach',
    regionName: 'Coast',
    solarEventTime: `${TODAY}T20:41:00`,
    rating: 5,
    driveMinutes: 30,
    distanceMiles: 21,
    far: false,
    ...overrides,
  };
}

const NEAR = spot();
const DALES = spot({
  key: '2', locationId: 2, locationName: 'Malham Cove', regionName: 'Dales', rating: 4, driveMinutes: 55,
});

const EVENT_SUMMARY = {
  targetType: 'SUNSET',
  regions: [
    {
      regionName: 'Coast',
      displayVerdict: 'WORTH_IT',
      summary: 'A clean eastern horizon.',
      meanRating: 4.4,
      bestRating: 5,
      meanRatingDelta: 0.5,
      slots: [{ canopy: false }],
    },
    {
      regionName: 'Dales',
      displayVerdict: 'MAYBE',
      summary: 'A thin high veil.',
      meanRating: 3.1,
      bestRating: 4,
      slots: [{ canopy: false }],
    },
  ],
  unregioned: [],
};

function card(overrides = {}) {
  const spots = overrides.spots ?? [NEAR, DALES];
  return {
    key: KEY,
    date: TODAY,
    targetType: 'SUNSET',
    lead: true,
    kicker: 'Tonight',
    when: 'Sunset',
    time: '20:41',
    verdict: 'WORTH_IT',
    verdictLabel: 'Worth it',
    bestRating: 5,
    confidence: 'high',
    movement: { regionName: 'Coast', delta: 0.5 },
    pick: null,
    rows: [],
    allBadges: [],
    allSpots: spots,
    // ⚠️ Derived the way `buildWindowCards` derives it — from the ORIGIN SCOPE, not from the drawn
    // set — so a fixture that empties `spots` to model a lens gate does not accidentally also model
    // an account with no drive times. Overridable, and the no-postcode cases override it by handing
    // over an `allSpots` whose every entry is unmeasured.
    reachMeasured: (overrides.allSpots ?? spots).some((sp) => sp?.driveMinutes != null),
    pool: spots,
    bestReach: spots[0] ?? null,
    reachTotal: spots.length,
    withinReachCount: spots.length,
    ...overrides,
    spots,
  };
}

function field(overrides = {}) {
  return {
    eventSummary: EVENT_SUMMARY,
    spots: HEAT_SPOTS,
    points: [{ id: 1, name: 'Bamburgh Beach', lat: 6, lng: 4, rid: 'Coast', r: [5] }],
    windows: [{ key: KEY, dow: 'Thu', sunrise: false, label: 'Tonight Sunset', time: '20:41' }],
    series: new Map(),
    reachById: new Map(),
    lens: { limitMinutes: 45, tierLabel: '45 min', minRating: null, ratingLabel: 'Any rating' },
    onSelectRegion: vi.fn(),
    selectedRegion: null,
    singleRegionScope: false,
    origin: null,
    ...overrides,
  };
}

function renderDialog(props = {}) {
  const onClose = vi.fn();
  const onStep = vi.fn();
  const view = render(
    <WindowSheetDialog
      card={card()}
      index={0}
      total={6}
      field={field()}
      topicIndex={new Map()}
      scopeNames={['Coast', 'Dales']}
      todayStr={TODAY}
      onClose={onClose}
      onStep={onStep}
      {...props}
    />,
  );
  return { onClose, onStep, ...view };
}

/**
 * Gives jsdom the two measurements the chip placer needs, and nothing else.
 *
 * <p>`clientWidth` is what `useHeatCanvas`'s gate measures before it will paint at all; the chips'
 * own `offsetWidth`/`offsetHeight` are what the greedy pass reads. jsdom reports 0 for all three,
 * which is why the placer's zero-guard exists — an unstubbed run drops every chip, and asserting
 * their absence there would pin the guard rather than the placement.
 */
function withMeasured(run) {
  const props = ['clientWidth', 'offsetWidth', 'offsetHeight'];
  const originals = props.map((name) => [
    name, Object.getOwnPropertyDescriptor(Element.prototype, name)
      ?? Object.getOwnPropertyDescriptor(HTMLElement.prototype, name),
  ]);
  Object.defineProperty(Element.prototype, 'clientWidth', { configurable: true, get: () => 400 });
  Object.defineProperty(HTMLElement.prototype, 'offsetWidth', { configurable: true, get: () => 60 });
  Object.defineProperty(HTMLElement.prototype, 'offsetHeight', { configurable: true, get: () => 14 });
  try {
    run();
  } finally {
    for (const [name, descriptor] of originals) {
      const target = name === 'clientWidth' ? Element.prototype : HTMLElement.prototype;
      if (descriptor) Object.defineProperty(target, name, descriptor);
      else delete target[name];
    }
  }
}


let originalGetContext;
beforeEach(() => {
  originalGetContext = HTMLCanvasElement.prototype.getContext;
  HTMLCanvasElement.prototype.getContext = () => ({});
});
afterEach(() => {
  HTMLCanvasElement.prototype.getContext = originalGetContext;
  cleanup();
  vi.clearAllMocks();
});

describe('WindowSheetDialog — dialog semantics', () => {
  /**
   * ⚠️ SC 4.1.3 (Status Messages). Stepping a window and picking a region both announced NOTHING.
   *
   * <p>`‹`/`›` and `←`/`→` replace the entire dialog while focus stays on the pressed button; the
   * dialog is not keyed, so `useDialogFocus` never re-fires and its accessible name is never
   * re-read; and the one "which of six" signal on screen is `aria-hidden`, because it is a glyph
   * pair a reader would hear as "one slash six". A region pick is the same silence on the popup's
   * PRIMARY interaction — the design's "the furniture never moves" is exactly what makes it
   * undiscoverable. The arm already had the idiom two files away (`WindowSpotSheet`'s always-mounted
   * `role="status"`, added because "pressing a chip otherwise rewrites the list in silence").
   */
  describe('the live region', () => {
    it('names the window and its place in the six, so a step is heard', () => {
      renderDialog({ index: 2, total: 6 });
      const live = screen.getByTestId('window-sheet-live');
      expect(live).toHaveAttribute('role', 'status');
      expect(live).toHaveTextContent('Tonight Sunset, window 3 of 6, Worth it');
    });

    it('names the region and how many places are listed, so a pick is heard', () => {
      renderDialog({ field: field({ selectedRegion: 'Dales' }) });
      expect(screen.getByTestId('window-sheet-live'))
        .toHaveTextContent('showing Dales, 1 location');
    });

    it('⚠️ is ALWAYS mounted, so the AT is already watching it when the text changes', () => {
      // A `role="status"` inserted at the same moment as its text is a region nothing was observing.
      // The unpicked state is the one that would tempt a conditional render, so it is the one pinned.
      renderDialog();
      expect(screen.getByTestId('window-sheet-live')).toBeInTheDocument();
      expect(screen.getByTestId('window-sheet-live')).not.toHaveTextContent('showing');
    });

    it('is not visible, because the counter beside it already says this to a sighted reader', () => {
      renderDialog();
      expect(screen.getByTestId('window-sheet-live')).toHaveClass('sr-only');
      expect(screen.getByTestId('window-sheet-of')).toHaveAttribute('aria-hidden', 'true');
    });
  });

  it('⚠️ titles itself at heading level TWO, under the masthead wordmark', () => {
    // The page's only other heading is `BrandLockup`'s `h1`, so an `h3` here skipped a level — axe's
    // `heading-order`, measured on the running app at M5 and invisible to every test that queried
    // this element by testid. Asserted through the ROLE with its level, which is the only query that
    // can see the difference.
    renderDialog();
    expect(screen.getByRole('heading', { level: 2, name: 'Tonight Sunset' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { level: 3 })).toBeNull();
  });

  it('is a real dialog, named for the window and its place in the six', () => {
    // ⚠️ COUNTED, never asserted: the nav's denominator is the openable window count, and a payload
    // that renders four windows must not have its dialog announce six. The same trap
    // `LocationFourDaySheet` records against its own `label`.
    renderDialog({ index: 4, total: 6 });
    expect(screen.getByRole('dialog', { name: 'Tonight Sunset — window 5 of 6' }))
      .toHaveAttribute('aria-modal', 'true');
    expect(screen.getByTestId('window-sheet-of')).toHaveTextContent('5/6');
  });

  it('takes focus when it opens, so a keyboard reader is not left behind the backdrop', () => {
    // `useDialogFocus` focuses the dialog ROOT on a frame. Deferred, so the assertion has to be too.
    renderDialog();
    return new Promise((resolve) => {
      requestAnimationFrame(() => {
        expect(document.activeElement).toBe(screen.getByTestId('window-sheet'));
        resolve();
      });
    });
  });

  it('steps and closes from its own controls', () => {
    const { onStep, onClose } = renderDialog();
    fireEvent.click(screen.getByTestId('window-sheet-next'));
    expect(onStep).toHaveBeenCalledWith(1);
    fireEvent.click(screen.getByTestId('window-sheet-prev'));
    expect(onStep).toHaveBeenCalledWith(-1);
    fireEvent.click(screen.getByTestId('window-sheet-close'));
    expect(onClose).toHaveBeenCalled();
  });

  it('⚠️ names its close control from its own visible text, never an aria-label', () => {
    // An `aria-label` REPLACES the rendered text, so `aria-label="Close Tonight Sunset"` over a
    // button reading `esc` leaves the accessible name with no substring of the visible one — WCAG
    // 2.5.3 fails and a speech-input user saying "click esc" gets nothing. Both sibling sheets in
    // this arm render `Close · Esc` with no label; the window is named by the DIALOG's accessible
    // name, which is what a reader hears on landing.
    renderDialog();
    const close = screen.getByTestId('window-sheet-close');
    expect(close).not.toHaveAttribute('aria-label');
    expect(close).toHaveAccessibleName('Close · Esc');
    expect(close.textContent).toContain('Esc');
  });

  it('⚠️ declines Escape when a layer is stacked over it', () => {
    // The whole of the one-layer-per-press rule: `Modal` installs a document-level listener per
    // instance, so without this guard two open dialogs both close on a single press.
    const { onClose } = renderDialog({ escapeEnabled: false });
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onClose).not.toHaveBeenCalled();
  });

  it('closes on Escape when it is topmost', () => {
    const { onClose } = renderDialog({ escapeEnabled: true });
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onClose).toHaveBeenCalled();
  });
});

/** The alpha of an `rgba()` string — the confidence channel's whole visible effect on a badge. */
const alphaOf = (rgba) => parseFloat(/rgba\([^)]*,\s*([\d.]+)\s*\)/.exec(rgba)[1]);

/**
 * The verdict badge — the confidence channel's render site in this dialog.
 *
 * <h2>⚠️ Salvaged from the deleted window card, and it had to be</h2>
 *
 * <p>{@code VERDICT_TREATMENT} moved here from `WindowFirstWindowCard` when that card was deleted,
 * and the first cut transcribed it from memory rather than copying: four values drifted, one of them
 * to a token `@theme static` does not define, so every "Maybe" badge in this header rendered in
 * inherited bone. Twenty-seven passing tests said nothing, because none of them looked at the badge.
 * These are the deleted card's own assertions, re-pointed — the plan's task 7 asks for exactly that
 * ("salvage assertions that still describe popup behaviour into the new suites").
 */
describe('WindowSheetDialog — the verdict badge', () => {
  const badge = () => screen.getByTestId('window-sheet-verdict');

  it('decays the fill and the border as confidence drops, and never the word', () => {
    // The whole point of the channel: a far-horizon "Worth it" reads more provisional than
    // tonight's without ever being harder to read. Asserted as an ordering rather than as three
    // literals, because `scaleRgbaAlpha` returns the ORIGINAL string untouched at scale 1.0 and a
    // re-serialised one below it — so the high tier and the others are spelled differently.
    const tiers = ['high', 'medium', 'low'].map((confidence) => {
      const { unmount } = renderDialog({ card: card({ confidence }) });
      const el = badge();
      const style = { fill: el.style.background, border: el.style.border, text: el.style.color };
      unmount();
      return style;
    });

    expect(alphaOf(tiers[0].fill)).toBeGreaterThan(alphaOf(tiers[1].fill));
    expect(alphaOf(tiers[1].fill)).toBeGreaterThan(alphaOf(tiers[2].fill));
    expect(alphaOf(tiers[0].border)).toBeGreaterThan(alphaOf(tiers[1].border));
    expect(alphaOf(tiers[1].border)).toBeGreaterThan(alphaOf(tiers[2].border));
    expect(new Set(tiers.map((t) => t.text)).size).toBe(1);
  });

  it('scales by the documented factors, not by whatever looks about right', () => {
    const { unmount } = renderDialog({ card: card({ confidence: 'medium' }) });
    const medium = alphaOf(badge().style.background);
    unmount();
    renderDialog({ card: card({ confidence: 'low' }) });

    expect(medium).toBeCloseTo(0.14 * 0.72, 3);
    expect(alphaOf(badge().style.background)).toBeCloseTo(0.14 * 0.5, 3);
  });

  it.each(['STAND_DOWN', 'AWAITING'])('leaves a %s badge at full strength', (verdict) => {
    // Confidence qualifies a recommendation. These are not recommendations, and the derivation
    // nulls the field for them — so the badge must not decay even though `resolveConfidence` would
    // happily infer a tier from the horizon.
    const labels = { STAND_DOWN: 'Poor', AWAITING: 'Awaiting' };
    renderDialog({ card: card({ verdict, verdictLabel: labels[verdict], confidence: null }) });
    expect(alphaOf(badge().style.background))
      .toBeCloseTo(verdict === 'STAND_DOWN' ? 0.12 : 0.04, 3);
  });

  it('⚠️ states no confidence word for a verdict the payload withholds one for', () => {
    // The same gate, one column along. Printing the tier unconditionally put "High confidence"
    // beside a Poor badge this component refuses to decay — two contradictory statements about one
    // window, from a number the payload never sent.
    renderDialog({ card: card({ verdict: 'STAND_DOWN', verdictLabel: 'Poor', confidence: null }) });
    expect(screen.queryByTestId('window-sheet-confidence')).toBeNull();
  });

  it('falls back to the horizon when a recommendation carries no backend confidence', () => {
    // The backend really does emit `{verdict: WORTH_IT, confidence: absent}` — a region whose stats
    // are empty but whose triage still says GO. Gating the decay on `confidence == null` rather than
    // on the verdict rendered that at FULL strength, which is the failure the channel exists to
    // prevent.
    renderDialog({ card: card({ confidence: null }) });
    expect(alphaOf(badge().style.background)).toBeCloseTo(0.14 * 0.72, 3);
  });

  it.each([
    ['WORTH_IT', 'Worth it', 'var(--color-badge-go)'],
    ['MAYBE', 'Maybe', 'var(--color-badge-maybe)'],
    ['STAND_DOWN', 'Poor', 'var(--color-badge-poor)'],
    ['AWAITING', 'Awaiting', 'var(--color-plex-text-secondary)'],
  ])('inks a %s badge in its own colour', (verdict, verdictLabel, expected) => {
    // ⚠️ THE assertion this dialog shipped without. The alpha tests above pin the DECAY and not the
    // hue, and a token that does not exist decays exactly as well as one that does — `MAYBE` was
    // spelled `--color-badge-marginal` and rendered in inherited bone with the whole suite green.
    renderDialog({
      card: card({ verdict, verdictLabel, confidence: verdict === 'WORTH_IT' ? 'high' : null }),
    });
    expect(badge().style.color).toBe(expected);
  });

  it('⚠️ names only tokens the stylesheet defines', () => {
    // The generalisation of the case above, and the guard that would have caught it whatever the
    // hue: jsdom does not resolve `var()`, so no cascade test can see an undefined token — but the
    // set of defined names is a fact about a file, and it is readable. Every `var(--…)` this
    // component emits inline must be in `@theme static`.
    const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8');
    const defined = new Set([...css.matchAll(/^\s*(--[a-z0-9-]+)\s*:/gm)].map((m) => m[1]));
    const used = new Set();
    for (const verdict of ['WORTH_IT', 'MAYBE', 'STAND_DOWN', 'AWAITING']) {
      const { unmount } = renderDialog({ card: card({ verdict, verdictLabel: verdict }) });
      for (const node of screen.getByTestId('window-sheet').querySelectorAll('[style]')) {
        for (const m of node.getAttribute('style').matchAll(/var\((--[a-z0-9-]+)\)/g)) used.add(m[1]);
      }
      unmount();
    }
    expect(used.size).toBeGreaterThan(0);
    expect([...used].filter((name) => !defined.has(name))).toEqual([]);
  });

  it('marks a recommendation with ◎ and withholds it from a verdict that recommends nothing', () => {
    const { unmount } = renderDialog();
    expect(badge().textContent).toContain('◎');
    unmount();

    renderDialog({ card: card({ verdict: 'AWAITING', verdictLabel: 'Awaiting', confidence: null }) });
    expect(badge().textContent).not.toContain('◎');
  });

  it('renders Awaiting on the neutral badge, never the red one', () => {
    // "AWAITING is reachable and means the window has neither a rating nor a triage signal — it is
    // not a synonym for a poor forecast, and must not render as one."
    const { unmount } = renderDialog({ card: card({ verdict: 'STAND_DOWN', verdictLabel: 'Poor', confidence: null }) });
    const poorFill = badge().style.background;
    unmount();

    renderDialog({ card: card({ verdict: 'AWAITING', verdictLabel: 'Awaiting', confidence: null }) });
    expect(badge().style.background).not.toBe(poorFill);
    expect(badge().style.background).toMatch(/255,\s*255,\s*255/);
  });
});

describe('WindowSheetDialog — what the header may claim', () => {
  it('⚠️ hides the star GLYPH and spells the unit, because NVDA does not speak U+2605', () => {
    // This arm's standing pattern (`WindowRowFieldMap`, `LocationFourDaySheet`, `HeatmapGrid` all
    // carry it with the same note) and the popup header was the one place it had not been applied —
    // so the most decision-relevant number in the dialog announced as "best 5 within reach".
    //
    // ⚠️ Asserted on the two ELEMENTS, not on `textContent`: `toHaveTextContent` concatenates
    // `sr-only` text, so a visible-string assertion here reads "best 5★ stars within reach" and
    // cannot tell the visible half from the spoken one. That is what broke the sibling below when
    // this landed, and its comment now says so.
    renderDialog();
    const best = screen.getByTestId('window-sheet-best');
    expect(within(best).getByText('★')).toHaveAttribute('aria-hidden', 'true');
    expect(within(best).getByText('stars')).toHaveClass('sr-only');
  });

  it('states the best rating WITHIN REACH, which is the pool head’s', () => {
    // ⚠️ `toHaveTextContent` includes `sr-only` text, so the spoken "stars" sits between the glyph
    // and the reach clause. The two halves are pinned as elements one test up; what this one is
    // about is the FIGURE and the claim, so it matches the parts rather than the whole line.
    renderDialog();
    const best = screen.getByTestId('window-sheet-best');
    expect(best).toHaveTextContent(/best 5★/);
    expect(best).toHaveTextContent(/within reach$/);
  });

  it('⚠️ states no cross-location average and no confidence percentage', () => {
    // A3/A2, both settled: a client mean over locations is the aggregation class the verdict
    // consolidation removed, and heat-field D3 rejected the percentage once already.
    renderDialog();
    const meta = screen.getByTestId('window-sheet-meta').textContent;
    expect(meta).not.toMatch(/average/i);
    expect(meta).not.toMatch(/%/);
    expect(meta).toContain('High confidence');
  });

  it('marks a low-confidence window as provisional rather than scoring it', () => {
    renderDialog({ card: card({ confidence: 'low' }) });
    expect(within(screen.getByTestId('window-sheet-confidence')).getByTestId('provisional-mark'))
      .toBeInTheDocument();
  });

  it('states the served movement, at last run, never "since"', () => {
    renderDialog();
    const moved = screen.getByTestId('window-sheet-moved');
    expect(moved).toHaveTextContent('▲0.5');
    expect(moved).toHaveTextContent('up 0.5 stars at last run');
    expect(moved).not.toHaveTextContent('since');
  });

  it('says nothing about movement when the payload carries no delta', () => {
    renderDialog({ card: card({ movement: null }) });
    expect(screen.queryByTestId('window-sheet-moved')).toBeNull();
  });

  it('distinguishes an empty pool from an unrated one', () => {
    // The empty-pool arm needs a MEASURED spot in `allSpots` — otherwise "in reach" is withheld for
    // the same reason it is withheld from the figure, and the sentence is "nothing to show". The
    // fixture keeps `allSpots` at its default (both spots measured) and empties only the gated set,
    // which is what an actual reach filter does.
    const { unmount } = renderDialog({
      card: card({
        spots: [], pool: [], bestReach: null, allSpots: [NEAR, DALES],
      }),
    });
    expect(screen.getByTestId('window-sheet-best')).toHaveTextContent('nothing in reach');
    unmount();

    renderDialog({ card: card({ bestReach: null }) });
    expect(screen.getByTestId('window-sheet-best')).toHaveTextContent('nothing rated yet');
  });

  it('⚠️ blames nothing when there is no drive time for the reach axis to have used', () => {
    // §6 clause 7 on the header's two absence sentences, not just on its figure. With no home
    // postcode nothing was gated, so an empty pool means the window has no sky-gated slots at all —
    // "nothing in reach" would credit a filter that did not run.
    const unmeasured = [{ ...NEAR, driveMinutes: null }, { ...DALES, driveMinutes: null }];
    renderDialog({
      card: card({
        spots: [], pool: [], bestReach: null, allSpots: unmeasured,
      }),
    });
    expect(screen.getByTestId('window-sheet-best')).toHaveTextContent('nothing to show');
    expect(screen.getByTestId('window-sheet-best')).not.toHaveTextContent('reach');
  });

  it('⚠️ states the best star WITHOUT the reach clause when nothing measured it', () => {
    // The figure survives; the claim about how it was chosen does not. Three review lenses charged
    // this line independently — it sat 250px above the footer M5 had already fixed.
    const unmeasured = [{ ...NEAR, driveMinutes: null }, { ...DALES, driveMinutes: null }];
    renderDialog({ card: card({ allSpots: unmeasured, spots: unmeasured, pool: unmeasured }) });
    expect(screen.getByTestId('window-sheet-best')).toHaveTextContent('best 5★');
    expect(screen.getByTestId('window-sheet-best')).not.toHaveTextContent('within reach');
  });

  it('renders the served pick as a real BUTTON, and no pick at all when none is served', () => {
    const onOpenPick = vi.fn();
    const { unmount } = renderDialog();
    expect(screen.queryByTestId('window-sheet-pick')).toBeNull();
    unmount();

    renderDialog({ card: card({ pick: { kind: 'best', regionName: 'Coast' } }), onOpenPick });
    // Role plus name, which the standards make mandatory for a control — a `<span>` here would pass
    // a test-id assertion and reach no keyboard.
    fireEvent.click(screen.getByRole('button', { name: /Best bet/ }));
    expect(onOpenPick).toHaveBeenCalled();
  });

  it('names the runner-up pick as itself, not as the best one', () => {
    // The other arm of the same ternary. Both picks exist across the forecast and only one is BEST;
    // labelling an ALSO pick "Best bet" would recommend the wrong window by name.
    renderDialog({ card: card({ pick: { kind: 'also', regionName: 'Coast' } }) });
    expect(screen.getByRole('button', { name: /Also good/ })).toHaveAttribute('data-pick', 'also');
  });
});

describe('WindowSheetDialog — picking a region swaps words, and moves nothing', () => {
  const railCells = () => screen.getAllByTestId('wf-region-cell');

  it('reads the top region unpicked, and that region picked', () => {
    const { rerender } = renderDialog();
    expect(screen.getByTestId('wf-prose-name')).toHaveTextContent('Coast');
    expect(screen.getByTestId('wf-prose-kicker')).toBeInTheDocument();

    rerender(
      <WindowSheetDialog
        card={card()}
        index={0}
        total={6}
        field={field({ selectedRegion: 'Dales' })}
        topicIndex={new Map()}
        scopeNames={['Coast', 'Dales']}
        todayStr={TODAY}
        onClose={vi.fn()}
      />,
    );
    expect(screen.getByTestId('wf-prose-name')).toHaveTextContent('Dales');
    expect(screen.getByTestId('wf-prose-body')).toHaveTextContent('A thin high veil.');
  });

  it('⚠️ keeps the same prose node in every state, so nothing below it moves', () => {
    // The point of the element. The band it replaces appeared on selection, so every pick and every
    // clear shoved the tide row and the ranked locations down the dialog.
    const { rerender } = renderDialog();
    const before = screen.getByTestId('wf-prose');
    rerender(
      <WindowSheetDialog
        card={card()}
        index={0}
        total={6}
        field={field({ selectedRegion: 'Dales' })}
        topicIndex={new Map()}
        scopeNames={['Coast', 'Dales']}
        todayStr={TODAY}
        onClose={vi.fn()}
      />,
    );
    expect(screen.getByTestId('wf-prose')).toBe(before);
  });

  it('hands the pick back to the caller rather than holding it', () => {
    const onSelectRegion = vi.fn();
    renderDialog({ field: field({ onSelectRegion }) });
    fireEvent.click(railCells()[2]);
    expect(onSelectRegion).toHaveBeenCalledWith('Dales');
  });

  it('filters the ranked strip and names the region in the footer', () => {
    renderDialog({ field: field({ selectedRegion: 'Dales' }) });
    expect(screen.getAllByTestId('window-spot').map((n) => n.textContent.includes('Malham')))
      .toEqual([true]);
    expect(screen.getByTestId('window-spot-filters')).toHaveTextContent('Dales');
  });

  it('⚠️ names the reach tier in the footer only when a drive time exists to gate on', () => {
    // §6 clause 7, measured in a browser at M5 with a fresh account: no home postcode means no drive
    // times, an unmeasured spot passes every tier (plan §2.5), and the footer still read
    // `· within 45 min` over the whole roster. The lens fixture is UNCHANGED between the two halves
    // — the tier is 45 min in both — so what is under test is the spots, not the control.
    const unmeasured = [{ ...NEAR, driveMinutes: null }, { ...DALES, driveMinutes: null }];
    renderDialog({ card: card({ allSpots: unmeasured, spots: unmeasured, pool: unmeasured }) });
    expect(screen.queryByTestId('window-spot-filters')).toBeNull();
  });

  it('names the tier when a drive time exists, which is the ordinary case', () => {
    renderDialog();
    expect(screen.getByTestId('window-spot-filters')).toHaveTextContent('within 45 min');
  });

  it('⚠️ asks the WHOLE origin scope, not the region focus’s survivors', () => {
    // The distinction the source comment defends, and which an adversarial review showed no test
    // could see: the fixture above passes one array as `allSpots`, `spots` AND `pool`, so swapping
    // the basis to the drawn set survived the full suite.
    //
    // Here they differ. `NEAR` (Coast) is measured and `DALES` is not; the reader has focused Dales,
    // so the drawn set is unmeasured while the scope holds a measured spot. Asking the drawn set
    // would withhold the clause for THIS window and print it for its neighbour — a filter that
    // flickers by window is a worse claim than one that is simply true of the reader's account.
    const unmeasuredDales = { ...DALES, driveMinutes: null };
    renderDialog({
      // `spots` is what the region focus gates, `allSpots` is the whole origin scope. Both are set
      // explicitly here, because `card()` derives one from the other and a fixture that let it would
      // be back to passing one array under three names.
      card: card({ spots: [NEAR, unmeasuredDales], allSpots: [NEAR, unmeasuredDales] }),
      field: field({ selectedRegion: 'Dales' }),
    });
    // The drawn set is [unmeasuredDales] — no drive time in it at all — while the scope has NEAR's.
    expect(screen.getAllByTestId('window-spot')).toHaveLength(1);
    expect(screen.getByTestId('window-spot-filters')).toHaveTextContent('within 45 min');
  });

  it('⚠️ hands the FOCUS to the kernel, which is what repaints the field', () => {
    // The prose swapping and the strip filtering are both true with the map's `selectedRegion` prop
    // deleted — the chips read the card's own array. What only this assertion catches is the focus
    // reaching `drawGeo`, which is the whole of "picking a region repaints the field".
    drawGeo.mockClear();
    withMeasured(() => {
      renderDialog({ field: field({ selectedRegion: 'Coast' }) });
    });
    expect(drawGeo.mock.calls.at(-1)[5].focus).toBe('Coast');
  });

  it('⚠️ moves nothing below the prose when a region is picked', () => {
    // The design's requirement in one assertion: a pick swaps words and repaints, it does not
    // re-order the dialog. Compared by the SEQUENCE of sections, so a row appearing, disappearing
    // or changing place fails.
    const sections = () => [...screen.getByTestId('window-sheet').querySelectorAll('[data-testid]')]
      .map((n) => n.dataset.testid)
      .filter((id) => ['wf-topic-rows', 'window-attribute-row', 'window-spot-strip', 'window-sheet-empty'].includes(id));
    const { unmount } = renderDialog();
    const before = sections();
    unmount();

    renderDialog({ field: field({ selectedRegion: 'Dales' }) });
    expect(sections()).toEqual(before);
  });

  it('withholds the rail entirely when the origin has already narrowed the page to one region', () => {
    renderDialog({ field: field({ singleRegionScope: true, origin: { name: 'Coast' } }) });
    expect(screen.queryByTestId('wf-region-rail')).toBeNull();
    // The prose still names a region, so the popup is not silent about where it is.
    expect(screen.getByTestId('wf-prose-name')).toHaveTextContent('Coast');
  });
});

describe('WindowSheetDialog — the null-prose line points at a night worth having', () => {
  // ⚠️ The derivation the prose slot's own test cannot reach: `WindowProseSlot` takes `bestWindow`
  // as a prop, so the walk that CHOOSES it — skip away days, ties to the earlier window — is the
  // dialog's, and every fixture in this file passes an empty series precisely because nothing else
  // needs one.
  const WINDOWS = [
    { key: `${TODAY}:SUNSET`, dow: 'Thu', sunrise: false, label: 'Tonight Sunset', time: '20:41' },
    { key: '2026-08-21:SUNRISE', dow: 'Fri', sunrise: true, label: 'Tomorrow sunrise', time: '05:48' },
    { key: '2026-08-21:SUNSET', dow: 'Fri', sunrise: false, label: 'Tomorrow sunset', time: '20:39', away: true },
  ];
  const silent = () => card({
    spots: [DALES],
  });
  const noProse = {
    ...EVENT_SUMMARY,
    regions: [{ ...EVENT_SUMMARY.regions[1], summary: null }],
  };

  it('names the region’s own best window when a different one is better', () => {
    renderDialog({
      card: silent(),
      field: field({
        eventSummary: noProse,
        selectedRegion: 'Dales',
        windows: WINDOWS,
        series: new Map([['Dales', new Map([[WINDOWS[0].key, 2.0], [WINDOWS[1].key, 4.6]])]]),
      }),
    });
    expect(screen.getByTestId('wf-prose-none')).toHaveTextContent('own best is tomorrow sunrise 05:48');
  });

  it('⚠️ never names an AWAY window, which nothing evaluated', () => {
    // A travel day is skipped by the pipeline, so a mark on it would recommend a night nobody
    // looked at — and the payload can still carry a mean for it.
    renderDialog({
      card: silent(),
      field: field({
        eventSummary: noProse,
        selectedRegion: 'Dales',
        windows: WINDOWS,
        // The away window carries the HIGHEST mean, and must still not be the one named.
        series: new Map([['Dales', new Map([
          [WINDOWS[0].key, 2.0], [WINDOWS[1].key, 3.0], [WINDOWS[2].key, 5.0],
        ])]]),
      }),
    });
    expect(screen.getByTestId('wf-prose-none')).toHaveTextContent('own best is tomorrow sunrise 05:48');
  });

  it('⚠️ gives a tie to the EARLIER window, so the mark never appears to jump back', () => {
    renderDialog({
      card: silent(),
      field: field({
        eventSummary: noProse,
        selectedRegion: 'Dales',
        windows: WINDOWS,
        series: new Map([['Dales', new Map([[WINDOWS[0].key, 4.0], [WINDOWS[1].key, 4.0]])]]),
      }),
    });
    // The open window IS the earlier one, so the sentence points at nothing rather than at itself.
    expect(screen.getByTestId('wf-prose-none')).not.toHaveTextContent('own best');
  });
});

describe('WindowSheetDialog — the field can never name a spot the list has excluded', () => {
  it('draws chips from the SAME gated array the ranked strip renders', () => {
    withMeasured(() => {
      renderDialog({ field: field({ selectedRegion: 'Dales' }) });
      const chips = screen.getAllByTestId('wf-row-map-chip').map((n) => n.dataset.location);
      // ⚠️ A region focus ORDERS the chips, it does not filter them (plan §5): the field repaints
      // with the other regions faded, and their strongest places stay named so the reader can see
      // what they are choosing against. So the focused region comes FIRST and the rest survive.
      expect(chips[0]).toBe('Malham Cove');
      expect(chips).toContain('Bamburgh Beach');
      // What the field may never do is name a spot the LENS excluded — every chip is on the card's
      // gated list, whatever the focus is.
      const gated = card().spots.map((s) => s.locationName);
      chips.forEach((name) => expect(gated).toContain(name));
    });
  });

  it('⚠️ titles a chip with region · drive · leave-by, and drops each clause independently', () => {
    // Deferred from M2 to land WITH the click (M4.2): a `title` on a `pointer-events: none` span
    // inside an `aria-hidden` subtree reaches nobody. The three clauses are independently absent for
    // the reasons `WindowSpotCard` states about the same three fields — no region means the slot
    // arrived unregioned; no drive means the reader has saved no postcode, which is UNKNOWN and
    // never "out of reach"; and no departure follows without both the drive and this slot's own
    // event time. A chip with none of them carries no `title` at all rather than an empty one.
    const spots = [
      {
        key: '1', locationId: 1, locationName: 'Full', regionName: 'Dales', rating: 4,
        driveMinutes: 42, solarEventTime: '2026-08-20T19:41:00',
      },
      {
        key: '2', locationId: 2, locationName: 'No drive', regionName: 'Dales', rating: 4,
        driveMinutes: null, solarEventTime: '2026-08-20T19:41:00',
      },
      {
        key: '3', locationId: 3, locationName: 'Bare', regionName: null, rating: 4,
        driveMinutes: null, solarEventTime: null,
      },
    ];
    withMeasured(() => {
      renderDialog({ card: card({ spots }), onOpenLocation: vi.fn() });
      const byName = new Map(screen.getAllByTestId('wf-row-map-chip')
        .map((n) => [n.dataset.location, n]));
      expect(byName.get('Full')).toHaveAttribute('title', 'Dales · 42 min · leave 19:39');
      expect(byName.get('No drive')).toHaveAttribute('title', 'Dales');
      expect(byName.get('Bare')).not.toHaveAttribute('title');
    });
  });

  it('⚠️ names the SHEET on its ranked cards, not the map', () => {
    // The card's own default is `◍ Open on map →`, which the drill-down sheet's copy of the same
    // component still needs — one component, two destinations since M4 (D-3). The words are the
    // caller's for exactly that reason, and this span sits inside the card's `<button>`, so a card
    // promising a map and delivering a sheet would be lying in its own accessible name.
    renderDialog();
    const card = screen.getAllByTestId('window-spot')[0];
    expect(card).toHaveTextContent('The next few days here');
    expect(card).not.toHaveTextContent('Open on map');
  });

  it('draws no chips at all when the window’s gated pool is empty', () => {
    withMeasured(() => {
      renderDialog({ card: card({ spots: [] }) });
      expect(screen.queryAllByTestId('wf-row-map-chip')).toHaveLength(0);
    });
  });

  it('⚠️ names a spot from OUTSIDE the focused region, which a filter would have erased', () => {
    // The regression this ordering replaces: built from the region-gated array, picking a region
    // blanked every other region's names off the field, so the picture stopped being a comparison.
    withMeasured(() => {
      renderDialog({ field: field({ selectedRegion: 'Dales' }) });
      const chips = screen.getAllByTestId('wf-row-map-chip').map((n) => n.dataset.location);
      expect(chips).toEqual(expect.arrayContaining(['Malham Cove', 'Bamburgh Beach']));
    });
  });
});

describe('WindowSheetDialog — the per-window quiet sentence', () => {
  it('⚠️ names the lens when the lens emptied it', () => {
    // `allSpots` explicit: it is the origin scope BEFORE the reach gate, so it is what says whether
    // a drive time existed for the tier to act on. `card()` derives it from `spots` by default,
    // which would make an emptied window look like an account with no drive times at all.
    renderDialog({
      card: card({ spots: [], allSpots: [NEAR, DALES] }),
      field: field({ lens: { limitMinutes: 45, tierLabel: '45 min', minRating: 4, ratingLabel: '4★+' } }),
    });
    expect(screen.getByTestId('window-sheet-empty'))
      .toHaveTextContent('Nothing at 4★+ within 45 min for this window.');
  });

  it('⚠️ drops the tier clause when no drive time exists for it to have used', () => {
    // §6 clause 7, eleven lines from the footer's own fix and still making the claim after it. A
    // reader with no home postcode was told the window held nothing "within 45 min" when nothing
    // had been filtered by distance at all. The RATING clause stays — that axis really did act.
    const unmeasured = [{ ...NEAR, driveMinutes: null }, { ...DALES, driveMinutes: null }];
    renderDialog({
      card: card({ spots: [], allSpots: unmeasured }),
      field: field({ lens: { limitMinutes: 45, tierLabel: '45 min', minRating: 4, ratingLabel: '4★+' } }),
    });
    expect(screen.getByTestId('window-sheet-empty'))
      .toHaveTextContent('Nothing at 4★+ for this window.');
  });

  it('⚠️ names the REGION as well when a region focus did the emptying', () => {
    // The variant the page-level message cannot cover: one window, or one region inside it, can be
    // empty while the plan as a whole is full.
    renderDialog({
      card: card({ spots: [NEAR] }),
      field: field({ selectedRegion: 'Dales' }),
    });
    expect(screen.getByTestId('window-sheet-empty'))
      .toHaveTextContent('Nothing within 45 min in Dales for this window.');
  });

  it('states the bare fact when neither lens axis is gating anything', () => {
    renderDialog({
      card: card({ spots: [] }),
      field: field({ lens: { limitMinutes: null, tierLabel: 'Any', minRating: null, ratingLabel: 'Any rating' } }),
    });
    expect(screen.getByTestId('window-sheet-empty')).toHaveTextContent('Nothing for this window.');
  });

  it('offers the route to the full list beside it, where the count is otherwise unactionable', () => {
    const onSeeAllSpots = vi.fn();
    renderDialog({ card: card({ spots: [] }), onSeeAllSpots });
    fireEvent.click(screen.getByTestId('window-sheet-see-all'));
    expect(onSeeAllSpots).toHaveBeenCalled();
  });
});

describe('WindowSheetDialog — the rows below', () => {
  it('draws the tide row and NOT the snow rows, which the topic rows already carry', () => {
    // Both come off `card.rows`; drawing the snow one too would state one topic twice, eight pixels
    // apart, because the topic rows below carry every topic's own detail and facts.
    renderDialog({
      card: card({
        rows: [
          { key: 'tide', channel: 'tide', kicker: '≈ Tide', facts: [{ segments: [{ text: 'HW 21:02', tone: 'strong' }] }], chart: null },
          { key: 'snow', channel: 'snow', kicker: '❄ Snow', facts: [{ segments: [{ text: '600 m', tone: 'strong' }] }], chart: null },
        ],
      }),
    });
    const rows = screen.getAllByTestId('window-attribute-row');
    expect(rows).toHaveLength(1);
    expect(rows[0]).toHaveAttribute('data-channel', 'tide');
  });

  it('draws no tide row when the payload carries none', () => {
    renderDialog();
    expect(screen.queryByTestId('window-attribute-row')).toBeNull();
  });

  it('joins topics through the shared util, including the NIGHT bucketing', () => {
    // The popup and the matrix cell that opened it must name the same topics — one join, one
    // filter, in `windowFirstTopics.js`. Driven through the real index so the join is exercised.
    const topics = [{
      type: 'AURORA',
      label: 'Aurora',
      date: '2026-08-19',
      eventType: 'NIGHT',
      detail: 'Kp 6 expected',
      description: 'Kp 6 pushes the oval south.',
      regions: ['Coast'],
      rarityRank: 2,
    }];
    renderDialog({
      card: card({
        key: `${TODAY}:SUNRISE`,
        targetType: 'SUNRISE',
        allBadges: [{ type: 'AURORA', label: 'Aurora', detail: 'Kp 6 expected', rarityRank: 2 }],
      }),
      topicIndex: buildTopicIndex(topics),
    });
    expect(screen.getByTestId('wf-topic-row-name')).toHaveTextContent('Aurora');
    expect(screen.getByTestId('window-sheet-topic-pill')).toHaveTextContent('Aurora');
  });
});
