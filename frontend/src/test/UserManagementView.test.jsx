import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import {
  getUsers, createUser, resetUserPassword, updateUserEmail, updateUserRole, updateUserEnabled, deleteUser,
  resendVerification, getUserSubRegions, saveUserSubRegions, clearUserSubRegionLock,
} from '../api/userApi.js';
import UserManagementView from '../components/UserManagementView.jsx';

vi.mock('../api/userApi.js', () => ({
  getUsers: vi.fn(),
  createUser: vi.fn(),
  resetUserPassword: vi.fn(),
  updateUserEmail: vi.fn(),
  updateUserRole: vi.fn(),
  updateUserEnabled: vi.fn(),
  deleteUser: vi.fn(),
  resendVerification: vi.fn(),
  getUserSubRegions: vi.fn(),
  saveUserSubRegions: vi.fn(),
  clearUserSubRegionLock: vi.fn(),
}));

const OPTIONS = [
  {
    regionId: 1,
    regionName: 'Northumberland & Tyneside',
    subRegions: [
      { areaId: 10, name: 'Berwick & Holy Island', locationCount: 12, exampleLocations: ['Holy Island Castle', 'Lindisfarne Point'] },
      { areaId: 11, name: 'Bamburgh to Howick', locationCount: 13, exampleLocations: ['Bamburgh Castle'] },
      { areaId: 12, name: 'Alnwick & the Coquet Coast', locationCount: 7, exampleLocations: ['Alnmouth'] },
    ],
  },
  {
    regionId: 2,
    regionName: 'The Lake District',
    subRegions: [
      { areaId: 20, name: 'Keswick & Borrowdale', locationCount: 10, exampleLocations: ['Castlerigg'] },
    ],
  },
];

const PICKS = [
  { areaId: 10, name: 'Berwick & Holy Island', regionName: 'Northumberland & Tyneside' },
  { areaId: 20, name: 'Keswick & Borrowdale', regionName: 'The Lake District' },
];

function makeUser(overrides = {}) {
  return {
    id: 1,
    username: 'tom_h',
    email: 'tom@example.com',
    role: 'LITE_USER',
    enabled: true,
    createdAt: '2026-10-19T09:00:00',
    lastActiveAt: '',
    termsAcceptedAt: '2026-10-19T09:00:00Z',
    termsVersion: 'April 2026',
    homePostcode: 'NE61 1AA',
    marketingEmailOptIn: false,
    subRegions: ['Berwick & Holy Island', 'Keswick & Borrowdale'],
    subRegionStatus: 'OPEN',
    freeUntil: null,
    nextChangeOn: null,
    overLimit: false,
    awaitingVerification: false,
    verificationSentAt: null,
    ...overrides,
  };
}

function makeState(overrides = {}) {
  return {
    enforced: true,
    limit: 3,
    picks: PICKS,
    options: OPTIONS,
    suggested: [],
    status: 'OPEN',
    ...overrides,
  };
}

/** The seven readers most tests need, each a different standing. */
function fixtureUsers() {
  return [
    makeUser({ id: 1, username: 'anna_r', email: 'anna.r@example.com', subRegionStatus: 'OPEN' }),
    makeUser({
      id: 2, username: 'bea.holy', email: 'bea@example.com', subRegionStatus: 'LOCKED', nextChangeOn: '2026-11-14',
      subRegions: ['Berwick & Holy Island', 'Bamburgh to Howick'],
    }),
    makeUser({
      id: 3, username: 'fellwalker', email: 'j.bird@example.co.uk', subRegionStatus: 'FREE_UNTIL', freeUntil: '2026-11-02',
      subRegions: ['The Langdales'],
    }),
    makeUser({
      id: 4, username: 'hollie.w', email: 'hollie@example.com', enabled: false, awaitingVerification: true,
      subRegions: [], subRegionStatus: 'FIRST_CHOICE', verificationSentAt: '2026-10-27T08:02:00Z',
    }),
    makeUser({
      id: 5, username: 'mkphoto', email: 'mk@example.com', role: 'PRO_USER', subRegionStatus: 'NOT_APPLICABLE',
      subRegions: ['Berwick & Holy Island'],
    }),
    makeUser({
      id: 6, username: 'ops_claire', email: 'claire@photocast.online', role: 'ADMIN', subRegionStatus: 'NOT_APPLICABLE',
      subRegions: [],
    }),
    makeUser({
      id: 7, username: 'stormchaser', email: 'k.lee@example.com', enabled: false, subRegionStatus: 'OPEN',
      subRegions: ['Sunderland & the Durham Coast'],
    }),
  ];
}

function rowFor(username) {
  return screen.getByRole('button', { name: new RegExp(`^${username.replace('.', '\\.')}\\S*@`) });
}

async function renderList(users = fixtureUsers()) {
  getUsers.mockResolvedValue(users);
  render(<UserManagementView />);
  await screen.findByTestId('users-table');
}

async function openReader(username, state = makeState()) {
  getUserSubRegions.mockResolvedValue(state);
  fireEvent.click(rowFor(username));
  return screen.findByTestId('user-reader-panel');
}

describe('UserManagementView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.location.hash = '';
  });

  describe('the list', () => {
    it('counts the users, labels roles plainly and shows each reader in a row without buttons', async () => {
      await renderList();

      expect(screen.getByTestId('user-total')).toHaveTextContent('7');
      const row = rowFor('anna_r');
      expect(within(row).getByText('Lite')).toBeInTheDocument();
      expect(within(row).queryByText('LITE_USER')).not.toBeInTheDocument();
      expect(within(rowFor('mkphoto')).getByText('Pro')).toBeInTheDocument();
      expect(within(rowFor('ops_claire')).getByText('Admin')).toBeInTheDocument();
      // Dates read as the UK day, not ISO.
      expect(within(row).getByText('19 Oct 2026')).toBeInTheDocument();
      expect(within(row).getByText('Never')).toBeInTheDocument();
    });

    it('holds no control inside a row: no delete, reset, disable or status toggle until a reader is opened', async () => {
      await renderList();

      for (const name of ['Delete', 'Reset', 'Disable', 'Enable', 'Edit', 'Resend verification email']) {
        expect(screen.queryByRole('button', { name })).not.toBeInTheDocument();
      }
      for (const row of screen.getAllByRole('button', { name: /@/ })) {
        expect(within(row).queryAllByRole('button')).toHaveLength(0);
      }
      expect(screen.queryByTestId('toggle-user-enabled-1')).not.toBeInTheDocument();
    });

    it('writes the sub-regions cell from the summary: count, standing and names', async () => {
      await renderList();

      expect(within(rowFor('anna_r')).getByText(/can change/)).toBeInTheDocument();
      expect(within(rowFor('bea.holy')).getByText(/locked to 14 Nov/)).toBeInTheDocument();
      expect(within(rowFor('bea.holy')).getByText('Berwick & Holy Island, Bamburgh to Howick')).toBeInTheDocument();
      expect(within(rowFor('fellwalker')).getByText(/free to 2 Nov/)).toBeInTheDocument();
      expect(within(rowFor('hollie.w')).getByText('None chosen')).toBeInTheDocument();
      expect(within(rowFor('mkphoto')).getByText('Pro: every sub-region')).toBeInTheDocument();
      expect(within(rowFor('ops_claire')).getByText('Admin: every sub-region')).toBeInTheDocument();
    });

    it('tells awaiting verification from disabled in the status column', async () => {
      await renderList();

      expect(screen.getByTestId('user-status-4')).toHaveTextContent('Awaiting verification');
      expect(screen.getByTestId('user-status-7')).toHaveTextContent('Disabled');
      expect(screen.getByTestId('user-status-1')).toHaveTextContent('Active');
    });

    it('searches the username OR the email', async () => {
      await renderList();
      const search = screen.getByRole('searchbox', { name: 'Search username or email' });

      fireEvent.change(search, { target: { value: 'bird' } });
      expect(screen.getByRole('button', { name: /fellwalker/ })).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /anna_r/ })).not.toBeInTheDocument();

      fireEvent.change(search, { target: { value: 'ANNA' } });
      expect(screen.getByRole('button', { name: /anna_r/ })).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /fellwalker/ })).not.toBeInTheDocument();

      fireEvent.change(search, { target: { value: 'photocast.online' } });
      expect(screen.getByRole('button', { name: /ops_claire/ })).toBeInTheDocument();
      expect(screen.getAllByRole('button', { name: /@/ })).toHaveLength(1);
    });

    it('carries a count on every chip', async () => {
      await renderList();

      expect(screen.getByTestId('chip-role-ALL-count')).toHaveTextContent('7');
      expect(screen.getByTestId('chip-role-LITE_USER-count')).toHaveTextContent('5');
      expect(screen.getByTestId('chip-role-PRO_USER-count')).toHaveTextContent('1');
      expect(screen.getByTestId('chip-role-ADMIN-count')).toHaveTextContent('1');
      expect(screen.getByTestId('chip-awaiting-count')).toHaveTextContent('1');
      expect(screen.getByTestId('chip-disabled-count')).toHaveTextContent('1');
      expect(screen.getByTestId('chip-noSubRegions-count')).toHaveTextContent('1');
      expect(screen.getByTestId('chip-locked-count')).toHaveTextContent('1');
      expect(screen.getByTestId('chip-overLimit-count')).toHaveTextContent('0');
    });

    it('treats the role chips as single-select', async () => {
      await renderList();

      fireEvent.click(screen.getByTestId('chip-role-PRO_USER'));
      expect(screen.getAllByRole('button', { name: /@/ })).toHaveLength(1);
      expect(screen.getByTestId('chip-role-PRO_USER')).toHaveAttribute('aria-pressed', 'true');

      fireEvent.click(screen.getByTestId('chip-role-ADMIN'));
      expect(screen.getByTestId('chip-role-PRO_USER')).toHaveAttribute('aria-pressed', 'false');
      expect(screen.getAllByRole('button', { name: /@/ })).toHaveLength(1);
      expect(screen.getByRole('button', { name: /ops_claire/ })).toBeInTheDocument();

      fireEvent.click(screen.getByTestId('chip-role-ALL'));
      expect(screen.getAllByRole('button', { name: /@/ })).toHaveLength(7);
    });

    it('ANDs the toggle chips with the role', async () => {
      await renderList();

      fireEvent.click(screen.getByTestId('chip-disabled'));
      expect(screen.getAllByRole('button', { name: /@/ })).toHaveLength(1);
      expect(screen.getByRole('button', { name: /stormchaser/ })).toBeInTheDocument();

      fireEvent.click(screen.getByTestId('chip-awaiting'));
      // Disabled AND awaiting verification: nobody is both.
      expect(screen.getByText('No users match.')).toBeInTheDocument();

      fireEvent.click(screen.getByTestId('chip-disabled'));
      expect(screen.getByRole('button', { name: /hollie\.w/ })).toBeInTheDocument();

      fireEvent.click(screen.getByTestId('chip-role-PRO_USER'));
      expect(screen.getByText('No users match.')).toBeInTheDocument();
    });

    it('filters on locked, no sub-regions and over limit', async () => {
      const users = fixtureUsers();
      users[0] = makeUser({ id: 1, username: 'anna_r', email: 'anna.r@example.com', overLimit: true });
      await renderList(users);

      fireEvent.click(screen.getByTestId('chip-locked'));
      expect(screen.getByRole('button', { name: /bea\.holy/ })).toBeInTheDocument();
      expect(screen.getAllByRole('button', { name: /@/ })).toHaveLength(1);
      fireEvent.click(screen.getByTestId('chip-locked'));

      fireEvent.click(screen.getByTestId('chip-noSubRegions'));
      expect(screen.getByRole('button', { name: /hollie\.w/ })).toBeInTheDocument();
      expect(screen.getAllByRole('button', { name: /@/ })).toHaveLength(1);
      fireEvent.click(screen.getByTestId('chip-noSubRegions'));

      fireEvent.click(screen.getByTestId('chip-overLimit'));
      expect(screen.getByRole('button', { name: /anna_r/ })).toBeInTheDocument();
      expect(screen.getAllByRole('button', { name: /@/ })).toHaveLength(1);
      expect(within(rowFor('anna_r')).getByText(/over limit/)).toBeInTheDocument();
    });

    it('says "No users match." with a Clear filters button that restores the list', async () => {
      await renderList();

      fireEvent.change(screen.getByRole('searchbox', { name: 'Search username or email' }), { target: { value: 'zzz' } });
      expect(screen.getByText('No users match.')).toBeInTheDocument();

      fireEvent.click(screen.getByRole('button', { name: 'Clear filters' }));
      expect(screen.getAllByRole('button', { name: /@/ })).toHaveLength(7);
      expect(screen.getByRole('searchbox', { name: 'Search username or email' })).toHaveValue('');
    });

    it('sorts by a header, username ascending by default, and drops the spacer rows', async () => {
      await renderList();

      const names = () => screen.getAllByRole('button', { name: /@/ }).map((r) => within(r).getAllByText(/./)[0].textContent);
      expect(names()[0]).toBe('anna_r');
      expect(names()[6]).toBe('stormchaser');

      fireEvent.click(screen.getByRole('button', { name: /^User/ }));
      expect(names()[0]).toBe('stormchaser');
      expect(document.querySelectorAll('[aria-hidden="true"] td')).toHaveLength(0);
    });

    it('paginates when there are more users than a page', async () => {
      const many = Array.from({ length: 15 }, (_, i) => makeUser({
        id: i + 1, username: `user${String(i + 1).padStart(2, '0')}`, email: `user${i + 1}@example.com`,
      }));
      await renderList(many);

      expect(screen.getByTestId('pagination-summary')).toHaveTextContent('Showing 1-10 of 15');
      expect(screen.queryByRole('button', { name: /user11/ })).not.toBeInTheDocument();
      fireEvent.click(screen.getByTestId('pagination-next'));
      expect(screen.getByRole('button', { name: /user11/ })).toBeInTheDocument();
    });

    it('survives a user summary that carries none of the newer fields', async () => {
      await renderList([{
        id: 1, username: 'old', email: 'old@example.com', role: 'LITE_USER', enabled: true,
        createdAt: '2026-01-01T00:00:00', lastActiveAt: null, termsAcceptedAt: null, termsVersion: null,
        homePostcode: null, marketingEmailOptIn: false,
      }]);

      expect(within(rowFor('old')).getByText('None chosen')).toBeInTheDocument();
      expect(screen.getByTestId('user-status-1')).toHaveTextContent('Active');
    });
  });

  describe('opening a reader', () => {
    it('opens the panel on a click, narrows the list and puts the id in the hash', async () => {
      await renderList();
      expect(screen.queryByTestId('user-panel')).not.toBeInTheDocument();

      const panel = await openReader('anna_r');

      expect(within(panel).getByRole('heading', { name: 'anna_r' })).toBeInTheDocument();
      expect(screen.getByTestId('user-panel-subtitle')).toHaveTextContent('Lite · Active · joined 19 Oct 2026');
      expect(window.location.hash).toBe('#manage/users/1');
      expect(rowFor('anna_r')).toHaveAttribute('aria-current', 'true');
      // The list is now User, Sub-regions, Role only.
      expect(screen.queryByRole('columnheader', { name: /Status/ })).not.toBeInTheDocument();
      expect(screen.queryByRole('columnheader', { name: /Joined/ })).not.toBeInTheDocument();
      expect(screen.getByRole('columnheader', { name: /Role/ })).toBeInTheDocument();
    });

    it('opens the panel on Enter, and on Space', async () => {
      await renderList();
      getUserSubRegions.mockResolvedValue(makeState());

      fireEvent.keyDown(rowFor('bea.holy'), { key: 'Enter' });
      expect(await screen.findByRole('heading', { name: 'bea.holy' })).toBeInTheDocument();

      fireEvent.keyDown(rowFor('fellwalker'), { key: ' ' });
      expect(await screen.findByRole('heading', { name: 'fellwalker' })).toBeInTheDocument();
      expect(window.location.hash).toBe('#manage/users/3');
    });

    it('closes on the × and on Esc, and takes the id out of the hash', async () => {
      await renderList();
      await openReader('anna_r');

      fireEvent.click(screen.getByRole('button', { name: 'Close panel' }));
      expect(screen.queryByTestId('user-panel')).not.toBeInTheDocument();
      expect(window.location.hash).toBe('#manage/users');

      await openReader('anna_r');
      fireEvent.keyDown(document, { key: 'Escape' });
      expect(screen.queryByTestId('user-panel')).not.toBeInTheDocument();
      expect(window.location.hash).toBe('#manage/users');
    });

    it('leaves Esc to an open dialog', async () => {
      await renderList();
      await openReader('anna_r');
      fireEvent.click(await screen.findByTestId('delete-user-1'));
      expect(screen.getByRole('dialog', { name: 'Delete anna_r?' })).toBeInTheDocument();

      fireEvent.keyDown(document, { key: 'Escape' });

      expect(screen.getByTestId('user-panel')).toBeInTheDocument();
    });

    it('opens the reader named in the hash when the page loads, and follows hash changes after', async () => {
      window.location.hash = '#manage/users/3';
      getUserSubRegions.mockResolvedValue(makeState());
      await renderList();

      expect(await screen.findByRole('heading', { name: 'fellwalker' })).toBeInTheDocument();

      window.location.hash = '#manage/users/2';
      fireEvent(window, new HashChangeEvent('hashchange'));
      expect(await screen.findByRole('heading', { name: 'bea.holy' })).toBeInTheDocument();

      window.location.hash = '#manage/users';
      fireEvent(window, new HashChangeEvent('hashchange'));
      await waitFor(() => expect(screen.queryByTestId('user-panel')).not.toBeInTheDocument());
    });

    it('ignores a hash naming nobody', async () => {
      window.location.hash = '#manage/users/999';
      await renderList();

      expect(screen.queryByTestId('user-panel')).not.toBeInTheDocument();
    });
  });

  describe('the reader panel', () => {
    it('shows the account, preferences and legal facts', async () => {
      await renderList();
      const panel = await openReader('anna_r');

      expect(within(panel).getByText('anna.r@example.com')).toBeInTheDocument();
      expect(screen.getByTestId('home-postcode-1')).toHaveTextContent('NE61 1AA');
      expect(screen.getByTestId('marketing-emails-1')).toHaveTextContent('No');
      expect(screen.getByTestId('terms-accepted-1')).toHaveTextContent('Accepted 19 Oct 2026 · April 2026');
    });

    it('says "Not set" and "Not accepted" for the empty preferences', async () => {
      await renderList([makeUser({ homePostcode: null, termsAcceptedAt: null, termsVersion: null, marketingEmailOptIn: true })]);
      await openReader('tom_h');

      expect(screen.getByTestId('home-postcode-1')).toHaveTextContent('Not set');
      expect(screen.getByTestId('terms-accepted-1')).toHaveTextContent('Not accepted');
      expect(screen.getByTestId('marketing-emails-1')).toHaveTextContent('Yes');
    });

    it('groups the picks by region with location counts and says how many of the limit', async () => {
      await renderList();
      await openReader('anna_r');

      const picks = await screen.findByTestId('panel-picks');
      expect(within(picks).getByText('Northumberland & Tyneside')).toBeInTheDocument();
      expect(within(picks).getByText('The Lake District')).toBeInTheDocument();
      expect(within(picks).getByText('Berwick & Holy Island')).toBeInTheDocument();
      expect(within(picks).getByText('12')).toBeInTheDocument();
      expect(screen.getByTestId('panel-pick-count')).toHaveTextContent('2 of 3');
      expect(getUserSubRegions).toHaveBeenCalledWith(1);
    });

    it('words each standing from the admin GET', async () => {
      await renderList();

      await openReader('bea.holy', makeState({ status: 'LOCKED', nextChangeOn: '2026-11-25' }));
      expect(await screen.findByText('Locked until 25 November')).toBeInTheDocument();
      expect(screen.getByText('They can next swap a pick on 25 November.')).toBeInTheDocument();

      await openReader('fellwalker', makeState({ status: 'FREE_UNTIL', freeUntil: '2026-11-02' }));
      expect(await screen.findByText('Free changes until 2 November')).toBeInTheDocument();

      await openReader('anna_r', makeState({ status: 'OPEN' }));
      expect(await screen.findByText('Can change now')).toBeInTheDocument();

      await openReader('stormchaser', makeState({ status: 'FIRST_CHOICE', picks: [] }));
      expect(await screen.findByText('None chosen yet')).toBeInTheDocument();
      expect(screen.getByText("They'll be asked to choose at their next sign-in.")).toBeInTheDocument();
    });

    it('says what a reader over a lowered limit holds', async () => {
      await renderList();
      await openReader('anna_r', makeState({
        limit: 1, overLimit: true, picks: PICKS, status: 'OPEN',
      }));

      expect(await screen.findByText('Holds 2, the limit is now 1. They keep them until they next change.')).toBeInTheDocument();
    });

    it('shows the dashed filtering-off note only while enforcement is off', async () => {
      await renderList();

      await openReader('anna_r', makeState({ enforced: false }));
      expect(await screen.findByTestId('filtering-off-note')).toHaveTextContent(
        "Filtering is off. Picks are saved but don't limit this reader's ratings yet.",
      );

      fireEvent.click(screen.getByRole('button', { name: 'Close panel' }));
      await openReader('bea.holy', makeState({ enforced: true }));
      await screen.findByTestId('panel-standing');
      expect(screen.queryByTestId('filtering-off-note')).not.toBeInTheDocument();
    });

    it('shows a Pro reader the line that covers everything and the picks kept from Lite, with no pick controls', async () => {
      await renderList();
      await openReader('mkphoto', makeState({ status: 'NOT_APPLICABLE', options: [], picks: [PICKS[0]] }));

      expect(await screen.findByText('Pro covers every sub-region.')).toBeInTheDocument();
      const kept = screen.getByTestId('panel-kept-picks');
      expect(within(kept).getByText('Kept from Lite, used again if they return to Lite')).toBeInTheDocument();
      expect(within(kept).getByText('Berwick & Holy Island')).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Change picks' })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Clear lock' })).not.toBeInTheDocument();
    });

    it('shows a Pro reader with no kept picks only the covering line', async () => {
      await renderList();
      await openReader('mkphoto', makeState({ status: 'NOT_APPLICABLE', options: [], picks: [] }));

      expect(await screen.findByText('Pro covers every sub-region.')).toBeInTheDocument();
      expect(screen.queryByTestId('panel-kept-picks')).not.toBeInTheDocument();
    });

    it('offers Clear lock only while locked', async () => {
      await renderList();

      await openReader('anna_r', makeState({ status: 'OPEN' }));
      await screen.findByRole('button', { name: 'Change picks' });
      expect(screen.queryByRole('button', { name: 'Clear lock' })).not.toBeInTheDocument();

      fireEvent.click(screen.getByRole('button', { name: 'Close panel' }));
      await openReader('bea.holy', makeState({ status: 'LOCKED', nextChangeOn: '2026-11-25' }));
      expect(await screen.findByRole('button', { name: 'Clear lock' })).toBeInTheDocument();
    });
  });

  describe('awaiting verification', () => {
    it('puts the amber box on top with Resend, and hides Reset and Disable', async () => {
      await renderList();
      await openReader('hollie.w', makeState({ status: 'FIRST_CHOICE', picks: [] }));

      const box = screen.getByTestId('awaiting-verification');
      expect(box).toHaveTextContent("Hasn't confirmed their email. The link was sent");
      expect(screen.getByTestId('user-panel-subtitle')).toHaveTextContent('Lite · Awaiting verification');
      expect(screen.queryByRole('button', { name: 'Reset' })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Disable' })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Enable' })).not.toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Delete' })).toBeInTheDocument();
      expect(await screen.findByText('They choose during sign-up, after verifying.')).toBeInTheDocument();
    });

    it('resends the verification email', async () => {
      resendVerification.mockResolvedValue({ message: 'Verification email sent' });
      await renderList();
      await openReader('hollie.w', makeState({ status: 'FIRST_CHOICE', picks: [] }));

      fireEvent.click(screen.getByRole('button', { name: 'Resend verification email' }));

      await waitFor(() => expect(resendVerification).toHaveBeenCalledWith(4));
      expect(await screen.findByText('Verification email sent to hollie@example.com.')).toBeInTheDocument();
    });

    it('does not show the box for an account an admin switched off', async () => {
      await renderList();
      await openReader('stormchaser');

      expect(screen.queryByTestId('awaiting-verification')).not.toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Enable' })).toBeInTheDocument();
    });
  });

  describe('account actions and their dialogs', () => {
    async function confirmDialog(name) {
      const dialog = await screen.findByRole('dialog', { name });
      return dialog;
    }

    it('Reset password: asks, then shows the temporary password', async () => {
      resetUserPassword.mockResolvedValue({ temporaryPassword: 'Temp-1234' });
      await renderList();
      await openReader('anna_r');

      fireEvent.click(await screen.findByRole('button', { name: 'Reset' }));
      const dialog = await confirmDialog("Reset anna_r's password?");
      expect(within(dialog).getByText("We'll email a temporary password. They'll set a new one when they next sign in.")).toBeInTheDocument();
      fireEvent.click(within(dialog).getByRole('button', { name: 'Reset password' }));

      await waitFor(() => expect(resetUserPassword).toHaveBeenCalledWith(1));
      expect(await screen.findByTestId('temp-password-value')).toHaveTextContent('Temp-1234');
      fireEvent.click(screen.getByTestId('close-temp-password-modal'));
      expect(screen.queryByTestId('temp-password-modal')).not.toBeInTheDocument();
    });

    it('Disable: asks with the handoff copy, then disables and refetches', async () => {
      updateUserEnabled.mockResolvedValue({ message: 'Updated' });
      await renderList();
      await openReader('anna_r');

      fireEvent.click(await screen.findByRole('button', { name: 'Disable' }));
      const dialog = await confirmDialog('Disable anna_r?');
      expect(within(dialog).getByText("They won't be able to sign in until you enable the account again.")).toBeInTheDocument();
      fireEvent.click(within(dialog).getByRole('button', { name: 'Disable' }));

      await waitFor(() => expect(updateUserEnabled).toHaveBeenCalledWith(1, false));
      await waitFor(() => expect(getUsers).toHaveBeenCalledTimes(2));
    });

    it('Enable: acts straight away, with no dialog', async () => {
      updateUserEnabled.mockResolvedValue({ message: 'Updated' });
      await renderList();
      await openReader('stormchaser');

      fireEvent.click(await screen.findByRole('button', { name: 'Enable' }));

      await waitFor(() => expect(updateUserEnabled).toHaveBeenCalledWith(7, true));
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });

    it('Delete: asks with the destructive copy, then deletes, closes the panel and refetches', async () => {
      deleteUser.mockResolvedValue({ message: 'User deleted' });
      await renderList();
      await openReader('anna_r');

      fireEvent.click(await screen.findByRole('button', { name: 'Delete' }));
      const dialog = await confirmDialog('Delete anna_r?');
      expect(within(dialog).getByText('This removes the account for good and emails them to say so.')).toBeInTheDocument();
      fireEvent.click(within(dialog).getByRole('button', { name: 'Delete account' }));

      await waitFor(() => expect(deleteUser).toHaveBeenCalledWith(1));
      await waitFor(() => expect(screen.queryByTestId('user-panel')).not.toBeInTheDocument());
      expect(window.location.hash).toBe('#manage/users');
    });

    it('Delete: a refusal stays in the panel', async () => {
      deleteUser.mockRejectedValue({ response: { data: { error: 'You cannot delete your own account' } } });
      await renderList();
      await openReader('anna_r');

      fireEvent.click(await screen.findByRole('button', { name: 'Delete' }));
      fireEvent.click(within(await confirmDialog('Delete anna_r?')).getByRole('button', { name: 'Delete account' }));

      expect(await screen.findByText('You cannot delete your own account')).toBeInTheDocument();
      expect(screen.getByTestId('user-panel')).toBeInTheDocument();
    });

    it('Clear lock: asks with the handoff copy, then DELETEs the lock and refetches the reader', async () => {
      clearUserSubRegionLock.mockResolvedValue();
      await renderList();
      await openReader('bea.holy', makeState({ status: 'LOCKED', nextChangeOn: '2026-11-25' }));

      fireEvent.click(await screen.findByRole('button', { name: 'Clear lock' }));
      const dialog = await confirmDialog("Clear bea.holy's lock?");
      expect(within(dialog).getByText(
        "They'll be able to change their sub-regions straight away. Their next change starts a new 30-day lock.",
      )).toBeInTheDocument();
      getUserSubRegions.mockResolvedValue(makeState({ status: 'OPEN' }));
      fireEvent.click(within(dialog).getByRole('button', { name: 'Clear lock' }));

      await waitFor(() => expect(clearUserSubRegionLock).toHaveBeenCalledWith(2));
      expect(await screen.findByText('Can change now')).toBeInTheDocument();
      expect(await screen.findByText("Cleared bea.holy's lock.")).toBeInTheDocument();
    });

    it('Cancel in a dialog does nothing', async () => {
      await renderList();
      await openReader('anna_r');

      fireEvent.click(await screen.findByRole('button', { name: 'Delete' }));
      fireEvent.click(within(await confirmDialog('Delete anna_r?')).getByRole('button', { name: 'Cancel' }));

      expect(deleteUser).not.toHaveBeenCalled();
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });
  });

  describe('Change picks', () => {
    async function openPicks(username = 'bea.holy', state = makeState({ status: 'LOCKED', nextChangeOn: '2026-11-25' })) {
      await renderList();
      await openReader(username, state);
      fireEvent.click(await screen.findByRole('button', { name: 'Change picks' }));
      return screen.findByTestId('user-change-picks');
    }

    it('swaps the body for the picker at the reader limit, with the footer and buttons', async () => {
      const panel = await openPicks();

      expect(within(panel).getByRole('heading', { name: "Change bea.holy's picks" })).toBeInTheDocument();
      expect(screen.getByTestId('user-panel-subtitle')).toHaveTextContent('2 of 3 chosen');
      expect(within(panel).getByText('Saving also clears their lock, so they can change their picks again straight away.')).toBeInTheDocument();
      expect(within(panel).getByRole('button', { name: 'Clear all picks' })).toBeInTheDocument();
      expect(within(panel).getByRole('button', { name: 'Cancel' })).toBeInTheDocument();
      expect(within(panel).getByRole('button', { name: 'Save picks' })).toBeInTheDocument();
      expect(within(panel).getByRole('checkbox', { name: /Berwick & Holy Island/ })).toBeChecked();
      expect(within(panel).getByRole('checkbox', { name: /Keswick & Borrowdale/ })).toBeChecked();
    });

    it('Save picks PUTs the chosen ids, clears the lock and says so', async () => {
      saveUserSubRegions.mockResolvedValue(makeState({
        status: 'OPEN',
        picks: [PICKS[0], { areaId: 11, name: 'Bamburgh to Howick', regionName: 'Northumberland & Tyneside' }],
      }));
      const panel = await openPicks();

      // Swap Keswick (20) for Bamburgh (11).
      fireEvent.click(within(panel).getByRole('checkbox', { name: /Keswick & Borrowdale/ }));
      fireEvent.click(within(panel).getByRole('checkbox', { name: /Bamburgh to Howick/ }));
      expect(screen.getByTestId('user-panel-subtitle')).toHaveTextContent('2 of 3 chosen');
      fireEvent.click(within(panel).getByRole('button', { name: 'Save picks' }));

      await waitFor(() => expect(saveUserSubRegions).toHaveBeenCalledWith(2, [10, 11]));
      expect(await screen.findByText("Saved bea.holy's picks. Their lock is cleared.")).toBeInTheDocument();
      expect(screen.queryByTestId('user-change-picks')).not.toBeInTheDocument();
      expect(screen.getByTestId('user-reader-panel')).toBeInTheDocument();
      expect(getUsers).toHaveBeenCalledTimes(2);
    });

    it('Save picks is off with nothing chosen, and a failure shows the server message', async () => {
      saveUserSubRegions.mockRejectedValue({ response: { data: { error: 'Choose at most 3 sub-regions' } } });
      const panel = await openPicks();

      fireEvent.click(within(panel).getByRole('button', { name: 'Save picks' }));
      expect(await screen.findByText('Choose at most 3 sub-regions')).toBeInTheDocument();
      expect(screen.getByTestId('user-change-picks')).toBeInTheDocument();

      fireEvent.click(within(panel).getByRole('checkbox', { name: /Berwick & Holy Island/ }));
      fireEvent.click(within(panel).getByRole('checkbox', { name: /Keswick & Borrowdale/ }));
      expect(within(panel).getByRole('button', { name: 'Save picks' })).toBeDisabled();
    });

    it('Clear all picks confirms with the handoff copy, then PUTs an empty list', async () => {
      saveUserSubRegions.mockResolvedValue(makeState({ status: 'FIRST_CHOICE', picks: [] }));
      const panel = await openPicks();

      fireEvent.click(within(panel).getByRole('button', { name: 'Clear all picks' }));
      const dialog = await screen.findByRole('dialog', { name: "Clear all of bea.holy's picks?" });
      expect(within(dialog).getByText(
        "They'll see no ratings until they choose again, and will be asked at their next sign-in.",
      )).toBeInTheDocument();
      expect(saveUserSubRegions).not.toHaveBeenCalled();
      fireEvent.click(within(dialog).getByRole('button', { name: 'Clear picks' }));

      await waitFor(() => expect(saveUserSubRegions).toHaveBeenCalledWith(2, []));
      expect(await screen.findByText("Cleared all of bea.holy's picks.")).toBeInTheDocument();
    });

    it('Cancel returns to the reader without saving', async () => {
      const panel = await openPicks();

      fireEvent.click(within(panel).getByRole('button', { name: 'Cancel' }));

      expect(screen.getByTestId('user-reader-panel')).toBeInTheDocument();
      expect(saveUserSubRegions).not.toHaveBeenCalled();
    });
  });

  describe('Edit account', () => {
    async function openEdit(username, state, user) {
      await renderList(user ? [user] : undefined);
      await openReader(username, state);
      fireEvent.click(await screen.findByTestId(/^edit-user-/));
      return screen.findByTestId('user-edit-account');
    }

    it('shows Lite, Pro and Admin as the role labels while the values stay the enum names', async () => {
      const panel = await openEdit('anna_r', makeState());

      const select = within(panel).getByLabelText('Role');
      expect(within(select).getAllByRole('option').map((o) => o.textContent)).toEqual(['Lite', 'Pro', 'Admin']);
      expect(within(select).getAllByRole('option').map((o) => o.value)).toEqual(['LITE_USER', 'PRO_USER', 'ADMIN']);
      expect(select).toHaveValue('LITE_USER');
      expect(screen.queryByTestId('role-change-note')).not.toBeInTheDocument();
    });

    it('notes what happens to the picks when a Lite reader with picks becomes Pro', async () => {
      const panel = await openEdit('anna_r', makeState());

      fireEvent.change(within(panel).getByLabelText('Role'), { target: { value: 'PRO_USER' } });

      expect(screen.getByTestId('role-change-note')).toHaveTextContent(
        "Pro sees every sub-region. anna_r's 2 picks are kept, and come back into use if the account returns to Lite.",
      );
    });

    it('says only that Admin sees everything when there are no picks to keep', async () => {
      const panel = await openEdit('anna_r', makeState({ picks: [] }));

      fireEvent.change(within(panel).getByLabelText('Role'), { target: { value: 'ADMIN' } });

      expect(screen.getByTestId('role-change-note')).toHaveTextContent('Admin sees every sub-region.');
      expect(screen.getByTestId('role-change-note')).not.toHaveTextContent('kept');
    });

    it('notes the kept picks coming back when Pro becomes Lite', async () => {
      const panel = await openEdit('mkphoto', makeState({ status: 'NOT_APPLICABLE', options: [] }), makeUser({
        id: 5, username: 'mkphoto', email: 'mk@example.com', role: 'PRO_USER', subRegionStatus: 'NOT_APPLICABLE',
      }));

      fireEvent.change(within(panel).getByLabelText('Role'), { target: { value: 'LITE_USER' } });

      expect(screen.getByTestId('role-change-note')).toHaveTextContent(
        "Lite readers choose up to 3 sub-regions. mkphoto's 2 kept picks come back into use; the lock carries on from their last change.",
      );
    });

    it('tells a Pro reader with no picks they will be asked at next sign-in', async () => {
      const panel = await openEdit('mkphoto', makeState({ status: 'NOT_APPLICABLE', options: [], picks: [] }), makeUser({
        id: 5, username: 'mkphoto', email: 'mk@example.com', role: 'PRO_USER', subRegionStatus: 'NOT_APPLICABLE',
      }));

      fireEvent.change(within(panel).getByLabelText('Role'), { target: { value: 'LITE_USER' } });

      expect(screen.getByTestId('role-change-note')).toHaveTextContent("They'll be asked to choose at their next sign-in.");
    });

    it('gives no note for a move between Pro and Admin', async () => {
      const panel = await openEdit('mkphoto', makeState({ status: 'NOT_APPLICABLE', options: [] }), makeUser({
        id: 5, username: 'mkphoto', email: 'mk@example.com', role: 'PRO_USER', subRegionStatus: 'NOT_APPLICABLE',
      }));

      fireEvent.change(within(panel).getByLabelText('Role'), { target: { value: 'ADMIN' } });

      expect(screen.queryByTestId('role-change-note')).not.toBeInTheDocument();
    });

    it('saves a changed role and email, then returns to the reader', async () => {
      updateUserEmail.mockResolvedValue({ message: 'Updated' });
      updateUserRole.mockResolvedValue({ message: 'Updated' });
      const panel = await openEdit('anna_r', makeState());

      fireEvent.change(within(panel).getByLabelText('Email'), { target: { value: 'new@example.com' } });
      fireEvent.change(within(panel).getByLabelText('Role'), { target: { value: 'PRO_USER' } });
      fireEvent.click(within(panel).getByRole('button', { name: 'Save changes' }));

      await waitFor(() => expect(updateUserEmail).toHaveBeenCalledWith(1, 'new@example.com'));
      expect(updateUserRole).toHaveBeenCalledWith(1, 'PRO_USER');
      expect(await screen.findByTestId('user-reader-panel')).toBeInTheDocument();
    });

    it('passes on the server message when an email edit voids a pending registration link', async () => {
      const pendingMessage = 'Updated. The old verification link no longer works: resend verification to the new address.';
      updateUserEmail.mockResolvedValue({ message: pendingMessage });
      const panel = await openEdit('hollie.w', makeState({ status: 'FIRST_CHOICE', picks: [] }),
        makeUser({ id: 4, username: 'hollie.w', email: 'hollie@example.com', enabled: false, awaitingVerification: true }));

      fireEvent.change(within(panel).getByLabelText('Email'), { target: { value: 'hollie2@example.com' } });
      fireEvent.click(within(panel).getByRole('button', { name: 'Save changes' }));

      expect(await screen.findByText(pendingMessage)).toBeInTheDocument();
      expect(updateUserRole).not.toHaveBeenCalled();
    });

    it('refuses a blank or malformed email, and shows a server failure', async () => {
      const panel = await openEdit('anna_r', makeState());

      fireEvent.change(within(panel).getByLabelText('Email'), { target: { value: '   ' } });
      fireEvent.click(within(panel).getByRole('button', { name: 'Save changes' }));
      expect(screen.getByText('Email address is required.')).toBeInTheDocument();

      fireEvent.change(within(panel).getByLabelText('Email'), { target: { value: 'nope' } });
      fireEvent.click(within(panel).getByRole('button', { name: 'Save changes' }));
      expect(screen.getByText('Please enter a valid email address.')).toBeInTheDocument();
      expect(updateUserEmail).not.toHaveBeenCalled();

      updateUserEmail.mockRejectedValue({ response: { data: { error: 'That email is already in use' } } });
      fireEvent.change(within(panel).getByLabelText('Email'), { target: { value: 'taken@example.com' } });
      fireEvent.click(within(panel).getByRole('button', { name: 'Save changes' }));
      expect(await screen.findByText('That email is already in use')).toBeInTheDocument();
    });

    it('Cancel returns to the reader', async () => {
      const panel = await openEdit('anna_r', makeState());

      fireEvent.click(within(panel).getByRole('button', { name: 'Cancel' }));

      expect(screen.getByTestId('user-reader-panel')).toBeInTheDocument();
      expect(updateUserEmail).not.toHaveBeenCalled();
    });
  });

  describe('Add user', () => {
    it('opens the form in the panel and keeps the list on screen', async () => {
      await renderList();

      fireEvent.click(screen.getByRole('button', { name: 'Add user' }));

      const panel = await screen.findByTestId('user-add-panel');
      expect(within(panel).getByRole('heading', { name: 'Add user' })).toBeInTheDocument();
      expect(screen.getByTestId('users-table')).toBeInTheDocument();
      const options = within(within(panel).getByLabelText('Role')).getAllByRole('option');
      expect(options.map((o) => o.textContent)).toEqual(['Lite', 'Pro', 'Admin']);
    });

    it('checks the fields, then creates the user and opens them', async () => {
      createUser.mockResolvedValue({ id: 8, username: 'jane', email: 'jane@example.com', role: 'PRO_USER' });
      await renderList();
      fireEvent.click(screen.getByRole('button', { name: 'Add user' }));
      const panel = await screen.findByTestId('user-add-panel');

      fireEvent.click(within(panel).getByRole('button', { name: 'Create user' }));
      expect(screen.getByText('Username and password are required.')).toBeInTheDocument();

      fireEvent.change(within(panel).getByLabelText('Username'), { target: { value: 'jane' } });
      fireEvent.change(within(panel).getByLabelText('Password'), { target: { value: 'Temp-1234' } });
      fireEvent.change(within(panel).getByLabelText('Email'), { target: { value: 'not-an-email' } });
      fireEvent.click(within(panel).getByRole('button', { name: 'Create user' }));
      expect(screen.getAllByText('Please enter a valid email address.').length).toBeGreaterThan(0);
      expect(createUser).not.toHaveBeenCalled();

      fireEvent.change(within(panel).getByLabelText('Email'), { target: { value: 'jane@example.com' } });
      fireEvent.change(within(panel).getByLabelText('Role'), { target: { value: 'PRO_USER' } });
      getUsers.mockResolvedValue([...fixtureUsers(), makeUser({ id: 8, username: 'jane', email: 'jane@example.com', role: 'PRO_USER' })]);
      getUserSubRegions.mockResolvedValue(makeState({ status: 'NOT_APPLICABLE', options: [], picks: [] }));
      fireEvent.click(within(panel).getByRole('button', { name: 'Create user' }));

      await waitFor(() => expect(createUser).toHaveBeenCalledWith({
        username: 'jane', password: 'Temp-1234', email: 'jane@example.com', role: 'PRO_USER',
      }));
      expect(await screen.findByRole('heading', { name: 'jane' })).toBeInTheDocument();
      expect(window.location.hash).toBe('#manage/users/8');
    });

    it('shows the server refusal and keeps the form', async () => {
      createUser.mockRejectedValue({ response: { data: { error: 'Username already exists' } } });
      await renderList();
      fireEvent.click(screen.getByRole('button', { name: 'Add user' }));
      const panel = await screen.findByTestId('user-add-panel');

      fireEvent.change(within(panel).getByLabelText('Username'), { target: { value: 'anna_r' } });
      fireEvent.change(within(panel).getByLabelText('Password'), { target: { value: 'Temp-1234' } });
      fireEvent.change(within(panel).getByLabelText('Email'), { target: { value: 'a@example.com' } });
      fireEvent.click(within(panel).getByRole('button', { name: 'Create user' }));

      expect(await screen.findByText('Username already exists')).toBeInTheDocument();
      expect(screen.getByTestId('user-add-panel')).toBeInTheDocument();
    });

    it('Cancel closes the panel', async () => {
      await renderList();
      fireEvent.click(screen.getByRole('button', { name: 'Add user' }));
      const panel = await screen.findByTestId('user-add-panel');

      fireEvent.click(within(panel).getByRole('button', { name: 'Cancel' }));

      expect(screen.queryByTestId('user-panel')).not.toBeInTheDocument();
    });
  });

  describe('loading', () => {
    it('shows a message when the list cannot be loaded', async () => {
      getUsers.mockRejectedValue(new Error('boom'));
      render(<UserManagementView />);

      expect(await screen.findByText("Couldn't load users.")).toBeInTheDocument();
    });

    it("says so when a reader's sub-regions cannot be loaded, and still offers the account actions", async () => {
      await renderList();
      getUserSubRegions.mockRejectedValue(new Error('nope'));
      fireEvent.click(rowFor('anna_r'));

      expect(await screen.findByText("Couldn't load this reader's sub-regions.")).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Delete' })).toBeInTheDocument();
    });
  });
});
