import React from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { renderToString } from 'react-dom/server';
import useVisualViewportHeight from '../hooks/useVisualViewportHeight.js';

function Probe() {
  const { height, bottomInset } = useVisualViewportHeight(true);
  return <output>{`${height}|${bottomInset}`}</output>;
}

describe('useVisualViewportHeight — server render', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('reports a zero layout viewport and no inset when there is no window at all', () => {
    vi.stubGlobal('window', undefined);

    const html = renderToString(<Probe />);

    expect(html).toContain('0|0');
  });
});
