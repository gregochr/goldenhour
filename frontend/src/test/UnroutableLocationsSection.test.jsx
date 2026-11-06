import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import UnroutableLocationsSection from '../components/scheduler/UnroutableLocationsSection.jsx';
import SchedulerJobPanel from '../components/scheduler/SchedulerJobPanel.jsx';
import { job } from './schedulerFixtures.js';

vi.mock('../api/driveTimesAdminApi', () => ({ fetchUnroutableLocations: vi.fn() }));
vi.mock('../api/schedulerApi', () => ({ fetchJobDetail: vi.fn(() => Promise.resolve(null)) }));

import { fetchUnroutableLocations } from '../api/driveTimesAdminApi';

const HELVELLYN = {
  locationId: 7, name: 'Helvellyn', unroutableHomes: 3, judgedHomes: 3, unroutableBases: 2, judgedBases: 4,
};
const SCAFELL = {
  locationId: 9, name: 'Scafell Pike', unroutableHomes: 1, judgedHomes: 2, unroutableBases: 0, judgedBases: 4,
};

describe('UnroutableLocationsSection', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('lists each place with its counts, a count badge and a link to its admin page', async () => {
    fetchUnroutableLocations.mockResolvedValue([HELVELLYN, SCAFELL]);

    render(<UnroutableLocationsSection />);

    expect(await screen.findByTestId('scheduler-panel-unroutable')).toHaveTextContent('Needs a drive point');
    expect(screen.getByTestId('scheduler-panel-unroutable-count')).toHaveTextContent('2');
    expect(screen.getByTestId('unroutable-7')).toHaveTextContent('Helvellyn');
    expect(screen.getByTestId('unroutable-7')).toHaveTextContent('no route from 3 of 3 homes, 2 of 4 bases');
    expect(screen.getByTestId('unroutable-9')).toHaveTextContent('no route from 1 of 2 homes');
    expect(screen.getByTestId('unroutable-link-7')).toHaveAttribute('href', '#manage/locations/7');
    expect(screen.getByTestId('unroutable-link-9')).toHaveAttribute('href', '#manage/locations/9');
  });

  it('renders nothing when no place is unroutable', async () => {
    fetchUnroutableLocations.mockResolvedValue([]);

    render(<UnroutableLocationsSection />);

    await waitFor(() => expect(fetchUnroutableLocations).toHaveBeenCalledTimes(1));
    expect(screen.queryByTestId('scheduler-panel-unroutable')).toBeNull();
  });

  it('renders nothing, and does not throw, when the read fails', async () => {
    fetchUnroutableLocations.mockRejectedValue(new Error('403'));

    render(<UnroutableLocationsSection />);

    await waitFor(() => expect(fetchUnroutableLocations).toHaveBeenCalledTimes(1));
    expect(screen.queryByTestId('scheduler-panel-unroutable')).toBeNull();
  });

  it('treats a non-array answer as empty', async () => {
    fetchUnroutableLocations.mockResolvedValue({ unexpected: true });

    render(<UnroutableLocationsSection />);

    await waitFor(() => expect(fetchUnroutableLocations).toHaveBeenCalledTimes(1));
    expect(screen.queryByTestId('scheduler-panel-unroutable')).toBeNull();
  });
});

describe('SchedulerJobPanel — the drive-time job', () => {
  const noop = () => {};
  const panelProps = {
    jobs: [], editing: false, actions: {}, onClose: noop, onSelectJob: noop, onEditStart: noop, onEditCancel: noop,
    onSolarSaved: noop, onPartial: noop, onInlineSave: noop, onSwitch: noop, onRunNow: noop, onPause: noop,
    onResume: noop,
  };

  beforeEach(() => {
    vi.clearAllMocks();
    fetchUnroutableLocations.mockResolvedValue([HELVELLYN]);
  });

  it('shows the Needs a drive point section on the drive_time_refresh panel', async () => {
    const drive = job({ jobKey: 'drive_time_refresh', displayName: 'Drive time refresh' });

    render(<SchedulerJobPanel job={drive} {...panelProps} />);

    expect(await screen.findByTestId('scheduler-panel-unroutable')).toBeInTheDocument();
  });

  it('does not read or show it on any other job', async () => {
    render(<SchedulerJobPanel job={job()} {...panelProps} />);

    await screen.findByTestId('scheduler-panel-meta');
    expect(fetchUnroutableLocations).not.toHaveBeenCalled();
    expect(screen.queryByTestId('scheduler-panel-unroutable')).toBeNull();
  });
});
