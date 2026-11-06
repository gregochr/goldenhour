/**
 * The drive-tier scope words on the Map's surfaces (docs/engineering/reach-scoped-card-plan.md R3, D6).
 *
 * <p><b>What breaks if these fail:</b> the pill, the window panel or the lists print a tier-scoped verdict ("Poor")
 * with nothing saying it is for the places within 45 minutes, so the Map's word reads as a claim about everywhere
 * (the defect this series exists to end); or, the other way, an unscoped verdict starts carrying scope words it
 * cannot back, or the phone loses its only line for them.
 */
import React from 'react';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import WindowControl from '../components/map/WindowControl.jsx';
import MapWindowPanel from '../components/map/MapWindowPanel.jsx';
import { MapPeekWindowsSection, OtherWindowValue } from '../components/map/MapPeekSheet.jsx';
import { windowPanelNote } from '../utils/mapDrilldown.js';
import { verdictScopeSpoken, verdictScopeText } from '../utils/reachLens.js';

const SUNRISE_ID = 'solar:2026-09-02:SUNRISE';
const EVENTS = [
  {
    id: SUNRISE_ID, kind: 'solar', eventType: 'SUNRISE', date: '2026-09-02', label: 'Today', dayLabel: 'Today',
    time: '06:30', bestRating: 3, scored: true, badges: [],
  },
];

const verdict = (extra = {}) => ({
  tier: 'STAND_DOWN', regionName: 'Northumberland', sharingCount: 1, allInScope: false, scopedRegionCount: 2, ...extra,
});
const SCOPE_45 = { scope: { tierMinutes: 45 } };

function renderControl(verdictOver, props = {}) {
  return render(
    <WindowControl
      events={EVENTS}
      activeIndex={0}
      onSelect={vi.fn()}
      verdicts={new Map([[SUNRISE_ID, verdict(verdictOver)]])}
      {...props}
    />,
  );
}

describe('the pill\'s verdict cell', () => {
  it('prints the scope short on the region line, and speaks it long in the accessible name', () => {
    renderControl(SCOPE_45);

    const sub = screen.getByTestId('wf-win-verdict-sub');
    expect(sub).toContainElement(screen.getByTestId('wf-win-verdict-region'));
    expect(screen.getByTestId('wf-win-verdict-region')).toHaveTextContent('Northumberland +1');
    const printed = screen.getByTestId('wf-win-verdict-scope');
    expect(printed).toHaveTextContent('within 45m');
    expect(printed).toHaveAttribute('aria-hidden', 'true');
    expect(sub).toContainElement(printed);
    // The accessible name carries the long form after the region, and not the abbreviation.
    const name = screen.getByTestId('wf-win-pill').textContent;
    expect(name).toContain('Northumberland +1');
    expect(name).toContain(', within 45 min');
  });

  it('speaks every printed spelling in full: the visible words are the spoken ones, abbreviation expanded', () => {
    for (const minutes of [45, 90, 150]) {
      const { unmount } = renderControl({ scope: { tierMinutes: minutes } });
      const printed = screen.getByTestId('wf-win-verdict-scope').textContent;
      const spoken = verdictScopeSpoken({ tierMinutes: minutes });
      expect(printed).toBe(verdictScopeText({ tierMinutes: minutes }));
      // WCAG 2.5.3 as the Plan's Spread qualifier is held to: the visible words, short spelling expanded, are in the name.
      expect(screen.getByTestId('wf-win-pill').textContent).toContain(spoken);
      expect(printed.startsWith('within ')).toBe(true);
      unmount();
    }
  });

  it('names the scope alone when the verdict has no region to name', () => {
    renderControl({ ...SCOPE_45, regionName: null, sharingCount: 0 });

    expect(screen.queryByTestId('wf-win-verdict-region')).toBeNull();
    expect(screen.getByTestId('wf-win-verdict-scope')).toHaveTextContent('within 45m');
  });

  it('draws an unscoped verdict exactly as before: no second-line wrapper, no scope words', () => {
    renderControl({});

    expect(screen.queryByTestId('wf-win-verdict-sub')).toBeNull();
    expect(screen.queryByTestId('wf-win-verdict-scope')).toBeNull();
    expect(screen.getByTestId('wf-win-verdict-region').parentElement).toBe(screen.getByTestId('wf-win-verdict'));
    expect(screen.getByTestId('wf-win-pill').textContent).not.toMatch(/within/);
  });

  it('captions the dropdown\'s verdict words with the tier, and not when unscoped', () => {
    const { unmount } = renderControl(SCOPE_45, { open: true, onOpenChange: vi.fn() });
    expect(screen.getByTestId('wf-win-scope-note')).toHaveTextContent('Verdicts within 45m');
    expect(screen.getByTestId('wf-win-scope-note').textContent).toContain('Verdicts within 45 min');
    unmount();

    renderControl({}, { open: true, onOpenChange: vi.fn() });
    expect(screen.queryByTestId('wf-win-scope-note')).toBeNull();
  });
});

describe('the phone: the scope words take the hidden region line\'s slot', () => {
  const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8');

  it('hides the region, its separator and the rated count at ≤639px, and never the scope', () => {
    const block = css.slice(css.indexOf('.wf-map-tab .wf-win-verdict-region { display: none; }'));
    const phone = block.slice(0, block.indexOf('.wf-map-tab .wf-win-pick {'));
    expect(phone).toContain('.wf-map-tab .wf-win-verdict-count { display: none; }');
    expect(phone).toContain('.wf-map-tab .wf-win-verdict-scope-sep { display: none; }');
    expect(phone).not.toMatch(/\.wf-win-verdict-scope\s*\{\s*display:\s*none/);
    expect(css).not.toMatch(/\.wf-map-tab \.wf-win-verdict-scope\s*\{\s*display:\s*none/);
  });

  it('keeps the scope from shrinking on every width (it never cuts)', () => {
    const rule = css.match(/\.wf-win-verdict-scope\s*\{([^}]*)\}/)[1];
    expect(rule).toMatch(/flex:\s*none/);
  });
});

describe('the window panel', () => {
  const ROW = {
    id: SUNRISE_ID, kind: 'solar', eventType: 'SUNSET', label: 'Tonight sunset', time: '20:28', confidence: 'medium',
  };

  function renderPanel(v) {
    return render(
      <MapWindowPanel
        row={ROW}
        verdict={v}
        note=""
        rows={[]}
        onClose={vi.fn()}
        onSelectRegion={vi.fn()}
      />,
    );
  }

  it('says the tier beside the header word: printed short, spoken long', () => {
    renderPanel({ tier: 'STAND_DOWN', ...SCOPE_45 });

    const head = screen.getByTestId('wf-win-panel-verdict');
    expect(head).toHaveTextContent('Poor');
    expect(screen.getByTestId('wf-win-panel-verdict-scope')).toHaveTextContent('within 45m');
    expect(head.textContent).toContain(', within 45 min');
  });

  it('prints nothing extra for an unscoped verdict', () => {
    renderPanel({ tier: 'STAND_DOWN' });

    expect(screen.queryByTestId('wf-win-panel-verdict-scope')).toBeNull();
    expect(screen.getByTestId('wf-win-panel-verdict').textContent).toBe('Poor');
  });

  it('words the note as a within-tier verdict over whole-region rows, in place of the tally sentences', () => {
    const scoped = windowPanelNote({ isSolar: true, verdict: verdict({ ...SCOPE_45, sharingCount: 2, allInScope: true }) });
    expect(scoped).toContain('over its places within 45 min');
    expect(scoped).toContain('each region as a whole');
    expect(scoped).not.toMatch(/choice between them is drive time|nothing here is worth the drive/);

    const unscoped = windowPanelNote({ isSolar: true, verdict: verdict({ sharingCount: 0 }) });
    expect(unscoped).not.toContain('within');
  });
});

describe('the phone\'s window lists', () => {
  const ROWS = EVENTS;

  it('captions the Windows section\'s verdict words with the tier', () => {
    render(
      <MapPeekWindowsSection
        heading="Today"
        rows={ROWS}
        verdicts={new Map([[SUNRISE_ID, { tier: 'MAYBE', ...SCOPE_45 }]])}
        onSelect={vi.fn()}
      />,
    );

    expect(screen.getByTestId('wf-map-peek-scope')).toHaveTextContent('Verdicts within 45m');
    expect(screen.getByTestId('wf-map-peek-scope').textContent).toContain('Verdicts within 45 min');
  });

  it('draws no caption when no verdict is scoped', () => {
    render(
      <MapPeekWindowsSection
        heading="Today"
        rows={ROWS}
        verdicts={new Map([[SUNRISE_ID, { tier: 'MAYBE' }]])}
        onSelect={vi.fn()}
      />,
    );

    expect(screen.queryByTestId('wf-map-peek-scope')).toBeNull();
  });

  it('speaks the tier in the Other windows value without printing it (the cell never cuts)', () => {
    render(
      <OtherWindowValue row={ROWS[0]} verdict={{ tier: 'MAYBE', ...SCOPE_45 }} />,
    );

    const word = screen.getByTestId('wf-map-peek-other-verdict');
    expect(word.textContent).toContain(', within 45 min');
    expect(word.querySelector('.sr-only')).not.toBeNull();
  });

  it('adds nothing to an unscoped Other windows value', () => {
    render(<OtherWindowValue row={ROWS[0]} verdict={{ tier: 'MAYBE' }} />);

    expect(screen.getByTestId('wf-map-peek-other-verdict').textContent).not.toMatch(/within/);
  });
});
