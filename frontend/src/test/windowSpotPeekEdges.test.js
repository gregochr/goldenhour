import { describe, it, expect, afterEach } from 'vitest';
import { spotPeekPlacement } from '../utils/windowSpotPeek.js';

function viewport(width, height) {
  Object.defineProperty(window, 'innerWidth', { configurable: true, writable: true, value: width });
  Object.defineProperty(window, 'innerHeight', { configurable: true, writable: true, value: height });
}

function box({ left, top, width, height }) {
  return { left, top, width, height, right: left + width, bottom: top + height };
}

const STRIP = box({ left: 20, top: 100, width: 1000, height: 300 });

describe('windowSpotPeek placement edges', () => {
  afterEach(() => {
    delete window.innerWidth;
    delete window.innerHeight;
  });

  it('flips above when below holds 219px and above holds more', () => {
    viewport(1280, 800);
    // roomBelow = 800 - 8 - (bottom + 10) = 219 -> bottom = 563
    const card = box({ left: 400, top: 485, width: 275, height: 78 });
    expect(card.bottom).toBe(563);
    const { placement, position } = spotPeekPlacement(card, STRIP);
    expect(placement).toBe('above');
    expect(position.bottom).toBe(800 - 485 + 10);
  });

  it('stays below when below holds exactly 220px even though above holds more', () => {
    viewport(1280, 800);
    // roomBelow = 220 -> bottom = 562
    const card = box({ left: 400, top: 484, width: 275, height: 78 });
    expect(card.bottom).toBe(562);
    const { placement, position } = spotPeekPlacement(card, STRIP);
    expect(placement).toBe('below');
    expect(position.top).toBe(572);
  });

  it('stays below when below has plenty of room even if above has more', () => {
    viewport(1280, 1000);
    // roomBelow = 1000 - 8 - 588 = 404; roomAbove = 500 - 18 = 482
    const card = box({ left: 400, top: 500, width: 275, height: 78 });
    const { placement } = spotPeekPlacement(card, STRIP);
    expect(placement).toBe('below');
  });

  it('stays below when above holds exactly as much as below', () => {
    viewport(1280, 300);
    // roomBelow = 300 - 8 - (bottom + 10); with bottom = 172 -> 110. roomAbove = top - 18 = 110 -> top = 128
    const card = box({ left: 400, top: 128, width: 275, height: 44 });
    expect(card.bottom).toBe(172);
    const { placement } = spotPeekPlacement(card, STRIP);
    expect(placement).toBe('below');
  });

  it('flips above when above holds one pixel more than a tight below', () => {
    viewport(1280, 300);
    // roomBelow = 109 (bottom 173), roomAbove = 110 (top 128)
    const card = box({ left: 400, top: 128, width: 275, height: 45 });
    expect(card.bottom).toBe(173);
    const { placement } = spotPeekPlacement(card, STRIP);
    expect(placement).toBe('above');
  });

  it('holds the arrow clear of the right corner when the card is scrolled half out to the right', () => {
    viewport(1280, 800);
    const card = box({ left: 1250, top: 300, width: 275, height: 78 });
    const { arrowLeft, position } = spotPeekPlacement(card, STRIP);
    // strip right 1020 - 10 - 280 = 730 is the max left
    expect(position.left).toBe(730);
    // tip = min(centre - left, 280 - 16) = 264; arrow offset by half of 11
    expect(arrowLeft).toBe(264 - 5.5);
  });
});
