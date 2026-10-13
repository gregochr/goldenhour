import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

/**
 * `index.css` as rules, for the UNRATED stylesheet pins (unrated-verdict-plan.md U2, review F7).
 *
 * <p>jsdom applies no stylesheet, so a component test that asserts only `data-tier="UNRATED"` proves
 * the attribute and nothing about the colour the browser will draw. These helpers read the sheet
 * itself, comments stripped (so prose that names a selector can never satisfy or fail a check), and
 * answer the two questions that matter: which rules can match an UNRATED element, and whether any of
 * them sets a verdict hue.
 */
const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');

/** Every flat `selector { body }` rule in the sheet, nested at-rule bodies included. */
const RULES = [...css.matchAll(/([^{}]+)\{([^{}]*)\}/g)].map(([, selector, body]) => ({
  selectors: selector.split(',').map((entry) => entry.trim()).filter(Boolean),
  body: body.trim(),
}));

/** The verdict hues: the lifted badge trio and the fill trio. Never legal on an UNRATED element. */
const HUE = /--color-(?:badge-(?:go|maybe|poor)|verdict-(?:go|marginal|standdown))\b/;

/**
 * The rules that can match an element carrying `UNRATED` as its verdict/tier attribute — a
 * selector entry that names the value.
 *
 * @returns {Array<{selector: string, body: string}>}
 */
export function unratedRules() {
  return RULES.flatMap(({ selectors, body }) => selectors
    .filter((entry) => /="UNRATED"|='UNRATED'/.test(entry))
    .map((selector) => ({ selector, body })));
}

/**
 * The UNRATED rules whose selector entry also mentions `fragment` (a class name).
 *
 * @param {string} fragment
 * @returns {Array<{selector: string, body: string}>}
 */
export function unratedRulesFor(fragment) {
  return unratedRules().filter(({ selector }) => selector.includes(fragment));
}

/**
 * Whether a declaration block names a verdict hue.
 *
 * @param {string} body
 * @returns {boolean}
 */
export function setsVerdictHue(body) {
  return HUE.test(body);
}
