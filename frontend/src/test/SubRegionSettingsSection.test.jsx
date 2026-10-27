import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import SubRegionSettingsSection from '../components/subregions/SubRegionSettingsSection.jsx';
import { storageKey } from '../utils/swrCache.js';
import {
  FIRST_CHOICE, FLAG_OFF, FREE_WEEK, LOCKED_FULL, LOCKED_ROOM, OPEN, OVER_LIMIT, PRO, makeState,
} from './subRegionFixtures.js';

let mockIsMobile = false;
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => mockIsMobile }));
vi.mock('../api/subRegionApi.js', () => ({ getSubRegions: vi.fn(), saveSubRegions: vi.fn() }));

import { getSubRegions, saveSubRegions } from '../api/subRegionApi.js';

const open = () => screen.getByTestId('sub-regions-open');
const box = (name) => screen.getByRole('checkbox', { name: new RegExp(name) });
/** Opens the Lake District group (closed unless it holds a pick) and ticks Keswick in it. */
async function chooseKeswick(user) {
  const lake = screen.getByRole('button', { name: /The Lake District/ });
  if (lake.getAttribute('aria-expanded') === 'false') await user.click(lake);
  await user.click(box('Keswick'));
}
const statusLine = () => screen.getByTestId('sub-regions-status-line');

/** Renders the section for a role and waits for the GET to have been answered and drawn. */
async function renderSection(state, { role = 'LITE_USER', ...props } = {}) {
  getSubRegions.mockResolvedValue(state);
  const view = render(<SubRegionSettingsSection userRole={role} {...props} />);
  if (state.enforced && role !== 'ADMIN') await screen.findByTestId('sub-regions-section');
  return view;
}

/** The state a successful save of the given picks answers with. */
const saved = (overrides) => makeState({ status: 'FREE_UNTIL', freeUntil: '2026-11-02', ...overrides });

describe('SubRegionSettingsSection (S4 A4-A6)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockIsMobile = false;
    localStorage.clear();
  });

  describe('hidden unless enforced (plan D9, Q10)', () => {
    it.each(['LITE_USER', 'PRO_USER'])('draws nothing for %s while the flag is off', async (role) => {
      getSubRegions.mockResolvedValue(FLAG_OFF);

      const { container } = render(<SubRegionSettingsSection userRole={role} />);
      await act(async () => {});

      expect(getSubRegions).toHaveBeenCalledTimes(1);
      expect(container).toBeEmptyDOMElement();
      expect(screen.queryByText('Your sub-regions')).toBeNull();
    });

    it('draws nothing for an Admin, and asks nothing, even with the flag on', async () => {
      getSubRegions.mockResolvedValue(OPEN);

      const { container } = render(<SubRegionSettingsSection userRole="ADMIN" />);
      await act(async () => {});

      expect(getSubRegions).not.toHaveBeenCalled();
      expect(container).toBeEmptyDOMElement();
    });

    it('draws nothing when the read fails', async () => {
      getSubRegions.mockRejectedValue(new Error('offline'));

      const { container } = render(<SubRegionSettingsSection userRole="LITE_USER" />);
      await act(async () => {});

      expect(container).toBeEmptyDOMElement();
    });
  });

  describe('Pro', () => {
    it('gets a Pro tag and the words, with no button and no link', async () => {
      await renderSection(PRO, { role: 'PRO_USER' });

      expect(screen.getByRole('heading', { name: 'Your sub-regions' })).toBeInTheDocument();
      expect(screen.getByTestId('sub-regions-pro-line')).toHaveTextContent('Pro covers every sub-region.');
      expect(screen.getByTestId('pro-tag')).toBeInTheDocument();
      expect(screen.queryByRole('button')).toBeNull();
      expect(screen.queryByRole('link')).toBeNull();
    });
  });

  describe('FREE_UNTIL', () => {
    it('shows the picks grouped by region, the rule line, and Change', async () => {
      await renderSection(FREE_WEEK);

      const picks = screen.getByTestId('sub-regions-picks');
      expect(within(picks).getByText('Northumberland & Tyneside')).toBeInTheDocument();
      expect(within(picks).getByText('Alnwick & the Coquet Coast, Newcastle & Durham')).toBeInTheDocument();
      expect(statusLine()).toHaveTextContent(
        'You can change these freely until 2 November. After that, once every 30 days.',
      );
      expect(open()).toHaveTextContent('Change');
      expect(screen.queryByRole('checkbox')).toBeNull();
    });

    it('expands the picker inline with Cancel and a Save that waits for a difference', async () => {
      const user = userEvent.setup();
      await renderSection(FREE_WEEK);

      await user.click(open());

      expect(screen.getByTestId('sub-regions-inline')).toBeInTheDocument();
      expect(screen.queryByTestId('bottom-sheet')).toBeNull();
      expect(screen.getByTestId('sub-regions-save')).toBeDisabled();
      expect(screen.getByTestId('sub-regions-cancel')).toBeInTheDocument();

      await user.click(box('Berwick'));

      expect(screen.getByTestId('sub-regions-save')).toBeEnabled();
    });

    it('keeps Save disabled at zero chosen', async () => {
      const user = userEvent.setup();
      await renderSection(makeState({ picks: [12], status: 'FREE_UNTIL', freeUntil: '2026-11-02' }));

      await user.click(open());
      await user.click(screen.getByRole('button', { name: 'Remove Alnwick & the Coquet Coast' }));

      expect(screen.getByTestId('sub-regions-save')).toBeDisabled();
    });

    it('Cancel closes the picker, drops the edit and hands focus back to Change', async () => {
      const user = userEvent.setup();
      await renderSection(FREE_WEEK);
      await user.click(open());
      await user.click(box('Berwick'));

      await user.click(screen.getByTestId('sub-regions-cancel'));

      expect(screen.queryByRole('checkbox')).toBeNull();
      expect(open()).toHaveFocus();
      expect(saveSubRegions).not.toHaveBeenCalled();
      await user.click(open());
      expect(box('Berwick')).not.toBeChecked();
    });

    it('saves with no confirmation, toasts, clears the SWR cache and asks the app to refetch', async () => {
      const user = userEvent.setup();
      const onSaved = vi.fn();
      localStorage.setItem(storageKey('briefing:LITE_USER'), '{"v":1}');
      localStorage.setItem(storageKey('forecast:LITE_USER'), '{"v":1}');
      await renderSection(FREE_WEEK, { onSaved });
      saveSubRegions.mockResolvedValue(saved({ picks: [12, 10] }));

      await user.click(open());
      await user.click(box('Newcastle'));
      await user.click(box('Berwick'));
      await user.click(screen.getByTestId('sub-regions-save'));

      expect(saveSubRegions).toHaveBeenCalledWith([12, 10]);
      expect(screen.queryByTestId('sub-region-confirm')).toBeNull();
      expect(await screen.findByTestId('sub-region-toast')).toHaveTextContent(/^Sub-regions saved\.$/);
      expect(localStorage.getItem(storageKey('briefing:LITE_USER'))).toBeNull();
      expect(localStorage.getItem(storageKey('forecast:LITE_USER'))).toBeNull();
      expect(onSaved).toHaveBeenCalledTimes(1);
      expect(within(screen.getByTestId('sub-regions-picks')).getByText('Alnwick & the Coquet Coast, Berwick & Holy Island'))
        .toBeInTheDocument();
    });
  });

  describe('OPEN', () => {
    it('says saving starts a lock, and a swap asks for confirmation naming the date, adding and removing', async () => {
      const user = userEvent.setup();
      await renderSection(OPEN);
      expect(statusLine()).toHaveTextContent('You can change your sub-regions now. Removing one starts a 30-day lock.');

      await user.click(open());
      await user.click(box('Newcastle'));
      await chooseKeswick(user);
      await user.click(screen.getByTestId('sub-regions-save'));

      const dialog = screen.getByRole('dialog', { name: 'Save and lock' });
      expect(within(dialog).getByRole('heading', { name: 'Save and lock until 25 November?' })).toBeInTheDocument();
      expect(within(dialog).getByText("You won't be able to change your sub-regions again until then.")).toBeInTheDocument();
      expect(within(dialog).getByText('Adding').nextSibling).toHaveTextContent('Keswick & Borrowdale');
      expect(within(dialog).getByText('Removing').nextSibling).toHaveTextContent('Newcastle & Durham');
      expect(saveSubRegions).not.toHaveBeenCalled();
    });

    it('Keep editing closes the confirmation and keeps the edit', async () => {
      const user = userEvent.setup();
      await renderSection(OPEN);
      await user.click(open());
      await user.click(box('Newcastle'));
      await chooseKeswick(user);
      await user.click(screen.getByTestId('sub-regions-save'));

      await user.click(screen.getByTestId('sub-region-confirm-keep'));

      expect(screen.queryByTestId('sub-region-confirm')).toBeNull();
      expect(box('Keswick')).toBeChecked();
      expect(saveSubRegions).not.toHaveBeenCalled();
    });

    it('Save and lock sends the PUT and toasts with the next-change date', async () => {
      const user = userEvent.setup();
      await renderSection(OPEN);
      saveSubRegions.mockResolvedValue(makeState({ picks: [12, 20], status: 'LOCKED', nextChangeOn: '2026-11-25' }));
      await user.click(open());
      await user.click(box('Newcastle'));
      await chooseKeswick(user);
      await user.click(screen.getByTestId('sub-regions-save'));

      await user.click(screen.getByTestId('sub-region-confirm-save'));

      expect(saveSubRegions).toHaveBeenCalledWith([12, 20]);
      expect(await screen.findByTestId('sub-region-toast'))
        .toHaveTextContent('Sub-regions saved. You can next change them on 25 November.');
      expect(screen.queryByTestId('sub-region-confirm')).toBeNull();
      expect(statusLine()).toHaveTextContent('You can next change these on 25 November.');
    });

    it('does not confirm a save that only adds, which stamps no lock', async () => {
      const user = userEvent.setup();
      await renderSection(OPEN);
      saveSubRegions.mockResolvedValue(makeState({ picks: [12, 13, 20], status: 'OPEN' }));
      await user.click(open());
      await chooseKeswick(user);

      await user.click(screen.getByTestId('sub-regions-save'));

      expect(screen.queryByTestId('sub-region-confirm')).toBeNull();
      expect(saveSubRegions).toHaveBeenCalledWith([12, 13, 20]);
    });

    it('marks Settings stacked while the confirmation is up, and not after', async () => {
      const user = userEvent.setup();
      const onStackedChange = vi.fn();
      await renderSection(OPEN, { onStackedChange });
      expect(onStackedChange).toHaveBeenLastCalledWith(false);
      await user.click(open());
      await user.click(box('Newcastle'));
      await chooseKeswick(user);

      await user.click(screen.getByTestId('sub-regions-save'));
      expect(onStackedChange).toHaveBeenLastCalledWith(true);

      await user.click(screen.getByTestId('sub-region-confirm-keep'));
      expect(onStackedChange).toHaveBeenLastCalledWith(false);
    });
  });

  describe('LOCKED', () => {
    it('with the limit held: View, the locked line, and the picker read-only with Close', async () => {
      const user = userEvent.setup();
      await renderSection(LOCKED_FULL);
      expect(open()).toHaveTextContent('View');
      expect(statusLine()).toHaveTextContent('You can next change these on 25 November.');

      await user.click(open());

      for (const row of screen.getAllByRole('checkbox')) {
        expect(row).toHaveAttribute('aria-disabled', 'true');
      }
      expect(screen.queryByRole('button', { name: /^Remove / })).toBeNull();
      expect(screen.queryByTestId('sub-regions-save')).toBeNull();
      expect(screen.queryByTestId('sub-regions-open')).toBeNull();
      await user.click(screen.getByTestId('sub-regions-close'));
      expect(screen.queryByRole('checkbox')).toBeNull();
      expect(open()).toHaveFocus();
    });

    it('with room under the limit: adding is allowed, removing waits, and an add-only save needs no confirmation', async () => {
      const user = userEvent.setup();
      await renderSection(LOCKED_ROOM);
      saveSubRegions.mockResolvedValue(makeState({ picks: [12, 13, 20], status: 'LOCKED', nextChangeOn: '2026-11-25' }));
      expect(open()).toHaveTextContent('View');

      await user.click(open());

      expect(statusLine()).toHaveTextContent('You can add up to 1 more now. Removing one waits until 25 November.');
      expect(box('Alnwick')).toHaveAttribute('aria-disabled', 'true');
      await user.click(screen.getByRole('button', { name: /The Lake District/ }));
      expect(box('Keswick')).not.toHaveAttribute('aria-disabled');
      expect(screen.queryByRole('button', { name: 'Remove Alnwick & the Coquet Coast' })).toBeNull();

      await user.click(box('Alnwick'));
      expect(within(screen.getByTestId('sub-regions-picker')).getByRole('status'))
        .toHaveTextContent('Removing one waits until 25 November.');
      expect(box('Alnwick')).toBeChecked();

      await user.click(box('Keswick'));
      await user.click(screen.getByTestId('sub-regions-save'));

      expect(screen.queryByTestId('sub-region-confirm')).toBeNull();
      expect(saveSubRegions).toHaveBeenCalledWith([12, 13, 20]);
    });
  });

  describe('over the limit', () => {
    it('says how many to remove, allows removals only, and saves without a confirmation', async () => {
      const user = userEvent.setup();
      await renderSection(OVER_LIMIT);
      saveSubRegions.mockResolvedValue(makeState({ picks: [12, 13], limit: 2, status: 'OPEN' }));
      expect(statusLine()).toHaveTextContent(
        "You have 3 chosen, and the limit is now 2. Remove 1 to carry on; this doesn't start a lock.",
      );

      await user.click(open());
      await user.click(box('Berwick'));
      expect(box('Berwick')).not.toBeChecked();
      expect(box('Berwick')).toHaveAttribute('aria-disabled', 'true');
      await user.click(screen.getByRole('button', { name: 'Remove Keswick & Borrowdale' }));
      await user.click(screen.getByTestId('sub-regions-save'));

      expect(screen.queryByTestId('sub-region-confirm')).toBeNull();
      expect(saveSubRegions).toHaveBeenCalledWith([12, 13]);
    });
  });

  describe('over the limit with more than one to remove', () => {
    it('keeps Save disabled until the draft is within the limit, then enables it', async () => {
      const user = userEvent.setup();
      await renderSection(makeState({
        picks: [12, 13, 20, 21, 10], limit: 3, status: 'OPEN', overLimit: true,
        nextChangeOnIfSavedToday: '2026-11-25',
      }));
      await user.click(open());

      await user.click(screen.getByRole('button', { name: 'Remove The Langdales' }));
      expect(screen.getByTestId('sub-regions-save')).toBeDisabled();

      await user.click(screen.getByRole('button', { name: 'Remove Keswick & Borrowdale' }));
      expect(screen.getByTestId('sub-regions-save')).toBeEnabled();
    });

    it('shows the sentence the server sent when a save is refused', async () => {
      const user = userEvent.setup();
      await renderSection(OVER_LIMIT);
      saveSubRegions.mockRejectedValue({ response: { status: 400, data: { error: 'Choose at most 2 sub-regions' } } });
      await user.click(open());
      await user.click(screen.getByRole('button', { name: 'Remove Keswick & Borrowdale' }));

      await user.click(screen.getByTestId('sub-regions-save'));

      expect(await screen.findByTestId('sub-regions-error')).toHaveTextContent('Choose at most 2 sub-regions');
    });
  });

  describe('toast and live regions', () => {
    it('keeps an empty status region mounted and writes the text into it', async () => {
      const user = userEvent.setup();
      await renderSection(FREE_WEEK);
      const region = screen.getByTestId('sub-region-toast-region');
      expect(region).toHaveAttribute('role', 'status');
      expect(region).toHaveTextContent('');
      saveSubRegions.mockResolvedValue(saved({ picks: [12, 13, 10] }));
      await user.click(open());
      await user.click(box('Berwick'));

      await user.click(screen.getByTestId('sub-regions-save'));

      const toast = await screen.findByTestId('sub-region-toast');
      expect(toast).toBe(region);
      expect(toast).toHaveTextContent('Sub-regions saved.');
    });

    it('states the lock after the free week when the server says when it ends, and sits clear of the foot of Settings', async () => {
      const user = userEvent.setup();
      await renderSection(FREE_WEEK);
      saveSubRegions.mockResolvedValue(saved({ picks: [12, 13, 10], nextChangeOnIfSavedToday: '2026-11-25' }));
      await user.click(open());
      await user.click(box('Berwick'));

      await user.click(screen.getByTestId('sub-regions-save'));

      const toast = await screen.findByTestId('sub-region-toast');
      expect(toast).toHaveTextContent('Sub-regions saved. After 2 November you can next change them on 25 November.');
      expect(toast).toHaveClass('top-4');
      expect(toast).not.toHaveClass('bottom-6');
    });
  });

  describe('locked rows explain themselves', () => {
    it('points each locked row at the reason with aria-describedby', async () => {
      const user = userEvent.setup();
      await renderSection(LOCKED_ROOM);
      await user.click(open());

      const describedBy = box('Alnwick').getAttribute('aria-describedby');
      expect(document.getElementById(describedBy)).toHaveTextContent('Removing one waits until 25 November.');
      expect(box('Newcastle')).toHaveAttribute('aria-describedby', describedBy);
    });
  });

  describe('FIRST_CHOICE', () => {
    it('opens the picker with the server suggestions, the computed date and Save, and no Change', async () => {
      await renderSection(FIRST_CHOICE);

      expect(screen.queryByTestId('sub-regions-open')).toBeNull();
      expect(screen.getByTestId('sub-regions-inline')).toBeInTheDocument();
      expect(statusLine()).toHaveTextContent(
        'You can change these freely until 2 November. After that, once every 30 days.',
      );
      expect(box('Alnwick')).toBeChecked();
      expect(box('Keswick')).toBeChecked();
      expect(screen.queryByTestId('sub-regions-cancel')).toBeNull();
      expect(screen.getByTestId('sub-regions-save')).toBeEnabled();
    });
  });

  describe('a refused or failed save', () => {
    async function toConfirm(user) {
      await user.click(open());
      await user.click(box('Newcastle'));
      await chooseKeswick(user);
      await user.click(screen.getByTestId('sub-regions-save'));
      await user.click(screen.getByTestId('sub-region-confirm-save'));
    }

    it('on 409 SUB_REGIONS_LOCKED closes the dialog, refetches, and the line says when', async () => {
      const user = userEvent.setup();
      await renderSection(OPEN);
      saveSubRegions.mockRejectedValue({
        response: { status: 409, data: { code: 'SUB_REGIONS_LOCKED', nextChangeOn: '2026-11-25', error: 'locked' } },
      });
      getSubRegions.mockResolvedValue(LOCKED_FULL);

      await toConfirm(user);

      expect(await screen.findByText('You can next change these on 25 November.')).toBeInTheDocument();
      expect(getSubRegions).toHaveBeenCalledTimes(2);
      expect(screen.queryByTestId('sub-region-confirm')).toBeNull();
      expect(screen.queryByRole('checkbox')).toBeNull();
      expect(screen.queryByTestId('sub-region-toast')).toBeNull();
      expect(localStorage.length).toBe(0);
    });

    it('on any other failure keeps the picker and the edit, and says so', async () => {
      const user = userEvent.setup();
      await renderSection(FREE_WEEK);
      saveSubRegions.mockRejectedValue({ response: { status: 500 } });
      await user.click(open());
      await user.click(box('Berwick'));

      await user.click(screen.getByTestId('sub-regions-save'));

      expect(await screen.findByTestId('sub-regions-error')).toHaveTextContent(
        "Couldn't save your sub-regions. Try again.",
      );
      expect(box('Berwick')).toBeChecked();
      expect(screen.queryByTestId('sub-region-toast')).toBeNull();
    });

    it('does not clear the SWR cache for a save the server refused', async () => {
      const user = userEvent.setup();
      localStorage.setItem(storageKey('briefing:LITE_USER'), '{"v":1}');
      await renderSection(FREE_WEEK);
      saveSubRegions.mockRejectedValue({ response: { status: 400, data: { error: 'Choose at most 3' } } });
      await user.click(open());
      await user.click(box('Berwick'));

      await user.click(screen.getByTestId('sub-regions-save'));
      await screen.findByTestId('sub-regions-error');

      expect(localStorage.getItem(storageKey('briefing:LITE_USER'))).not.toBeNull();
    });
  });

  describe('phone', () => {
    beforeEach(() => { mockIsMobile = true; });

    it('opens the picker in a bottom sheet with the status line in its header and Cancel and Save pinned', async () => {
      const user = userEvent.setup();
      await renderSection(OPEN);

      await user.click(open());

      const sheet = screen.getByRole('dialog', { name: 'Your sub-regions' });
      expect(sheet).toBe(screen.getByTestId('bottom-sheet'));
      expect(screen.queryByTestId('sub-regions-inline')).toBeNull();
      expect(within(sheet).getByText('You can change your sub-regions now. Removing one starts a 30-day lock.')).toBeInTheDocument();
      const footer = screen.getByTestId('sub-regions-sheet-footer');
      expect(within(footer).getByRole('button', { name: 'Cancel' })).toBeInTheDocument();
      expect(within(footer).getByRole('button', { name: 'Save' })).toBeDisabled();
    });

    it('never holds more than the sheet: the confirmation replaces its content, with stacked full-width buttons', async () => {
      const user = userEvent.setup();
      const onStackedChange = vi.fn();
      await renderSection(OPEN, { onStackedChange });
      await user.click(open());
      expect(onStackedChange).toHaveBeenLastCalledWith(true);
      await user.click(box('Newcastle'));
      await chooseKeswick(user);

      await user.click(within(screen.getByTestId('sub-regions-sheet-footer')).getByRole('button', { name: 'Save' }));

      expect(screen.getAllByRole('dialog')).toHaveLength(1);
      expect(screen.getByRole('heading', { name: 'Save and lock until 25 November?' })).toBeInTheDocument();
      expect(screen.getByTestId('sub-region-confirm-save').parentElement).toHaveClass('flex-col');
      expect(screen.queryByTestId('sub-regions-sheet-footer')).toBeNull();

      await user.click(screen.getByTestId('sub-region-confirm-keep'));
      expect(box('Keswick')).toBeChecked();
    });

    it('pins the title, status line and count above the scroller, and the footer below it', async () => {
      const user = userEvent.setup();
      await renderSection(OPEN);

      await user.click(open());

      const sheet = screen.getByTestId('bottom-sheet');
      const header = within(sheet).getByTestId('bottom-sheet-header');
      const scroller = within(sheet).getByTestId('bottom-sheet-scroller');
      expect(scroller.contains(header)).toBe(false);
      expect(within(header).getByRole('heading', { name: 'Your sub-regions' })).toBeInTheDocument();
      expect(header).toHaveTextContent('Removing one starts a 30-day lock.');
      expect(within(header).getByTestId('sub-regions-sheet-count')).toHaveTextContent('2 of 3 chosen');
      expect(scroller.contains(screen.getByTestId('sub-regions-sheet-footer'))).toBe(false);
      expect(sheet).toHaveAttribute('data-size', 'tall');
    });

    it('shows the confirmation as a short sheet sized to its content, not the tall one', async () => {
      const user = userEvent.setup();
      await renderSection(OPEN);
      await user.click(open());
      await user.click(box('Newcastle'));
      await user.click(screen.getByRole('button', { name: /The Lake District/ }));
      await user.click(box('Keswick'));

      await user.click(within(screen.getByTestId('sub-regions-sheet-footer')).getByRole('button', { name: 'Save' }));

      expect(screen.getByTestId('bottom-sheet')).not.toHaveAttribute('data-size');
      expect(screen.queryByTestId('bottom-sheet-header')).toBeNull();
    });

    it('stacks Keep editing as a full-width secondary button under Save and lock', async () => {
      const user = userEvent.setup();
      await renderSection(OPEN);
      await user.click(open());
      await user.click(box('Newcastle'));
      await user.click(screen.getByRole('button', { name: /The Lake District/ }));
      await user.click(box('Keswick'));

      await user.click(within(screen.getByTestId('sub-regions-sheet-footer')).getByRole('button', { name: 'Save' }));

      const save = screen.getByRole('button', { name: 'Save and lock' });
      const keep = screen.getByRole('button', { name: 'Keep editing' });
      expect(keep).toHaveClass('btn-secondary', 'w-full');
      expect(save).toHaveClass('w-full');
      expect(save.compareDocumentPosition(keep) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    });

    it('puts the toast at the foot of the screen, clear of the dialog title', async () => {
      const user = userEvent.setup();
      await renderSection(FREE_WEEK);
      saveSubRegions.mockResolvedValue(saved({ picks: [12, 13, 10] }));
      await user.click(open());
      await user.click(box('Berwick'));
      await user.click(within(screen.getByTestId('sub-regions-sheet-footer')).getByRole('button', { name: 'Save' }));

      const toast = await screen.findByRole('status', { name: '' });
      expect(toast.className).toContain('bottom-[max(1.5rem,var(--safe-b))]');
      expect(toast).not.toHaveClass('top-4');
    });

    it('closes the sheet and toasts after a save', async () => {
      const user = userEvent.setup();
      await renderSection(FREE_WEEK);
      saveSubRegions.mockResolvedValue(saved({ picks: [12, 13, 10] }));
      await user.click(open());
      await user.click(box('Berwick'));

      await user.click(within(screen.getByTestId('sub-regions-sheet-footer')).getByRole('button', { name: 'Save' }));

      expect(await screen.findByTestId('sub-region-toast')).toHaveTextContent('Sub-regions saved.');
      expect(screen.queryByTestId('bottom-sheet')).toBeNull();
    });

    it('LOCKED View opens the sheet read-only with Close', async () => {
      const user = userEvent.setup();
      await renderSection(LOCKED_FULL);

      await user.click(open());

      const footer = screen.getByTestId('sub-regions-sheet-footer');
      expect(within(footer).getByRole('button', { name: 'Close' })).toBeInTheDocument();
      expect(within(footer).queryByRole('button', { name: 'Save' })).toBeNull();
      expect(box('Alnwick')).toHaveAttribute('aria-disabled', 'true');
    });
  });

  describe('desktop', () => {
    it('never draws the bottom sheet', async () => {
      const user = userEvent.setup();
      await renderSection(OPEN);

      await user.click(open());

      expect(screen.queryByTestId('bottom-sheet')).toBeNull();
      expect(screen.getByTestId('sub-regions-inline')).toBeInTheDocument();
    });
  });

  describe('no links and no upgrade, in any state', () => {
    it.each([
      ['PRO', PRO, 'PRO_USER'],
      ['FREE_UNTIL', FREE_WEEK, 'LITE_USER'],
      ['OPEN', OPEN, 'LITE_USER'],
      ['LOCKED', LOCKED_FULL, 'LITE_USER'],
      ['over limit', OVER_LIMIT, 'LITE_USER'],
      ['FIRST_CHOICE', FIRST_CHOICE, 'LITE_USER'],
    ])('%s', async (_name, state, role) => {
      const user = userEvent.setup();
      const { container } = await renderSection(state, { role });
      const trigger = screen.queryByTestId('sub-regions-open');
      if (trigger) await user.click(trigger);

      expect(container.querySelectorAll('a')).toHaveLength(0);
      expect(screen.queryByRole('link')).toBeNull();
      expect(document.body.textContent).not.toMatch(/upgrade/i);
    });
  });
});
