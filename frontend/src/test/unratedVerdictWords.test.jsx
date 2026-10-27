import { describe, it, expect } from 'vitest';
import React from 'react';
import { render } from '@testing-library/react';
import {
  VERDICT_LABEL, VERDICT_LABEL_SHORT, SLOT_VERDICT_LABEL, shortVerdictLabel,
} from '../utils/windowFirstCards.js';
import { VERDICT_TINT } from '../components/WindowFirstHeatStrip.jsx';
import { VERDICT_TREATMENT } from '../components/WindowSheetDialog.jsx';
import { resolveRegionDisplay } from '../utils/tierUtils.js';
import VerdictPill from '../components/shared/VerdictPill.jsx';
import { unratedRules, setsVerdictHue } from './unratedStylesheet.js';

/**
 * The explicit-arm rule in one table (unrated-verdict-plan.md U2, adversarial-review fix F7).
 *
 * <p>Eight stylesheet comments, and the plan, say "every verdict word is handled in the JS that sets
 * this attribute". This is that test: for EVERY key of {@code VERDICT_LABEL} it asserts the entry in
 * each exported JS map or Set that keys on a verdict, and the one rendered surface that spells the
 * words itself ({@code VerdictPill}). A new verdict value added to the label table fails here until
 * every one of them has an arm — instead of reading as AWAITING by a lookup miss.
 */
const WORDS = Object.keys(VERDICT_LABEL);

describe('every verdict word has an explicit arm in every map that keys on one', () => {
  it('has the five words this table is written against', () => {
    expect(WORDS.sort()).toEqual(['AWAITING', 'MAYBE', 'STAND_DOWN', 'UNRATED', 'WORTH_IT']);
  });

  it.each(WORDS)('%s has an entry in VERDICT_LABEL_SHORT, SLOT_VERDICT_LABEL, VERDICT_TINT and VERDICT_TREATMENT', (word) => {
    expect(VERDICT_LABEL_SHORT, 'VERDICT_LABEL_SHORT').toHaveProperty(word);
    expect(SLOT_VERDICT_LABEL, 'SLOT_VERDICT_LABEL').toHaveProperty(word);
    expect(VERDICT_TINT, 'VERDICT_TINT').toHaveProperty(word);
    expect(VERDICT_TREATMENT, 'VERDICT_TREATMENT').toHaveProperty(word);
  });

  it.each(WORDS)('%s resolves to itself', (word) => {
    expect(resolveRegionDisplay({ displayVerdict: word })).toBe(word);
  });

  it.each(WORDS)('%s is spelled by VerdictPill exactly as the shared label table spells it', (word) => {
    const { container, unmount } = render(<VerdictPill displayVerdict={word} />);
    expect(container.textContent).toBe(VERDICT_LABEL[word]);
    unmount();
  });

  it('shortens only UNRATED, and never to a word that claims there are no ratings', () => {
    const differing = WORDS.filter((word) => VERDICT_LABEL_SHORT[word] !== VERDICT_LABEL[word]);
    expect(differing).toEqual(['UNRATED']);
    expect(VERDICT_LABEL_SHORT.UNRATED).toBe('Too few');
    expect(VERDICT_LABEL_SHORT.UNRATED).not.toMatch(/^unrated$/i);
    // The short form is a prefix of the full one, so it truncates rather than rewords.
    expect(VERDICT_LABEL.UNRATED.startsWith(VERDICT_LABEL_SHORT.UNRATED)).toBe(true);
  });

  it('shortVerdictLabel prefers the short form, and passes any other word through unchanged', () => {
    expect(shortVerdictLabel('UNRATED', 'Too few rated')).toBe('Too few');
    expect(shortVerdictLabel('WORTH_IT', 'Worth it')).toBe('Worth it');
    // An away window has no verdict; a descriptor with its own label keeps it.
    expect(shortVerdictLabel(null, 'Not forecast')).toBe('Not forecast');
    expect(shortVerdictLabel('WORTH_IT', 'A custom label')).toBe('A custom label');
  });
});

describe('the stylesheet gives UNRATED no verdict hue anywhere', () => {
  it('finds the UNRATED rules at all (a vacuous pass would prove nothing)', () => {
    expect(unratedRules().length).toBeGreaterThanOrEqual(6);
  });

  it('has no rule matching UNRATED that names a go / maybe / poor ink or fill', () => {
    const offenders = unratedRules().filter(({ body }) => setsVerdictHue(body)).map(({ selector }) => selector);
    expect(offenders).toEqual([]);
  });

  it('never groups UNRATED into a selector list with a hued verdict', () => {
    const grouped = unratedRules().filter(({ selector }) => /="(?:WORTH_IT|MAYBE|STAND_DOWN)"/.test(selector));
    expect(grouped).toEqual([]);
  });
});
