import { useState } from 'react';
import { createElement } from 'react';
import ConfirmDialog from '../components/shared/ConfirmDialog.jsx';

/**
 * Hook that manages confirm dialog state.
 *
 * For simple consumers (no children): use `dialogElement` directly in JSX.
 * For consumers with custom children: use `config` + render `<ConfirmDialog>` manually.
 * For consumers that mutate config (e.g. slot toggles): use `setConfig`.
 */
export default function useConfirmDialog() {
  const [config, setConfig] = useState(null);

  const openDialog = (cfg) => setConfig(cfg);
  const closeDialog = () => setConfig(null);

  const dialogElement = config
    ? createElement(ConfirmDialog, {
      title: config.title,
      message: config.message,
      confirmLabel: config.confirmLabel,
      onConfirm: config.onConfirm,
      onCancel: closeDialog,
      destructive: config.destructive,
      maxWidth: config.maxWidth,
    })
    : null;

  return { config, openDialog, closeDialog, setConfig, dialogElement };
}
