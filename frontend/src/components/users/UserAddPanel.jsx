import { useState } from 'react';
import PropTypes from 'prop-types';
import PanelShell from '../data/PanelShell.jsx';
import { createUser } from '../../api/userApi.js';
import { ROLE_OPTIONS } from '../../utils/userAdmin.js';
import { inputClass, labelClass, selectClass } from './userFormClasses.js';

const USERNAME_PATTERN = /^[a-zA-Z0-9_-]{3,30}$/;
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/**
 * Add user, opened in the panel so the list stays where it is (handoff B7). The same fields and checks as the
 * screen it replaces; the role options read Lite, Pro and Admin while the values stay {@code LITE_USER} and so on.
 *
 * @param {object} props
 * @param {() => void} props.onClose closes the panel
 * @param {(created: object) => void} props.onCreated told the created user once the server has made it
 */
export default function UserAddPanel({ onClose, onCreated }) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [email, setEmail] = useState('');
  const [role, setRole] = useState('LITE_USER');
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);

  const usernameValid = USERNAME_PATTERN.test(username.trim());
  const emailValid = EMAIL_PATTERN.test(email.trim());

  async function handleCreate() {
    const trimmedUsername = username.trim();
    const trimmedEmail = email.trim();
    if (!trimmedUsername || !password.trim()) {
      setError('Username and password are required.');
      return;
    }
    if (!usernameValid) {
      setError('Username must be 3-30 characters: letters, numbers, hyphens, or underscores.');
      return;
    }
    if (!trimmedEmail) {
      setError('Email address is required.');
      return;
    }
    if (!emailValid) {
      setError('Please enter a valid email address.');
      return;
    }
    setError('');
    setSaving(true);
    try {
      const created = await createUser({ username: trimmedUsername, password, email: trimmedEmail, role });
      onCreated(created);
    } catch (err) {
      setError(err?.response?.data?.error ?? 'Failed to create user.');
    } finally {
      setSaving(false);
    }
  }

  return (
    <PanelShell title="Add user" onClose={onClose} testIdPrefix="user-panel" testId="user-add-panel">
      <div className="flex flex-col gap-4 px-[18px] py-4 max-[639px]:px-4">
        <div>
          <label htmlFor="add-user-username" className={labelClass}>Username</label>
          <input
            id="add-user-username"
            type="text"
            autoComplete="off"
            className={`${inputClass} ${username.length > 0 && !usernameValid ? '!border-red-700' : ''}`}
            placeholder="e.g. janesmith"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
          />
          <p className={`mt-1 text-xs ${username.length > 0 && !usernameValid ? 'text-red-400' : 'text-plex-text-muted'}`}>
            3-30 characters, letters, numbers, hyphens, or underscores
          </p>
        </div>
        <div>
          <label htmlFor="add-user-email" className={labelClass}>Email</label>
          <input
            id="add-user-email"
            type="email"
            autoComplete="off"
            className={`${inputClass} ${email.length > 0 && !emailValid ? '!border-red-700' : ''}`}
            placeholder="jane@example.com"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
          />
          {email.length > 0 && !emailValid && (
            <p className="mt-1 text-xs text-red-400">Please enter a valid email address</p>
          )}
        </div>
        <div>
          <label htmlFor="add-user-password" className={labelClass}>Password</label>
          <div className="relative">
            <input
              id="add-user-password"
              type={showPassword ? 'text' : 'password'}
              autoComplete="new-password"
              className={`${inputClass} pr-16`}
              placeholder="Temporary password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
            <button
              type="button"
              aria-label={showPassword ? 'Hide password' : 'Show password'}
              onClick={() => setShowPassword((v) => !v)}
              className="absolute inset-y-0 right-0 flex items-center px-3 text-xs text-plex-text-secondary hover:text-plex-text"
            >
              {showPassword ? 'Hide' : 'Show'}
            </button>
          </div>
        </div>
        <div>
          <label htmlFor="add-user-role" className={labelClass}>Role</label>
          <select id="add-user-role" className={selectClass} value={role} onChange={(e) => setRole(e.target.value)}>
            {ROLE_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>{option.label}</option>
            ))}
          </select>
        </div>

        {error && <p className="text-xs text-red-400" role="alert">{error}</p>}

        <div className="flex items-center justify-end gap-3">
          <button
            type="button"
            className="rounded-sm px-1 py-0.5 text-sm text-plex-text-secondary hover:text-plex-text focus-visible:outline-2 focus-visible:outline-plex-gold max-[639px]:min-h-11"
            onClick={onClose}
            disabled={saving}
          >
            Cancel
          </button>
          <button type="button" className="btn-primary max-[639px]:min-h-11" onClick={handleCreate} disabled={saving} data-testid="create-user">
            {saving ? 'Saving...' : 'Create user'}
          </button>
        </div>
      </div>
    </PanelShell>
  );
}

UserAddPanel.propTypes = {
  onClose: PropTypes.func.isRequired,
  onCreated: PropTypes.func.isRequired,
};
