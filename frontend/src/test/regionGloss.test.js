import { describe, it, expect } from 'vitest';
import { glossLead } from '../utils/regionGloss.js';

describe('glossLead', () => {
  it('appends a full stop to a headline with no terminal punctuation', () => {
    expect(glossLead('Clear skies limit colour potential')).toBe('Clear skies limit colour potential.');
  });

  it.each(['Great sky.', 'Great sky!', 'Great sky?', 'Great sky…'])('leaves %s as it is', (h) => {
    expect(glossLead(h)).toBe(h);
  });

  it('trims trailing whitespace before deciding', () => {
    expect(glossLead('Great sky.  ')).toBe('Great sky.');
    expect(glossLead('Great sky  ')).toBe('Great sky.');
  });

  it('returns null for null, empty or blank input', () => {
    expect(glossLead(null)).toBeNull();
    expect(glossLead(undefined)).toBeNull();
    expect(glossLead('')).toBeNull();
    expect(glossLead('   ')).toBeNull();
  });
});
