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
import { computeAccessibleName } from 'dom-accessibility-api';
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
    expect(screen.getByTestId('wf-win-verdict-scope-full')).toHaveTextContent('within 45m');
    expect(printed).toHaveAttribute('aria-hidden', 'true');
    expect(sub).toContainElement(printed);
    // The COMPUTED accessible name (aria-hidden text excluded) carries the long form after the region, and neither
    // printed abbreviation.
    const name = computeAccessibleName(screen.getByTestId('wf-win-pill'));
    expect(name).toContain('Northumberland +1');
    expect(name).toContain('within 45 min');
    expect(name).not.toContain('45m');
    expect(name).not.toContain('≤');
  });

  it('prints two spellings of the scope for the stylesheet to swap: the full one and the short ≤ form', () => {
    for (const [minutes, full, short] of [[45, 'within 45m', '≤45m'], [90, 'within 1h30', '≤1h30'], [150, 'within 2h30', '≤2h30']]) {
      const { unmount } = renderControl({ scope: { tierMinutes: minutes } });
      expect(screen.getByTestId('wf-win-verdict-scope-full').textContent).toBe(full);
      expect(screen.getByTestId('wf-win-verdict-scope-short').textContent).toBe(short);
      unmount();
    }
  });

  it('labels a scoped all-in-scope verdict "everywhere": the scope words qualify it', () => {
    renderControl({ ...SCOPE_45, allInScope: true, sharingCount: 1 });

    expect(screen.getByTestId('wf-win-verdict-region')).toHaveTextContent(/^everywhere$/);
    expect(screen.getByTestId('wf-win-verdict-scope-full')).toHaveTextContent('within 45m');
  });

  it('speaks the scope in the stepper names of a scoped neighbour', () => {
    const second = {
      ...EVENTS[0], id: 'solar:2026-09-02:SUNSET', eventType: 'SUNSET', label: 'Tonight', dayLabel: 'Tonight',
    };
    render(
      <WindowControl
        events={[EVENTS[0], second]}
        activeIndex={0}
        onSelect={vi.fn()}
        verdicts={new Map([
          [EVENTS[0].id, verdict(SCOPE_45)],
          [second.id, verdict({ ...SCOPE_45, tier: 'WORTH_IT' })],
        ])}
      />,
    );

    expect(screen.getByTestId('wf-win-next')).toHaveAttribute('aria-label', 'Next event, Worth it, within 45 min');
  });

  it('speaks every printed spelling in full: the visible words are the spoken ones, abbreviation expanded', () => {
    for (const minutes of [45, 90, 150]) {
      const { unmount } = renderControl({ scope: { tierMinutes: minutes } });
      const printed = screen.getByTestId('wf-win-verdict-scope-full').textContent;
      const spoken = verdictScopeSpoken({ tierMinutes: minutes });
      expect(printed).toBe(verdictScopeText({ tierMinutes: minutes }));
      // WCAG 2.5.3 as the Plan's Spread qualifier is held to: the visible words, short spelling expanded, are in the name.
      expect(computeAccessibleName(screen.getByTestId('wf-win-pill'))).toContain(spoken);
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
    expect(screen.getByTestId('wf-win-verdict')).not.toHaveClass('wf-win-verdict--scoped');
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

  it('swaps the full scope for the short ≤ form at ≤811px (the VerdictWord swap pattern), and shows the full one above', () => {
    // Default: the short spelling is hidden, so desktop and tablet above 811px print the full words.
    expect(css).toMatch(/\.wf-scope-short\s*\{\s*display:\s*none;\s*\}/);
    const bare = css.replace(/\/\*[\s\S]*?\*\//g, '');
    const at = bare.indexOf('.wf-map-tab .wf-win-verdict-scope .wf-scope-full { display: none; }');
    expect(at).toBeGreaterThan(-1);
    const block = bare.slice(bare.lastIndexOf('@media', at), bare.indexOf('\n}\n', at) + 3);
    expect(block).toContain('(max-width: 811px)');
    expect(block).toContain('.wf-map-tab .wf-win-verdict-scope .wf-scope-short { display: inline; }');
    // A squeezed scoped cell keeps its content width, so the short scope is never clipped under its word.
    expect(block).toContain('.wf-map-tab .wf-win-verdict--scoped { min-width: max-content; }');
  });

  it('clips the scoped line inside its cell, so nothing can paint into the caret', () => {
    const rule = css.match(/\.wf-win-verdict-sub\s*\{([^}]*)\}/)[1];
    expect(rule).toMatch(/overflow:\s*hidden/);
    expect(rule).toMatch(/min-width:\s*0/);
  });
});

describe('the dropdown caption', () => {
  it('is referenced by the listbox (aria-describedby), so the scope is announced with the list', () => {
    renderControl(SCOPE_45, { open: true, onOpenChange: vi.fn() });

    const note = screen.getByTestId('wf-win-scope-note');
    expect(screen.getByTestId('wf-win-listbox')).toHaveAttribute('aria-describedby', note.id);
  });

  it('is withdrawn when any listed verdict is not scoped (a mixed list)', () => {
    const second = { ...EVENTS[0], id: 'solar:2026-09-02:SUNSET', eventType: 'SUNSET' };
    render(
      <WindowControl
        events={[EVENTS[0], second]}
        activeIndex={0}
        onSelect={vi.fn()}
        open
        onOpenChange={vi.fn()}
        verdicts={new Map([[EVENTS[0].id, verdict(SCOPE_45)], [second.id, verdict({})]])}
      />,
    );

    expect(screen.queryByTestId('wf-win-scope-note')).toBeNull();
    expect(screen.getByTestId('wf-win-listbox')).not.toHaveAttribute('aria-describedby');
  });

  it('is withdrawn from the phone\'s Windows section on a mixed list too', () => {
    render(
      <MapPeekWindowsSection
        heading="Today"
        rows={EVENTS}
        verdicts={new Map([
          [EVENTS[0].id, { tier: 'MAYBE', ...SCOPE_45 }],
          ['other', { tier: 'MAYBE' }],
        ])}
        onSelect={vi.fn()}
      />,
    );

    expect(screen.queryByTestId('wf-map-peek-scope')).toBeNull();
  });
});

describe('the window panel note under a scope (each branch keeps its meaning and names the tier)', () => {
  const noteOf = (over, args = {}) => windowPanelNote({
    isSolar: true, verdict: verdict({ ...SCOPE_45, ...over }), ...args,
  });

  it('words the tier as "within a 45 min drive"', () => {
    expect(noteOf({ sharingCount: 0 })).toContain('within a 45 min drive');
  });

  it('keeps the UNRATED message (not "the strongest region\'s average") and qualifies it', () => {
    const lone = noteOf({ tier: 'UNRATED', sharingCount: 0 });
    expect(lone).toContain('Too few rated to call within a 45 min drive');
    expect(lone).not.toContain('strongest region');
    const all = noteOf({ tier: 'UNRATED', allInScope: true, sharingCount: 1 });
    expect(all).toContain('enough ratings within a 45 min drive for a verdict');
    const several = noteOf({ tier: 'UNRATED', sharingCount: 2 });
    expect(several).toContain('3 regions have too few ratings within a 45 min drive');
  });

  it('keeps "Poor in all N regions", qualified', () => {
    const note = noteOf({ tier: 'STAND_DOWN', allInScope: true, sharingCount: 2, scopedRegionCount: 3 });
    expect(note).toContain('Poor in all 3 regions in your area within a 45 min drive');
    const some = noteOf({ tier: 'STAND_DOWN', sharingCount: 1 });
    expect(some).toContain('2 regions are poor for this window within a 45 min drive');
  });

  it('keeps the drive-time sentence for a shared rated tier, with the whole-region rows note', () => {
    const note = noteOf({ tier: 'WORTH_IT', sharingCount: 1 });
    expect(note).toContain('2 regions are worth it for this window within a 45 min drive');
    expect(note).toContain('each region as a whole');
    expect(note).not.toContain('only decides what the map draws');
  });

  it('gives a window the answer names with nothing in reach its own note, not the ranking sentence', () => {
    const note = windowPanelNote({ isSolar: true, verdict: null, emptyScope: { tierMinutes: 45 } });
    expect(note).toContain('Nothing rated within a 45 min drive');
    expect(note).not.toContain('strongest region');
    // With no scope the null verdict still reads as it always did.
    expect(windowPanelNote({ isSolar: true, verdict: null })).toContain('strongest region');
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

  it('words a scoped single-region note as a within-tier verdict over whole-region rows', () => {
    const scoped = windowPanelNote({ isSolar: true, verdict: verdict({ ...SCOPE_45, sharingCount: 0 }) });
    expect(scoped).toContain('over its places within a 45 min drive');
    expect(scoped).toContain('each region as a whole');

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
