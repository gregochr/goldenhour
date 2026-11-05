import React from 'react';
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { render, renderHook, screen } from '@testing-library/react';
import { useChromeTlHeight, CHROME_TL_HEIGHT_VAR } from '../hooks/useChromeTlHeight.js';

function Frame() {
  const [ref] = useChromeTlHeight();
  return (
    <div data-testid="frame">
      <div data-testid="chrome" ref={ref}>group</div>
    </div>
  );
}

describe('useChromeTlHeight — environments without a ResizeObserver, and detached refs', () => {
  const OriginalRO = globalThis.ResizeObserver;
  let original;

  beforeEach(() => {
    globalThis.ResizeObserver = undefined;
    original = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetHeight');
    Object.defineProperty(HTMLElement.prototype, 'offsetHeight', {
      configurable: true,
      get() { return this.dataset?.testid === 'chrome' ? 52 : 0; },
    });
  });

  afterEach(() => {
    globalThis.ResizeObserver = OriginalRO;
    if (original) Object.defineProperty(HTMLElement.prototype, 'offsetHeight', original);
    else delete HTMLElement.prototype.offsetHeight;
  });

  it('still publishes the height once and removes it on unmount, with nothing to observe', () => {
    const { unmount } = render(<Frame />);

    expect(screen.getByTestId('frame').style.getPropertyValue(CHROME_TL_HEIGHT_VAR)).toBe('52px');

    const frame = screen.getByTestId('frame');
    unmount();
    expect(frame.style.getPropertyValue(CHROME_TL_HEIGHT_VAR)).toBe('');
  });

  it('does nothing for a null node or a node with no parent', () => {
    const { result } = renderHook(() => useChromeTlHeight());
    const [ref] = result.current;

    expect(ref(null)).toBeUndefined();
    expect(ref(document.createElement('div'))).toBeUndefined();
  });
});
