/**
 * `components/map/EclipseSpotLine.jsx` — the per-location eclipse line (L7,
 * `docs/engineering/lunar-eclipse-plan.md` §3 L7), the ONE shared component `LocationFourDaySheet`
 * and `MapCallout` both mount as a sibling block right after `TideFitBlock`.
 *
 * Pure-render coverage: the unmeasured-facts discipline (no sight → nothing rendered), the exact
 * text for each of the three tail shapes, and the leading glyph. `eclipseSpotLine`'s own boundary
 * cases (null altitude/bearing, the fallback when a flag has no instant, the negative-altitude
 * print) are covered in `dawnRace.test.js` — this file only proves the component renders what that
 * function returns and nothing when it returns null.
 */
import React from 'react';
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import EclipseSpotLine from '../components/map/EclipseSpotLine.jsx';

const SETS_IN_SHADOW = {
  moonAltAtMax: 7,
  moonAzCardinal: 'WSW',
  moonset: '2026-08-28T06:16:00',
  moonrise: null,
  setsInShadow: true,
  risesInShadow: false,
};

const RISES_IN_SHADOW = {
  moonAltAtMax: 5,
  moonAzCardinal: 'ENE',
  moonset: null,
  moonrise: '2028-12-31T15:53:00',
  setsInShadow: false,
  risesInShadow: true,
};

const ABOVE_THROUGHOUT = {
  moonAltAtMax: 22,
  moonAzCardinal: 'S',
  moonset: null,
  moonrise: null,
  setsInShadow: false,
  risesInShadow: false,
};

describe('EclipseSpotLine — the unmeasured-facts discipline', () => {
  it('renders nothing when sight is null — never a "no eclipse" line', () => {
    const { container } = render(<EclipseSpotLine sight={null} />);
    expect(container.textContent).toBe('');
  });

  it('renders nothing when the sight carries no altitude or bearing', () => {
    const { container } = render(
      <EclipseSpotLine sight={{ ...SETS_IN_SHADOW, moonAltAtMax: null }} />,
    );
    expect(container.textContent).toBe('');
  });
});

describe('EclipseSpotLine — the three tail shapes', () => {
  it('states "sets … in shadow" and carries the eclipse glyph, hidden from assistive tech', () => {
    render(<EclipseSpotLine sight={SETS_IN_SHADOW} />);
    const line = screen.getByTestId('eclipse-spot-line');
    expect(line).toHaveTextContent('◑ moon 7° up WSW at max · sets 06:16 in shadow');
    // The glyph is decorative — `WindowTopicRows`' own aside precedent for this same ◑ mark.
    // A screen reader must announce the fact, never the character.
    const glyph = line.querySelector('span[aria-hidden="true"]');
    expect(glyph).toHaveAttribute('aria-hidden', 'true');
    expect(glyph).toHaveTextContent('◑');
  });

  it('states "rises … in shadow"', () => {
    render(<EclipseSpotLine sight={RISES_IN_SHADOW} />);
    expect(screen.getByTestId('eclipse-spot-line'))
      .toHaveTextContent('◑ moon 5° up ENE at max · rises 15:53 in shadow');
  });

  it('states "above the horizon throughout" with no horizon-clearance claim', () => {
    render(<EclipseSpotLine sight={ABOVE_THROUGHOUT} />);
    expect(screen.getByTestId('eclipse-spot-line'))
      .toHaveTextContent('◑ moon 22° up S at max · above the horizon throughout');
  });
});
