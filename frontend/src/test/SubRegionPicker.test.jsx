import { useState } from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import SubRegionPicker from '../components/subregions/SubRegionPicker.jsx';
import { OPTIONS } from './subRegionFixtures.js';

/** The picker with its own selection, as every real caller holds it. */
function Harness({ initial = [], limit = 3, onChangeSpy = vi.fn(), ...props }) {
  const [value, setValue] = useState(initial);
  return (
    <SubRegionPicker
      options={OPTIONS}
      value={value}
      limit={limit}
      onChange={(ids) => { onChangeSpy(ids); setValue(ids); }}
      {...props}
    />
  );
}

const row = (name) => screen.getByRole('checkbox', { name: new RegExp(name) });

describe('SubRegionPicker (S4 A1)', () => {
  describe('count bar and chips', () => {
    it('says how many are chosen of the limit, in a polite live region', () => {
      render(<Harness initial={[12, 13]} />);

      const count = screen.getByTestId('sub-region-picker-count');
      expect(count).toHaveTextContent('2 of 3 chosen');
      expect(count).toHaveAttribute('aria-live', 'polite');
    });

    it('shows None chosen yet when nothing is chosen', () => {
      render(<Harness />);

      expect(screen.getByText('None chosen yet')).toBeInTheDocument();
      expect(screen.getByTestId('sub-region-picker-count')).toHaveTextContent('0 of 3 chosen');
    });

    it('gives each chosen sub-region a chip with a Remove button that unchooses it', async () => {
      const user = userEvent.setup();
      const spy = vi.fn();
      render(<Harness initial={[12, 13]} onChangeSpy={spy} />);

      await user.click(screen.getByRole('button', { name: 'Remove Alnwick & the Coquet Coast' }));

      expect(spy).toHaveBeenCalledWith([13]);
      expect(screen.getByTestId('sub-region-picker-count')).toHaveTextContent('1 of 3 chosen');
      expect(screen.queryByRole('button', { name: 'Remove Alnwick & the Coquet Coast' })).toBeNull();
    });

    it('returns focus to the sub-regions own row when its chip is removed, opening a closed region', async () => {
      const user = userEvent.setup();
      render(<Harness initial={[20]} />);
      // Close the region the pick is in, so the row is not on screen.
      await user.click(screen.getByRole('button', { name: /The Lake District/ }));
      expect(screen.queryByRole('checkbox', { name: /Keswick/ })).toBeNull();

      await user.click(screen.getByRole('button', { name: 'Remove Keswick & Borrowdale' }));

      expect(row('Keswick')).toHaveFocus();
      expect(row('Keswick')).not.toBeChecked();
    });
  });

  describe('region groups', () => {
    it('starts closed with nothing chosen, each header showing its count of sub-regions', () => {
      render(<Harness />);

      const lake = screen.getByRole('button', { name: /The Lake District/ });
      expect(lake).toHaveAttribute('aria-expanded', 'false');
      expect(lake).toHaveTextContent('2 sub-regions');
      expect(screen.queryAllByRole('checkbox')).toHaveLength(0);
    });

    it('starts open for a region holding a chosen sub-region, the header showing k chosen', () => {
      render(<Harness initial={[12, 13]} />);

      const north = screen.getByRole('button', { name: /Northumberland & Tyneside/ });
      expect(north).toHaveAttribute('aria-expanded', 'true');
      expect(north).toHaveTextContent('2 chosen');
      expect(screen.getByRole('button', { name: /The Lake District/ })).toHaveAttribute('aria-expanded', 'false');
      expect(screen.getAllByRole('checkbox')).toHaveLength(4);
    });

    it('opens and closes on the header, and each row names its locations and examples', async () => {
      const user = userEvent.setup();
      render(<Harness />);

      await user.click(screen.getByRole('button', { name: /Northumberland & Tyneside/ }));

      expect(screen.getByRole('checkbox', { name: /Alnwick & the Coquet Coast.*7 locations · Alnmouth, Warkworth Castle/ }))
        .toBeInTheDocument();
      await user.click(screen.getByRole('button', { name: /Northumberland & Tyneside/ }));
      expect(screen.queryAllByRole('checkbox')).toHaveLength(0);
    });
  });

  describe('choosing', () => {
    it('toggles a row with Space from the keyboard', async () => {
      const user = userEvent.setup();
      const spy = vi.fn();
      render(<Harness initial={[12]} onChangeSpy={spy} />);

      row('Newcastle').focus();
      await user.keyboard(' ');

      expect(spy).toHaveBeenCalledWith([12, 13]);
      expect(row('Newcastle')).toBeChecked();
      expect(row('Newcastle')).toHaveFocus();
    });

    it('is a real checkbox inside a label, so the name is the sub-regions and its meta', () => {
      render(<Harness initial={[12]} />);

      const box = row('Alnwick');
      expect(box.tagName).toBe('INPUT');
      expect(box.closest('label')).not.toBeNull();
      expect(box).toBeChecked();
    });
  });

  describe('at the limit', () => {
    it('says so, and marks unchosen rows aria-disabled (never disabled) pointing at the reason', () => {
      render(<Harness initial={[12, 13, 10]} />);

      const line = screen.getByText('Choose up to 3 — remove one to swap.');
      const unchosen = row('Bamburgh');
      expect(unchosen).toHaveAttribute('aria-disabled', 'true');
      expect(unchosen).not.toBeDisabled();
      expect(unchosen).toHaveAttribute('aria-describedby', line.id);
      expect(row('Alnwick')).not.toHaveAttribute('aria-disabled');
    });

    it('keeps an at-limit row focusable', () => {
      render(<Harness initial={[12, 13, 10]} />);

      row('Bamburgh').focus();

      expect(row('Bamburgh')).toHaveFocus();
    });

    it('activating one changes nothing and shows the reason under that row until the next change', async () => {
      const user = userEvent.setup();
      const spy = vi.fn();
      render(<Harness initial={[12, 13, 10]} onChangeSpy={spy} />);

      await user.click(row('Bamburgh'));

      expect(spy).not.toHaveBeenCalled();
      expect(row('Bamburgh')).not.toBeChecked();
      expect(screen.getByTestId('sub-region-picker-count')).toHaveTextContent('3 of 3 chosen');
      expect(screen.getByRole('status')).toHaveTextContent('Choose up to 3 — remove one to swap.');

      await user.click(row('Alnwick'));

      expect(screen.queryByRole('status')).toBeNull();
      expect(spy).toHaveBeenCalledWith([13, 10]);
    });

    it('lets a swap through once one is removed', async () => {
      const user = userEvent.setup();
      render(<Harness initial={[12, 13, 10]} />);

      await user.click(screen.getByRole('button', { name: 'Remove Berwick & Holy Island' }));
      await user.click(row('Bamburgh'));

      expect(row('Bamburgh')).toBeChecked();
      expect(row('Berwick')).not.toBeChecked();
      expect(screen.getByTestId('sub-region-picker-count')).toHaveTextContent('3 of 3 chosen');
    });
  });

  describe('read mode', () => {
    it('makes every row aria-disabled, chips plain, and the count a bare number', async () => {
      const user = userEvent.setup();
      const spy = vi.fn();
      render(<Harness initial={[12, 13]} mode="read" onChangeSpy={spy} />);

      expect(screen.getByTestId('sub-region-picker-count')).toHaveTextContent(/^2 chosen$/);
      for (const box of screen.getAllByRole('checkbox')) {
        expect(box).toHaveAttribute('aria-disabled', 'true');
      }
      expect(screen.queryByRole('button', { name: /^Remove / })).toBeNull();
      await user.click(row('Alnwick'));
      await user.click(row('Berwick'));
      expect(spy).not.toHaveBeenCalled();
      expect(screen.queryByRole('status')).toBeNull();
    });
  });

  describe('lockedIds (adding while locked)', () => {
    it('keeps locked picks checked and unremovable, with no Remove on their chips, and lets a new one in', async () => {
      const user = userEvent.setup();
      const spy = vi.fn();
      render(<Harness initial={[12, 13]} lockedIds={[12, 13]} lockedReason="Removing one waits until 25 November." onChangeSpy={spy} />);

      expect(row('Alnwick')).toHaveAttribute('aria-disabled', 'true');
      expect(row('Alnwick')).toBeChecked();
      expect(row('Berwick')).not.toHaveAttribute('aria-disabled');
      expect(screen.queryByRole('button', { name: 'Remove Alnwick & the Coquet Coast' })).toBeNull();

      await user.click(row('Alnwick'));
      expect(spy).not.toHaveBeenCalled();
      expect(screen.getByRole('status')).toHaveTextContent('Removing one waits until 25 November.');

      await user.click(row('Berwick'));
      expect(spy).toHaveBeenCalledWith([12, 13, 10]);
      // The one just added can still be taken back.
      expect(screen.getByRole('button', { name: 'Remove Berwick & Holy Island' })).toBeInTheDocument();
    });
  });

  describe('removalsOnly (over a lowered limit)', () => {
    it('allows removing but not adding, whatever the count', async () => {
      const user = userEvent.setup();
      const spy = vi.fn();
      render(<Harness initial={[12, 13, 20]} limit={2} removalsOnly onChangeSpy={spy} />);

      await user.click(screen.getByRole('button', { name: 'Remove Keswick & Borrowdale' }));
      expect(spy).toHaveBeenLastCalledWith([12, 13]);

      await user.click(row('Berwick'));
      expect(spy).toHaveBeenCalledTimes(1);
      expect(row('Berwick')).toHaveAttribute('aria-disabled', 'true');
      expect(within(screen.getByTestId('sub-region-picker')).getByRole('status'))
        .toHaveTextContent('Remove sub-regions to get within the limit.');
    });
  });

  it('puts neither a link nor the word upgrade anywhere', () => {
    const { container } = render(<Harness initial={[12, 13, 10]} />);

    expect(container.querySelectorAll('a')).toHaveLength(0);
    expect(container.textContent).not.toMatch(/upgrade/i);
  });

  it('is operable by fireEvent as well: a change event on an unchosen row at the limit is refused', () => {
    const spy = vi.fn();
    render(<Harness initial={[12, 13, 10]} onChangeSpy={spy} />);

    fireEvent.click(row('Bamburgh'));

    expect(spy).not.toHaveBeenCalled();
  });
});
