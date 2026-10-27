import React from 'react';
import { describe, it, expect } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import WindowSpotSheet from '../components/WindowSpotSheet.jsx';

/**
 * The drill-down sheet's list for a body shaped by the reader's sub-region picks (sub-regions-plan §11 S5b-1, item 7):
 * the reader's places first, the outside ones after and labelled, and the count over the reader's places only with
 * "· {m} outside" apart from it.
 *
 * <p><b>What breaks if these fail:</b> an outside place is counted in "N of M", wears a star or an unrated word, or
 * ranks above a place the reader holds; or a Pro, Admin or flag-off sheet prints an outside count.
 */

const spot = (key, name, rating, extra = {}) => ({
  key, locationId: Number(key), locationName: name, regionName: 'Coast', rating, driveMinutes: null, ...extra,
});

const MINE = spot('1', 'Bamburgh', 4);
const MINE_UNRATED = spot('2', 'Craster', null);
const OUT_A = spot('3', 'Alnmouth', null, { gated: true });
const OUT_B = spot('4', 'Amble', null, { gated: true });

const card = (allSpots) => ({
  key: '2026-08-08:SUNSET', kicker: 'Tonight', when: 'Sunset', time: '20:41', allSpots, spots: allSpots,
});

const renderSheet = (allSpots) => render(
  <WindowSpotSheet card={card(allSpots)} barTierId="any" barFloorId="any" typesByName={new Map()} onClose={() => {}} />,
);

describe('WindowSpotSheet for a shaped body', () => {
  it('lists the reader\'s places first and labels the outside ones, with no star or unrated word', () => {
    renderSheet([OUT_A, MINE_UNRATED, OUT_B, MINE]);

    const cards = within(screen.getByTestId('window-spot-sheet-list')).getAllByTestId('window-spot');
    expect(cards.map((c) => c.textContent.match(/Bamburgh|Craster|Alnmouth|Amble/)[0]))
      .toEqual(['Bamburgh', 'Craster', 'Alnmouth', 'Amble']);
    expect(within(cards[2]).getByTestId('window-spot-outside')).toHaveTextContent('outside your sub-regions');
    expect(cards[2].textContent).not.toMatch(/★|not scored|not rated/i);
  });

  it('counts the reader\'s places and names the outside ones apart', () => {
    renderSheet([MINE, MINE_UNRATED, OUT_A, OUT_B]);

    expect(screen.getByTestId('window-spot-sheet-count').textContent)
      .toBe('2 spots · 2 outside · Ranked by rating.');
  });

  it('is singular at one place and one outside', () => {
    renderSheet([MINE, OUT_A]);

    expect(screen.getByTestId('window-spot-sheet-count').textContent).toBe('1 spot · 1 outside · Ranked by rating.');
  });

  it('keeps the outside places out of "N of M" when a filter hides some of the reader\'s own', () => {
    renderSheet([MINE, MINE_UNRATED, OUT_A, OUT_B]);
    // The rating floor acts on the reader's rated place only: the unrated one and the outside ones fall away.
    fireEvent.click(within(screen.getByTestId('window-spot-sheet-rating')).getByRole('button', { name: /4/ }));

    expect(screen.getByTestId('window-spot-sheet-count').textContent).toMatch(/^1 of 2/);
    expect(screen.getByTestId('window-spot-sheet-count').textContent).not.toMatch(/outside/);
  });

  it('is exactly what it was for a body that is not shaped', () => {
    renderSheet([MINE, MINE_UNRATED]);

    expect(screen.getByTestId('window-spot-sheet-count').textContent).toBe('2 spots · Ranked by rating.');
    expect(screen.queryByTestId('window-spot-outside')).toBeNull();
  });
});
