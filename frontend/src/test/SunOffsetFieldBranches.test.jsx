import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import SunOffsetField from '../components/scheduler/SunOffsetField.jsx';

const previewState = vi.hoisted(() => ({ value: { status: 'idle', preview: null } }));
vi.mock('../hooks/useSchedulePreview.js', () => ({ default: () => previewState.value }));

const renderField = (over = {}) => {
  const onEvent = vi.fn();
  const onOffset = vi.fn();
  render(
    <SunOffsetField
      idPrefix="f"
      jobKey="some_job"
      event="SUNSET"
      offset={90}
      storedOffset={90}
      onEvent={onEvent}
      onOffset={onOffset}
      {...over}
    />,
  );
  return { onEvent, onOffset };
};

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'], now: new Date('2026-11-02T12:00:00Z') });
  previewState.value = { status: 'idle', preview: null };
});

afterEach(() => {
  vi.useRealTimers();
});

describe('SunOffsetField preview line', () => {
  it('says nothing while idle or pending, and shows the Checking note only while pending', () => {
    renderField();
    expect(screen.getByTestId('f-preview')).toBeEmptyDOMElement();
    expect(screen.queryByTestId('f-checking')).toBeNull();
  });

  it('shows Checking while pending', () => {
    previewState.value = { status: 'pending', preview: null };
    renderField();
    expect(screen.getByTestId('f-checking')).toBeInTheDocument();
    expect(screen.getByTestId('f-preview')).toBeEmptyDOMElement();
  });

  it('warns when the check itself failed', () => {
    previewState.value = { status: 'failed', preview: null };
    renderField();
    const line = screen.getByTestId('f-preview');
    expect(line).toHaveTextContent("Couldn't check this schedule.");
    expect(line).toHaveClass('text-verdict-marginal');
  });

  it('shows the served error for an invalid rule', () => {
    previewState.value = { status: 'ready', preview: { valid: false, error: 'Offset too small' } };
    renderField();
    const line = screen.getByTestId('f-preview');
    expect(line).toHaveTextContent('Offset too small');
    expect(line).toHaveClass('text-red-400');
  });

  it("words today's fire and the yearly range", () => {
    previewState.value = {
      status: 'ready',
      preview: {
        valid: true,
        nextFires: ['2026-11-02T15:10:00Z'],
        yearRange: { earliest: '14:00', latest: '19:30' },
      },
    };
    renderField();
    expect(screen.getByTestId('f-preview')).toHaveTextContent(
      "Today that's 15:10. Across the year it moves between 14:00 and 19:30 UK time.",
    );
  });

  it('words a later fire as Next fire, without a range', () => {
    previewState.value = {
      status: 'ready',
      preview: { valid: true, nextFires: ['2026-11-03T15:10:00Z'] },
    };
    renderField();
    expect(screen.getByTestId('f-preview')).toHaveTextContent('Next fire Tomorrow 15:10.');
    expect(screen.getByTestId('f-preview')).not.toHaveTextContent('Across the year');
  });

  it('shows only the range when there is no next fire', () => {
    previewState.value = {
      status: 'ready',
      preview: { valid: true, nextFires: [], yearRange: { earliest: '14:00', latest: '19:30' } },
    };
    renderField();
    expect(screen.getByTestId('f-preview').textContent).toBe(
      'Across the year it moves between 14:00 and 19:30 UK time.',
    );
  });

  it('shows an empty line when a valid preview has nothing to say', () => {
    previewState.value = { status: 'ready', preview: { valid: true } };
    renderField();
    expect(screen.getByTestId('f-preview')).toHaveTextContent('');
  });
});

describe('SunOffsetField inputs', () => {
  it('shows the stored value beside the readout when the offset is off the grid, with the thumb snapped', () => {
    renderField({ offset: 93 });
    expect(screen.getByTestId('f-offset-readout')).toHaveTextContent('(stored: 1h 33m)');
    expect(screen.getByTestId('f-offset')).toHaveValue('95');
  });

  it('omits the stored note for an on-grid offset and reports slider and select changes', () => {
    const { onEvent, onOffset } = renderField();
    expect(screen.getByTestId('f-offset-readout')).not.toHaveTextContent('stored');
    fireEvent.change(screen.getByTestId('f-offset'), { target: { value: '120' } });
    expect(onOffset).toHaveBeenCalledWith(120);
    fireEvent.change(screen.getByTestId('f-event'), { target: { value: 'SUNRISE' } });
    expect(onEvent).toHaveBeenCalledWith('SUNRISE');
  });

  it('explains a missing stored offset, shows an offset error and wires it to the slider', () => {
    renderField({ storedOffset: null, offsetError: 'Too late' });
    expect(screen.getByTestId('f-no-stored')).toHaveTextContent('slider starts at 1h 30m');
    expect(screen.getByTestId('f-offset-error')).toHaveTextContent('Too late');
    const slider = screen.getByTestId('f-offset');
    expect(slider).toHaveAttribute('aria-invalid', 'true');
    expect(slider).toHaveAttribute('aria-describedby', 'f-offset-error');
  });

  it('has no error wiring and no no-stored note when all is well; disables the controls while saving', () => {
    renderField({ disabled: true });
    const slider = screen.getByTestId('f-offset');
    expect(slider).toHaveAttribute('aria-invalid', 'false');
    expect(slider).not.toHaveAttribute('aria-describedby');
    expect(screen.queryByTestId('f-no-stored')).toBeNull();
    expect(slider).toBeDisabled();
    expect(screen.getByTestId('f-event')).toBeDisabled();
  });

  it('links the intraday refresh to Runs Timing, and no other job', () => {
    renderField({ jobKey: 'intraday_forecast_refresh' });
    expect(screen.getByTestId('f-timing-link').getAttribute('href')).toMatch(/runs/);
  });

  it('shows no timing link for other jobs', () => {
    renderField();
    expect(screen.queryByTestId('f-timing-link')).toBeNull();
  });
});
