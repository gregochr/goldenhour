import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import { getWaitlist } from '../api/waitlistApi.js';
import { useIsMobile } from '../hooks/useIsMobile.js';
import { useIsWide } from '../hooks/useIsWide.js';
import ManageView from '../components/ManageView.jsx';

vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: vi.fn() }));
vi.mock('../hooks/useIsWide.js', () => ({ useIsWide: vi.fn() }));
vi.mock('../api/waitlistApi.js', () => ({ getWaitlist: vi.fn() }));
vi.mock('../api/axiosClient.js', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

// Counts how many times the Job Runs stub has mounted (not rendered).
const { mounts } = vi.hoisted(() => ({ mounts: { jobRuns: 0 } }));

const { stub } = vi.hoisted(() => ({
  stub: (name) => ({ default: () => <div data-testid="stub-view">{name}</div> }),
}));
vi.mock('../components/UserManagementView.jsx', () => stub('Users'));
vi.mock('../components/LocationManagementView.jsx', () => stub('Locations'));
vi.mock('../components/RegionManagementView.jsx', () => stub('Regions'));
vi.mock('../components/TideManagementView.jsx', () => stub('Tides'));
vi.mock('../components/WaitlistManagementView.jsx', () => stub('Waitlist'));
vi.mock('../components/ModelSelectionView.jsx', () => stub('Run Config'));
vi.mock('../components/SchedulerView.jsx', () => stub('Scheduler'));
vi.mock('../components/TravelDaysView.jsx', () => stub('Travel Days'));
vi.mock('../components/SkyRatingEvalView.jsx', () => stub('Sky Eval'));
vi.mock('../components/PipelineRunsView.jsx', () => stub('Pipeline Runs'));
vi.mock('../components/HotTopicSimulation.jsx', () => stub('Hot Topics'));
vi.mock('../components/RewindView.jsx', () => stub('Rewind'));
vi.mock('../components/JobRunsMetricsView.jsx', async () => {
  const React = await import('react');
  function JobRunsStub() {
    React.useEffect(() => {
      mounts.jobRuns += 1;
    }, []);
    return <div data-testid="stub-view">Job Runs</div>;
  }
  return { default: JobRunsStub };
});

// The expected layout, written out literally (deliberately not imported from ManageView).
const EXPECTED = [
  { group: 'runs', label: 'Runs', tabs: [
    ['pipelineruns', 'Pipeline Runs'], ['metrics', 'Job Runs'],
    ['scheduler', 'Scheduler'], ['traveldays', 'Travel Days'],
  ] },
  { group: 'tuning', label: 'Tuning', tabs: [['models', 'Run Config'], ['skyeval', 'Sky Eval']] },
  { group: 'places', label: 'Places', tabs: [
    ['locations', 'Locations'], ['regions', 'Regions'], ['tides', 'Tides'],
  ] },
  { group: 'people', label: 'People', tabs: [['users', 'Users'], ['waitlist', 'Waitlist']] },
  { group: 'testing', label: 'Testing', tabs: [['hottopics', 'Hot Topics'], ['rewind', 'Rewind']] },
];
const ALL_TABS = EXPECTED.flatMap((g) => g.tabs.map(([value, label]) => ({ group: g.group, value, label })));

function setLayout(layout) {
  useIsMobile.mockReturnValue(layout === 'phone');
  useIsWide.mockReturnValue(layout === 'wide');
}

const navigationCount = () =>
  ['manage-sidebar', 'manage-section-select', 'manage-group-bar'].filter(
    (id) => screen.queryByTestId(id) !== null,
  ).length;

describe('ManageView — wide sidebar', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.location.hash = '';
    getWaitlist.mockResolvedValue([]);
    setLayout('wide');
  });

  it('renders a labelled nav with every group heading and every tab, in order', () => {
    render(<ManageView onComplete={vi.fn()} />);
    const nav = screen.getByTestId('manage-sidebar');
    expect(nav.tagName).toBe('NAV');
    expect(nav).toHaveAttribute('aria-label', 'Operations sections');
    EXPECTED.forEach(({ group, label, tabs }) => {
      const section = within(nav).getByTestId(`manage-sidebar-group-${group}`);
      expect(section).toHaveTextContent(label);
      const ids = within(section).getAllByRole('button').map((b) => b.dataset.testid);
      expect(ids).toEqual(tabs.map(([value]) => `manage-tab-${value}`));
      tabs.forEach(([value, tabLabel]) => {
        expect(screen.getByTestId(`manage-tab-${value}`)).toHaveTextContent(tabLabel);
      });
    });
  });

  it('announces each group by name and nests its tabs', () => {
    render(<ManageView onComplete={vi.fn()} />);
    const group = screen.getByRole('group', { name: 'Testing' });
    expect(within(group).getByTestId('manage-tab-rewind')).toBeInTheDocument();
  });

  it('renders exactly one navigation and no group buttons', () => {
    render(<ManageView onComplete={vi.fn()} />);
    expect(navigationCount()).toBe(1);
    expect(screen.queryByTestId('manage-group-runs')).toBeNull();
    expect(screen.queryByTestId('manage-tab-bar')).toBeNull();
  });

  it('marks the active tab aria-current="page" and follows selection', () => {
    render(<ManageView onComplete={vi.fn()} />);
    expect(screen.getByTestId('manage-tab-pipelineruns')).toHaveAttribute('aria-current', 'page');
    fireEvent.click(screen.getByTestId('manage-tab-rewind'));
    expect(screen.getByTestId('manage-tab-rewind')).toHaveAttribute('aria-current', 'page');
    expect(screen.getByTestId('manage-tab-pipelineruns')).not.toHaveAttribute('aria-current');
    expect(document.querySelectorAll('[aria-current="page"]')).toHaveLength(1);
  });

  it('switches content and syncs the hash', () => {
    render(<ManageView onComplete={vi.fn()} />);
    fireEvent.click(screen.getByTestId('manage-tab-tides'));
    expect(screen.getByTestId('stub-view')).toHaveTextContent('Tides');
    expect(window.location.hash).toBe('#manage/tides');
  });

  it('shows the waitlist count badge on the Waitlist button', async () => {
    getWaitlist.mockResolvedValue([{ id: 1 }, { id: 2 }, { id: 3 }]);
    render(<ManageView onComplete={vi.fn()} />);
    const badge = await screen.findByTestId('waitlist-count-badge');
    expect(badge).toHaveTextContent('3');
    expect(screen.getByTestId('manage-tab-waitlist')).toContainElement(badge);
  });

  it('opens on the tab named by the hash', () => {
    window.location.hash = '#manage/skyeval';
    render(<ManageView onComplete={vi.fn()} />);
    expect(screen.getByTestId('manage-tab-skyeval')).toHaveAttribute('aria-current', 'page');
    expect(screen.getByTestId('stub-view')).toHaveTextContent('Sky Eval');
  });
});

describe('ManageView — phone section menu', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.location.hash = '';
    getWaitlist.mockResolvedValue([]);
    setLayout('phone');
  });

  it('renders one labelled select and no bar or sidebar', () => {
    render(<ManageView onComplete={vi.fn()} />);
    const select = screen.getByTestId('manage-section-select');
    expect(select.tagName).toBe('SELECT');
    expect(select).toHaveClass('field');
    expect(screen.getByLabelText('Section')).toBe(select);
    expect(screen.getByText('Section')).toHaveClass('sr-only');
    expect(navigationCount()).toBe(1);
    expect(screen.queryByTestId('manage-tab-bar')).toBeNull();
  });

  it('lists every tab under its own optgroup, in order', () => {
    render(<ManageView onComplete={vi.fn()} />);
    const groups = screen.getByTestId('manage-section-select').querySelectorAll('optgroup');
    expect([...groups].map((g) => g.label)).toEqual(EXPECTED.map((g) => g.label));
    EXPECTED.forEach(({ tabs }, i) => {
      const options = [...groups[i].querySelectorAll('option')];
      expect(options.map((o) => [o.value, o.textContent])).toEqual(tabs);
    });
  });

  it('selects the current tab and changing it switches content and hash', () => {
    render(<ManageView onComplete={vi.fn()} />);
    const select = screen.getByTestId('manage-section-select');
    expect(select).toHaveValue('pipelineruns');
    fireEvent.change(select, { target: { value: 'regions' } });
    expect(select).toHaveValue('regions');
    expect(screen.getByTestId('stub-view')).toHaveTextContent('Regions');
    expect(window.location.hash).toBe('#manage/regions');
  });

  it('shows the waitlist count in the option text when it is above zero', async () => {
    getWaitlist.mockResolvedValue([{ id: 1 }, { id: 2 }, { id: 3 }]);
    render(<ManageView onComplete={vi.fn()} />);
    expect(await screen.findByRole('option', { name: 'Waitlist (3)' })).toBeInTheDocument();
  });

  it('keeps the plain "Waitlist" option text when the count is zero', async () => {
    render(<ManageView onComplete={vi.fn()} />);
    await vi.waitFor(() => expect(getWaitlist).toHaveBeenCalled());
    expect(screen.getByRole('option', { name: 'Waitlist' })).toBeInTheDocument();
  });

  it.each(ALL_TABS)('the select reaches $value', ({ value }) => {
    render(<ManageView onComplete={vi.fn()} />);
    fireEvent.change(screen.getByTestId('manage-section-select'), { target: { value } });
    expect(screen.getByTestId('manage-section-select')).toHaveValue(value);
    expect(window.location.hash).toBe(`#manage/${value}`);
  });
});

describe('ManageView — layout precedence and tablet', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.location.hash = '';
    getWaitlist.mockResolvedValue([]);
  });

  it.each([
    ['phone', 'manage-section-select'],
    ['wide', 'manage-sidebar'],
    ['tablet', 'manage-group-bar'],
  ])('%s renders exactly one navigation', (layout, testid) => {
    setLayout(layout);
    render(<ManageView onComplete={vi.fn()} />);
    expect(navigationCount()).toBe(1);
    expect(screen.getByTestId(testid)).toBeInTheDocument();
  });

  it('mobile wins when both hooks report true', () => {
    useIsMobile.mockReturnValue(true);
    useIsWide.mockReturnValue(true);
    render(<ManageView onComplete={vi.fn()} />);
    expect(navigationCount()).toBe(1);
    expect(screen.getByTestId('manage-section-select')).toBeInTheDocument();
  });

  it('tablet renders the phase-1 bar exactly: group bar and tab bar, no sidebar, no select', () => {
    setLayout('tablet');
    render(<ManageView onComplete={vi.fn()} />);
    expect(screen.getByTestId('manage-group-bar')).toBeInTheDocument();
    expect(screen.getByTestId('manage-tab-bar')).toBeInTheDocument();
    expect(screen.queryByTestId('manage-sidebar')).toBeNull();
    expect(screen.queryByTestId('manage-section-select')).toBeNull();
    expect(screen.getAllByTestId(/^manage-group-(?!bar$)/)).toHaveLength(5);
    expect(screen.getByTestId('manage-tab-pipelineruns')).not.toHaveAttribute('aria-current');
  });

  it('does not remount the sub-view when the layout changes', () => {
    mounts.jobRuns = 0;
    setLayout('wide');
    const { rerender } = render(<ManageView onComplete={vi.fn()} />);
    fireEvent.click(screen.getByTestId('manage-tab-metrics'));
    expect(screen.getByTestId('stub-view')).toHaveTextContent('Job Runs');
    expect(mounts.jobRuns).toBe(1);

    setLayout('tablet');
    rerender(<ManageView onComplete={vi.fn()} />);
    expect(screen.getByTestId('manage-group-bar')).toBeInTheDocument();
    expect(mounts.jobRuns).toBe(1);

    setLayout('phone');
    rerender(<ManageView onComplete={vi.fn()} />);
    expect(screen.getByTestId('manage-section-select')).toBeInTheDocument();
    expect(mounts.jobRuns).toBe(1);

    setLayout('wide');
    rerender(<ManageView onComplete={vi.fn()} />);
    expect(screen.getByTestId('manage-sidebar')).toBeInTheDocument();
    expect(mounts.jobRuns).toBe(1);
  });

  it('keeps the group in sync when a sidebar selection crosses to the tablet bar', () => {
    setLayout('wide');
    const { rerender } = render(<ManageView onComplete={vi.fn()} />);
    fireEvent.click(screen.getByTestId('manage-tab-rewind'));
    setLayout('tablet');
    rerender(<ManageView onComplete={vi.fn()} />);
    expect(screen.getByTestId('manage-group-testing').className).toContain('text-plex-gold');
    expect(screen.getByTestId('manage-group-runs').className).not.toContain('text-plex-gold');
    expect(screen.getByTestId('manage-tab-rewind').className).toContain('text-plex-text ');
  });
});
