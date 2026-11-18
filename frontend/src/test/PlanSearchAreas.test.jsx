import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import React from 'react';
import PlanSearch from '../components/PlanSearch.jsx';

/**
 * Areas in the search dialog (find-and-favourites-plan.md F2): the combobox and listbox contract still holds with the
 * new kind of row, and an area is chosen like any other row.
 */
describe('PlanSearch — areas', () => {
  const AREAS = [{
    id: 1, name: "Hadrian's Wall", kind: 'NAMED', aliases: ['Roman Wall'], regionName: null, locationIds: [1, 2],
  }];
  const OUTLOOK = new Map([[1, [{
    date: '2026-10-05', targetType: 'SUNSET', ratedMembers: 2, totalMembers: 2, gatedMembers: 0, topRating: 4,
  }]]]);
  const WINDOWS = [{
    key: '2026-10-05:SUNSET', date: '2026-10-05', targetType: 'SUNSET', label: 'Tonight Sunset', dow: 'Mon', time: '17:41',
  }];

  const setup = (props = {}) => {
    const onPickArea = vi.fn();
    const onClose = vi.fn();
    render(
      <PlanSearch
        windows={WINDOWS}
        locations={[{ id: 2, name: 'Cawfields', regionName: 'North East' }]}
        areas={AREAS}
        areaOutlook={OUTLOOK}
        onClose={onClose}
        onPickArea={onPickArea}
        {...props}
      />,
    );
    return { onPickArea, onClose };
  };
  const type = (value) => fireEvent.change(screen.getByTestId('plan-search-input'), { target: { value } });

  it('names areas in the dialog and the combobox', () => {
    setup();

    expect(screen.getByRole('dialog', { name: 'Search days, regions, areas and places' })).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: 'Search days, regions, areas and places' })).toBeInTheDocument();
  });

  it('⚠️ an area row has no interactive children: the option is the one control', () => {
    setup();
    type('hadrian');

    const option = screen.getByRole('option', { name: /Hadrian's Wall/ });

    expect(option.querySelectorAll('button, a, input, select, textarea, [tabindex], [role="button"]')).toHaveLength(0);
    expect(option).toHaveAttribute('tabindex', '-1');
    // The chip is decorative (it names what Enter does) and so is the glyph.
    expect(within(option).getByText('Its spots')).toHaveAttribute('aria-hidden', 'true');
    expect(within(option).getByText('▦')).toHaveAttribute('aria-hidden', 'true');
  });

  it('⚠️ the figure is spoken as the top of N rated spots and never as the best of the area', () => {
    setup();
    type('hadrian');

    const option = screen.getByRole('option', { name: /Hadrian's Wall/ });

    expect(option).toHaveTextContent('4★');
    expect(option).toHaveTextContent('Tonight Sunset');
    expect(option).toHaveTextContent('not a verdict on the area');
    expect(option.textContent).not.toMatch(/best of the area/i);
  });

  it('every row sits in a labelled group, areas before places', () => {
    setup();
    type('caw');
    expect(screen.queryByRole('group', { name: 'Areas' })).toBeNull();

    type('wall');
    expect(screen.getByRole('group', { name: 'Areas' })).toBeInTheDocument();
    type('hadrian');
    expect(screen.getAllByRole('group').map((g) => g.getAttribute('aria-label'))).toEqual(['Areas']);
  });

  it('Enter on the active area row chooses it and closes the box', () => {
    const { onPickArea, onClose } = setup();
    type('roman wall');

    fireEvent.keyDown(screen.getByTestId('plan-search-input'), { key: 'Enter' });

    expect(onPickArea).toHaveBeenCalledWith(AREAS[0]);
    expect(onClose).toHaveBeenCalled();
  });

  it('a click chooses it too, and with no directory there are simply no area rows', () => {
    const { onPickArea } = setup();
    type('hadrian');
    fireEvent.click(screen.getByRole('option', { name: /Hadrian's Wall/ }));
    expect(onPickArea).toHaveBeenCalledTimes(1);
  });

  it('shows nothing for an area before the directory lands', () => {
    setup({ areas: null, areaOutlook: null });
    type('hadrian');

    expect(screen.queryByRole('option', { name: /Hadrian's Wall/ })).toBeNull();
  });
});
