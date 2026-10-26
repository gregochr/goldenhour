import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { render, fireEvent } from '@testing-library/react';
import ProUpsellDoor from '../components/shared/ProUpsell.jsx';

/**
 * The Map's LITE viewline door sits at the bottom-left (`.wf-map-chrome-bl`), so its popover opens UPWARD
 * (aurora-lite-alert-plan.md §2.4). Asserted on the REAL `index.css` injected into jsdom's cascade: the
 * browser claim stays "it looks right"; the testable claim is which declaration wins.
 */
const CSS = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8');
let style;

beforeEach(() => {
  style = document.createElement('style');
  style.textContent = CSS;
  document.head.appendChild(style);
});
afterEach(() => style.remove());

function openDoor(className) {
  const utils = render(
    <ProUpsellDoor label="Viewline" heading="H" body="B" testIdPrefix="d" className={className} />,
  );
  fireEvent.click(utils.getByTestId('d-trigger'));
  return utils.getByTestId('d-popover');
}

describe('the upward Pro door variant', () => {
  it('anchors the popover above the trigger, not below it', () => {
    const pop = getComputedStyle(openDoor('wf-pro-upsell-door--up'));
    expect(pop.top).toBe('auto');
    expect(pop.bottom).toMatch(/100%/);
    expect(pop.position).toBe('absolute');
  });

  it('sizes the popover from the viewport, since the door is shrink-to-fit', () => {
    expect(CSS).toMatch(/\.wf-pro-upsell-door--up \.wf-pro-upsell \{[^}]*width: min\(360px, calc\(100vw - 32px\)\);/);
    expect(getComputedStyle(openDoor('wf-pro-upsell-door--up')).zIndex).toBe('19');
  });

  it('leaves the default door opening downward', () => {
    const pop = getComputedStyle(openDoor(''));
    expect(pop.bottom).not.toMatch(/100%/);
    expect(pop.top).toMatch(/100%/);
  });
});
