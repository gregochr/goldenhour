import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import OutcomeModal from '../components/OutcomeModal.jsx';

vi.mock('../api/forecastApi.js', () => ({
  recordOutcome: vi.fn(),
}));

import { recordOutcome } from '../api/forecastApi.js';

const defaultProps = {
  date: '2026-02-20',
  type: 'SUNSET',
  locationLat: 50.62,
  locationLon: -2.27,
  locationName: 'Durdle Door',
  onClose: vi.fn(),
  onSaved: vi.fn(),
};

function renderModal(overrides = {}) {
  return render(<OutcomeModal {...defaultProps} {...overrides} />);
}

describe('OutcomeModal', () => {
  // React 19 entangles async actions process-wide: while one is in flight, the state a LATER
  // useActionState returns waits on it. So a save promise that never settles does not just affect
  // its own test — every subsequent submit in this file stays stuck on "Saving…" and its error
  // state never commits. That is what made "shows error message on failed save" fail whenever the
  // shuffler put it after the in-progress test. Any test that leaves a save pending parks its
  // resolver here so the action scope is released even when an assertion throws first.
  let releasePendingSave = null;

  beforeEach(() => {
    vi.clearAllMocks();
    releasePendingSave = null;
  });

  afterEach(() => {
    releasePendingSave?.();
  });

  it('renders the modal with a title containing date and type', () => {
    renderModal();
    const title = screen.getByText(/Sunset Outcome/i);
    expect(title).toBeInTheDocument();
    expect(title.textContent).toContain('2026-02-20');
  });

  it('shows "Did you go out?" buttons', () => {
    renderModal();
    expect(screen.getByText('Did you go out?')).toBeInTheDocument();
    expect(screen.getByTestId('went-out-yes')).toBeInTheDocument();
    expect(screen.getByTestId('went-out-no')).toBeInTheDocument();
  });

  it('clicking Yes highlights the yes button', () => {
    renderModal();
    const yesButton = screen.getByTestId('went-out-yes');
    fireEvent.click(yesButton);
    expect(yesButton).toHaveClass('bg-green-700');
  });

  it('clicking No highlights the no button', () => {
    renderModal();
    const noButton = screen.getByTestId('went-out-no');
    fireEvent.click(noButton);
    expect(noButton).toHaveClass('bg-red-700');
  });

  it('clicking Cancel calls onClose', () => {
    const onClose = vi.fn();
    renderModal({ onClose });
    fireEvent.click(screen.getByText('Cancel'));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('renders a submit button labeled "Save outcome"', () => {
    renderModal();
    const submit = screen.getByTestId('outcome-submit');
    expect(submit).toBeInTheDocument();
    expect(submit.textContent).toBe('Save outcome');
  });

  it('shows error message on failed save', async () => {
    const user = userEvent.setup();
    recordOutcome.mockRejectedValue(new Error('Network error'));
    renderModal();

    await user.click(screen.getByTestId('outcome-submit'));

    await waitFor(() => {
      const alert = screen.getByRole('alert');
      expect(alert).toBeInTheDocument();
      expect(alert.textContent).toContain('Network error');
    });
  });

  it('shows "Saving..." while save is in progress', async () => {
    const user = userEvent.setup();
    recordOutcome.mockReturnValue(new Promise((resolve) => {
      releasePendingSave = () => resolve({});
    }));
    renderModal();

    await user.click(screen.getByTestId('outcome-submit'));

    await waitFor(() => {
      expect(screen.getByText('Saving…')).toBeInTheDocument();
    });

    // Let the save finish before the test ends — see the note on releasePendingSave above.
    releasePendingSave();
    await screen.findByTestId('outcome-saved-message');
  });

  it('shows success message after successful save', async () => {
    const user = userEvent.setup();
    recordOutcome.mockResolvedValue({});
    renderModal();

    await user.click(screen.getByTestId('outcome-submit'));

    await waitFor(() => {
      expect(screen.getByTestId('outcome-saved-message')).toBeInTheDocument();
      expect(screen.getByText('Outcome saved')).toBeInTheDocument();
    });
  });

  /**
   * Settles the save action without letting WALL-CLOCK time pass.
   *
   * <p>⚠️ These tests deliberately do NOT use `shouldAdvanceTime`. It ties the fake clock to real
   * time, and the hand-off timer is armed the moment `recordOutcome` resolves — so any real time
   * spent in a `waitFor` poll before the test reaches `unmount()` burns the 1.5s window itself. The
   * suite's own budget is 4s per `waitFor` and this repo has measured multi-second waits under CPU
   * starvation, so that gap is a documented flake shape here rather than a theoretical one.
   * Pumping with a zero advance flushes the action's microtasks and moves the clock not at all.
   */
  async function settleSave() {
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
  }

  /**
   * ⚠️ The hand-off to {@code onSaved} is on a 1.5s timer that used to fire whether or not the
   * dialog was still mounted. Two limits on what this test claims, both established by review:
   * nothing in the app renders {@code OutcomeModal} (outcome recording has been API-only since
   * 2026-02-27), so this is a latent defect rather than one a reader can reach; and {@code onSaved}
   * is a caller-supplied function — here a mock — so firing it late reads no {@code window} and
   * throws nothing. The measured vitest-5 unhandled error came from `ModelSelectionView`'s
   * {@code setSuccess}, not from this file.
   */
  it('does not hand off to onSaved when it unmounts inside the confirmation window', async () => {
    vi.useFakeTimers();
    try {
      const onSaved = vi.fn();
      recordOutcome.mockResolvedValue({});
      const { unmount } = renderModal({ onSaved });

      fireEvent.click(screen.getByTestId('outcome-submit'));
      await settleSave();
      expect(screen.getByTestId('outcome-saved-message')).toBeInTheDocument();

      // Positive control: a timer really is pending, so a green result below cannot come from a
      // component that never scheduled the hand-off at all.
      expect(vi.getTimerCount()).toBeGreaterThan(0);
      expect(onSaved).not.toHaveBeenCalled();

      unmount();
      await vi.advanceTimersByTimeAsync(3000);

      expect(onSaved).not.toHaveBeenCalled();
    } finally {
      vi.useRealTimers();
    }
  });

  /**
   * ⚠️ The companion hole: the timer is armed after {@code recordOutcome} resolves, so unmounting
   * while the save is still in flight — Cancel stays enabled during {@code isPending}, and the
   * backdrop closes too — leaves the cleanup nothing to cancel and lets the continuation arm an
   * unowned timer. Caught by the {@code mounted} guard, not by the cleanup.
   */
  it('does not arm the hand-off when the save settles after it unmounts', async () => {
    vi.useFakeTimers();
    try {
      const onSaved = vi.fn();
      recordOutcome.mockReturnValue(new Promise((resolve) => {
        releasePendingSave = () => resolve({});
      }));
      const { unmount } = renderModal({ onSaved });

      fireEvent.click(screen.getByTestId('outcome-submit'));
      await act(async () => {
        await vi.advanceTimersByTimeAsync(0);
      });

      unmount();
      await act(async () => {
        releasePendingSave();
        await vi.advanceTimersByTimeAsync(0);
      });

      await vi.advanceTimersByTimeAsync(3000);
      expect(onSaved).not.toHaveBeenCalled();
    } finally {
      vi.useRealTimers();
    }
  });

  it('holds the hand-off until the confirmation window elapses, then calls onSaved once', async () => {
    vi.useFakeTimers();
    try {
      const onSaved = vi.fn();
      recordOutcome.mockResolvedValue({});
      renderModal({ onSaved });

      fireEvent.click(screen.getByTestId('outcome-submit'));
      await settleSave();

      // Below the threshold as well as at it — otherwise shortening the delay (1500 → 500) would
      // snatch the confirmation away early with every assertion here still passing.
      await vi.advanceTimersByTimeAsync(1499);
      expect(onSaved).not.toHaveBeenCalled();

      await vi.advanceTimersByTimeAsync(1);
      expect(onSaved).toHaveBeenCalledTimes(1);
    } finally {
      vi.useRealTimers();
    }
  });

  it('renders as a dialog with aria-modal', () => {
    renderModal();
    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveAttribute('aria-modal', 'true');
  });

  it('renders the form with data-testid', () => {
    renderModal();
    expect(screen.getByTestId('outcome-form')).toBeInTheDocument();
  });
});
