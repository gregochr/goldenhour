import React from 'react';
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import WindowAwayRow from '../components/WindowAwayRow.jsx';

const renderRow = (props = {}) => render(
  <WindowAwayRow label="Wed 5 – Thu 6" windowCount={3} {...props} />,
);

describe('WindowAwayRow', () => {
  it('names the days, says how many windows went with them, and says nothing was forecast', () => {
    renderRow();

    expect(screen.getByTestId('window-away-label')).toHaveTextContent('Wed 5 – Thu 6');
    expect(screen.getByTestId('window-away-row'))
      .toHaveTextContent('Wed 5 – Thu 6 · away — 3 windows not forecast');
  });

  it('says "not forecast", never "not generated"', () => {
    // The mock's word is wrong about this pipeline: generation is exactly what DID happen on a
    // travel day — the slots exist and only the evaluation was skipped. "Not forecast" is the
    // rail tile's own word for the same state, so the two surfaces agree.
    renderRow();
    expect(screen.getByTestId('window-away-row').textContent).not.toMatch(/not generated/i);
    expect(screen.getByTestId('window-away-row')).toHaveTextContent('not forecast');
  });

  it('says "1 window" for a single one', () => {
    renderRow({ label: 'Wed 5', windowCount: 1 });
    expect(screen.getByTestId('window-away-row')).toHaveTextContent('1 window not forecast');
  });

  it('points at the sun times that are still on screen', () => {
    // Not filler. P4c settled that an away WINDOW keeps its sunrise or sunset, because those are
    // almanac and true whether or not a forecast ran — so this row has something to offer a reader
    // who was going to shoot anyway. Without it the row is purely a subtraction.
    //
    // ⚠️ The line used to name "the rail", and the day rail was retired at P2 of the heat-field
    // plan (D1). Live copy pointing at a component that no longer exists sends the reader looking
    // for something that is not there — the guarantee moved to the heat strip's away thumbnail
    // (`windowFirstStrip.js`), so only the noun changed.
    renderRow();
    expect(screen.getByTestId('window-away-almanac'))
      .toHaveTextContent('Sun times still show above');
    expect(screen.getByTestId('window-away-almanac').textContent).not.toMatch(/rail/i);
  });

  it('shows the operator\'s own note when there is one', () => {
    renderRow({ note: 'Business trip' });
    expect(screen.getByTestId('window-away-note')).toHaveTextContent('Business trip');
  });

  it('renders no note element at all when there is none to attribute', () => {
    // Its own span rather than a clause, so the sentence beside it stays complete and grammatical
    // in the normal case — which is a travel range saved with no note.
    renderRow({ note: null });
    expect(screen.queryByTestId('window-away-note')).toBeNull();
    expect(screen.getByTestId('window-away-row'))
      .toHaveTextContent('Wed 5 – Thu 6 · away — 3 windows not forecast');
  });

  it('offers no action, because the surface one would need is ADMIN-only and unrouted', () => {
    // The mock's "Mark yourself back →" needs /api/admin/travel-days, which most readers cannot
    // call and which this arm's tab bar has no route to. §6 bans a control that cannot act.
    renderRow();
    expect(screen.queryByRole('button')).toBeNull();
    expect(screen.queryByRole('link')).toBeNull();
  });

  it('offers no "show them anyway", because there is nothing withheld to show', () => {
    // The mock's multi-user variant claims the windows were generated and merely muted. On this
    // pipeline they were never evaluated, so the action would reveal an empty set.
    renderRow();
    expect(screen.getByTestId('window-away-row').textContent).not.toMatch(/show them anyway/i);
  });
});
