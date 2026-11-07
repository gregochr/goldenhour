import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import TravelRangeForm from '../components/traveldays/TravelRangeForm.jsx';
import { NOTE_MAX_LENGTH } from '../utils/travelDays.js';

const FORM = { startDate: '2026-11-10', endDate: '2026-11-12', note: 'London — work' };

function setup(props = {}) {
  const onChange = vi.fn();
  const onSubmit = vi.fn();
  render(
    <TravelRangeForm
      idPrefix="travel-add"
      form={FORM}
      errors={{}}
      onChange={onChange}
      onSubmit={onSubmit}
      {...props}
    />,
  );
  return { onChange, onSubmit };
}

afterEach(() => {
  delete HTMLInputElement.prototype.showPicker;
});

describe('TravelRangeForm', () => {
  it('shows the three values under their labels', () => {
    setup();
    expect(screen.getByLabelText('From (inclusive)')).toHaveValue('2026-11-10');
    expect(screen.getByLabelText('To (inclusive)')).toHaveValue('2026-11-12');
    expect(screen.getByLabelText('Note (optional)')).toHaveValue('London — work');
  });

  it('reports each field with its name and new value', () => {
    const { onChange } = setup();
    fireEvent.change(screen.getByLabelText('From (inclusive)'), { target: { value: '2026-11-11' } });
    expect(onChange).toHaveBeenLastCalledWith('startDate', '2026-11-11');
    fireEvent.change(screen.getByLabelText('To (inclusive)'), { target: { value: '2026-11-13' } });
    expect(onChange).toHaveBeenLastCalledWith('endDate', '2026-11-13');
    fireEvent.change(screen.getByLabelText('Note (optional)'), { target: { value: 'Paris' } });
    expect(onChange).toHaveBeenLastCalledWith('note', 'Paris');
  });

  it('limits the note length', () => {
    setup();
    expect(screen.getByLabelText('Note (optional)')).toHaveAttribute('maxlength', String(NOTE_MAX_LENGTH));
  });

  it('submits through the form without a page reload', () => {
    const { onSubmit } = setup();
    const form = screen.getByTestId('travel-add-form');
    expect(form).toHaveAttribute('id', 'travel-add-form');
    expect(fireEvent.submit(form)).toBe(false);
    expect(onSubmit).toHaveBeenCalledTimes(1);
  });

  it('prefixes every id and test id with the panel it sits in', () => {
    setup({ idPrefix: 'travel-edit' });
    expect(screen.getByTestId('travel-edit-form')).toBeInTheDocument();
    expect(screen.getByTestId('travel-edit-start')).toBeInTheDocument();
    expect(screen.getByTestId('travel-edit-end')).toBeInTheDocument();
    expect(screen.getByTestId('travel-edit-note')).toBeInTheDocument();
  });

  it('shows an error under only the field that has one and marks it invalid', () => {
    setup({ errors: { endDate: 'End must be on or after the start' } });
    expect(screen.getByTestId('travel-add-end-error')).toHaveTextContent('End must be on or after the start');
    expect(screen.getByLabelText('To (inclusive)')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText('From (inclusive)')).not.toHaveAttribute('aria-invalid');
    expect(screen.getByLabelText('Note (optional)')).not.toHaveAttribute('aria-invalid');
    expect(screen.queryByTestId('travel-add-start-error')).toBeNull();
    expect(screen.queryByTestId('travel-add-note-error')).toBeNull();
  });

  it('flags start and note errors too', () => {
    setup({ errors: { startDate: 'Pick a start', note: 'Too long' } });
    expect(screen.getByTestId('travel-add-start-error')).toHaveTextContent('Pick a start');
    expect(screen.getByTestId('travel-add-note-error')).toHaveTextContent('Too long');
    expect(screen.getByLabelText('From (inclusive)')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText('Note (optional)')).toHaveAttribute('aria-invalid', 'true');
  });

  it('disables all three inputs while a save is in flight', () => {
    setup({ disabled: true });
    expect(screen.getByLabelText('From (inclusive)')).toBeDisabled();
    expect(screen.getByLabelText('To (inclusive)')).toBeDisabled();
    expect(screen.getByLabelText('Note (optional)')).toBeDisabled();
  });

  it('opens the native picker when a date field is clicked anywhere', () => {
    const showPicker = vi.fn();
    HTMLInputElement.prototype.showPicker = showPicker;
    setup();
    fireEvent.click(screen.getByLabelText('From (inclusive)'));
    fireEvent.click(screen.getByLabelText('To (inclusive)'));
    expect(showPicker).toHaveBeenCalledTimes(2);
  });

  it('does not open a picker from the note field', () => {
    const showPicker = vi.fn();
    HTMLInputElement.prototype.showPicker = showPicker;
    setup();
    fireEvent.click(screen.getByLabelText('Note (optional)'));
    expect(showPicker).not.toHaveBeenCalled();
  });

  it('survives a browser without showPicker', () => {
    setup();
    expect(() => fireEvent.click(screen.getByLabelText('From (inclusive)'))).not.toThrow();
  });

  it('survives showPicker throwing (already open, or no user gesture)', () => {
    HTMLInputElement.prototype.showPicker = vi.fn(() => { throw new DOMException('already open'); });
    setup();
    expect(() => fireEvent.click(screen.getByLabelText('From (inclusive)'))).not.toThrow();
  });
});
