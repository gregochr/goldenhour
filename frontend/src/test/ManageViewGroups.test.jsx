import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { getWaitlist } from '../api/waitlistApi.js';
import ManageView from '../components/ManageView.jsx';

vi.mock('../api/waitlistApi.js', () => ({ getWaitlist: vi.fn() }));
vi.mock('../api/axiosClient.js', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

// Every sub-view is stubbed: this file tests the shell's grouping, not the views.
const { stub } = vi.hoisted(() => ({
  stub: (name) => ({ default: () => <div data-testid="stub-view">{name}</div> }),
}));
vi.mock('../components/UserManagementView.jsx', () => stub('Users'));
vi.mock('../components/LocationManagementView.jsx', () => stub('Locations'));
vi.mock('../components/RegionManagementView.jsx', () => stub('Regions'));
vi.mock('../components/TideManagementView.jsx', () => stub('Tides'));
vi.mock('../components/WaitlistManagementView.jsx', () => stub('Waitlist'));
vi.mock('../components/runs/RunsView.jsx', () => stub('Runs'));
vi.mock('../components/ModelSelectionView.jsx', () => stub('Run Config'));
vi.mock('../components/SchedulerView.jsx', () => stub('Scheduler'));
vi.mock('../components/TravelDaysView.jsx', () => stub('Travel Days'));
vi.mock('../components/SkyRatingEvalView.jsx', () => stub('Sky Eval'));
vi.mock('../components/PipelineRunsView.jsx', () => stub('Pipeline Runs'));
vi.mock('../components/HotTopicSimulation.jsx', () => stub('Hot Topics'));
vi.mock('../components/RewindView.jsx', () => stub('Rewind'));

// The expected layout, written out literally (deliberately not imported from ManageView).
const EXPECTED = [
  { group: 'runs', label: 'Runs', tabs: [
    ['runs', 'Runs'], ['pipelineruns', 'Pipeline Runs'],
    ['scheduler', 'Scheduler'], ['traveldays', 'Travel Days'],
  ] },
  { group: 'tuning', label: 'Tuning', tabs: [['models', 'Run Config'], ['skyeval', 'Sky Eval']] },
  { group: 'places', label: 'Places', tabs: [
    ['locations', 'Locations'], ['regions', 'Regions'], ['tides', 'Tides'],
  ] },
  { group: 'people', label: 'People', tabs: [
    ['users', 'Users'], ['waitlist', 'Waitlist'],
  ] },
  { group: 'testing', label: 'Testing', tabs: [['hottopics', 'Hot Topics'], ['rewind', 'Rewind']] },
];

const ALL_TABS = EXPECTED.flatMap((g) => g.tabs.map(([value, label]) => ({ group: g.group, value, label })));

const GROUP_IDS = EXPECTED.map((g) => `manage-group-${g.group}`);
const subTabIds = () => screen.getAllByTestId(/^manage-tab-(?!bar$)/).map((el) => el.dataset.testid.replace('manage-tab-', ''));
const isSelectedGroup = (group) => screen.getByTestId(`manage-group-${group}`).className.includes('text-plex-gold');

describe('ManageView — grouping', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.location.hash = '';
    getWaitlist.mockResolvedValue([]);
  });

  it('renders exactly the five groups, in order, and no old Data/Operations group', () => {
    render(<ManageView onComplete={vi.fn()} />);
    const ids = screen.getAllByTestId(/^manage-group-(?!bar$)/).map((el) => el.dataset.testid);
    expect(ids).toEqual(GROUP_IDS);
    expect(screen.queryByTestId('manage-group-data')).toBeNull();
    expect(screen.queryByTestId('manage-group-operations')).toBeNull();
  });

  it.each(EXPECTED)('$group group shows exactly its tabs, in order', ({ group, label, tabs }) => {
    render(<ManageView onComplete={vi.fn()} />);
    expect(screen.getByTestId(`manage-group-${group}`)).toHaveTextContent(label);
    fireEvent.click(screen.getByTestId(`manage-group-${group}`));
    expect(subTabIds()).toEqual(tabs.map(([value]) => value));
    tabs.forEach(([value, tabLabel]) => {
      expect(screen.getByTestId(`manage-tab-${value}`)).toHaveTextContent(tabLabel);
    });
  });

  it('lets both tab rows wrap so five groups fit a phone', () => {
    render(<ManageView onComplete={vi.fn()} />);
    expect(screen.getByTestId('manage-group-bar')).toHaveClass('flex-wrap');
    expect(screen.getByTestId('manage-tab-bar')).toHaveClass('flex-wrap');
  });

  it('lands on Runs / Runs with no hash', () => {
    render(<ManageView onComplete={vi.fn()} />);
    expect(isSelectedGroup('runs')).toBe(true);
    expect(subTabIds()[0]).toBe('runs');
    expect(screen.getByTestId('stub-view')).toHaveTextContent('Runs');
  });

  it('lands on Runs / Runs for a bare #manage', () => {
    window.location.hash = '#manage';
    render(<ManageView onComplete={vi.fn()} />);
    expect(isSelectedGroup('runs')).toBe(true);
    expect(screen.getByTestId('stub-view')).toHaveTextContent('Runs');
  });

  it('falls back to Runs / Runs for an unknown tab', () => {
    window.location.hash = '#manage/nonsense';
    render(<ManageView onComplete={vi.fn()} />);
    expect(isSelectedGroup('runs')).toBe(true);
    expect(screen.getByTestId('stub-view')).toHaveTextContent('Runs');
  });

  it.each(ALL_TABS)('#manage/$value lands on the $group group and the $label tab', ({ group, value, label }) => {
    window.location.hash = `#manage/${value}`;
    render(<ManageView onComplete={vi.fn()} />);
    expect(isSelectedGroup(group)).toBe(true);
    expect(screen.getByTestId(`manage-tab-${value}`)).toBeInTheDocument();
    expect(screen.getByTestId('stub-view')).toHaveTextContent(label);
  });

  it('the retired #manage/subregions link falls back to Runs / Runs', () => {
    window.location.hash = '#manage/subregions';
    render(<ManageView onComplete={vi.fn()} />);
    expect(isSelectedGroup('runs')).toBe(true);
    expect(screen.getByTestId('stub-view')).toHaveTextContent('Runs');
  });

  it('a user deep link (#manage/users/<id>) still resolves to People / Users', () => {
    window.location.hash = '#manage/users/42';
    render(<ManageView onComplete={vi.fn()} />);
    expect(isSelectedGroup('people')).toBe(true);
    expect(screen.getByTestId('stub-view')).toHaveTextContent('Users');
  });

  it.each(EXPECTED)('clicking $group selects its first tab and writes the hash', ({ group, tabs }) => {
    render(<ManageView onComplete={vi.fn()} />);
    fireEvent.click(screen.getByTestId(`manage-group-${group}`));
    expect(window.location.hash).toBe(`#manage/${tabs[0][0]}`);
    expect(screen.getByTestId('stub-view')).toHaveTextContent(tabs[0][1]);
  });

  describe('People group waitlist badge', () => {
    it('shows the count on the People button when the waitlist is non-empty', async () => {
      getWaitlist.mockResolvedValue([{ id: 1 }, { id: 2 }, { id: 3 }]);
      render(<ManageView onComplete={vi.fn()} />);
      const badge = await screen.findByTestId('waitlist-group-badge');
      expect(badge).toHaveTextContent('3');
      expect(within(screen.getByTestId('manage-group-people')).getByTestId('waitlist-group-badge')).toBe(badge);
    });

    it('is hidden when the count is zero', async () => {
      render(<ManageView onComplete={vi.fn()} />);
      await waitFor(() => expect(getWaitlist).toHaveBeenCalled());
      expect(screen.queryByTestId('waitlist-group-badge')).toBeNull();
    });

    it('is hidden when the fetch fails', async () => {
      getWaitlist.mockRejectedValue(new Error('boom'));
      render(<ManageView onComplete={vi.fn()} />);
      await waitFor(() => expect(getWaitlist).toHaveBeenCalled());
      expect(screen.queryByTestId('waitlist-group-badge')).toBeNull();
    });
  });
});
