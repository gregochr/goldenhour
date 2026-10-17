import { describe, it, expect, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import useModalPresent from '../hooks/useModalPresent.js';

/** Adds a node to <body>, as a portalled or in-place dialog would. */
function addDialog({ ariaModal = 'true', role = 'dialog', prompt = false } = {}) {
  const node = document.createElement('div');
  node.setAttribute('role', role);
  if (ariaModal != null) node.setAttribute('aria-modal', ariaModal);
  if (prompt) node.setAttribute('data-postcode-prompt', '');
  document.body.appendChild(node);
  return node;
}

/** Runs a DOM change inside an awaited act, long enough for the observer's microtask to land. */
async function change(fn) {
  await act(async () => {
    fn();
    await Promise.resolve();
    await Promise.resolve();
  });
}

describe('useModalPresent', () => {
  afterEach(async () => {
    // Inside an awaited act: a hook from the test is still mounted and its observer will see this.
    await change(() => {
      document.querySelectorAll('[role="dialog"], [role="alertdialog"]').forEach((n) => n.remove());
    });
  });

  it('is false with no dialog in the document', () => {
    const { result } = renderHook(() => useModalPresent());

    expect(result.current).toBe(false);
  });

  it('is true at mount when a modal dialog is already open', () => {
    addDialog();

    const { result } = renderHook(() => useModalPresent());

    expect(result.current).toBe(true);
  });

  it('turns true when a modal dialog appears later, and false when it goes', async () => {
    const { result } = renderHook(() => useModalPresent());
    expect(result.current).toBe(false);

    let dialog;
    await change(() => { dialog = addDialog(); });
    expect(result.current).toBe(true);

    await change(() => { dialog.remove(); });
    expect(result.current).toBe(false);
  });

  it("sees a dialog's aria-modal arrive on a node that was already there", async () => {
    const node = addDialog({ ariaModal: null });
    const { result } = renderHook(() => useModalPresent());
    expect(result.current).toBe(false); // a dialog that does not claim to be modal is not one

    await change(() => { node.setAttribute('aria-modal', 'true'); });

    expect(result.current).toBe(true);
  });

  it('sees a stacked layer give up its claim', async () => {
    const node = addDialog();
    const { result } = renderHook(() => useModalPresent());
    expect(result.current).toBe(true);

    await change(() => { node.removeAttribute('aria-modal'); });

    expect(result.current).toBe(false);
  });

  it('does not count the postcode prompt itself, at mount or after — or it would unmount itself', async () => {
    const { result } = renderHook(() => useModalPresent());

    await change(() => { addDialog({ prompt: true }); });

    expect(result.current).toBe(false);
    expect(renderHook(() => useModalPresent()).result.current).toBe(false);
  });

  it('counts a foreign dialog beside the prompt', async () => {
    addDialog({ prompt: true });
    const { result } = renderHook(() => useModalPresent());
    expect(result.current).toBe(false);

    await change(() => { addDialog(); });

    expect(result.current).toBe(true);
  });

  it('ignores a role=dialog that does not claim aria-modal, and other roles', () => {
    addDialog({ ariaModal: 'false' });
    addDialog({ role: 'alertdialog' });

    const { result } = renderHook(() => useModalPresent());

    expect(result.current).toBe(false);
  });

  it('stops observing on unmount', async () => {
    const { result, unmount } = renderHook(() => useModalPresent());
    unmount();

    await change(() => { addDialog(); });

    expect(result.current).toBe(false);
  });
});
