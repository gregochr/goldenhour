import React from 'react';
import { describe, it, expect, afterEach } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import DawnRace from '../components/DawnRace.jsx';

/**
 * `DawnRace` — the dawn/dusk race component (`lunar-eclipse-plan.md` §2.7, §3 L4).
 *
 * <p>The geometry itself is `dawnRace.test.js`'s job (`raceModel`/`raceSentence`, pure functions).
 * This file owns the render: the track is `aria-hidden`, the caption names the spot, the phone
 * attributes land on the right label roles, and the component renders nothing when there is
 * nothing to draw.
 */

afterEach(cleanup);

const DAWN_SIGHT = {
  race: 'DAWN',
  moonAltAtMax: 7,
  moonAzCardinal: 'WSW',
  maximum: '2026-08-28T05:13:00',
  umbraStart: '2026-08-28T03:34:00',
  umbraEnd: '2026-08-28T06:52:00',
  moonset: '2026-08-28T06:16:00',
  moonrise: null,
  setsInShadow: true,
  risesInShadow: false,
  stops: [
    { key: 'NAUTICAL_DAWN', time: '2026-08-28T03:09:00' },
    { key: 'CIVIL_DAWN', time: '2026-08-28T05:24:00' },
    { key: 'SUNRISE', time: '2026-08-28T06:11:00' },
    { key: 'GOLDEN_MORNING_END', time: '2026-08-28T06:41:00' },
  ],
};

/** The DUSK mirror — a moon rising already in shadow near dusk (2028-12-31-like). */
const DUSK_SIGHT = {
  race: 'DUSK',
  moonAltAtMax: 5,
  moonAzCardinal: 'ENE',
  maximum: '2028-12-31T17:42:00',
  umbraStart: '2028-12-31T15:07:00',
  umbraEnd: '2028-12-31T19:52:00',
  moonset: null,
  moonrise: '2028-12-31T15:53:00',
  setsInShadow: false,
  risesInShadow: true,
  stops: [
    { key: 'GOLDEN_EVENING_START', time: '2028-12-31T15:35:00' },
    { key: 'SUNSET', time: '2028-12-31T16:00:00' },
    { key: 'CIVIL_DUSK', time: '2028-12-31T16:38:00' },
    { key: 'NAUTICAL_DUSK', time: '2028-12-31T17:16:00' },
  ],
};

describe('DawnRace — mount conditions', () => {
  it('renders nothing with no sight', () => {
    const { container } = render(<DawnRace sight={null} spotName="Dunstanburgh Castle" />);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing when the sight carries no race', () => {
    const { container } = render(
      <DawnRace sight={{ ...DAWN_SIGHT, race: null }} spotName="Dunstanburgh Castle" />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it('renders the track when the sight carries a race', () => {
    render(<DawnRace sight={DAWN_SIGHT} spotName="Dunstanburgh Castle" />);
    expect(screen.getByTestId('dawn-race')).toBeInTheDocument();
  });
});

describe('DawnRace — the caption names the spot', () => {
  it('names the spot passed in', () => {
    render(<DawnRace sight={DAWN_SIGHT} spotName="Dunstanburgh Castle" />);
    expect(screen.getByText(/Dunstanburgh Castle/)).toBeInTheDocument();
  });

  it('still renders with no spot name', () => {
    render(<DawnRace sight={DAWN_SIGHT} spotName={null} />);
    expect(screen.getByTestId('dawn-race')).toBeInTheDocument();
  });
});

describe('DawnRace — the track is decoration', () => {
  it('hides the whole track from the accessibility tree', () => {
    render(<DawnRace sight={DAWN_SIGHT} spotName="Dunstanburgh Castle" />);
    expect(screen.getByTestId('dawn-race-track')).toHaveAttribute('aria-hidden', 'true');
  });

  it('draws the umbra band and the hatch, since this sight sets in shadow', () => {
    render(<DawnRace sight={DAWN_SIGHT} spotName="Dunstanburgh Castle" />);
    expect(screen.getByTestId('dawn-race-umbra')).toBeInTheDocument();
    expect(screen.getByTestId('dawn-race-hatch')).toBeInTheDocument();
  });

  it('draws no hatch when the sight does not set in shadow', () => {
    render(<DawnRace sight={{ ...DAWN_SIGHT, setsInShadow: false }} spotName="x" />);
    expect(screen.queryByTestId('dawn-race-hatch')).toBeNull();
  });

  it('draws exactly three visual marks — maximum, the race event and the horizon', () => {
    render(<DawnRace sight={DAWN_SIGHT} spotName="Dunstanburgh Castle" />);
    const marks = screen.getAllByTestId('dawn-race-marker');
    expect(marks.map((m) => m.getAttribute('data-role')).sort()).toEqual(
      ['horizon', 'maximum', 'raceEvent'].sort(),
    );
  });
});

describe('DawnRace — the accessible answer', () => {
  it('renders the sentence, visibly, not screen-reader-only', () => {
    render(<DawnRace sight={DAWN_SIGHT} spotName="Dunstanburgh Castle" />);
    const sentence = screen.getByTestId('dawn-race-sentence');
    expect(sentence).toHaveTextContent(/In shadow from/);
    expect(sentence.className).not.toContain('sr-only');
  });
});

describe('DawnRace — phone labels', () => {
  it('marks the track-start and race-event labels for hiding on phone, and no others', () => {
    render(<DawnRace sight={DAWN_SIGHT} spotName="Dunstanburgh Castle" />);
    const labels = screen.getAllByTestId('dawn-race-label');
    const hidden = labels.filter((l) => l.getAttribute('data-hide-phone') === 'true');
    expect(hidden.map((l) => l.getAttribute('data-role')).sort()).toEqual(
      ['raceEvent', 'trackStart'].sort(),
    );
    const shown = labels.filter((l) => l.getAttribute('data-hide-phone') !== 'true');
    expect(shown.map((l) => l.getAttribute('data-role')).sort()).toEqual(
      ['horizon', 'maximum', 'umbraLabel'].sort(),
    );
  });

  it('marks every phase tick for the same treatment via its own class, not the label attribute', () => {
    render(<DawnRace sight={DAWN_SIGHT} spotName="Dunstanburgh Castle" />);
    // Ticks are hidden by CSS (`.wf-race-tick { display: none }` under the phone media query)
    // rather than a data attribute — three of them, one per served stop but the race event.
    expect(screen.getAllByTestId('dawn-race-tick')).toHaveLength(3);
  });
});

describe('DawnRace — legend', () => {
  it('names both bands', () => {
    render(<DawnRace sight={DAWN_SIGHT} spotName="Dunstanburgh Castle" />);
    expect(screen.getByText('in the umbra')).toBeInTheDocument();
    expect(screen.getByText('below the horizon')).toBeInTheDocument();
  });
});

describe('DawnRace — literal clock labels (the BST double-conversion regression)', () => {
  // ⚠️ Adversarial review (two independent lenses) found an earlier draft ran every EclipseSight
  // instant through the UK-zone `solarEventTime` formatter, double-applying the BST offset — every
  // label printed an hour late. `dawnRace.js`'s own class doc has the full account. This asserts
  // the RENDERED label text against the fixture's own literal digits, not against the formatter
  // under test, so the regression cannot hide behind a self-fulfilling assertion again.
  it('prints the maximum label at the fixture’s own digits, not shifted by a UK-zone conversion', () => {
    render(<DawnRace sight={DAWN_SIGHT} spotName="Dunstanburgh Castle" />);
    const maxLabel = screen.getAllByTestId('dawn-race-label')
      .find((l) => l.getAttribute('data-role') === 'maximum');
    expect(maxLabel).toHaveTextContent('05:13');
    expect(maxLabel).not.toHaveTextContent('06:13');
  });

  it('prints the sentence at the fixture’s own digits too', () => {
    render(<DawnRace sight={DAWN_SIGHT} spotName="Dunstanburgh Castle" />);
    expect(screen.getByTestId('dawn-race-sentence')).toHaveTextContent('maximum 05:13');
  });
});

describe('DawnRace — the DUSK mirror, rendered (not only pure geometry)', () => {
  it('renders the track for a DUSK sight', () => {
    render(<DawnRace sight={DUSK_SIGHT} spotName="Some coastal spot" />);
    expect(screen.getByTestId('dawn-race')).toBeInTheDocument();
    expect(screen.getByTestId('dawn-race-track')).toHaveAttribute('aria-hidden', 'true');
  });

  it('draws the umbra band and the hatch, since this sight rises in shadow', () => {
    render(<DawnRace sight={DUSK_SIGHT} spotName="Some coastal spot" />);
    expect(screen.getByTestId('dawn-race-umbra')).toBeInTheDocument();
    expect(screen.getByTestId('dawn-race-hatch')).toBeInTheDocument();
  });

  it('draws no hatch when the sight does not rise in shadow', () => {
    render(<DawnRace sight={{ ...DUSK_SIGHT, risesInShadow: false }} spotName="x" />);
    expect(screen.queryByTestId('dawn-race-hatch')).toBeNull();
  });

  it('labels the umbra boundary "leaves shadow" — the DUSK mirror of DAWN’s "enters shadow"', () => {
    render(<DawnRace sight={DUSK_SIGHT} spotName="Some coastal spot" />);
    const labels = screen.getAllByTestId('dawn-race-label');
    const umbraLabel = labels.find((l) => l.getAttribute('data-role') === 'umbraLabel');
    expect(umbraLabel).toHaveTextContent('leaves shadow');
  });

  it('marks the track-start and race-event (sunset) labels for phone hiding, and no others', () => {
    render(<DawnRace sight={DUSK_SIGHT} spotName="Some coastal spot" />);
    const labels = screen.getAllByTestId('dawn-race-label');
    const hidden = labels.filter((l) => l.getAttribute('data-hide-phone') === 'true');
    expect(hidden.map((l) => l.getAttribute('data-role')).sort()).toEqual(
      ['raceEvent', 'trackStart'].sort(),
    );
  });

  it('renders the DUSK accessible sentence, opening with "Rises" since it rises already in shadow', () => {
    render(<DawnRace sight={DUSK_SIGHT} spotName="Some coastal spot" />);
    expect(screen.getByTestId('dawn-race-sentence')).toHaveTextContent(/^Rises/);
  });
});
