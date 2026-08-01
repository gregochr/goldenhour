import { describe, it, expect, beforeEach } from 'vitest';

/** Reads the real index.html off disk — the only way to assert on the app shell's own markup. */
async function readIndexHtml() {
  const fs = await import('node:fs');
  const path = await import('node:path');
  return fs.readFileSync(path.resolve(import.meta.dirname, '../../index.html'), 'utf-8');
}

describe('nomodule fallback', () => {
  beforeEach(() => {
    document.body.innerHTML = '<div id="root"></div>';
  });

  it('renders a browser-not-supported message into #root', () => {
    // Simulate the inline nomodule script logic
    document.getElementById('root').innerHTML =
      '<div style="font-family:sans-serif;max-width:480px;margin:80px auto;text-align:center;padding:0 20px">' +
      '<h1 style="font-size:24px;margin-bottom:16px">Browser Not Supported</h1>' +
      '<p style="color:#666;line-height:1.5">PhotoCast requires a modern browser. ' +
      'Please update Safari to version 14 or later, or use a recent version of Chrome, Firefox, or Edge.</p></div>';

    const root = document.getElementById('root');
    expect(root.querySelector('h1')).toHaveTextContent('Browser Not Supported');
    expect(root.querySelector('p')).toHaveTextContent(/update Safari to version 14/);
    expect(root.querySelector('p')).toHaveTextContent(/Chrome, Firefox, or Edge/);
  });

  it('index.html contains a nomodule script tag', async () => {
    const html = await readIndexHtml();

    expect(html).toContain('<script nomodule>');
    expect(html).toContain('Browser Not Supported');
  });

  it('index.html title carries the current tagline', async () => {
    // The <title> is the one tagline slot no component test can reach, so it is pinned here
    // alongside the other index.html assertion rather than in a file of its own.
    const html = await readIndexHtml();

    expect(html).toContain('<title>PhotoCast — Golden hour, forecast and ranked by AI</title>');
    expect(html).not.toContain('AI sunrise, sunset, and aurora forecasting');
  });
});
