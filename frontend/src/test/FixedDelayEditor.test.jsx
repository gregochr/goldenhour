import { render, screen, fireEvent, act } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import FixedDelayEditor from '../components/scheduler/FixedDelayEditor.jsx';

const JOB = { jobKey: 'tide_refresh', displayName: 'Tide refresh', fixedDelayMs: 300_000 };

function setup(onSave = vi.fn().mockResolvedValue(undefined), job = JOB) {
  const onCancel = vi.fn();
  render(<FixedDelayEditor job={job} onSave={onSave} onCancel={onCancel} />);
  return { onSave, onCancel };
}

const save = () => screen.getByTestId('save-btn-tide_refresh');
const preset = (ms) => screen.getByTestId(`fixed-delay-tide_refresh-${ms}`);

describe('FixedDelayEditor', () => {
  it('keeps Save off until the choice differs from the stored delay', () => {
    setup();
    expect(preset(300000)).toHaveAttribute('aria-pressed', 'true');
    expect(save()).toBeDisabled();
    fireEvent.click(preset(600000));
    expect(save()).toBeEnabled();
    fireEvent.click(preset(300000));
    expect(save()).toBeDisabled();
  });

  it('does not save when the unchanged Save is pressed', () => {
    const { onSave } = setup();
    fireEvent.click(save());
    expect(onSave).not.toHaveBeenCalled();
  });

  it('saves the chosen delay as a string of milliseconds with the job', async () => {
    const { onSave } = setup();
    fireEvent.click(preset(600000));
    fireEvent.click(save());
    await vi.waitFor(() => expect(onSave).toHaveBeenCalledWith(JOB, '600000'));
  });

  it('sends only one save when Save is clicked twice before React re-renders', async () => {
    let resolve;
    const onSave = vi.fn(() => new Promise((r) => { resolve = r; }));
    setup(onSave);
    fireEvent.click(preset(600000));
    const btn = save();

    // Two native clicks in ONE act: the button is still enabled in the DOM for the second, because React has
    // not re-rendered in between, so only the in-flight ref stops a double submit.
    act(() => { btn.click(); btn.click(); });

    expect(onSave).toHaveBeenCalledTimes(1);
    expect(save()).toBeDisabled();
    await act(async () => { resolve(); });
    expect(save()).toBeEnabled();
  });

  it('disables the presets while saving and allows a second save after it finishes', async () => {
    let resolve;
    const onSave = vi.fn(() => new Promise((r) => { resolve = r; }));
    setup(onSave);
    fireEvent.click(preset(600000));
    fireEvent.click(save());
    expect(preset(60000)).toBeDisabled();
    await act(async () => { resolve(); });
    expect(preset(60000)).toBeEnabled();
    fireEvent.click(save());
    expect(onSave).toHaveBeenCalledTimes(2);
    await act(async () => { resolve(); });
  });

  it('shows a failed save\'s sentence, keeps the editor open and re-enables Save', async () => {
    const onSave = vi.fn().mockRejectedValue(new Error('boom'));
    setup(onSave);
    fireEvent.click(preset(600000));
    fireEvent.click(save());
    expect(await screen.findByTestId('inline-edit-error-tide_refresh')).toHaveTextContent(
      "Couldn't save Tide refresh's schedule.",
    );
    expect(save()).toBeEnabled();
  });

  it('clears an earlier error when the next save starts', async () => {
    const onSave = vi.fn()
      .mockRejectedValueOnce(new Error('boom'))
      .mockReturnValueOnce(new Promise(() => {}));
    setup(onSave);
    fireEvent.click(preset(600000));
    fireEvent.click(save());
    await screen.findByTestId('inline-edit-error-tide_refresh');
    fireEvent.click(save());
    expect(screen.queryByTestId('inline-edit-error-tide_refresh')).toBeNull();
  });

  it('shows a stored non-preset delay as an extra selected option', () => {
    setup(undefined, { ...JOB, fixedDelayMs: 150_000 });
    expect(preset(150000)).toHaveAttribute('aria-pressed', 'true');
    expect(preset(150000)).toHaveTextContent('2 min 30 s');
  });

  it('focuses the first preset when no delay is chosen', () => {
    setup(undefined, { ...JOB, fixedDelayMs: 0 });
    expect(screen.queryAllByRole('button', { pressed: true })).toHaveLength(0);
    expect(preset(60000)).toHaveFocus();
  });

  it('focuses the chosen delay when opened and cancels from Cancel', () => {
    const { onCancel } = setup();
    expect(preset(300000)).toHaveFocus();
    fireEvent.click(screen.getByTestId('cancel-btn-tide_refresh'));
    expect(onCancel).toHaveBeenCalledTimes(1);
  });
});
