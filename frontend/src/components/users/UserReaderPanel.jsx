import { useCallback, useEffect, useState } from 'react';
import PropTypes from 'prop-types';
import PanelShell from './PanelShell.jsx';
import UserChangePicks from './UserChangePicks.jsx';
import UserEditAccount from './UserEditAccount.jsx';
import ErrorBanner from '../shared/ErrorBanner.jsx';
import useConfirmDialog from '../../hooks/useConfirmDialog.js';
import {
  clearUserSubRegionLock, deleteUser, getUserSubRegions, resendVerification, resetUserPassword,
  updateUserEnabled,
} from '../../api/userApi.js';
import { apiErrorMessage } from '../../utils/apiError.js';
import { formatInstantUk } from '../../utils/conversions.js';
import { formatCivilDay, groupPicksByRegion } from '../../utils/subRegionCopy.js';
import {
  STATUS, STATUS_LABELS, formatJoinedPhrase, formatLastActive, formatSentWhen, roleLabel, statusOf,
} from '../../utils/userAdmin.js';
import { sectionLabelClass, textButtonClass } from './userFormClasses.js';

/** One labelled block of the panel, headed by a mono uppercase label (and an optional right-hand slot). */
function Section({ label, aside = null, children, testId }) {
  return (
    <section className="border-b border-plex-border px-5 py-4 last:border-b-0" aria-label={label} data-testid={testId}>
      <div className="mb-3 flex items-center justify-between gap-3">
        <h3 className={sectionLabelClass}>{label}</h3>
        {aside}
      </div>
      {children}
    </section>
  );
}

Section.propTypes = {
  label: PropTypes.string.isRequired,
  aside: PropTypes.node,
  children: PropTypes.node,
  testId: PropTypes.string,
};

/** A label and its value on one line. */
function Fact({ term, children, testId }) {
  return (
    <div className="flex gap-4 py-0.5 text-sm">
      <dt className="w-28 shrink-0 text-plex-text-muted">{term}</dt>
      <dd className="min-w-0 break-words text-plex-text" data-testid={testId}>{children}</dd>
    </div>
  );
}

Fact.propTypes = { term: PropTypes.string.isRequired, children: PropTypes.node, testId: PropTypes.string };

/** An account action: what it does, one line on why, and its button. */
function ActionRow({ title, hint, children }) {
  return (
    <div className="flex items-center justify-between gap-4 py-2">
      <div className="min-w-0">
        <p className="text-sm text-plex-text">{title}</p>
        <p className="text-xs text-plex-text-secondary">{hint}</p>
      </div>
      {children}
    </div>
  );
}

ActionRow.propTypes = { title: PropTypes.string.isRequired, hint: PropTypes.string.isRequired, children: PropTypes.node };

/** Picks grouped by region, each with its location count when the options carry one. */
function GroupedPicks({ picks, counts, testId }) {
  return (
    <div className="flex flex-col gap-2" data-testid={testId}>
      {groupPicksByRegion(picks).map((group) => (
        <div key={group.regionName}>
          <p className="text-xs text-plex-text-muted">{group.regionName}</p>
          <ul>
            {picks.filter((p) => (p.regionName ?? '') === group.regionName).map((pick) => (
              <li key={pick.areaId} className="text-sm text-plex-text">
                {pick.name}
                {counts.has(pick.areaId) && (
                  <span className="ml-2 font-mono text-[11px] text-plex-text-muted">{counts.get(pick.areaId)}</span>
                )}
              </li>
            ))}
          </ul>
        </div>
      ))}
    </div>
  );
}

GroupedPicks.propTypes = {
  picks: PropTypes.arrayOf(PropTypes.object).isRequired,
  counts: PropTypes.instanceOf(Map).isRequired,
  testId: PropTypes.string,
};

/** The location count of every sub-region the options list. */
function locationCounts(state) {
  const counts = new Map();
  for (const region of state?.options ?? []) {
    for (const option of region.subRegions) counts.set(option.areaId, option.locationCount);
  }
  return counts;
}

/**
 * What the sub-regions section says about a Lite reader's standing, from the admin GET alone.
 *
 * @returns {{tone: string, line: string, note: string|null}}
 */
function standingLine(state, awaiting) {
  if (state.overLimit) {
    return {
      tone: 'text-verdict-marginal',
      line: `Holds ${state.picks.length}, the limit is now ${state.limit}. They keep them until they next change.`,
      note: null,
    };
  }
  switch (state.status) {
    case 'LOCKED': {
      const day = formatCivilDay(state.nextChangeOn);
      return { tone: 'text-verdict-marginal', line: `Locked until ${day}`, note: `They can next swap a pick on ${day}.` };
    }
    case 'FREE_UNTIL':
      return { tone: 'text-plex-ok', line: `Free changes until ${formatCivilDay(state.freeUntil)}`, note: null };
    case 'OPEN':
      return { tone: 'text-plex-text', line: 'Can change now', note: null };
    default:
      return {
        tone: 'text-plex-text',
        line: 'None chosen yet',
        note: awaiting
          ? 'They choose during sign-up, after verifying.'
          : "They'll be asked to choose at their next sign-in.",
      };
  }
}

/**
 * The reader panel (handoff B3): everything the old ▶ row and the Edit screen held, in sections. Account,
 * Sub-regions, Preferences and legal, Account actions; an amber box on top while the account is awaiting
 * verification. Change picks and Edit account swap the body; the confirm dialogs carry the handoff's copy.
 *
 * <p>The sub-region standing is read from {@code GET /api/users/{id}/sub-regions}; nothing is derived here.
 * A Pro or Admin reader shows the line that covers every sub-region, plus the picks kept from Lite when
 * there are any, and no Change or Clear buttons. While {@code enforced} is false a dashed note says the picks
 * are saved but do not limit this reader's ratings yet.
 *
 * @param {object} props
 * @param {object} props.user the user summary
 * @param {'view'|'picks'|'edit'} props.mode which body the panel shows (held by the parent, so Esc can step back)
 * @param {(mode: string) => void} props.onModeChange
 * @param {() => void} props.onClose closes the panel
 * @param {() => Promise<void>} props.onUsersChanged refetches the list
 * @param {(info: {username: string, password: string}) => void} props.onTempPassword shows the temporary
 *   password modal
 * @param {(message: string) => void} props.onToast shows a one-line confirmation
 * @param {() => void} props.onDeleted told after the account is gone
 */
export default function UserReaderPanel({
  user, mode, onModeChange, onClose, onUsersChanged, onTempPassword, onToast, onDeleted,
}) {
  const [sub, setSub] = useState({ loading: true, state: null, error: false });
  const setMode = onModeChange;
  const [actionError, setActionError] = useState('');
  const [busy, setBusy] = useState(null);
  const { openDialog, closeDialog, dialogElement } = useConfirmDialog();

  const status = statusOf(user);
  const awaiting = status === STATUS.AWAITING;
  const isLite = user.role === 'LITE_USER';

  const loadSub = useCallback(async () => {
    try {
      const state = await getUserSubRegions(user.id);
      setSub({ loading: false, state, error: false });
    } catch {
      setSub({ loading: false, state: null, error: true });
    }
  }, [user.id]);

  useEffect(() => {
    let live = true;
    getUserSubRegions(user.id)
      .then((state) => { if (live) setSub({ loading: false, state, error: false }); })
      .catch(() => { if (live) setSub({ loading: false, state: null, error: true }); });
    return () => { live = false; };
  }, [user.id, user.role]);

  function handleResetPassword() {
    openDialog({
      title: `Reset ${user.username}'s password?`,
      message: "We'll email a temporary password. They'll set a new one when they next sign in.",
      confirmLabel: 'Reset password',
      maxWidth: 'sm',
      onConfirm: async () => {
        closeDialog();
        setBusy('reset');
        setActionError('');
        try {
          const data = await resetUserPassword(user.id);
          onTempPassword({ username: user.username, password: data.temporaryPassword });
        } catch {
          setActionError(`Failed to reset password for ${user.username}.`);
        } finally {
          setBusy(null);
        }
      },
    });
  }

  function handleDisable() {
    openDialog({
      title: `Disable ${user.username}?`,
      message: "They won't be able to sign in until you enable the account again.",
      confirmLabel: 'Disable',
      maxWidth: 'sm',
      onConfirm: async () => {
        closeDialog();
        setActionError('');
        try {
          await updateUserEnabled(user.id, false);
          await onUsersChanged();
        } catch (err) {
          setActionError(apiErrorMessage(err, `Failed to disable ${user.username}.`));
        }
      },
    });
  }

  async function handleEnable() {
    setActionError('');
    try {
      await updateUserEnabled(user.id, true);
      await onUsersChanged();
      onToast(`Enabled ${user.username}.`);
    } catch (err) {
      setActionError(apiErrorMessage(err, `Failed to enable ${user.username}.`));
    }
  }

  function handleDelete() {
    openDialog({
      title: `Delete ${user.username}?`,
      message: 'This removes the account for good and emails them to say so.',
      confirmLabel: 'Delete account',
      destructive: true,
      maxWidth: 'sm',
      onConfirm: async () => {
        closeDialog();
        setActionError('');
        try {
          await deleteUser(user.id);
          onDeleted();
        } catch (err) {
          setActionError(apiErrorMessage(err, `Failed to delete ${user.username}.`));
        }
      },
    });
  }

  function handleClearLock() {
    openDialog({
      title: `Clear ${user.username}'s lock?`,
      message: "They'll be able to change their sub-regions straight away. Their next change starts a new 30-day lock.",
      confirmLabel: 'Clear lock',
      maxWidth: 'sm',
      onConfirm: async () => {
        closeDialog();
        setActionError('');
        try {
          await clearUserSubRegionLock(user.id);
          await loadSub();
          await onUsersChanged();
          onToast(`Cleared ${user.username}'s lock.`);
        } catch (err) {
          setActionError(apiErrorMessage(err, `Failed to clear ${user.username}'s lock.`));
        }
      },
    });
  }

  async function handleResend() {
    setBusy('resend');
    setActionError('');
    try {
      await resendVerification(user.id);
      await onUsersChanged();
      onToast(`Verification email sent to ${user.email}.`);
    } catch (err) {
      setActionError(apiErrorMessage(err, `Failed to resend verification for ${user.username}.`));
    } finally {
      setBusy(null);
    }
  }

  if (mode === 'picks' && sub.state) {
    return (
      <UserChangePicks
        user={user}
        state={sub.state}
        onClose={onClose}
        onCancel={() => setMode('view')}
        onSaved={async (next, toast) => {
          setSub({ loading: false, state: next, error: false });
          setMode('view');
          await onUsersChanged();
          onToast(toast);
        }}
      />
    );
  }

  if (mode === 'edit') {
    return (
      <UserEditAccount
        user={user}
        pickCount={sub.state ? sub.state.picks.length : null}
        limit={sub.state?.limit ?? 0}
        onClose={onClose}
        onCancel={() => setMode('view')}
        onPartial={onUsersChanged}
        onSaved={async (message) => {
          setMode('view');
          await onUsersChanged();
          if (message) onToast(message);
        }}
      />
    );
  }

  const state = sub.state;
  const counts = locationCounts(state);
  const standing = state && isLite ? standingLine(state, awaiting) : null;
  const termsDate = formatInstantUk(user.termsAcceptedAt, { day: 'numeric', month: 'short', year: 'numeric' });
  const sentWhen = formatSentWhen(user.verificationSentAt);

  return (
    <>
      <PanelShell
        title={user.username}
        subtitle={`${roleLabel(user.role)} · ${STATUS_LABELS[status]} · joined ${formatJoinedPhrase(user.createdAt)}`}
        onClose={onClose}
        testId="user-reader-panel"
      >
        {awaiting && (
          <div className="border-b border-plex-border px-5 py-4" data-testid="awaiting-verification">
            <p className="rounded border border-verdict-marginal/50 bg-verdict-marginal/10 px-3 py-2 text-sm text-plex-text">
              Hasn&apos;t confirmed their email.{sentWhen ? ` The link was sent ${sentWhen}.` : ''}
            </p>
            {user.email && (
              <button
                type="button"
                className="btn-primary mt-3 text-sm"
                onClick={handleResend}
                disabled={busy === 'resend'}
                data-testid={`resend-verify-${user.id}`}
              >
                {busy === 'resend' ? 'Sending…' : 'Resend verification email'}
              </button>
            )}
          </div>
        )}

        <Section
          label="Account"
          aside={(
            <button type="button" className={textButtonClass} onClick={() => setMode('edit')} data-testid={`edit-user-${user.id}`}>
              Edit
            </button>
          )}
        >
          <dl>
            <Fact term="Email">{user.email || '—'}</Fact>
            <Fact term="Role">{roleLabel(user.role)}</Fact>
            <Fact term="Last active">{formatLastActive(user.lastActiveAt)}</Fact>
          </dl>
        </Section>

        <Section
          label="Sub-regions"
          testId="panel-sub-regions"
          aside={isLite && state ? (
            <span className="font-mono text-xs text-plex-text-secondary" data-testid="panel-pick-count">
              {state.picks.length} of {state.limit}
            </span>
          ) : null}
        >
          {sub.loading && <p className="text-xs text-plex-text-muted">Loading…</p>}
          {sub.error && <p className="text-xs text-plex-error">Couldn&apos;t load this reader&apos;s sub-regions.</p>}
          {state && !isLite && (
            <div className="flex flex-col gap-3">
              <p className="text-sm text-plex-text" data-testid="panel-covers-all">
                {user.role === 'ADMIN' ? 'Admin' : 'Pro'} covers every sub-region.
              </p>
              {state.picks.length > 0 && (
                <div data-testid="panel-kept-picks">
                  <p className="mb-1 text-xs text-plex-text-secondary">
                    Kept from Lite, used again if they return to Lite
                  </p>
                  <GroupedPicks picks={state.picks} counts={counts} />
                </div>
              )}
            </div>
          )}
          {state && isLite && (
            <div className="flex flex-col gap-3">
              {state.picks.length > 0 && <GroupedPicks picks={state.picks} counts={counts} testId="panel-picks" />}
              <div data-testid="panel-standing">
                <p className={`text-sm font-medium ${standing.tone}`}>{standing.line}</p>
                {standing.note && <p className="text-xs text-plex-text-secondary">{standing.note}</p>}
              </div>
              <div className="flex flex-wrap gap-2">
                {!awaiting && (
                  <button type="button" className="btn-secondary text-xs" onClick={() => setMode('picks')} data-testid="change-picks">
                    Change picks
                  </button>
                )}
                {state.status === 'LOCKED' && (
                  <button type="button" className="btn-secondary text-xs" onClick={handleClearLock} data-testid="clear-lock">
                    Clear lock
                  </button>
                )}
              </div>
              {!state.enforced && !awaiting && (
                <p
                  className="rounded border border-dashed border-plex-border-light px-3 py-2 text-xs text-plex-text-secondary"
                  data-testid="filtering-off-note"
                >
                  Filtering is off. Picks are saved but don&apos;t limit this reader&apos;s ratings yet.
                </p>
              )}
            </div>
          )}
        </Section>

        <Section label="Preferences and legal">
          <dl>
            <Fact term="Home postcode" testId={`home-postcode-${user.id}`}>
              {user.homePostcode || <span className="text-plex-text-muted">Not set</span>}
            </Fact>
            <Fact term="Marketing" testId={`marketing-emails-${user.id}`}>{user.marketingEmailOptIn ? 'Yes' : 'No'}</Fact>
            <Fact term="Terms" testId={`terms-accepted-${user.id}`}>
              {termsDate
                ? `Accepted ${termsDate}${user.termsVersion ? ` · ${user.termsVersion}` : ''}`
                : <span className="text-plex-text-muted">Not accepted</span>}
            </Fact>
          </dl>
        </Section>

        <Section label="Account actions">
          <ErrorBanner message={actionError} className="mb-2" data-testid="panel-action-error" />
          {!awaiting && (
            <ActionRow title="Reset password" hint="Emails a temporary password. They set a new one at sign-in.">
              <button
                type="button"
                className="btn-secondary text-xs shrink-0"
                onClick={handleResetPassword}
                disabled={busy === 'reset'}
                data-testid={`reset-password-${user.id}`}
              >
                {busy === 'reset' ? 'Resetting…' : 'Reset'}
              </button>
            </ActionRow>
          )}
          {!awaiting && (user.enabled ? (
            <ActionRow title="Disable account" hint="They can't sign in until it's enabled again.">
              <button type="button" className="btn-secondary text-xs shrink-0" onClick={handleDisable} data-testid={`disable-user-${user.id}`}>
                Disable
              </button>
            </ActionRow>
          ) : (
            <ActionRow title="Enable account" hint="They can sign in again.">
              <button type="button" className="btn-secondary text-xs shrink-0" onClick={handleEnable} data-testid={`enable-user-${user.id}`}>
                Enable
              </button>
            </ActionRow>
          ))}
          <ActionRow title="Delete account" hint="Permanent. They get an email saying so.">
            <button
              type="button"
              className="shrink-0 rounded-lg border border-plex-coral px-3 py-1.5 text-xs font-medium text-plex-coral transition-colors hover:bg-plex-coral/10 focus-visible:outline-2 focus-visible:outline-plex-gold"
              onClick={handleDelete}
              data-testid={`delete-user-${user.id}`}
            >
              Delete
            </button>
          </ActionRow>
        </Section>
      </PanelShell>
      {dialogElement}
    </>
  );
}

UserReaderPanel.propTypes = {
  user: PropTypes.object.isRequired,
  mode: PropTypes.oneOf(['view', 'picks', 'edit']).isRequired,
  onModeChange: PropTypes.func.isRequired,
  onClose: PropTypes.func.isRequired,
  onUsersChanged: PropTypes.func.isRequired,
  onTempPassword: PropTypes.func.isRequired,
  onToast: PropTypes.func.isRequired,
  onDeleted: PropTypes.func.isRequired,
};
