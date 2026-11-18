import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import React from 'react';
import AreaSheet from '../components/AreaSheet.jsx';
import { buildScoreIndex } from '../utils/locationSheet.js';

/**
 * The area sheet (find-and-favourites-plan.md F2): its words, its rows and its foot. Never a verdict: the figures are
 * places' own, and the note says an area has no rating of its own.
 */
describe('AreaSheet', () => {
  const WINDOWS = [{
    key: '2026-10-05:SUNSET', date: '2026-10-05', targetType: 'SUNSET', label: 'Tonight Sunset',
  }];
  const scoreIndex = buildScoreIndex([
    { locationId: 1, locationName: 'Bamburgh', date: '2026-10-05', targetType: 'SUNSET', rating: 4 },
  ]);
  const SPOTS = [
    { id: 1, name: 'Bamburgh', regionName: 'North East' },
    { id: 2, name: 'Craster' },
  ];
  const AREA = {
    id: 5, name: 'Northumberland Coast', kind: 'SUB_REGION', regionName: 'North East', locationIds: [1, 2],
  };

  const setup = (props = {}) => {
    const handlers = { onClose: vi.fn(), onOpenSpot: vi.fn(), onShowOnMap: vi.fn() };
    render(
      <AreaSheet
        area={AREA}
        spots={SPOTS}
        windows={WINDOWS}
        scoreIndex={scoreIndex}
        reachById={new Map([[1, { driveMinutes: 45 }]])}
        {...handlers}
        {...props}
      />,
    );
    return handlers;
  };

  it('names the area, its kind, region, spot count and drive range, and says an area has no rating of its own', () => {
    setup();

    expect(screen.getByRole('dialog', { name: 'Northumberland Coast — 2 spots' })).toBeInTheDocument();
    expect(screen.getByTestId('area-sheet-meta')).toHaveTextContent('Sub-region · North East · 2 spots · 45 min drive');
    expect(screen.getByTestId('area-sheet-note')).toHaveTextContent('An area has no rating of its own.');
  });

  it('lists each spot with its own best window, or Not rated, and opens the spot on a press', () => {
    const { onOpenSpot } = setup();

    const [first, second] = screen.getAllByTestId('area-sheet-spot');
    expect(first).toHaveTextContent('Bamburgh');
    expect(first).toHaveTextContent('North East · 45 min');
    expect(first).toHaveTextContent('4★ · Tonight Sunset');
    expect(second).toHaveTextContent('Craster');
    expect(second).toHaveTextContent('Not rated');

    fireEvent.click(second);
    expect(onOpenSpot).toHaveBeenCalledWith(SPOTS[1]);
  });

  it('says a spot is outside the reader\'s picks, with the count in the note (singular and plural)', () => {
    setup({ allowedLocationIds: [1] });
    expect(screen.getByTestId('area-sheet-note')).toHaveTextContent('1 is outside your picks');
    expect(screen.getByText('Outside your picks')).toBeInTheDocument();
  });

  it('plural: two outside the picks', () => {
    setup({ allowedLocationIds: [99] });
    expect(screen.getByTestId('area-sheet-note')).toHaveTextContent('2 are outside your picks');
  });

  it('a one-spot area reads in the singular and shows a lone drive', () => {
    setup({ area: { ...AREA, locationIds: [1] }, spots: [SPOTS[0]] });

    expect(screen.getByRole('dialog', { name: 'Northumberland Coast — 1 spot' })).toBeInTheDocument();
    expect(screen.getByTestId('area-sheet-meta')).toHaveTextContent('1 spot');
  });

  it('an area none of whose spots are on the plan says so and has no rows', () => {
    setup({ spots: [] });

    expect(screen.getByTestId('area-sheet-empty')).toBeInTheDocument();
    expect(screen.queryAllByTestId('area-sheet-spot')).toHaveLength(0);
  });

  it('a named area with no region or drive names neither', () => {
    setup({ area: { ...AREA, kind: 'NAMED', regionName: null }, reachById: null });

    expect(screen.getByTestId('area-sheet-meta').textContent).toBe('Named area · 2 spots');
  });

  it('the foot opens the map with the area, and Close dismisses', () => {
    const { onShowOnMap, onClose } = setup();

    fireEvent.click(screen.getByTestId('area-sheet-map'));
    expect(onShowOnMap).toHaveBeenCalledWith(AREA);

    fireEvent.click(screen.getByTestId('area-sheet-close'));
    expect(onClose).toHaveBeenCalled();
  });

  it('without a map door the foot says so rather than showing a dead control', () => {
    setup({ onShowOnMap: undefined });

    expect(screen.queryByTestId('area-sheet-map')).toBeNull();
    expect(screen.getByTestId('area-sheet-nomap')).toBeInTheDocument();
  });

  it('⚠️ stacked: inert, no aria-modal, and Escape is withheld', () => {
    const { onClose } = setup({ stacked: true });

    const dialog = screen.getByTestId('area-sheet');
    expect(dialog).toHaveAttribute('inert');
    expect(dialog).not.toHaveAttribute('aria-modal');
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onClose).not.toHaveBeenCalled();
  });

  it('Escape closes it when nothing is over it', () => {
    const { onClose } = setup();

    fireEvent.keyDown(document, { key: 'Escape' });

    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
