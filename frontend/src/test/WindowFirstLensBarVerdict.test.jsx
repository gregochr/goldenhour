import React from 'react';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import WindowFirstLensBar from '../components/WindowFirstLensBar.jsx';
import useRatingLens from '../hooks/useRatingLens.js';
import useReachLens from '../hooks/useReachLens.js';

vi.mock('../context/GuideContext.jsx', async () => (await import('./guideContextMock.js')).guideContextMock);

/**
 * The lens bar's verdict line (lens-verdict-line-plan.md): the plan-level conflict, shown in the
 * readout in place of the count, with one action.
 *
 * <p>Like `WindowFirstLensBar.test.jsx`, jsdom evaluates no CSS and `useIsMobile` is false unless the
 * matchMedia stub is replaced, so what is asserted here is the DOM and the attributes the stylesheet
 * keys off — the amber modifier classes, `data-variant`, where the button sits relative to the
 * ellipsising span — and `lensVerdictCss.test.js` pins the rules that read them.
 */

const VERDICT = {
  lead: '0',
  text: 'at 4★+ this week · best is 3★, Angel of the North',
  action: { kind: 'rating', id: '3', label: 'Drop to 3★+' },
  announce: 'Nothing at 4 stars or better this week. Best is 3 stars, Angel of the North.',
};

function Lens({ verdict = null, onVerdictAction = vi.fn() }) {
  const lens = useReachLens('2026-08-04', false);
  const ratingLens = useRatingLens();
  return (
    <WindowFirstLensBar
      lens={lens}
      ratingLens={ratingLens}
      spotCount={0}
      windowCount={4}
      verdict={verdict}
      onVerdictAction={onVerdictAction}
    />
  );
}

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

describe('WindowFirstLensBar — the verdict line', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => vi.unstubAllGlobals());

  it('desktop: the readout becomes the verdict, the button is a SIBLING of the ellipsising span', () => {
    render(<Lens verdict={VERDICT} />);
    const readout = screen.getByTestId('window-first-lens-readout');
    expect(readout).toHaveAttribute('data-variant', 'verdict');
    expect(readout).toHaveClass('wf-lens-res', 'wf-lens-res--verdict');
    expect(readout).toHaveTextContent('0 at 4★ stars+ this week · best is 3★ stars, Angel of the North');
    // The count and the summary are not drawn alongside it.
    expect(readout).not.toHaveTextContent('weekday default');
    expect(readout.querySelector('b')).toHaveTextContent('0');

    const button = screen.getByTestId('window-first-lens-verdict-act');
    const span = readout.querySelector(':scope > span');
    // Inside the span it would be clipped by the ellipsis; the span is what has `overflow: hidden`.
    expect(span).not.toContainElement(button);
    expect(button.parentElement).toBe(readout);
    expect(span).toHaveAttribute('title', '0 at 4★+ this week · best is 3★, Angel of the North');
  });

  it('phone: the verdict and its button live in the count line, before the ?', () => {
    asPhone();
    render(<Lens verdict={VERDICT} />);
    const readout = screen.getByTestId('window-first-lens-readout');
    expect(readout).toHaveAttribute('data-variant', 'verdict');
    expect(readout).toHaveClass('wf-lens-count', 'wf-lens-count--verdict');
    expect(readout).toHaveTextContent('0 at 4★ stars+ this week · best is 3★ stars, Angel of the North');
    const button = within(readout).getByTestId('window-first-lens-verdict-act');
    expect(button).toHaveTextContent('Drop to 3★ stars+');
    const guide = within(readout).getByTestId('guide-link');
    expect(button.compareDocumentPosition(guide) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.getAllByTestId('guide-link')).toHaveLength(1);
  });

  it('keeps the ? after the verdict on desktop', () => {
    render(<Lens verdict={VERDICT} />);
    expect(within(screen.getByTestId('window-first-lens')).getByTestId('guide-link')).toBeInTheDocument();
  });

  it('carries the amber modifier ONLY in the verdict state', () => {
    const { rerender } = render(<Lens verdict={null} />);
    const readout = screen.getByTestId('window-first-lens-readout');
    expect(readout).not.toHaveClass('wf-lens-res--verdict');
    expect(readout).toHaveAttribute('data-variant', 'summary');
    expect(readout).not.toHaveAttribute('tabindex');
    expect(screen.queryByTestId('window-first-lens-verdict-act')).toBeNull();
    rerender(<Lens verdict={VERDICT} />);
    expect(screen.getByTestId('window-first-lens-readout')).toHaveClass('wf-lens-res--verdict');
    // Never a tab stop, but focusable by the shell when an action leaves nothing else to land on.
    expect(screen.getByTestId('window-first-lens-readout')).toHaveAttribute('tabindex', '-1');
  });

  it('presses through to onVerdictAction with the whole action descriptor', () => {
    const onVerdictAction = vi.fn();
    render(<Lens verdict={VERDICT} onVerdictAction={onVerdictAction} />);
    const button = screen.getByTestId('window-first-lens-verdict-act');
    expect(button).toHaveAttribute('data-loosen', 'rating');
    fireEvent.click(button);
    expect(onVerdictAction).toHaveBeenCalledWith(VERDICT.action);
  });

  it('draws no button when the verdict has no action, and tolerates no handler', () => {
    const { unmount } = render(<Lens verdict={{ ...VERDICT, action: null }} />);
    expect(screen.queryByTestId('window-first-lens-verdict-act')).toBeNull();
    unmount();
    asPhone();
    render(<Lens verdict={{ ...VERDICT, action: null }} />);
    expect(screen.queryByTestId('window-first-lens-verdict-act')).toBeNull();
    expect(screen.getByTestId('window-first-lens-readout')).toHaveTextContent('0 at 4★ stars+ this week');
  });

  it('does not throw when pressed with no handler wired', () => {
    render(<WindowFirstLensBar
      lens={{
        tier: { id: '45', label: '45 min' }, tierId: '45', defaultTier: { label: '45 min' }, defaultTierId: '45',
        weekend: false, overridden: false, locked: false, selectTier: vi.fn(), resetToDefault: vi.fn(),
      }}
      ratingLens={{ floor: { id: 'any', label: 'Any rating' }, floorId: 'any', selectFloor: vi.fn() }}
      spotCount={0}
      windowCount={1}
      verdict={VERDICT}
    />);
    expect(() => fireEvent.click(screen.getByTestId('window-first-lens-verdict-act'))).not.toThrow();
  });
});

describe('WindowFirstLensBar — the verdict line for assistive tech', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => vi.unstubAllGlobals());

  it('speaks "stars" for every glyph, hides the glyph and the separators, and names the button by its words', () => {
    render(<Lens verdict={VERDICT} />);
    const readout = screen.getByTestId('window-first-lens-readout');
    // NVDA does not speak U+2605: each glyph is hidden and the word sits beside it.
    const glyphs = [...readout.querySelectorAll('[aria-hidden="true"]')].map((n) => n.textContent.trim());
    expect(glyphs.filter((g) => g === '★')).toHaveLength(3);
    expect(glyphs).toContain('·');
    expect(readout.querySelectorAll('.sr-only')).toHaveLength(3);
    // WCAG 2.5.3: the accessible name holds the visible words, plus "stars" for the glyph; the arrow is hidden.
    expect(screen.getByTestId('window-first-lens-verdict-act')).toHaveAccessibleName(/^Drop to 3\s*stars\+$/);
    expect(screen.getByTestId('window-first-lens-verdict-act')).not.toHaveAttribute('aria-label');
  });

  it('says "star" for a one-star rating', () => {
    render(<Lens verdict={{ ...VERDICT, text: 'at 2★+ · best is 1★', action: { kind: 'rating', id: 'any', label: 'Show any rating' } }} />);
    const sr = [...screen.getByTestId('window-first-lens-readout').querySelectorAll('.sr-only')].map((n) => n.textContent);
    expect(sr).toEqual([' stars', ' star']);
  });

  it('keeps an always-mounted status span, a direct child of the bar, empty without a verdict', () => {
    render(<Lens verdict={null} />);
    const status = screen.getByTestId('window-first-lens-verdict-status');
    expect(status).toHaveAttribute('role', 'status');
    expect(status).toHaveAttribute('aria-live', 'polite');
    expect(status).toHaveClass('sr-only');
    expect(status).toBeEmptyDOMElement();
    expect(status.parentElement).toBe(screen.getByTestId('window-first-lens'));
  });

  it('fills the SAME status node with the announce sentence, so the change is announced', () => {
    const { rerender } = render(<Lens verdict={null} />);
    const before = screen.getByTestId('window-first-lens-verdict-status');
    rerender(<Lens verdict={VERDICT} />);
    const after = screen.getByTestId('window-first-lens-verdict-status');
    expect(after).toBe(before);
    expect(after).toHaveTextContent(VERDICT.announce);
    // Plain words: no glyph, no separator, no arrow.
    expect(after.textContent).not.toMatch(/[★·→]/);
  });

  it('does not remount the status span when the viewport flips between phone and desktop', () => {
    const { unmount } = render(<Lens verdict={VERDICT} />);
    const desktop = screen.getByTestId('window-first-lens-verdict-status');
    expect(desktop.parentElement).toBe(screen.getByTestId('window-first-lens'));
    unmount();
    asPhone();
    render(<Lens verdict={VERDICT} />);
    const phone = screen.getByTestId('window-first-lens-verdict-status');
    expect(phone.parentElement).toBe(screen.getByTestId('window-first-lens'));
    expect(phone).toHaveTextContent(VERDICT.announce);
  });
});
