import { describe, it, expect } from 'vitest';
import { fold, renderSummary } from '../../scripts/a11y-summary-lib.mjs';

const rec = (project, scan, violations) => ({ project, scan, violations });

describe('a11y summary folding', () => {
  it('sums nodes and lists the scans per rule and impact', () => {
    const rows = fold([
      rec('a11y-desktop', 'map', [{ id: 'color-contrast', impact: 'serious', nodes: 3 }]),
      rec('a11y-phone', 'map', [{ id: 'color-contrast', impact: 'serious', nodes: 2 }]),
      rec('a11y-desktop', 'plan', [{ id: 'color-contrast', impact: 'serious', nodes: 4 }]),
    ]);
    expect(rows).toEqual([{
      rule: 'color-contrast',
      impact: 'serious',
      scans: ['a11y-desktop/map', 'a11y-desktop/plan', 'a11y-phone/map'],
      nodes: 9,
    }]);
  });

  it('keeps one rule with two impacts as two rows', () => {
    const rows = fold([
      rec('p', 'a', [{ id: 'r', impact: 'minor', nodes: 1 }]),
      rec('p', 'b', [{ id: 'r', impact: 'serious', nodes: 1 }]),
    ]);
    expect(rows.map((r) => r.impact)).toEqual(['serious', 'minor']);
  });

  it('orders worst impact first, then most nodes, then rule id, with unknown impacts last', () => {
    const rows = fold([
      rec('p', 's', [
        { id: 'b-rule', impact: 'moderate', nodes: 5 },
        { id: 'a-rule', impact: 'moderate', nodes: 5 },
        { id: 'big', impact: 'moderate', nodes: 9 },
        { id: 'crit', impact: 'critical', nodes: 1 },
        { id: 'odd', impact: null, nodes: 50 },
        { id: 'min', impact: 'minor', nodes: 7 },
      ]),
    ]);
    expect(rows.map((r) => r.rule)).toEqual(['crit', 'big', 'a-rule', 'b-rule', 'min', 'odd']);
  });

  it('renders a table with the serious and critical count in the headline', () => {
    const md = renderSummary([
      rec('a11y-desktop', 'map', [
        { id: 'color-contrast', impact: 'serious', nodes: 3 },
        { id: 'region', impact: 'moderate', nodes: 1 },
      ]),
    ]);
    expect(md).toContain('1 scans, 2 distinct rule/impact pairs, 1 serious or critical.');
    expect(md).toContain('| `color-contrast` | serious | 1: a11y-desktop/map | 3 |');
    expect(md).toContain('| `region` | moderate | 1: a11y-desktop/map | 1 |');
  });

  it('says so when there are no violations, and when there are no records at all', () => {
    expect(renderSummary([rec('p', 's', [])])).toContain('No violations.');
    expect(renderSummary([])).toContain('No scan records were found');
  });
});

describe('a11y summary incomplete results', () => {
  it('reports axe incomplete nodes as a separate count, even with no violations', () => {
    const md = renderSummary([
      { project: 'p', scan: 'a', violations: [], incomplete: [{ id: 'color-contrast', nodes: 4 }] },
      { project: 'p', scan: 'b', violations: [], incomplete: [{ id: 'color-contrast', nodes: 2 }] },
      { project: 'p', scan: 'c', violations: [] },
    ]);
    expect(md).toContain('No violations.');
    expect(md).toContain('color-contrast 6');
  });
});
