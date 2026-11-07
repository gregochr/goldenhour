import React from 'react';
import PropTypes from 'prop-types';
import useBackLayer from '../hooks/useBackLayer.js';

/**
 * Stand-in for the settings dialog while its chunk loads. It is a dialog on purpose: the shell's
 * foreign-dialog checks and the Map's Escape and outside-press rules all look for
 * `[role="dialog"][aria-modal="true"]`, so a bare null fallback would leave a window in which a Plan
 * popup or search could open over settings (a third stacked modal), Ask would not refuse, and the Map
 * would close its callout. It must NOT take focus: the real dialog's `useDialogFocus` records the
 * opener when it mounts.
 *
 * <p>It is a Back layer too (`back-closes-dialogs-plan.md`): a reader who presses Back while the chunk
 * loads closes Settings, as they will once it has arrived. The real dialog mounts in the commit this
 * leaves in, so it takes over this layer's history entry (a same-depth swap) rather than pushing another.
 *
 * @param {object} props
 * @param {function(): void} props.onBack what Back does: close Settings
 */
export default function SettingsFallback({ onBack }) {
  useBackLayer({ open: true, onBack });
  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="Settings"
      data-testid="settings-fallback"
      className="fixed inset-0 z-50 app-scrim"
    />
  );
}

SettingsFallback.propTypes = { onBack: PropTypes.func.isRequired };
