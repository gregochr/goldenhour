import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import GlanceLabel from '../components/GlanceLabel.jsx';
import { GLANCE_LABEL, GLANCE_SHORT_LABEL } from '../utils/tabLabels.js';

describe('GlanceLabel — the At a Glance tab name as a CSS-switched pair', () => {
  it('renders both words, the long one from 640px and the short one below it', () => {
    render(<GlanceLabel />);
    const long = screen.getByTestId('glance-label-long');
    const short = screen.getByTestId('glance-label-short');
    expect(long).toHaveTextContent(GLANCE_LABEL);
    expect(long).toHaveClass('hidden', 'sm:inline');
    expect(short).toHaveTextContent(GLANCE_SHORT_LABEL);
    expect(short).toHaveClass('sm:hidden');
  });

  it('says the words the owner chose', () => {
    expect(GLANCE_LABEL).toBe('At a Glance');
    expect(GLANCE_SHORT_LABEL).toBe('Glance');
  });
});

describe('the phone tab bar rules', () => {
  const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8');

  it('sit inside the max-width: 639px media query, and nowhere outside it', () => {
    const rule = '.wf-tab { flex: 1 1 auto; min-height: 36px; position: relative; }';
    expect(css.split(rule)).toHaveLength(2);
    const at = css.indexOf(rule);
    const open = css.lastIndexOf('@media (max-width: 639px) {', at);
    expect(open).toBeGreaterThan(-1);
    // The block that opened at `open` is still open at the rule: no unmatched `}` between.
    let depth = 0;
    for (const ch of css.slice(open, at)) {
      if (ch === '{') depth += 1;
      if (ch === '}') depth -= 1;
    }
    expect(depth).toBe(1);
  });

  it('keep the bar scrollable for the admin four-tab case', () => {
    expect(/\.wf-tabs \{[^}]*overflow-x: auto/.test(css)).toBe(true);
  });
});
