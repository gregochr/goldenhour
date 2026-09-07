import { describe, it, expect, afterEach } from 'vitest';
import { render, act } from '@testing-library/react';
import useDialogFocus from '../hooks/useDialogFocus.js';

/**
 * `hooks/useDialogFocus.js` — focus-in on open, restore on close, and the guard that stops the
 * restore stealing a reader's own choice.
 *
 * ⚠️ **This hook had no test file at all** until `map-tab-v2-plan.md` O-20 arm C. Its behaviour was
 * covered only incidentally, through whichever dialog a given test happened to open — so the one
 * rule that is about the PAGE rather than the dialog (what happens to focus when the reader has
 * moved it elsewhere) had nowhere to be asserted.
 *
 * <p>⚠️ **Four call sites, not fourteen** — and the first cut of this file, its changelog entry and
 * its commit message all said fourteen. That was `grep -rl`, which counts files CONTAINING the
 * string: doc comments and cross-references, not mounts. The real callers are `Modal`,
 * `BottomSheet`, `MapOverlay` and `RegionsJump` — everything else reaches the hook THROUGH `Modal`
 * or `BottomSheet`, which is a wider blast radius than four but not a longer list of call sites.
 *
 * <h2>What is deliberately NOT covered here</h2>
 *
 * <p>The hook keys on `[active]`, so its cleanup also runs on a **deactivation** — `active` going
 * false while the component stays mounted. The guard's behaviour there is unexercised, and that is
 * stated rather than tested because no consumer does it: `BottomSheet` is `if (!open) return null`
 * and `RegionsJump` swaps its desktop popover for a `BottomSheet` on the same flip, so both take
 * the dialog's DOM with them and focus is on `<body>` by the time the cleanup runs — the same path
 * the unmount cases below cover. A consumer that deactivates while keeping its dialog mounted would
 * need its own case here.
 */
describe('useDialogFocus', () => {
  let trigger = null;
  let elsewhere = null;

  afterEach(() => {
    trigger?.remove(); trigger = null;
    elsewhere?.remove(); elsewhere = null;
  });

  const button = (id) => {
    const b = document.createElement('button');
    b.id = id;
    document.body.appendChild(b);
    return b;
  };

  function Dialog({ active = true }) {
    const ref = useDialogFocus(active);
    return <div ref={ref} tabIndex={-1} role="dialog" data-testid="dlg"><button id="inner">x</button></div>;
  }

  /** The hook focuses on a rAF, so a test that does not spend the frame asserts the frame's absence. */
  const settle = async () => { await act(async () => { await new Promise(requestAnimationFrame); }); };

  it('takes focus onto the dialog root, not its first control', async () => {
    const { getByTestId } = render(<Dialog />);
    await settle();

    expect(document.activeElement).toBe(getByTestId('dlg'));
  });

  it('restores focus to the trigger on a normal close', async () => {
    trigger = button('trigger');
    trigger.focus();
    const { unmount } = render(<Dialog />);
    await settle();
    expect(document.activeElement).not.toBe(trigger);

    unmount();

    expect(document.activeElement).toBe(trigger);
  });

  it('⚠️ does NOT restore when the reader has moved focus somewhere real', async () => {
    // O-20 arm C. This hook refuses containment app-wide, so Tabbing out of a dialog is a supported
    // thing to do — and a restore that fires regardless takes back with one hand what that refusal
    // grants with the other. The route that surfaced it: sheet open over the map, Tab out, open the
    // drilldown, Escape. The sheet closes, the panel correctly survives, and this cleanup used to
    // pull focus out of the panel the reader was standing in.
    trigger = button('trigger');
    elsewhere = button('elsewhere');
    trigger.focus();
    const { unmount } = render(<Dialog />);
    await settle();

    elsewhere.focus();          // the reader Tabs out and lands somewhere real
    unmount();

    expect(document.activeElement, 'the reader chose where they are').toBe(elsewhere);
  });

  it('still restores when focus is nowhere — `<body>` is not a choice', async () => {
    trigger = button('trigger');
    trigger.focus();
    const { unmount } = render(<Dialog />);
    await settle();
    document.body.focus();

    unmount();

    expect(document.activeElement).toBe(trigger);
  });

  it('⚠️ treats the document ROOT as nowhere too, not as a choice', () => {
    // `Modal`'s own copy of this guard records this as MEASURED in this app, not hypothetical:
    // "`WindowFirstShell`'s tab-select records `activeElement` landing on the document root after
    // the overlay's map hatch". jsdom will not put focus on `<html>` unaided, so the fixture makes
    // it focusable — synthetic in HOW focus gets there, real in what is then asserted. Without the
    // `documentElement` clause the guard reads the root as a deliberate choice and skips a restore
    // the reader is owed; that mutant survived every other case in this file.
    trigger = button('trigger');
    trigger.focus();
    const { unmount } = render(<Dialog />);
    document.documentElement.tabIndex = -1;
    try {
      document.documentElement.focus();
      expect(document.activeElement, 'fixture precondition').toBe(document.documentElement);

      unmount();

      expect(document.activeElement).toBe(trigger);
    } finally {
      document.documentElement.removeAttribute('tabindex');
    }
  });

  it('does nothing when the trigger has left the document', async () => {
    // A poll, an SSE event or a parent re-render can drop the row the trigger lived on. Focusing a
    // detached node throws the reader's place away rather than returning it.
    trigger = button('trigger');
    trigger.focus();
    const { unmount } = render(<Dialog />);
    await settle();
    trigger.remove();

    expect(() => unmount()).not.toThrow();
    expect(document.activeElement).toBe(document.body);
  });

  it('yields to a consumer that has already placed focus on one of its own controls', async () => {
    const { getByTestId } = render(<Dialog />);
    document.getElementById('inner').focus();
    await settle();

    expect(document.activeElement, 'the container must not take it back a frame later')
      .toBe(document.getElementById('inner'));
    expect(document.activeElement).not.toBe(getByTestId('dlg'));
  });
});
