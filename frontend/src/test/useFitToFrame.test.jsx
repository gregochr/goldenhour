/**
 * `useFitToFrame` and the four Map tab panels that use it (the Filters popover, the Regions menu,
 * the window menu and the legend). Each of them used to keep a fixed `max-height: 420px`, which on
 * a short map frame ran past the frame's edge, was clipped by it, and left its last rows
 * unreachable. jsdom has no layout, so the rects are stubbed; the real geometry is a browser check
 * recorded in the PR.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import RegionsJump from '../components/map/RegionsJump.jsx';
import MapLegendPanel from '../components/map/MapLegendPanel.jsx';
import WindowControl from '../components/map/WindowControl.jsx';

let mockIsMobile = false;
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => mockIsMobile }));

const PROP = '--wf-fit-room';
let originalRect;
let originalInnerHeight;
let rects;

beforeEach(() => {
  mockIsMobile = false;
  originalRect = Element.prototype.getBoundingClientRect;
  originalInnerHeight = window.innerHeight;
  rects = {
    pane: { top: 100, bottom: 600 },
    'wf-jump-menu': { top: 300, bottom: 720 },
    'wf-win-menu': { top: 160, bottom: 580 },
    'wf-legend-panel': { top: 20, bottom: 560 },
  };
  Element.prototype.getBoundingClientRect = function stubRect() {
    const key = ['wf-map-tab', ...Object.keys(rects)].find((c) => this.classList.contains(c));
    const r = (key === 'wf-map-tab' ? rects.pane : rects[key]) ?? { top: 0, bottom: 0 };
    return { ...r, left: 0, right: 0, width: 0, height: r.bottom - r.top, x: 0, y: r.top };
  };
  Object.defineProperty(window, 'innerHeight', { configurable: true, value: 1000 });
});

afterEach(() => {
  Element.prototype.getBoundingClientRect = originalRect;
  Object.defineProperty(window, 'innerHeight', { configurable: true, value: originalInnerHeight });
});

const room = (testid) => screen.getByTestId(testid).style.getPropertyValue(PROP);

const REGIONS = [{ name: 'North East', driveMinutes: 35, beyondArea: false, bestRating: 5 }];
const EVENTS = [{
  id: 'solar:2026-09-02:SUNSET', kind: 'solar', eventType: 'SUNSET', date: '2026-09-02',
  label: 'Tonight', time: '19:45', bestRating: 4, scored: true, badges: [],
}];

function inPane(chromeClass, node) {
  return <div className="wf-map-tab"><div className={chromeClass}>{node}</div></div>;
}

describe('Regions menu — opens down, fitted to the frame bottom', () => {
  const jump = (open) => (
    <RegionsJump open={open} onOpenChange={vi.fn()} rows={REGIONS} onSelectRegion={vi.fn()} />
  );

  it('writes the room from its top to the pane bottom, less the 8px inset', () => {
    render(inPane('wf-map-chrome-tr', jump(true)));
    expect(room('wf-jump-menu')).toBe('292px');
  });

  it('is bounded by the viewport when that ends first', () => {
    Object.defineProperty(window, 'innerHeight', { configurable: true, value: 500 });
    render(inPane('wf-map-chrome-tr', jump(true)));
    expect(room('wf-jump-menu')).toBe('192px');
  });

  it('writes nothing on a phone, where the menu is a sheet that scrolls its own body', () => {
    mockIsMobile = true;
    render(inPane('wf-map-chrome-tr', jump(true)));
    expect(screen.queryByTestId('wf-jump-menu')?.style.getPropertyValue(PROP) ?? '').toBe('');
  });

  it('writes nothing outside a map pane', () => {
    render(jump(true));
    expect(room('wf-jump-menu')).toBe('');
  });

  it('re-measures on a window resize', () => {
    render(inPane('wf-map-chrome-tr', jump(true)));
    rects.pane = { top: 100, bottom: 800 };
    act(() => { window.dispatchEvent(new Event('resize')); });
    expect(room('wf-jump-menu')).toBe('492px');
  });
});

describe('Window menu — opens down, fitted to the frame bottom', () => {
  it('measures once the menu is opened', () => {
    render(inPane('wf-map-chrome-tl', (
      <WindowControl events={EVENTS} activeIndex={0} onSelect={vi.fn()} />
    )));
    expect(screen.queryByTestId('wf-win-menu')).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    // 600 − 160 − 8.
    expect(room('wf-win-menu')).toBe('432px');
  });
});

describe('Legend panel — opens UP, fitted to the frame top', () => {
  const legend = (
    <MapLegendPanel
      open
      onOpenChange={vi.fn()}
      handoverFraction={0}
      ringsEnabled
      onToggleRings={vi.fn()}
    />
  );

  it('writes the room from the panel bottom up to the pane top, less the 8px inset', () => {
    rects.pane = { top: 100, bottom: 900 };
    rects['wf-legend-panel'] = { top: 300, bottom: 560 };
    render(inPane('wf-map-chrome-bl', legend));
    // 560 (panel bottom, fixed above its chip) − 100 (frame top) − 8.
    expect(room('wf-legend-panel')).toBe('452px');
  });

  it('is bounded by the viewport top when the frame starts above it', () => {
    rects.pane = { top: -200, bottom: 900 };
    rects['wf-legend-panel'] = { top: 300, bottom: 560 };
    render(inPane('wf-map-chrome-bl', legend));
    expect(room('wf-legend-panel')).toBe('552px');
  });

  it('gives a cramped frame only what it has, and never a negative figure', () => {
    rects.pane = { top: 555, bottom: 900 };
    rects['wf-legend-panel'] = { top: 300, bottom: 560 };
    render(inPane('wf-map-chrome-bl', legend));
    expect(room('wf-legend-panel')).toBe('0px');
  });
});

describe('stylesheet', () => {
  const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
  const blockOf = (cls) => {
    const blocks = [...css.matchAll(/([^{}]+)\{([^{}]*)\}/g)]
      .filter(([, sel, body]) => body.includes('max-height')
        && sel.split(',').some((s) => s.trim() === cls));
    expect(blocks).toHaveLength(1);
    return blocks[0][2];
  };

  it.each(['.wf-win-menu', '.wf-jump-menu', '.wf-legend-panel', '.wf-filters-panel'])(
    '%s is capped at the measured room and scrolls inside it',
    (cls) => {
      const body = blockOf(cls);
      expect(body).toMatch(/max-height:\s*min\(420px,\s*var\(--wf-fit-room,\s*420px\)\);/);
      expect(body).toMatch(/overflow-y:\s*auto;/);
      expect(body).toMatch(/overscroll-behavior:\s*contain;/);
      expect(body).toMatch(/scroll-padding-block:\s*6px;/);
    },
  );

  it('an open Regions or window menu lifts its cluster to the menus rung', () => {
    expect(css).toMatch(/\.wf-map-chrome-tl:has\(\.wf-win-menu\)/);
    expect(css).toMatch(/\.wf-map-chrome-tl:has\(\.wf-win-menu\)\s*\{\s*z-index:\s*1500;\s*\}/);
  });
});
