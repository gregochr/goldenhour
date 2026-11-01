import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import JobSchedule from '../components/scheduler/JobSchedule.jsx';
import { tideJob } from './schedulerFixtures.js';

// The inline cron or delay editor on its own: a save that returns with the editor still open must not leave Save dead.

function renderEditing(onInlineSave) {
  const noop = () => {};
  return render(
    <JobSchedule
      job={tideJob()}
      editing
      onEditStart={noop}
      onEditCancel={noop}
      onSolarSaved={noop}
      onPartial={noop}
      onInlineSave={onInlineSave}
      onSwitch={noop}
    />,
  );
}

describe('JobSchedule: the inline editor\'s Save', () => {
  it('sends again after a save that returned without closing the editor (a skipped save)', async () => {
    const onInlineSave = vi.fn().mockResolvedValue(undefined);
    renderEditing(onInlineSave);

    fireEvent.click(screen.getByTestId('save-btn-tide_refresh'));
    await waitFor(() => expect(onInlineSave).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByTestId('save-btn-tide_refresh'));

    await waitFor(() => expect(onInlineSave).toHaveBeenCalledTimes(2));
  });

  it('sends again after a failed save, with the failure said in the job\'s own name', async () => {
    const onInlineSave = vi.fn().mockRejectedValueOnce(new Error('400')).mockResolvedValue(undefined);
    renderEditing(onInlineSave);

    fireEvent.click(screen.getByTestId('save-btn-tide_refresh'));
    expect(await screen.findByTestId('inline-edit-error-tide_refresh')).toHaveTextContent(
      "Couldn't save Tide Refresh's schedule.",
    );
    fireEvent.click(screen.getByTestId('save-btn-tide_refresh'));

    await waitFor(() => expect(onInlineSave).toHaveBeenCalledTimes(2));
  });

  it('sends one request for a double press while the first is in flight', async () => {
    let release;
    const onInlineSave = vi.fn(() => new Promise((resolve) => { release = () => resolve(); }));
    renderEditing(onInlineSave);

    fireEvent.click(screen.getByTestId('save-btn-tide_refresh'));
    fireEvent.click(screen.getByTestId('save-btn-tide_refresh'));
    expect(onInlineSave).toHaveBeenCalledTimes(1);
    release();
    await waitFor(() => expect(onInlineSave).toHaveBeenCalledTimes(1));
  });
});
