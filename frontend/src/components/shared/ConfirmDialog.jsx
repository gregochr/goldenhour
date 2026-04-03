import React from 'react';
import PropTypes from 'prop-types';
import Modal from './Modal.jsx';

/**
 * Shared confirmation dialog with title, message, and confirm/cancel buttons.
 */
export default function ConfirmDialog({
  title,
  message,
  confirmLabel,
  onConfirm,
  onCancel,
  destructive = false,
  maxWidth = 'md',
  children,
}) {
  return (
    <Modal label={title} onClose={onCancel} maxWidth={maxWidth} data-testid="confirm-dialog">
      <p className="text-sm font-semibold text-plex-text">{title}</p>
      <p className="text-sm text-plex-text-secondary">{message}</p>
      {children}
      <div className="flex justify-end gap-2">
        <button
          className="btn-secondary text-sm"
          onClick={onCancel}
          data-testid="confirm-dialog-cancel"
        >
          Cancel
        </button>
        <button
          className={`text-sm px-4 py-1.5 rounded font-medium ${
            destructive
              ? 'bg-red-700 hover:bg-red-600 text-white'
              : 'btn-primary'
          }`}
          onClick={onConfirm}
          data-testid="confirm-dialog-confirm"
        >
          {confirmLabel}
        </button>
      </div>
    </Modal>
  );
}

ConfirmDialog.propTypes = {
  title: PropTypes.string.isRequired,
  message: PropTypes.string.isRequired,
  confirmLabel: PropTypes.string.isRequired,
  onConfirm: PropTypes.func.isRequired,
  onCancel: PropTypes.func.isRequired,
  destructive: PropTypes.bool,
  maxWidth: PropTypes.oneOf(['sm', 'md', 'lg']),
  children: PropTypes.node,
};
