import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import PasswordInput from '../components/auth/PasswordInput.jsx';

describe('PasswordInput', () => {
  it('renders a password-type .field input with the given id, name and autocomplete', () => {
    render(<PasswordInput id="pw" name="password" testId="pw-input" autoComplete="current-password" />);

    const input = screen.getByTestId('pw-input');
    expect(input).toHaveAttribute('id', 'pw');
    expect(input).toHaveAttribute('name', 'password');
    expect(input).toHaveAttribute('type', 'password');
    expect(input).toHaveAttribute('autocomplete', 'current-password');
    expect(input).toBeRequired();
    // `.field` replaces the class string; only the layout utility that clears the eye may remain.
    expect(input.className).toBe('field pr-10');
  });

  it('toggles type and the aria-label with the eye, both ways', () => {
    render(<PasswordInput id="pw" testId="pw-input" autoComplete="new-password" />);
    const input = screen.getByTestId('pw-input');

    fireEvent.click(screen.getByRole('button', { name: 'Show password' }));
    expect(input).toHaveAttribute('type', 'text');
    expect(screen.queryByRole('button', { name: 'Show password' })).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'Hide password' }));
    expect(input).toHaveAttribute('type', 'password');
    expect(screen.getByTestId('pw-input-toggle')).toHaveAttribute('aria-label', 'Show password');
  });

  it('is a plain type="button" so it cannot submit the form it sits in', () => {
    const onSubmit = vi.fn((e) => e.preventDefault());
    render(
      <form onSubmit={onSubmit}>
        <PasswordInput id="pw" autoComplete="new-password" />
      </form>,
    );

    const eye = screen.getByRole('button', { name: 'Show password' });
    expect(eye).toHaveAttribute('type', 'button');
    fireEvent.click(eye);
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('is controlled when a value is given: shows it and reports edits', () => {
    const onChange = vi.fn();
    render(<PasswordInput id="pw" testId="pw-input" autoComplete="new-password" value="abc" onChange={onChange} />);

    const input = screen.getByTestId('pw-input');
    expect(input).toHaveValue('abc');
    fireEvent.change(input, { target: { value: 'abcd' } });
    expect(onChange).toHaveBeenCalledTimes(1);
    // Controlled: React puts the prop back, because the parent did not update it.
    expect(input).toHaveValue('abc');
  });

  it('is uncontrolled without a value: keeps what is typed, with no React warning', () => {
    const error = vi.spyOn(console, 'error').mockImplementation(() => {});
    render(<PasswordInput id="pw" name="password" testId="pw-input" autoComplete="current-password" />);

    const input = screen.getByTestId('pw-input');
    fireEvent.change(input, { target: { value: 'typed' } });
    expect(input).toHaveValue('typed');
    expect(error).not.toHaveBeenCalled();
    error.mockRestore();
  });

  it('puts the typed value in FormData under its name (how the sign-in form reads it)', () => {
    render(
      <form data-testid="f">
        <PasswordInput id="pw" name="password" testId="pw-input" autoComplete="current-password" />
      </form>,
    );

    fireEvent.change(screen.getByTestId('pw-input'), { target: { value: 's3cret!' } });
    expect(new FormData(screen.getByTestId('f')).get('password')).toBe('s3cret!');
  });

  it('honours disabled and required={false}', () => {
    render(<PasswordInput id="pw" testId="pw-input" autoComplete="new-password" disabled required={false} />);

    const input = screen.getByTestId('pw-input');
    expect(input).toBeDisabled();
    expect(input).not.toBeRequired();
  });

  it('gives the eye no test id when the input has none', () => {
    render(<PasswordInput id="pw" autoComplete="new-password" />);
    expect(screen.getByRole('button', { name: 'Show password' })).not.toHaveAttribute('data-testid');
  });

  it('names the field in the eye\'s accessible name when given an eyeLabel, both ways', () => {
    render(<PasswordInput id="pw" testId="pw-input" autoComplete="new-password" eyeLabel="new password" />);

    expect(screen.queryByRole('button', { name: 'Show password' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Show new password' }));
    expect(screen.getByTestId('pw-input')).toHaveAttribute('type', 'text');
    expect(screen.getByTestId('pw-input-toggle')).toHaveAttribute('aria-label', 'Hide new password');
  });
});
