// Pure folding logic for scripts/a11y-summary.mjs (kept apart so Vitest can test it without the filesystem).

/** Impacts in severity order, worst first. */
export const IMPACT_ORDER = ['critical', 'serious', 'moderate', 'minor'];

/** The impacts that fail a scan (and so the advisory job). */
export const BLOCKING_IMPACTS = ['critical', 'serious'];

/**
 * Folds the per-scan records into one row per (rule, impact), worst impact first and then the most nodes.
 * Deterministic: ties break on the rule id, so identical scans always print identical tables.
 *
 * @param {Array<{project: string, scan: string, violations: Array<{id: string, impact: string, nodes: number}>}>} records
 * @returns {Array<{rule: string, impact: string, scans: string[], nodes: number}>}
 */
export function fold(records) {
  const rows = new Map();
  for (const record of records) {
    for (const v of record.violations) {
      const key = `${v.id}\u0000${v.impact}`;
      const row = rows.get(key) ?? { rule: v.id, impact: v.impact, scans: new Set(), nodes: 0 };
      row.scans.add(`${record.project}/${record.scan}`);
      row.nodes += v.nodes;
      rows.set(key, row);
    }
  }
  const rank = (impact) => {
    const i = IMPACT_ORDER.indexOf(impact);
    return i === -1 ? IMPACT_ORDER.length : i;
  };
  return [...rows.values()]
    .map((row) => ({ ...row, scans: [...row.scans].sort() }))
    .sort((a, b) => rank(a.impact) - rank(b.impact) || b.nodes - a.nodes || a.rule.localeCompare(b.rule));
}

/**
 * Renders the markdown summary: a headline, then the table (or a clean-bill line).
 *
 * @param {Array<object>} records the per-scan records
 * @returns {string}
 */
export function renderSummary(records) {
  const rows = fold(records);
  const blocking = rows.filter((r) => BLOCKING_IMPACTS.includes(r.impact));
  const incomplete = new Map();
  for (const record of records) {
    for (const i of record.incomplete ?? []) incomplete.set(i.id, (incomplete.get(i.id) ?? 0) + i.nodes);
  }
  const lines = [
    '## Accessibility scan (advisory)',
    '',
    `${records.length} scans, ${rows.length} distinct rule/impact pairs, ${blocking.length} serious or critical.`,
    '',
  ];
  if (records.length === 0) {
    lines.push('No scan records were found: the suite did not reach its first scan.', '');
    return lines.join('\n');
  }
  if (rows.length === 0) {
    lines.push('No violations.', '');
  } else {
    lines.push('| Rule | Impact | Scans affected | Nodes |', '|---|---|---|---|');
    for (const r of rows) {
      lines.push(`| \`${r.rule}\` | ${r.impact} | ${r.scans.length}: ${r.scans.join(', ')} | ${r.nodes} |`);
    }
    lines.push('');
  }
  const undecided = [...incomplete.entries()].sort(([a], [b]) => a.localeCompare(b));
  if (undecided.length > 0) {
    lines.push(
      `Needs review (axe could not decide; not failures; contrast over map imagery is not covered, the tiles are stubbed): ${
        undecided.map(([id, n]) => `${id} ${n}`).join(', ')}`,
      '',
    );
  }
  return lines.join('\n');
}
