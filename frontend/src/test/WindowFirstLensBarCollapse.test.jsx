import React, { useRef } from 'react';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, fireEvent, act, within } from '@testing-library/react';
import WindowFirstLensBar from '../components/WindowFirstLensBar.jsx';
import useRatingLens from '../hooks/useRatingLens.js';
import useReachLens from '../hooks/useReachLens.js';
import useLensReserve from '../hooks/useLensReserve.js';
import { mockOpenGuide } from './guideContextMock.js';

vi.mock('../context/GuideContext.jsx', async () => (await import('./guideContextMock.js')).guideContextMock);

/**
 * The phone lens that collapses past This week (screen-spec.md D6, `useCollapsingLens`).
 *
 * <p>jsdom evaluates no CSS and has no layout, so the heights are a stub: the lens measures {@code FULL} until it carries
 * the collapsed class, then {@code COLLAPSED}. What is asserted is therefore the state machine and the numbers it writes
 * (the spacer's height, the published properties); that the page does not move is a browser measurement, taken in the
 * smoke spec.
 */
const FULL = 90;
const COLLAPSED = 36;
const TUESDAY = '2026-08-04';

const asPhone = () => vi.stubGlobal('matchMedia', (query) => ({
  matches: query === '(max-width: 639px)',
  media: query,
  onchange: null,
  addEventListener() {},
  removeEventListener() {},
  addListener() {},
  removeListener() {},
  dispatchEvent() { return false; },
}));

let fullHeight;
let observers;

beforeEach(() => {
  localStorage.clear();
  mockOpenGuide.mockClear?.();
  fullHeight = FULL;
  observers = [];
  vi.stubGlobal('ResizeObserver', class {
    constructor(callback) { this.callback = callback; observers.push(this); }
    observe() {}
    disconnect() { observers = observers.filter((o) => o !== this); }
  });
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function rect() {
    const collapsed = this.classList?.contains('wf-lens-collapsed');
    const h = this.classList?.contains('wf-lens') ? (collapsed ? COLLAPSED : fullHeight) : 0;
    return {
      width: 390, height: h, top: 0, left: 0, right: 390, bottom: h, x: 0, y: 0, toJSON() {},
    };
  });
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

function Lens({ stuck = true, pastWeek = false, verdict = null, spotCount = 6, reachedCount = 6 }) {
  const lens = useReachLens(TUESDAY, false);
  const ratingLens = useRatingLens();
  return (
    <WindowFirstLensBar
      lens={lens}
      ratingLens={ratingLens}
      spotCount={spotCount}
      reachedCount={reachedCount}
      windowCount={4}
      stuck={stuck}
      pastWeek={pastWeek}
      verdict={verdict}
    />
  );
}

const summary = () => screen.getByTestId('window-first-lens-summary');
const spacerHeight = () => screen.getByTestId('window-first-lens-spacer').style.height;

describe('the lens on desktop and tablet (provably unchanged)', () => {
  it('draws no summary, no body wrapper and no spacer, whatever the scroll says', () => {
    render(<Lens pastWeek />);
    expect(screen.queryByTestId('window-first-lens-summary')).toBeNull();
    expect(screen.queryByTestId('window-first-lens-body')).toBeNull();
    expect(screen.queryByTestId('window-first-lens-spacer')).toBeNull();
    expect(screen.getByTestId('window-first-lens')).not.toHaveClass('wf-lens-collapsed');
    expect(screen.getByTestId('window-first-lens-tiers')).toBeVisible();
  });
});

describe('the phone lens', () => {
  beforeEach(asPhone);

  it('at rest keeps the controls and hides the persistent summary, with no spacer', () => {
    render(<Lens />);
    expect(summary()).toHaveAttribute('hidden');
    expect(screen.getByTestId('window-first-lens-body')).not.toHaveAttribute('hidden');
    expect(spacerHeight()).toBe('0px');
  });

  it('past This week it collapses to the one summary line, naming drive, rating and count', () => {
    render(<Lens pastWeek />);
    const button = summary();
    expect(button).not.toHaveAttribute('hidden');
    expect(button).toHaveTextContent('Drive 45 min · any rating · 6 spots ▾');
    expect(button).toHaveAttribute('aria-expanded', 'false');
    expect(button).toHaveAttribute('aria-controls', screen.getByTestId('window-first-lens-body').id);
    expect(screen.getByTestId('window-first-lens')).toHaveClass('wf-lens-collapsed');
  });

  it('keeps the controls mounted while collapsed, only hidden', () => {
    render(<Lens pastWeek />);
    const body = screen.getByTestId('window-first-lens-body');
    expect(body).toHaveAttribute('hidden');
    expect(within(body).getAllByRole('button', { hidden: true }).length).toBeGreaterThan(4);
  });

  it('speaks the stars of a rating floor and the glyph is decoration', () => {
    render(<Lens pastWeek />);
    fireEvent.click(summary());
    fireEvent.click(within(screen.getByTestId('window-first-lens-floors')).getByText('3★+'));
    fireEvent.click(summary());
    expect(summary()).toHaveTextContent('3★ stars+');
    expect(summary().querySelector('.sr-only')).toHaveTextContent('stars');
    expect(summary().querySelector('[aria-hidden="true"]')).not.toBeNull();
  });

  it('says "any distance" for the Any tier and the of-N wording when the floor trims', () => {
    render(<Lens pastWeek spotCount={3} reachedCount={9} />);
    fireEvent.click(summary());
    fireEvent.click(within(screen.getByTestId('window-first-lens-tiers')).getByText('Any'));
    fireEvent.click(summary());
    expect(summary()).toHaveTextContent('Drive any distance · any rating · 3 of 9 spots within reach');
  });

  it('writes a spacer of full minus collapsed, so the document is as tall as it was', () => {
    const { rerender } = render(<Lens />);
    rerender(<Lens pastWeek />);
    expect(spacerHeight()).toBe(`${FULL - COLLAPSED}px`);
  });

  it('a tap opens the lens in place: same button, expanded, spacer gone, controls shown', () => {
    render(<Lens pastWeek />);
    const before = summary();
    fireEvent.click(before);
    expect(summary()).toBe(before);
    expect(before).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByTestId('window-first-lens-body')).not.toHaveAttribute('hidden');
    expect(screen.getByTestId('window-first-lens')).not.toHaveClass('wf-lens-collapsed');
    expect(spacerHeight()).toBe('0px');
  });

  it('stays open while stuck, and tapping it again puts it back to one line', () => {
    const { rerender } = render(<Lens pastWeek />);
    fireEvent.click(summary());
    rerender(<Lens pastWeek stuck />);
    expect(summary()).toHaveAttribute('aria-expanded', 'true');
    fireEvent.click(summary());
    expect(summary()).toHaveAttribute('aria-expanded', 'false');
    expect(screen.getByTestId('window-first-lens')).toHaveClass('wf-lens-collapsed');
  });

  it('un-sticking (back at the top) resets an opened lens, so the next scroll collapses it again', () => {
    const { rerender } = render(<Lens pastWeek />);
    fireEvent.click(summary());
    rerender(<Lens pastWeek={false} stuck={false} />);
    expect(summary()).toHaveAttribute('hidden');
    rerender(<Lens pastWeek stuck />);
    expect(summary()).toHaveAttribute('aria-expanded', 'false');
    expect(screen.getByTestId('window-first-lens')).toHaveClass('wf-lens-collapsed');
  });

  it('never collapses under a focused chip, and collapses on the blur', () => {
    const { rerender } = render(<Lens />);
    const chip = within(screen.getByTestId('window-first-lens-tiers')).getByText('Any');
    act(() => chip.focus());
    rerender(<Lens pastWeek />);
    expect(screen.getByTestId('window-first-lens')).not.toHaveClass('wf-lens-collapsed');
    expect(document.activeElement).toBe(chip);
    act(() => chip.blur());
    expect(screen.getByTestId('window-first-lens')).toHaveClass('wf-lens-collapsed');
  });

  it('a focus move between two chips does not count as leaving the lens', () => {
    const { rerender } = render(<Lens />);
    const [first, second] = within(screen.getByTestId('window-first-lens-tiers')).getAllByRole('button');
    act(() => first.focus());
    rerender(<Lens pastWeek />);
    act(() => second.focus());
    expect(screen.getByTestId('window-first-lens')).not.toHaveClass('wf-lens-collapsed');
  });

  it('a reset never moves focus: the summary keeps it, and stays drawn while it does', () => {
    const { rerender } = render(<Lens pastWeek />);
    act(() => summary().focus());
    fireEvent.click(summary());
    rerender(<Lens pastWeek={false} stuck={false} />);
    expect(document.activeElement).toBe(summary());
    expect(summary()).not.toHaveAttribute('hidden');
    act(() => summary().blur());
    expect(summary()).toHaveAttribute('hidden');
  });

  it('focusing the collapsed summary does not re-open it', () => {
    render(<Lens pastWeek />);
    act(() => summary().focus());
    expect(summary()).toHaveAttribute('aria-expanded', 'false');
    expect(screen.getByTestId('window-first-lens')).toHaveClass('wf-lens-collapsed');
  });

  it('re-measures the full height on a resize while it is full', () => {
    const { rerender } = render(<Lens />);
    fullHeight = 98;
    act(() => { window.dispatchEvent(new Event('resize')); });
    rerender(<Lens pastWeek />);
    expect(spacerHeight()).toBe(`${98 - COLLAPSED}px`);
  });

  it('re-measures when the lens itself resizes while full (rotation, a wrapped mark)', () => {
    const { rerender } = render(<Lens />);
    fullHeight = 104;
    act(() => { observers.forEach((o) => o.callback()); });
    rerender(<Lens pastWeek />);
    expect(spacerHeight()).toBe(`${104 - COLLAPSED}px`);
  });

  it('corrects the spacer from the measured collapsed height', () => {
    const { rerender } = render(<Lens />);
    rerender(<Lens pastWeek />);
    expect(spacerHeight()).toBe(`${FULL - COLLAPSED}px`);
    act(() => { observers.forEach((o) => o.callback()); });
    expect(spacerHeight()).toBe(`${FULL - COLLAPSED}px`);
  });

  it('works without a ResizeObserver (a very old browser): it still measures once', () => {
    vi.stubGlobal('ResizeObserver', undefined);
    const { rerender } = render(<Lens />);
    rerender(<Lens pastWeek />);
    expect(spacerHeight()).toBe(`${FULL - COLLAPSED}px`);
  });

  it('does not collapse behind a plan-conflict verdict, which is a warning with an action', () => {
    const verdict = {
      lead: 'Clash', text: 'Nothing fits', action: null, announce: 'Clash. Nothing fits',
    };
    render(<Lens pastWeek verdict={verdict} />);
    expect(screen.getByTestId('window-first-lens')).not.toHaveClass('wf-lens-collapsed');
    expect(summary()).toHaveAttribute('hidden');
  });

  it('a collapsed lens and an opened one reach for the same body id', () => {
    render(<Lens pastWeek />);
    const id = summary().getAttribute('aria-controls');
    fireEvent.click(summary());
    expect(summary().getAttribute('aria-controls')).toBe(id);
    expect(document.getElementById(id)).toBe(screen.getByTestId('window-first-lens-body'));
  });
});

describe('--wf-bar-h and --wf-lens-h while collapsed', () => {
  beforeEach(asPhone);

  function Root(props) {
    const ref = useRef(null);
    useLensReserve(ref);
    return <div ref={ref} data-testid="root"><Lens {...props} /></div>;
  }

  it('publish the collapsed height, so the bars stick right under the one line', () => {
    const { rerender } = render(<Root />);
    const root = screen.getByTestId('root');
    expect(root.style.getPropertyValue('--wf-bar-h')).toBe(`${FULL}px`);
    rerender(<Root pastWeek />);
    act(() => { observers.forEach((o) => o.callback()); });
    expect(root.style.getPropertyValue('--wf-bar-h')).toBe(`${COLLAPSED}px`);
    expect(root.style.getPropertyValue('--wf-lens-h')).toBe(`${COLLAPSED}px`);
    expect(root.style.getPropertyValue('--wf-lens-reserve')).toBe(`${COLLAPSED + 6}px`);
  });
});
