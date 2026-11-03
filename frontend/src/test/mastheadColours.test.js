import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

/**
 * The masthead's colour literals, pinned against the stylesheet they duplicate.
 *
 * <p>Three of `RULE_COLOURS`' entries are byte-copies of live design tokens, and the component's own
 * doc explains why they stay literals: both tokens sit in index.css's PRUNABLE `@theme` block, and a
 * `linear-gradient` containing one unresolved colour is invalid in its entirety — so
 * `var(--color-plex-coral)` inside the gradient would trade a cosmetic drift for a vanished rule.
 *
 * <p>What that leaves is the drift, which is what this file is for. It reads the stylesheet as text
 * rather than through the cascade because jsdom does not resolve `var()`, and because the failure
 * being caught is a source edit: someone retunes `--color-plex-coral` — a knob the token's own
 * comment already frets about — and the kicker moves while the sunrise and sunset stops two lines
 * beneath it do not. One band, two corals, and nothing else in the suite notices.
 *
 * <p>The unset-postcode nudge is no longer coral (map-mobile-compact README §6): it is the red group
 * `#EE8064` / hover `#F49A84`, literals in `index.css` that the tick line must not shadow with a
 * Tailwind colour of its own. They are pinned at the bottom of this file.
 */

const read = (rel) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8');

const cssToken = (css, name) => {
  const match = new RegExp(`--${name}:\\s*(#[0-9A-Fa-f]{3,8})\\s*;`).exec(css);
  if (!match) throw new Error(`--${name} is not declared as a hex literal in index.css`);
  return match[1].toUpperCase();
};

const jsLiteral = (src, key) => {
  const match = new RegExp(`${key}:\\s*'(#[0-9A-Fa-f]{3,8})'`).exec(src);
  if (!match) throw new Error(`${key} is not a hex literal in MastheadLight.jsx`);
  return match[1].toUpperCase();
};

describe('masthead rule colours vs the tokens they copy', () => {
  const css = read('../index.css');
  const component = read('../components/shared/MastheadLight.jsx');
  // M3 split the band in two. The rule's stop table stayed with the light; the amber's Tailwind
  // hover class went with the nudge it styles, because Tailwind's scanner reads the source file
  // that USES the class. So the pair below is now a CROSS-FILE equality, which is the one the
  // drift could actually happen across.
  const tickLine = read('../components/MastheadTickLine.jsx');

  it.each([
    ['SUNRISE', 'color-plex-coral'],
    ['SUNSET', 'color-plex-coral'],
    ['SOLAR_NOON', 'color-plex-text'],
  ])('keeps the %s stop equal to --%s', (stop, token) => {
    expect(jsLiteral(component, stop)).toBe(cssToken(css, token));
  });

  it('has the tick line IMPORT the amber rather than re-declare it', () => {
    // The nudge's amber hover class is gone (the nudge hovers to a lighter red now), so the old
    // cross-file equality has nothing to compare. What remains is the import: the two golden clock
    // times paint with `GOLDEN`, and a second literal in the tick line could drift from the rule's.
    expect(tickLine).toContain("import { GOLDEN } from './shared/MastheadLight.jsx';");
    expect(tickLine).not.toMatch(/^const GOLDEN = /m);
    expect(tickLine).not.toContain('GOLDEN_HOVER');
  });

  it('pins the unset nudge to the red group, in the stylesheet and nowhere else', () => {
    // Spec §6: text and pin #EE8064 (the SUNSET tag's ink), hover #F49A84, border and fill on the
    // GROUP. The tick line must not carry a Tailwind colour that would beat the group's rule, and the
    // old coral-only `.wf-tick-nudge` colour must stay deleted.
    expect(css).toMatch(/\.wf-tick-group\[data-unset='true'\] \.wf-tick-nudge \{ color: #EE8064; \}/);
    expect(css).toMatch(
      /\.wf-tick-group\[data-unset='true'\]:hover \.wf-tick-nudge \{ color: #F49A84; \}/,
    );
    expect(css).toMatch(/\.wf-tick-group\[data-unset='true'\] \{[^}]*border-color: rgba\(200, 69, 47, 0\.75\)/);
    expect(css).toMatch(/\.wf-tick-group\[data-unset='true'\] \{[^}]*background: rgba\(200, 69, 47, 0\.12\)/);
    expect(css).not.toMatch(/\.wf-tick-nudge \{ color: var\(--color-plex-coral\)/);
    expect(tickLine).not.toMatch(/wf-tick-nudge[^'"`]*(?:hover:)?text-\[/);
    expect(tickLine).toContain("data-unset={noHome ? 'true' : undefined}");
  });

  it('reads a token that really is declared, so the parse cannot silently pass', () => {
    // If the regexes stopped matching, every assertion above would throw rather than pass — but a
    // future index.css that declares the token via another token would parse to something that is
    // not a hex and this would catch it before the comparisons do.
    expect(cssToken(css, 'color-plex-coral')).toMatch(/^#[0-9A-F]{6}$/);
  });
});
