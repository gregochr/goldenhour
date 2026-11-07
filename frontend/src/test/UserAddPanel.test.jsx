import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../api/userApi.js', () => ({ createUser: vi.fn() }));

import UserAddPanel from '../components/users/UserAddPanel.jsx';
import { createUser } from '../api/userApi.js';

function setup() {
  const onClose = vi.fn();
  const onCreated = vi.fn();
  render(<UserAddPanel onClose={onClose} onCreated={onCreated} />);
  return { onClose, onCreated };
}

const type = (label, value) => fireEvent.change(screen.getByLabelText(label), { target: { value } });
const submit = () => fireEvent.click(screen.getByTestId('create-user'));

function fill({ username = 'janesmith', email = 'jane@example.com', password = 'secret' } = {}) {
  type('Username', username);
  type('Email', email);
  type('Password', password);
}

beforeEach(() => {
  createUser.mockReset();
});

describe('UserAddPanel validation', () => {
  it('requires a username and password before calling the server', () => {
    setup();
    type('Email', 'jane@example.com');
    submit();
    expect(screen.getByRole('alert')).toHaveTextContent('Username and password are required.');
    expect(createUser).not.toHaveBeenCalled();
  });

  it('treats a whitespace-only password as missing', () => {
    setup();
    fill({ password: '   ' });
    submit();
    expect(screen.getByRole('alert')).toHaveTextContent('Username and password are required.');
  });

  it('rejects a username that is too short or has illegal characters', () => {
    setup();
    fill({ username: 'ab' });
    submit();
    expect(screen.getByRole('alert')).toHaveTextContent(
      'Username must be 3-30 characters: letters, numbers, hyphens, or underscores.',
    );
    type('Username', 'bad name!');
    submit();
    expect(screen.getByRole('alert')).toHaveTextContent('Username must be 3-30 characters');
    expect(createUser).not.toHaveBeenCalled();
  });

  it('requires an email', () => {
    setup();
    fill({ email: '' });
    submit();
    expect(screen.getByRole('alert')).toHaveTextContent('Email address is required.');
  });

  it('rejects a malformed email', () => {
    setup();
    fill({ email: 'not-an-email' });
    submit();
    expect(screen.getByRole('alert')).toHaveTextContent('Please enter a valid email address.');
    expect(createUser).not.toHaveBeenCalled();
  });

  it('flags an invalid username and email inline only once something is typed', () => {
    setup();
    expect(screen.getByLabelText('Username').className).not.toContain('!border-red-700');
    expect(screen.queryByText('Please enter a valid email address')).toBeNull();
    type('Username', 'a');
    type('Email', 'nope');
    expect(screen.getByLabelText('Username').className).toContain('!border-red-700');
    expect(screen.getByLabelText('Email').className).toContain('!border-red-700');
    expect(screen.getByText('Please enter a valid email address')).toBeInTheDocument();
    type('Username', 'janesmith');
    type('Email', 'jane@example.com');
    expect(screen.getByLabelText('Username').className).not.toContain('!border-red-700');
    expect(screen.getByLabelText('Email').className).not.toContain('!border-red-700');
    expect(screen.queryByText('Please enter a valid email address')).toBeNull();
  });
});

describe('UserAddPanel password field', () => {
  it('toggles between masked and visible', () => {
    setup();
    const field = screen.getByLabelText('Password');
    expect(field).toHaveAttribute('type', 'password');
    fireEvent.click(screen.getByRole('button', { name: 'Show password' }));
    expect(field).toHaveAttribute('type', 'text');
    fireEvent.click(screen.getByRole('button', { name: 'Hide password' }));
    expect(field).toHaveAttribute('type', 'password');
  });
});

describe('UserAddPanel create', () => {
  it('sends trimmed fields and the chosen role, then reports the created user', async () => {
    createUser.mockResolvedValue({ id: 9, username: 'janesmith' });
    const { onCreated } = setup();
    fill({ username: '  janesmith ', email: ' jane@example.com ' });
    fireEvent.change(screen.getByLabelText('Role'), { target: { value: 'PRO_USER' } });
    submit();
    await vi.waitFor(() => expect(onCreated).toHaveBeenCalledWith({ id: 9, username: 'janesmith' }));
    expect(createUser).toHaveBeenCalledWith({
      username: 'janesmith', password: 'secret', email: 'jane@example.com', role: 'PRO_USER',
    });
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('defaults the role to Lite and offers Lite, Pro and Admin', () => {
    setup();
    expect(screen.getByLabelText('Role')).toHaveValue('LITE_USER');
    expect(screen.getAllByRole('option').map((o) => o.textContent)).toEqual(['Lite', 'Pro', 'Admin']);
  });

  it('disables both buttons and shows Saving... while the request is in flight', async () => {
    let resolve;
    createUser.mockReturnValue(new Promise((r) => { resolve = r; }));
    const { onCreated } = setup();
    fill();
    submit();
    const button = await screen.findByRole('button', { name: 'Saving...' });
    expect(button).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeDisabled();
    resolve({ id: 1 });
    await screen.findByRole('button', { name: 'Create user' });
    expect(onCreated).toHaveBeenCalledWith({ id: 1 });
  });

  it('shows the server error message on failure and re-enables the form', async () => {
    createUser.mockRejectedValue({ response: { data: { error: 'Username already exists' } } });
    const { onCreated } = setup();
    fill();
    submit();
    expect(await screen.findByRole('alert')).toHaveTextContent('Username already exists');
    expect(onCreated).not.toHaveBeenCalled();
    expect(screen.getByTestId('create-user')).toBeEnabled();
  });

  it('falls back to a generic message when the failure carries none', async () => {
    createUser.mockRejectedValue(new Error('network'));
    setup();
    fill();
    submit();
    expect(await screen.findByRole('alert')).toHaveTextContent('Failed to create user.');
  });

  it('clears an earlier validation error when a later attempt passes', async () => {
    createUser.mockResolvedValue({ id: 2 });
    const { onCreated } = setup();
    fill({ email: '' });
    submit();
    expect(screen.getByRole('alert')).toHaveTextContent('Email address is required.');
    type('Email', 'jane@example.com');
    submit();
    await vi.waitFor(() => expect(onCreated).toHaveBeenCalled());
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('closes from Cancel', () => {
    const { onClose } = setup();
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
