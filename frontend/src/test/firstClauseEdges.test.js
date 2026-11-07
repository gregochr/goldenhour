import { describe, it, expect, vi } from 'vitest';

// BOUNDARY and TRAILING_JUNK are module constants, so each test loads a fresh module after Stryker activates a mutant.
async function load() {
  vi.resetModules();
  return (await import('../utils/firstClause.js')).firstClause;
}

describe('firstClause boundaries', () => {
  it('cuts at the first comma that is at least 28 characters in', async () => {
    const firstClause = await load();
    const text = 'Broken cloud over the western hills, with clearing later';
    expect(firstClause(text)).toBe('Broken cloud over the western hills.');
  });

  it('cuts at a boundary exactly at the 28th character', async () => {
    const firstClause = await load();
    const head = 'a'.repeat(28);
    expect(firstClause(`${head}, and then more words`)).toBe(`${head}.`);
  });

  it('skips a boundary before the 28th character and takes the next', async () => {
    const firstClause = await load();
    expect(firstClause('Clear, but cloud builds over the hills; later')).toBe('Clear, but cloud builds over the hills.');
  });

  it('cuts at semicolons and colons', async () => {
    const firstClause = await load();
    expect(firstClause('Broken cloud over the western hills; clearing later')).toBe('Broken cloud over the western hills.');
    expect(firstClause('Broken cloud over the western hills: clearing later')).toBe('Broken cloud over the western hills.');
  });

  it('cuts at a spaced em dash, en dash or hyphen', async () => {
    const firstClause = await load();
    expect(firstClause('Broken cloud over the western hills — clearing later')).toBe('Broken cloud over the western hills.');
    expect(firstClause('Broken cloud over the western hills – clearing later')).toBe('Broken cloud over the western hills.');
    expect(firstClause('Broken cloud over the western hills - clearing later')).toBe('Broken cloud over the western hills.');
  });

  it('does not cut at a dash that is not surrounded by spaces', async () => {
    const firstClause = await load();
    expect(firstClause('Cloud builds over the high ground pre- and post-sunset later tonight'))
      .toBe('Cloud builds over the high ground pre- and post-sunset later tonight.');
    expect(firstClause('Cloud builds over the high ground pre -and post sunset later tonight'))
      .toBe('Cloud builds over the high ground pre -and post sunset later tonight.');
    expect(firstClause('Cloud builds over the high ground pre-sunset and high-cloud later'))
      .toBe('Cloud builds over the high ground pre-sunset and high-cloud later.');
  });

  it('does not cut at ordinary spaced words or characters', async () => {
    const firstClause = await load();
    const text = 'Cloud builds over the high ground and clears later tonight';
    expect(firstClause(text)).toBe(`${text}.`);
  });

  it('cuts at a sentence end followed by whitespace', async () => {
    const firstClause = await load();
    expect(firstClause('Cloud builds over the high ground. Then it clears')).toBe('Cloud builds over the high ground.');
    expect(firstClause('Cloud builds over the high ground! Then it clears')).toBe('Cloud builds over the high ground.');
    expect(firstClause('Cloud builds over the high ground? Then it clears')).toBe('Cloud builds over the high ground.');
  });

  it('turns a terminal exclamation or question mark into a full stop', async () => {
    const firstClause = await load();
    expect(firstClause('Cloud builds over the high ground tonight!')).toBe('Cloud builds over the high ground tonight.');
    expect(firstClause('Cloud builds over the high ground tonight?')).toBe('Cloud builds over the high ground tonight.');
  });

  it('keeps decimals whole', async () => {
    const firstClause = await load();
    expect(firstClause('Cloud builds over the high ground to 4.5 oktas then drops')).toBe(
      'Cloud builds over the high ground to 4.5 oktas then drops.',
    );
    expect(firstClause('Cloud builds over the high ground to 0.3 µg then drops')).toBe(
      'Cloud builds over the high ground to 0.3 µg then drops.',
    );
  });

  it('strips every piece of trailing junk left before the boundary', async () => {
    const firstClause = await load();
    expect(firstClause('Broken cloud over the western hills  ; clearing later')).toBe('Broken cloud over the western hills.');
    expect(firstClause('Broken cloud over the western hills  — clearing later')).toBe('Broken cloud over the western hills.');
    expect(firstClause('Broken cloud over the western hills -  - clearing later')).toBe('Broken cloud over the western hills.');
  });

  it('keeps inner spaces and words of the clause', async () => {
    const firstClause = await load();
    expect(firstClause('Broken cloud over the western hills, x')).toBe('Broken cloud over the western hills.');
  });

  it('returns null for nothing and trims whitespace', async () => {
    const firstClause = await load();
    expect(firstClause(null)).toBeNull();
    expect(firstClause('   ')).toBeNull();
    expect(firstClause('  Short.  ')).toBe('Short.');
    expect(firstClause('Short')).toBe('Short.');
  });

  it('gives the same answer on repeated calls', async () => {
    const firstClause = await load();
    const text = 'Broken cloud over the western hills, with clearing later';
    expect(firstClause(text)).toBe(firstClause(text));
  });
});
