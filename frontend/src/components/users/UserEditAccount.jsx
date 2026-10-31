import { useState } from 'react';
import PropTypes from 'prop-types';
import PanelShell from '../data/PanelShell.jsx';
import { updateUserEmail, updateUserRole } from '../../api/userApi.js';
import {
  ROLE_OPTIONS, STATUS_LABELS, roleChangeNote, roleLabel, statusOf,
} from '../../utils/userAdmin.js';
import { inputClass, labelClass, selectClass } from './userFormClasses.js';

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/**
 * Edit account, in the panel rather than on a screen of its own (handoff B5). Email and Role, the role
 * select in plain labels with the values still {@code LITE_USER} and the rest. Changing the role shows an
 * amber note saying what happens to the reader's sub-regions. The server's answer to an email edit is kept: a
 * pending registration's "the old verification link no longer works" message reaches the admin.
 *
 * @param {object} props
 * @param {object} props.user the user summary being edited
 * @param {number|null} props.pickCount the picks the account holds (kept picks while Pro or Admin); null
 *   while unknown, which hides the role note
 * @param {number} props.limit the Lite pick limit
 * @param {() => void} props.onClose closes the whole panel
 * @param {() => void} props.onCancel back to the reader view
 * @param {(message: string|null) => void} props.onSaved told after a save, with the server's email message
 *   when it said something beyond "Updated"
 * @param {(message: string|null) => Promise<void>|void} props.onPartial told when the email saved but the role
 *   did not: the list must refetch, and the email message stays visible beside the error
 */
export default function UserEditAccount({ user, pickCount, limit, onClose, onCancel, onSaved, onPartial }) {
  const [email, setEmail] = useState(user.email || '');
  const [role, setRole] = useState(user.role);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState(null);

  const note = pickCount == null
    ? null
    : roleChangeNote({ from: user.role, to: role, username: user.username, pickCount, limit });

  async function handleSave() {
    const trimmed = email.trim();
    if (!trimmed) {
      setError('Email address is required.');
      return;
    }
    if (!EMAIL_PATTERN.test(trimmed)) {
      setError('Please enter a valid email address.');
      return;
    }
    setError('');
    setSaving(true);
    try {
      let message = null;
      let emailSaved = false;
      if (trimmed !== (user.email || '')) {
        const result = await updateUserEmail(user.id, trimmed);
        emailSaved = true;
        if (result?.message && result.message !== 'Updated') message = result.message;
      }
      if (role !== user.role) {
        try {
          await updateUserRole(user.id, role);
        } catch (err) {
          if (emailSaved) {
            setNotice(message);
            await onPartial();
          }
          throw err;
        }
      }
      onSaved(message);
    } catch (err) {
      setError(err?.response?.data?.error ?? 'Failed to save changes.');
    } finally {
      setSaving(false);
    }
  }

  return (
    <PanelShell
      title={`Edit ${user.username}`}
      subtitle={`${roleLabel(user.role)} · ${STATUS_LABELS[statusOf(user)]}`}
      onClose={onClose}
      testIdPrefix="user-panel"
      testId="user-edit-account"
    >
      <div className="flex flex-col gap-4 px-[18px] py-4 max-[639px]:px-4">
        <div>
          <label htmlFor="edit-user-email" className={labelClass}>Email</label>
          <input
            id="edit-user-email"
            type="email"
            className={inputClass}
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            data-testid={`edit-email-${user.id}`}
          />
        </div>
        <div>
          <label htmlFor="edit-user-role" className={labelClass}>Role</label>
          <select
            id="edit-user-role"
            className={selectClass}
            value={role}
            onChange={(e) => setRole(e.target.value)}
            data-testid={`edit-role-${user.id}`}
          >
            {ROLE_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>{option.label}</option>
            ))}
          </select>
          {note && (
            <p
              className="mt-2 rounded border border-verdict-marginal/50 bg-verdict-marginal/10 px-3 py-2 text-xs text-plex-text"
              data-testid="role-change-note"
            >
              {note}
            </p>
          )}
        </div>

        {notice && <p className="text-xs text-plex-text-secondary" data-testid="edit-notice">{notice}</p>}
        {error && <p className="text-xs text-plex-error" role="alert">{error}</p>}

        <div className="flex items-center justify-end gap-3">
          <button
            type="button"
            className="rounded-sm px-1 py-0.5 text-sm text-plex-text-secondary hover:text-plex-text focus-visible:outline-2 focus-visible:outline-plex-gold max-[639px]:min-h-11"
            onClick={onCancel}
            disabled={saving}
            data-testid="edit-cancel"
          >
            Cancel
          </button>
          <button
            type="button"
            className="btn-primary max-[639px]:min-h-11"
            onClick={handleSave}
            disabled={saving}
            data-testid={`save-edit-${user.id}`}
          >
            {saving ? 'Saving…' : 'Save changes'}
          </button>
        </div>
      </div>
    </PanelShell>
  );
}

UserEditAccount.propTypes = {
  user: PropTypes.shape({
    id: PropTypes.number.isRequired,
    username: PropTypes.string.isRequired,
    email: PropTypes.string,
    role: PropTypes.string.isRequired,
    enabled: PropTypes.bool,
    awaitingVerification: PropTypes.bool,
  }).isRequired,
  pickCount: PropTypes.number,
  limit: PropTypes.number,
  onClose: PropTypes.func.isRequired,
  onCancel: PropTypes.func.isRequired,
  onSaved: PropTypes.func.isRequired,
  onPartial: PropTypes.func.isRequired,
};
