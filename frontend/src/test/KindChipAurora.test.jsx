/**
 * The window control's kind chip: AURORA wears the aurora banner's green and follows the banner's
 * alert level on the night the alert describes; ASTRO stays the night violet (owner request 2026-10-31).
 */
import React from 'react';
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { KindChip } from '../components/map/WindowControl.jsx';
import { SEVERITY } from '../components/AuroraBanner.jsx';
import { auroraChipLevel, auroraSeverityTier } from '../utils/auroraChipLevel.js';

const D = '2026-10-31';
const D1 = '2026-11-01';

const night = (eventType, date = D) => ({
  id: `night:${date}:${eventType}`, kind: 'night', eventType, date,
  label: 'Tonight', dayLabel: 'Tonight', time: '22:00', bestRating: null, scored: false, badges: [],
});

describe('KindChip night kinds', () => {
  it('draws an AURORA row in the aurora class, not night', () => {
    render(<KindChip row={night('AURORA')} />);
    const chip = screen.getByText('Aurora');
    expect(chip).toHaveClass('wf-hc-sun', 'aurora');
    expect(chip).not.toHaveClass('night');
  });

  it('keeps an ASTRO row in the night class with no aurora level', () => {
    render(<KindChip row={night('ASTRO')} auroraAlertNight={D} auroraAlertLevel="STRONG" />);
    const chip = screen.getByText('Astro');
    expect(chip).toHaveClass('wf-hc-sun', 'night');
    expect(chip).not.toHaveClass('aurora');
    expect(chip).not.toHaveAttribute('data-aurora-level');
  });

  it.each(['STRONG', 'SEVERE', 'EXTREME'])('a %s alert makes its own night strong', (level) => {
    render(<KindChip row={night('AURORA', D)} auroraAlertNight={D} auroraAlertLevel={level} />);
    expect(screen.getByText('Aurora')).toHaveAttribute('data-aurora-level', 'strong');
  });

  it('a STRONG alert for night D leaves the D+1 aurora chip moderate', () => {
    render(<KindChip row={night('AURORA', D1)} auroraAlertNight={D} auroraAlertLevel="STRONG" />);
    expect(screen.getByText('Aurora')).toHaveAttribute('data-aurora-level', 'moderate');
  });

  it.each([['MODERATE'], [null], [undefined], ['QUIET']])('level %s is moderate', (level) => {
    render(<KindChip row={night('AURORA')} auroraAlertNight={D} auroraAlertLevel={level} />);
    expect(screen.getByText('Aurora')).toHaveAttribute('data-aurora-level', 'moderate');
  });

  it('no alert at all is moderate', () => {
    render(<KindChip row={night('AURORA')} />);
    expect(screen.getByText('Aurora')).toHaveAttribute('data-aurora-level', 'moderate');
  });
});

describe('auroraChipLevel helper', () => {
  it('shares the banner rule: STRONG, SEVERE, EXTREME are strong, the rest moderate', () => {
    expect(auroraSeverityTier('STRONG')).toBe('strong');
    expect(auroraSeverityTier('SEVERE')).toBe('strong');
    expect(auroraSeverityTier('EXTREME')).toBe('strong');
    expect(auroraSeverityTier('MODERATE')).toBe('moderate');
    expect(auroraSeverityTier(null)).toBe('moderate');
  });

  it('is null off aurora rows', () => {
    expect(auroraChipLevel({ eventType: 'ASTRO', date: D }, D, 'STRONG')).toBeNull();
  });
});

describe('aurora chip tokens', () => {
  const css = readFileSync(resolve(__dirname, '../index.css'), 'utf8');
  const hex = (name) => css.match(new RegExp(`${name}:\\s*(#[0-9A-Fa-f]{6});`))?.[1]?.toLowerCase();

  it('pin the four token hexes to the banner SEVERITY rows so they cannot drift', () => {
    expect(hex('--color-plex-aurora')).toBe(SEVERITY.MODERATE.acc.toLowerCase());
    expect(hex('--color-plex-aurora-bright')).toBe(SEVERITY.MODERATE.ink.toLowerCase());
    expect(hex('--color-plex-aurora-strong')).toBe(SEVERITY.STRONG.acc.toLowerCase());
    expect(hex('--color-plex-aurora-strong-bright')).toBe(SEVERITY.STRONG.ink.toLowerCase());
  });

  it('style the moderate chip and the strong variant from them', () => {
    const base = css.match(/\.wf-hc-sun\.aurora \{[^}]*\}/)?.[0] ?? '';
    expect(base).toMatch(/var\(--color-plex-aurora-bright\)/);
    expect(base).toMatch(/var\(--color-plex-aurora\)/);
    const strong = css.match(/\.wf-hc-sun\.aurora\[data-aurora-level="strong"\] \{[^}]*\}/)?.[0] ?? '';
    expect(strong).toMatch(/var\(--color-plex-aurora-strong-bright\)/);
    expect(strong).toMatch(/var\(--color-plex-aurora-strong\)/);
  });
});
