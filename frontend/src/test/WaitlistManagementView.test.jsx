import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, fireEvent, within, act } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { getWaitlist, inviteWaitlistEntry, removeWaitlistEntry } from '../api/waitlistApi.js';
import { useIsMobile } from '../hooks/useIsMobile.js';
import WaitlistManagementView from '../components/WaitlistManagementView.jsx';

vi.mock('../api/waitlistApi.js', () => ({
  getWaitlist: vi.fn(),
  inviteWaitlistEntry: vi.fn(),
  removeWaitlistEntry: vi.fn(),
}));
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: vi.fn() }));

// The clock is frozen at 31 Oct 2026, noon UK (after the clocks went back), so "Today" and "Waiting" are decided
// by the fixtures and not by the day the suite runs. Only Date is faked: the waits below still use real timers.
const NOW = '2026-10-31T12:00:00Z';

const FIRST = { id: 42, email: 'hello@kirstyfoto.co.uk', submittedAt: '2026-10-02T07:14:00', invitedAt: null, inviteExpiresAt: null };
const SECOND = { id: 99, email: 'd.armstrong@example.com', submittedAt: '2026-10-09T20:40:00', invitedAt: null, inviteExpiresAt: null };
const THIRD = { id: 7, email: 'peterlee.light@example.com', submittedAt: '2026-10-31T07:48:00', invitedAt: null, inviteExpiresAt: null };
const QUEUE = [FIRST, SECOND, THIRD];
const INVITED = { ...FIRST, invitedAt: '2026-10-09T08:00:00', inviteExpiresAt: '2026-10-16T08:00:00' };

function renderView(onCountChange = vi.fn()) {
  render(<WaitlistManagementView onCountChange={onCountChange} />);
  return { onCountChange };
}

/** Resolves the list and waits for the grid, which only the response renders. */
async function renderLoaded(entries = QUEUE) {
  getWaitlist.mockResolvedValue(entries);
  const handlers = renderView();
  await screen.findByTestId('waitlist');
  return handlers;
}

function installClipboard(writeText) {
  Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
}

/** The text of the Blob the page handed to URL.createObjectURL. */
function readBlob(blob) {
  return new Promise((resolve) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result);
    reader.readAsText(blob);
  });
}

describe('WaitlistManagementView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(NOW));
    useIsMobile.mockReturnValue(false);
    window.scrollTo = vi.fn();
    window.location.hash = '';
  });

  afterEach(() => {
    vi.useRealTimers();
    delete navigator.clipboard;
    delete globalThis.URL.createObjectURL;
    // The download revokes its URL on a timer, which may fire after the test: leave a no-op there.
    globalThis.URL.revokeObjectURL = () => {};
  });

  describe('the list', () => {
    it('lists the queue in the server\'s order, oldest first, and reports the count', async () => {
      const { onCountChange } = await renderLoaded();

      const rows = screen.getAllByRole('button', { name: /in the queue/ });
      expect(rows.map((r) => within(r).getByTestId(/waitlist-cell-email/).textContent)).toEqual([
        'hello@kirstyfoto.co.uk', 'd.armstrong@example.com', 'peterlee.light@example.com',
      ]);
      expect(onCountChange).toHaveBeenCalledWith(3);
      expect(screen.getByTestId('waitlist-total')).toHaveTextContent('3 · oldest first');
    });

    it('numbers the queue from 1 by place in the list, not by database id', async () => {
      await renderLoaded();
      expect(screen.getByTestId('waitlist-cell-position-42')).toHaveTextContent('1');
      expect(screen.getByTestId('waitlist-cell-position-99')).toHaveTextContent('2');
      expect(screen.getByTestId('waitlist-cell-position-7')).toHaveTextContent('3');
    });

    it('shows when each address joined, on the UK clock with the time, and "Today" for today', async () => {
      await renderLoaded();
      // 07:14 UTC on 2 Oct is 08:14 BST.
      expect(screen.getByTestId('waitlist-cell-joined-42')).toHaveTextContent('2 Oct 2026, 08:14');
      expect(screen.getByTestId('waitlist-cell-joined-7')).toHaveTextContent('Today, 07:48');
    });

    it('shows how long each has waited in whole days', async () => {
      await renderLoaded();
      expect(screen.getByTestId('waitlist-cell-waiting-42')).toHaveTextContent('29 days');
      expect(screen.getByTestId('waitlist-cell-waiting-99')).toHaveTextContent('22 days');
      expect(screen.getByTestId('waitlist-cell-waiting-7')).toHaveTextContent('Today');
    });

    it('draws the columns #, Email, Joined and Waiting, with # and Waiting the secondary ones', async () => {
      await renderLoaded();
      const header = screen.getByTestId('waitlist-header');
      for (const label of ['#', 'Email', 'Joined', 'Waiting']) {
        expect(within(header).getByText(label)).toBeInTheDocument();
      }
      expect(screen.getByTestId('waitlist-cell-position-42').className).toContain('@max-[820px]:hidden');
      expect(screen.getByTestId('waitlist-cell-waiting-42').className).toContain('@max-[820px]:hidden');
      expect(screen.getByTestId('waitlist-cell-email-42').className).not.toContain('@max-[820px]:hidden');
    });

    it('gives each row, in card mode, a status of the wait and a summary of position and join', async () => {
      await renderLoaded();
      expect(screen.getByTestId('waitlist-cell-card-status-42')).toHaveTextContent('29 days');
      expect(screen.getByTestId('waitlist-cell-card-summary-42')).toHaveTextContent('1 · joined 2 Oct 2026, 08:14');
    });

    it('holds no button, link or field inside a row: the whole row is the target', async () => {
      await renderLoaded();
      for (const id of [42, 99, 7]) {
        expect(screen.getByTestId(`waitlist-row-${id}`).querySelector('button, a, input, select, textarea')).toBeNull();
      }
    });

    it('shows a loading line until the list arrives', () => {
      getWaitlist.mockReturnValue(new Promise(() => {}));
      renderView();
      expect(screen.getByText('Loading waitlist...')).toBeInTheDocument();
    });

    it('fetches the admin list once', async () => {
      await renderLoaded();
      expect(getWaitlist).toHaveBeenCalledTimes(1);
    });
  });

  describe('empty and failed', () => {
    it('says no one is on the waitlist, tells how entries appear, and reports a count of zero', async () => {
      getWaitlist.mockResolvedValue([]);
      const { onCountChange } = renderView();

      expect(await screen.findByTestId('waitlist-empty')).toHaveTextContent("No one's on the waitlist yet");
      expect(screen.getByTestId('waitlist-empty')).toHaveTextContent('Sign-ups appear here, oldest first, with the time they joined.');
      expect(onCountChange).toHaveBeenCalledWith(0);
      expect(screen.getByTestId('waitlist-total')).toHaveTextContent('0');
    });

    it('offers no export, search or grid when the list is empty', async () => {
      getWaitlist.mockResolvedValue([]);
      renderView();
      await screen.findByTestId('waitlist-empty');
      expect(screen.queryByTestId('waitlist-copy')).not.toBeInTheDocument();
      expect(screen.queryByTestId('waitlist-csv')).not.toBeInTheDocument();
      expect(screen.queryByTestId('waitlist-search')).not.toBeInTheDocument();
      expect(screen.queryByTestId('waitlist')).not.toBeInTheDocument();
    });

    it('says the load failed, and does not claim the list is empty', async () => {
      getWaitlist.mockRejectedValue(new Error('Network error'));
      const { onCountChange } = renderView();

      expect(await screen.findByTestId('waitlist-load-error')).toHaveTextContent("Couldn't load the waitlist.");
      expect(screen.getByRole('alert')).toBeInTheDocument();
      expect(screen.queryByTestId('waitlist-empty')).not.toBeInTheDocument();
      // Nothing loaded, so nothing is reported: the badge must not read "0" for a failure.
      expect(onCountChange).not.toHaveBeenCalled();
    });
  });

  describe('search', () => {
    it('filters by email, ignoring case, and keeps each survivor\'s place in the queue', async () => {
      await renderLoaded();
      fireEvent.change(screen.getByTestId('waitlist-search'), { target: { value: 'PETERLEE' } });

      expect(screen.queryByTestId('waitlist-row-42')).not.toBeInTheDocument();
      expect(screen.getByTestId('waitlist-row-7')).toBeInTheDocument();
      // Third in the queue, still: a search never renumbers.
      expect(screen.getByTestId('waitlist-cell-position-7')).toHaveTextContent('3');
      expect(screen.getByTestId('waitlist-total')).toHaveTextContent('3 · oldest first');
    });

    it('says no one matches, and Clear search brings the list back', async () => {
      await renderLoaded();
      fireEvent.change(screen.getByTestId('waitlist-search'), { target: { value: 'zzz' } });

      expect(screen.getByTestId('waitlist-no-match')).toHaveTextContent('No one matches.');
      fireEvent.click(screen.getByTestId('waitlist-no-match-clear'));
      expect(screen.getByTestId('waitlist-row-42')).toBeInTheDocument();
      expect(screen.getByTestId('waitlist-search')).toHaveValue('');
    });
  });

  describe('Copy emails and Download CSV', () => {
    it('copies every address, one per line, and says how many', async () => {
      const writeText = vi.fn().mockResolvedValue(undefined);
      installClipboard(writeText);
      await renderLoaded();

      fireEvent.click(screen.getByTestId('waitlist-copy'));

      await waitFor(() => expect(writeText).toHaveBeenCalledWith(
        'hello@kirstyfoto.co.uk\nd.armstrong@example.com\npeterlee.light@example.com',
      ));
      expect(await screen.findByText('Copied 3 emails.')).toBeInTheDocument();
    });

    it('copies the whole list even while a search is narrowing it', async () => {
      const writeText = vi.fn().mockResolvedValue(undefined);
      installClipboard(writeText);
      await renderLoaded();
      fireEvent.change(screen.getByTestId('waitlist-search'), { target: { value: 'peterlee' } });

      fireEvent.click(screen.getByTestId('waitlist-copy'));

      await waitFor(() => expect(writeText).toHaveBeenCalledTimes(1));
      expect(writeText.mock.calls[0][0].split('\n')).toHaveLength(3);
    });

    it('says "Copied 1 email." for a list of one', async () => {
      installClipboard(vi.fn().mockResolvedValue(undefined));
      await renderLoaded([FIRST]);
      fireEvent.click(screen.getByTestId('waitlist-copy'));
      expect(await screen.findByText('Copied 1 email.')).toBeInTheDocument();
    });

    it('points at Download CSV when the clipboard refuses, and shows no success toast', async () => {
      installClipboard(vi.fn().mockRejectedValue(new Error('denied')));
      await renderLoaded();

      fireEvent.click(screen.getByTestId('waitlist-copy'));

      expect(await screen.findByTestId('waitlist-copy-error')).toHaveTextContent("Couldn't copy. Use Download CSV instead.");
      expect(screen.queryByText(/^Copied/)).not.toBeInTheDocument();
    });

    it('points at Download CSV when there is no clipboard at all', async () => {
      await renderLoaded();
      fireEvent.click(screen.getByTestId('waitlist-copy'));
      expect(await screen.findByTestId('waitlist-copy-error')).toBeInTheDocument();
    });

    it('clears the copy error on the next successful copy', async () => {
      await renderLoaded();
      fireEvent.click(screen.getByTestId('waitlist-copy'));
      await screen.findByTestId('waitlist-copy-error');

      installClipboard(vi.fn().mockResolvedValue(undefined));
      fireEvent.click(screen.getByTestId('waitlist-copy'));

      await screen.findByText('Copied 3 emails.');
      expect(screen.queryByTestId('waitlist-copy-error')).not.toBeInTheDocument();
    });

    it('downloads email,submittedAt for the whole list, as the server sent the instants', async () => {
      const blobs = [];
      globalThis.URL.createObjectURL = vi.fn((blob) => { blobs.push(blob); return 'blob:x'; });
      globalThis.URL.revokeObjectURL = vi.fn();
      const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
      await renderLoaded();
      fireEvent.change(screen.getByTestId('waitlist-search'), { target: { value: 'peterlee' } });

      fireEvent.click(screen.getByTestId('waitlist-csv'));

      expect(click).toHaveBeenCalledTimes(1);
      vi.useRealTimers();
      const text = await readBlob(blobs[0]);
      expect(text).toBe(
        'email,submittedAt\r\n'
        + 'hello@kirstyfoto.co.uk,2026-10-02T07:14:00\r\n'
        + 'd.armstrong@example.com,2026-10-09T20:40:00\r\n'
        + 'peterlee.light@example.com,2026-10-31T07:48:00\r\n',
      );
      click.mockRestore();
    });
  });

  describe('the panel', () => {
    it('opens on a row click, titled by the email, and writes the selection to the hash', async () => {
      await renderLoaded();

      fireEvent.click(screen.getByTestId('waitlist-row-99'));

      expect(screen.getByTestId('waitlist-panel-shell-title')).toHaveTextContent('d.armstrong@example.com');
      expect(window.location.hash).toBe('#manage/waitlist/99');
      expect(screen.getByTestId('waitlist-row-99')).toHaveAttribute('aria-current', 'true');
    });

    it('opens from the keyboard on Enter', async () => {
      await renderLoaded();
      fireEvent.keyDown(screen.getByTestId('waitlist-row-42'), { key: 'Enter' });
      expect(screen.getByTestId('waitlist-panel-shell-title')).toHaveTextContent('hello@kirstyfoto.co.uk');
    });

    it('shows the dates, queue position and invitation status', async () => {
      await renderLoaded();
      fireEvent.click(screen.getByTestId('waitlist-row-42'));

      expect(screen.getByTestId('waitlist-panel-shell-subtitle')).toHaveTextContent('Number 1 of 3 in the queue');
      expect(screen.getByTestId('waitlist-panel-joined')).toHaveTextContent('2 Oct 2026, 08:14');
      expect(screen.getByTestId('waitlist-panel-waiting')).toHaveTextContent('29 days');
      expect(screen.getByTestId('waitlist-invited')).toHaveTextContent('Not invited');
    });

    it('is a labelled aside, never a dialog', async () => {
      await renderLoaded();
      fireEvent.click(screen.getByTestId('waitlist-row-42'));
      expect(screen.getByRole('complementary', { name: 'Waitlist entry hello@kirstyfoto.co.uk' })).toBeInTheDocument();
      expect(document.querySelector('[role="dialog"], [aria-modal]')).toBeNull();
    });

    it('closes from the ×, clearing the hash', async () => {
      await renderLoaded();
      fireEvent.click(screen.getByTestId('waitlist-row-42'));

      fireEvent.click(screen.getByTestId('waitlist-panel-shell-close'));

      expect(screen.queryByTestId('waitlist-panel-shell')).not.toBeInTheDocument();
      expect(window.location.hash).toBe('#manage/waitlist');
    });

    it('closes on Esc', async () => {
      await renderLoaded();
      fireEvent.click(screen.getByTestId('waitlist-row-42'));

      fireEvent.keyDown(document.body, { key: 'Escape' });

      expect(screen.queryByTestId('waitlist-panel-shell')).not.toBeInTheDocument();
    });

    it('leaves Esc to the search box while it has focus', async () => {
      await renderLoaded();
      fireEvent.click(screen.getByTestId('waitlist-row-42'));

      fireEvent.keyDown(screen.getByTestId('waitlist-search'), { key: 'Escape' });

      expect(screen.getByTestId('waitlist-panel-shell')).toBeInTheDocument();
    });

    it('leaves Esc to a dialog that is open over it', async () => {
      await renderLoaded();
      fireEvent.click(screen.getByTestId('waitlist-row-42'));
      const dialog = document.createElement('div');
      dialog.setAttribute('role', 'dialog');
      document.body.appendChild(dialog);

      fireEvent.keyDown(document.body, { key: 'Escape' });
      dialog.remove();

      expect(screen.getByTestId('waitlist-panel-shell')).toBeInTheDocument();
    });

    it('opens on mount for a hash naming an entry', async () => {
      window.location.hash = '#manage/waitlist/99';
      await renderLoaded();
      expect(screen.getByTestId('waitlist-panel-shell-title')).toHaveTextContent('d.armstrong@example.com');
    });

    it('shows no panel for a hash naming an entry that is not on the list', async () => {
      window.location.hash = '#manage/waitlist/12345';
      await renderLoaded();
      expect(screen.queryByTestId('waitlist-panel-shell')).not.toBeInTheDocument();
    });

    it('follows Back and Forward: the hash moving to another entry, or to none', async () => {
      await renderLoaded();
      fireEvent.click(screen.getByTestId('waitlist-row-42'));

      act(() => {
        window.location.hash = '#manage/waitlist/7';
        window.dispatchEvent(new HashChangeEvent('hashchange'));
      });
      expect(screen.getByTestId('waitlist-panel-shell-title')).toHaveTextContent('peterlee.light@example.com');

      act(() => {
        window.location.hash = '#manage/waitlist';
        window.dispatchEvent(new HashChangeEvent('hashchange'));
      });
      expect(screen.queryByTestId('waitlist-panel-shell')).not.toBeInTheDocument();
    });

    it('keeps the panel open when an unrelated hash (a stripped token) goes by', async () => {
      await renderLoaded();
      fireEvent.click(screen.getByTestId('waitlist-row-42'));

      act(() => {
        window.location.hash = '#invite=abc';
        window.dispatchEvent(new HashChangeEvent('hashchange'));
      });

      expect(screen.getByTestId('waitlist-panel-shell')).toBeInTheDocument();
    });
  });

  describe('phone', () => {
    it('shows the panel as a page with a back bar, and the list stays mounted behind it, unseen', async () => {
      useIsMobile.mockReturnValue(true);
      await renderLoaded();
      fireEvent.change(screen.getByTestId('waitlist-search'), { target: { value: 'peterlee' } });

      fireEvent.click(screen.getByTestId('waitlist-row-7'));

      expect(screen.getByRole('button', { name: 'Back to Waitlist' })).toBeInTheDocument();
      expect(screen.getByTestId('waitlist-split-list')).toHaveAttribute('hidden');
      fireEvent.click(screen.getByRole('button', { name: 'Back to Waitlist' }));
      // The search survived the round trip.
      expect(screen.getByTestId('waitlist-search')).toHaveValue('peterlee');
      expect(screen.getByTestId('waitlist-split-list')).not.toHaveAttribute('hidden');
    });

    it('gives every phone control a 44px touch target: header buttons, search, back, Invite, Remove, Keep', async () => {
      useIsMobile.mockReturnValue(true);
      await renderLoaded();
      for (const id of ['waitlist-copy', 'waitlist-csv', 'waitlist-search']) {
        expect(screen.getByTestId(id).className).toContain('max-[639px]:min-h-11');
      }
      fireEvent.click(screen.getByTestId('waitlist-row-42'));
      expect(screen.getByTestId('waitlist-split-back').className).toContain('min-h-[44px]');
      expect(screen.getByTestId('waitlist-invite').className).toContain('max-[639px]:min-h-11');
      expect(screen.getByTestId('waitlist-remove').className).toContain('max-[639px]:min-h-11');
      fireEvent.click(screen.getByTestId('waitlist-remove'));
      expect(screen.getByTestId('waitlist-remove-confirm').className).toContain('max-[639px]:min-h-11');
      expect(screen.getByTestId('waitlist-remove-cancel').className).toContain('max-[639px]:min-h-11');
    });

    it('makes a row a two-line card in a narrow list, with the 60px minimum, by class', async () => {
      await renderLoaded();
      expect(screen.getByTestId('waitlist-row-42').className).toContain('@max-[540px]:min-h-[60px]');
    });
  });

  describe('invitations and removal', () => {
    async function openFirst(entries = [FIRST]) {
      await renderLoaded(entries);
      fireEvent.click(screen.getByTestId(`waitlist-row-${entries[0].id}`));
    }

    it('offers Invite for an address never invited', async () => {
      await openFirst();
      expect(screen.getByTestId('waitlist-invite')).toHaveTextContent('Invite');
      expect(screen.getByTestId('waitlist-invite')).not.toHaveTextContent('again');
    });

    it('shows "Invited <date> · expires <date>" and offers Invite again for an invited address', async () => {
      await openFirst([INVITED]);
      expect(screen.getByTestId('waitlist-invited')).toHaveTextContent(/Invited 9 Oct 2026 · expires 16 Oct 2026/);
      expect(screen.getByTestId('waitlist-invite')).toHaveTextContent('Invite again');
    });

    it('Invite calls the API for that entry, then re-reads so the dates come from the server', async () => {
      const user = userEvent.setup();
      getWaitlist.mockResolvedValueOnce([FIRST]).mockResolvedValueOnce([INVITED]);
      inviteWaitlistEntry.mockResolvedValue({ message: 'Invitation sent' });
      renderView();
      await user.click(await screen.findByTestId('waitlist-row-42'));

      await user.click(screen.getByTestId('waitlist-invite'));

      await waitFor(() => expect(inviteWaitlistEntry).toHaveBeenCalledWith(42));
      await waitFor(() => expect(screen.getByTestId('waitlist-invited')).toHaveTextContent('expires 16 Oct 2026'));
      expect(screen.getByTestId('waitlist-notice')).toHaveTextContent('Invitation sent.');
      expect(screen.getByTestId('waitlist-invite')).toHaveTextContent('Invite again');
    });

    it('disables the buttons while the request is in flight', async () => {
      let release;
      inviteWaitlistEntry.mockReturnValue(new Promise((resolve) => { release = resolve; }));
      await openFirst();

      fireEvent.click(screen.getByTestId('waitlist-invite'));

      expect(screen.getByTestId('waitlist-invite')).toBeDisabled();
      expect(screen.getByTestId('waitlist-remove')).toBeDisabled();
      getWaitlist.mockResolvedValue([FIRST]);
      await act(async () => { release({}); });
      expect(screen.getByTestId('waitlist-invite')).not.toBeDisabled();
    });

    it('a refused invitation shows the server sentence as an alert and changes nothing', async () => {
      inviteWaitlistEntry.mockRejectedValue({ response: { status: 400, data: { error: 'Not ASCII' } } });
      await openFirst();

      fireEvent.click(screen.getByTestId('waitlist-invite'));

      const alert = await screen.findByTestId('waitlist-action-error');
      expect(alert).toHaveTextContent('Not ASCII');
      expect(alert).toHaveAttribute('role', 'alert');
      expect(screen.getByTestId('waitlist-invited')).toHaveTextContent('Not invited');
      expect(getWaitlist).toHaveBeenCalledTimes(1);
      expect(screen.queryByTestId('waitlist-notice')).not.toBeInTheDocument();
    });

    it('a failed invitation without a server sentence says so in plain words', async () => {
      inviteWaitlistEntry.mockRejectedValue(new Error('boom'));
      await openFirst();
      fireEvent.click(screen.getByTestId('waitlist-invite'));
      expect(await screen.findByTestId('waitlist-action-error')).toHaveTextContent("Couldn't send the invitation");
    });

    it('still says the invitation went out when only the re-read fails', async () => {
      inviteWaitlistEntry.mockResolvedValue({});
      await openFirst();
      getWaitlist.mockRejectedValue(new Error('flaky'));

      fireEvent.click(screen.getByTestId('waitlist-invite'));

      expect(await screen.findByTestId('waitlist-notice')).toHaveTextContent('Invitation sent.');
      expect(screen.queryByTestId('waitlist-action-error')).not.toBeInTheDocument();
    });

    it('clears an earlier error when the invitation is retried', async () => {
      inviteWaitlistEntry.mockRejectedValueOnce(new Error('boom')).mockResolvedValueOnce({});
      await openFirst();
      fireEvent.click(screen.getByTestId('waitlist-invite'));
      await screen.findByTestId('waitlist-action-error');

      getWaitlist.mockResolvedValue([INVITED]);
      fireEvent.click(screen.getByTestId('waitlist-invite'));

      await screen.findByTestId('waitlist-notice');
      expect(screen.queryByTestId('waitlist-action-error')).not.toBeInTheDocument();
    });

    it('Remove asks first, and Keep backs out without calling the API', async () => {
      await openFirst();

      fireEvent.click(screen.getByTestId('waitlist-remove'));
      expect(removeWaitlistEntry).not.toHaveBeenCalled();
      expect(screen.getByTestId('waitlist-remove-confirm')).toHaveTextContent('Remove for good');
      fireEvent.click(screen.getByTestId('waitlist-remove-cancel'));

      expect(screen.getByTestId('waitlist-remove')).toBeInTheDocument();
      expect(removeWaitlistEntry).not.toHaveBeenCalled();
    });

    it('confirming Remove deletes that entry, closes the panel, clears the hash and updates the count', async () => {
      removeWaitlistEntry.mockResolvedValue(undefined);
      const { onCountChange } = await renderLoaded([FIRST, SECOND]);
      fireEvent.click(screen.getByTestId('waitlist-row-42'));
      fireEvent.click(screen.getByTestId('waitlist-remove'));

      fireEvent.click(screen.getByTestId('waitlist-remove-confirm'));

      await waitFor(() => expect(removeWaitlistEntry).toHaveBeenCalledWith(42));
      await waitFor(() => expect(screen.queryByTestId('waitlist-row-42')).not.toBeInTheDocument());
      expect(screen.queryByTestId('waitlist-panel-shell')).not.toBeInTheDocument();
      expect(window.location.hash).toBe('#manage/waitlist');
      expect(onCountChange).toHaveBeenLastCalledWith(1);
      // The one left is now first in the queue.
      expect(screen.getByTestId('waitlist-cell-position-99')).toHaveTextContent('1');
    });

    it('removing the last entry shows the empty state', async () => {
      removeWaitlistEntry.mockResolvedValue(undefined);
      const { onCountChange } = await renderLoaded([FIRST]);
      fireEvent.click(screen.getByTestId('waitlist-row-42'));
      fireEvent.click(screen.getByTestId('waitlist-remove'));
      fireEvent.click(screen.getByTestId('waitlist-remove-confirm'));

      expect(await screen.findByTestId('waitlist-empty')).toBeInTheDocument();
      expect(onCountChange).toHaveBeenLastCalledWith(0);
    });

    it('a failed Remove keeps the entry, keeps the panel and says so', async () => {
      removeWaitlistEntry.mockRejectedValue(new Error('boom'));
      await openFirst();
      fireEvent.click(screen.getByTestId('waitlist-remove'));

      fireEvent.click(screen.getByTestId('waitlist-remove-confirm'));

      expect(await screen.findByTestId('waitlist-action-error')).toHaveTextContent("Couldn't remove");
      expect(screen.getByTestId('waitlist-row-42')).toBeInTheDocument();
      expect(screen.getByTestId('waitlist-panel-shell')).toBeInTheDocument();
      expect(screen.getByTestId('waitlist-remove-confirm')).not.toBeDisabled();
    });

    it('does not carry a notice or a confirm from one entry to the next', async () => {
      inviteWaitlistEntry.mockResolvedValue({});
      await renderLoaded([FIRST, SECOND]);
      fireEvent.click(screen.getByTestId('waitlist-row-42'));
      getWaitlist.mockResolvedValue([FIRST, SECOND]);
      fireEvent.click(screen.getByTestId('waitlist-invite'));
      await screen.findByTestId('waitlist-notice');
      fireEvent.click(screen.getByTestId('waitlist-remove'));

      fireEvent.click(screen.getByTestId('waitlist-row-99'));

      expect(screen.queryByTestId('waitlist-notice')).not.toBeInTheDocument();
      expect(screen.getByTestId('waitlist-remove')).toBeInTheDocument();
      expect(screen.queryByTestId('waitlist-remove-confirm')).not.toBeInTheDocument();
    });
  });

  describe('Esc and the two-step Remove', () => {
    it('Esc while the confirm shows cancels the confirm; a second Esc closes the panel', async () => {
      await renderLoaded();
      fireEvent.click(screen.getByTestId('waitlist-row-42'));
      fireEvent.click(screen.getByTestId('waitlist-remove'));

      fireEvent.keyDown(document.body, { key: 'Escape' });

      expect(screen.queryByTestId('waitlist-remove-confirm')).not.toBeInTheDocument();
      expect(screen.getByTestId('waitlist-panel-shell')).toBeInTheDocument();
      expect(removeWaitlistEntry).not.toHaveBeenCalled();
      fireEvent.keyDown(document.body, { key: 'Escape' });
      expect(screen.queryByTestId('waitlist-panel-shell')).not.toBeInTheDocument();
    });

    it('takes the Esc even though a dialog is mounted, hidden, in the Map pane', async () => {
      await renderLoaded();
      fireEvent.click(screen.getByTestId('waitlist-row-42'));
      const pane = document.createElement('div');
      pane.hidden = true;
      const dialog = document.createElement('div');
      dialog.setAttribute('role', 'dialog');
      pane.appendChild(dialog);
      document.body.appendChild(pane);

      fireEvent.keyDown(document.body, { key: 'Escape' });
      pane.remove();

      expect(screen.queryByTestId('waitlist-panel-shell')).not.toBeInTheDocument();
    });
  });

  describe('focus', () => {
    it('moves focus to the panel title on open', async () => {
      await renderLoaded();
      fireEvent.click(screen.getByTestId('waitlist-row-42'));
      expect(screen.getByTestId('waitlist-panel-shell-title')).toHaveFocus();
    });

    it('returns focus to the row after the close button, and after Esc', async () => {
      await renderLoaded();
      fireEvent.click(screen.getByTestId('waitlist-row-42'));
      fireEvent.click(screen.getByTestId('waitlist-panel-shell-close'));
      expect(screen.getByTestId('waitlist-row-42')).toHaveFocus();

      fireEvent.click(screen.getByTestId('waitlist-row-99'));
      fireEvent.keyDown(document.activeElement, { key: 'Escape' });
      expect(screen.getByTestId('waitlist-row-99')).toHaveFocus();
    });

    it('moves focus into the confirm step, and back to Remove on Keep', async () => {
      await renderLoaded();
      fireEvent.click(screen.getByTestId('waitlist-row-42'));

      fireEvent.click(screen.getByTestId('waitlist-remove'));
      expect(screen.getByTestId('waitlist-remove-confirm')).toHaveFocus();

      fireEvent.click(screen.getByTestId('waitlist-remove-cancel'));
      expect(screen.getByTestId('waitlist-remove')).toHaveFocus();
    });

    it('puts focus on the search box after a Remove, because the entry has no row left', async () => {
      removeWaitlistEntry.mockResolvedValue(undefined);
      await renderLoaded([FIRST, SECOND]);
      fireEvent.click(screen.getByTestId('waitlist-row-42'));
      fireEvent.click(screen.getByTestId('waitlist-remove'));
      fireEvent.click(screen.getByTestId('waitlist-remove-confirm'));

      await waitFor(() => expect(screen.queryByTestId('waitlist-row-42')).not.toBeInTheDocument());
      expect(screen.getByTestId('waitlist-search')).toHaveFocus();
    });
  });

  describe('a removal racing a re-read', () => {
    it('does not bring a removed entry back when an invite re-read, already in flight, lands after it', async () => {
      let landRead;
      inviteWaitlistEntry.mockResolvedValue({});
      removeWaitlistEntry.mockResolvedValue(undefined);
      const { onCountChange } = await renderLoaded([FIRST, SECOND]);
      getWaitlist.mockReturnValue(new Promise((resolve) => { landRead = resolve; }));

      fireEvent.click(screen.getByTestId('waitlist-row-42'));
      fireEvent.click(screen.getByTestId('waitlist-invite'));
      await waitFor(() => expect(getWaitlist).toHaveBeenCalledTimes(2));
      // While that re-read is out, remove the other entry.
      fireEvent.click(screen.getByTestId('waitlist-row-99'));
      fireEvent.click(screen.getByTestId('waitlist-remove'));
      fireEvent.click(screen.getByTestId('waitlist-remove-confirm'));
      await waitFor(() => expect(screen.queryByTestId('waitlist-row-99')).not.toBeInTheDocument());

      // The server's answer was read before the delete, so it still lists the removed entry.
      await act(async () => { landRead([INVITED, SECOND]); });

      expect(screen.queryByTestId('waitlist-row-99')).not.toBeInTheDocument();
      expect(screen.getByTestId('waitlist-row-42')).toBeInTheDocument();
      expect(onCountChange).toHaveBeenLastCalledWith(1);
      expect(screen.getByTestId('waitlist-total')).toHaveTextContent('1 · oldest first');
    });

    it('reports the count after an invite re-read', async () => {
      inviteWaitlistEntry.mockResolvedValue({});
      const { onCountChange } = await renderLoaded([FIRST, SECOND]);
      getWaitlist.mockResolvedValue([INVITED, SECOND, THIRD]);

      fireEvent.click(screen.getByTestId('waitlist-row-42'));
      fireEvent.click(screen.getByTestId('waitlist-invite'));

      await waitFor(() => expect(onCountChange).toHaveBeenLastCalledWith(3));
    });
  });

  describe('the address when the selection vanishes', () => {
    it('replaces, not adds, the history entry when the selected entry is removed', async () => {
      removeWaitlistEntry.mockResolvedValue(undefined);
      const replace = vi.spyOn(window.history, 'replaceState');
      await renderLoaded([FIRST, SECOND]);
      fireEvent.click(screen.getByTestId('waitlist-row-42'));
      expect(window.location.hash).toBe('#manage/waitlist/42');
      fireEvent.click(screen.getByTestId('waitlist-remove'));
      fireEvent.click(screen.getByTestId('waitlist-remove-confirm'));

      await waitFor(() => expect(window.location.hash).toBe('#manage/waitlist'));
      expect(replace.mock.calls.at(-1).slice(1)).toEqual(['', '#manage/waitlist']);
      replace.mockRestore();
    });

    it('replaces the address when a pasted id is not on the list', async () => {
      window.location.hash = '#manage/waitlist/12345';
      const replace = vi.spyOn(window.history, 'replaceState');
      await renderLoaded();
      await waitFor(() => expect(window.location.hash).toBe('#manage/waitlist'));
      expect(replace.mock.calls.at(-1).slice(1)).toEqual(['', '#manage/waitlist']);
      replace.mockRestore();
    });

    it('leaves the address alone when the list failed to load, because nothing is known about the id', async () => {
      window.location.hash = '#manage/waitlist/12345';
      getWaitlist.mockRejectedValue(new Error('down'));
      renderView();
      await screen.findByTestId('waitlist-load-error');
      expect(window.location.hash).toBe('#manage/waitlist/12345');
    });

    it('still adds a history entry when the reader closes a panel that is open', async () => {
      const replace = vi.spyOn(window.history, 'replaceState');
      await renderLoaded();
      fireEvent.click(screen.getByTestId('waitlist-row-42'));
      fireEvent.click(screen.getByTestId('waitlist-panel-shell-close'));
      expect(replace).not.toHaveBeenCalled();
      replace.mockRestore();
    });
  });

  describe('loading and load-error insets', () => {
    it('carry no padding of their own, so they sit where the loaded list does', async () => {
      getWaitlist.mockReturnValue(new Promise(() => {}));
      const { unmount } = render(<WaitlistManagementView onCountChange={vi.fn()} />);
      expect(screen.getByText('Loading waitlist...').className).not.toMatch(/\bp-\d/);
      unmount();
      getWaitlist.mockRejectedValue(new Error('x'));
      renderView();
      expect((await screen.findByTestId('waitlist-load-error')).className).not.toMatch(/\bp-\d/);
    });
  });
});
