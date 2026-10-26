import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, fireEvent, screen } from '@testing-library/react';

/**
 * The Lite strip must not clip its Pro door's popover (aurora-lite-alert-plan.md §2.3, review finding):
 * `.wf-aurora-strip` is a single flex row with `overflow: hidden`, so the Lite modifier is declared AFTER
 * it with `overflow: visible`, and the door is taken out of the row's positioning so the popover anchors
 * to the strip. Asserted on the REAL `index.css` injected into jsdom's cascade (the jsdom-cascade
 * technique: the browser claim stays "it looks right", the testable claim is "which declaration wins").
 */
let mockMobile = true;
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => mockMobile }));
import LiteAuroraBanner from '../components/LiteAuroraBanner.jsx';

const CSS = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8');
let style;

beforeEach(() => {
  mockMobile = true;
  style = document.createElement('style');
  style.textContent = CSS;
  document.head.appendChild(style);
});
afterEach(() => style.remove());

const alertOf = {
  level: 'MODERATE', active: true, triggerType: 'forecast', allOvercast: false, currentNightEndsAt: null,
};

describe('the Lite strip does not clip its popover', () => {
  it('declares the Lite modifier after the base strip rule', () => {
    expect(CSS.indexOf('.wf-aurora-strip--lite { overflow: visible; }')).toBeGreaterThan(
      CSS.indexOf('.wf-aurora-strip {'),
    );
  });

  it('the Lite strip computes overflow visible while the Pro strip stays hidden', () => {
    const { getByTestId, container } = render(<LiteAuroraBanner liteAlert={alertOf} />);
    expect(getComputedStyle(getByTestId('aurora-banner')).overflow).toBe('visible');

    const pro = document.createElement('div');
    pro.className = 'wf-aurora-strip';
    container.appendChild(pro);
    expect(getComputedStyle(pro).overflow).toBe('hidden');
  });

  it('the door is not a positioning box inside the Lite strip, so the popover anchors to the strip', () => {
    const { getByTestId } = render(<LiteAuroraBanner liteAlert={alertOf} />);
    expect(getComputedStyle(getByTestId('aurora-banner-upsell-door')).position).toBe('static');
    expect(getComputedStyle(getByTestId('aurora-banner')).position).toBe('relative');
  });
});

describe('the Lite card and strip popovers', () => {
  it('the card popover is sized from the viewport, not the shrink-to-fit door', () => {
    mockMobile = false;
    const { getByTestId } = render(<LiteAuroraBanner liteAlert={alertOf} />);
    fireEvent.click(getByTestId('aurora-banner-upsell-trigger'));
    const pop = getByTestId('aurora-banner-upsell-popover');

    expect(CSS).toMatch(/\.wf-aurora-card--lite \.wf-pro-upsell \{\s*width: min\(360px, calc\(100vw - 32px\)\);/);
    expect(getComputedStyle(pop).zIndex).toBe('19');
  });

  it('the Lite strip glow bar draws no shadow that could leak past the unclipped strip', () => {
    const { getByTestId } = render(<LiteAuroraBanner liteAlert={alertOf} />);
    expect(getByTestId('aurora-banner')).toBeInTheDocument();
    expect(CSS).toMatch(/\.wf-aurora-strip--lite::after \{[^}]*box-shadow: none;/);
  });
});

describe('dismissing while the door is open', () => {
  it('the popover is already closed when focus reaches the dismiss, and the dismiss removes the banner', () => {
    mockMobile = false;
    const { getByTestId, queryByTestId } = render(<LiteAuroraBanner liteAlert={alertOf} />);
    fireEvent.click(getByTestId('aurora-banner-upsell-trigger'));
    expect(getByTestId('aurora-banner-upsell-popover')).toBeInTheDocument();

    // Focus moving to the dismiss is a focusin outside the door, which closes the popover (the door's
    // own rule), so the door is never open when the dismiss unmounts it; focus then falls to <body>, as
    // it does when the Pro banner is dismissed (nothing focusable holds the banner).
    fireEvent.focusIn(getByTestId('aurora-banner-dismiss'));
    expect(queryByTestId('aurora-banner-upsell-popover')).toBeNull();
    fireEvent.click(getByTestId('aurora-banner-dismiss'));
    expect(queryByTestId('aurora-banner')).toBeNull();
    expect(screen.queryByRole('dialog')).toBeNull();
  });
});
