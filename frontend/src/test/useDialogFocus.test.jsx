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
    // ⚠️ The precondition is ASSERTED, and the first cut of this case did not assert it — which made
    // it a duplicate of its neighbour above wearing a different branch's name. `document.body.focus()`
    // is a silent no-op in this jsdom, so without the assertion the case reached the `<body>` clause
    // only via `unmount()` detaching the focused root: the same mechanism the normal-close test
    // already covers. Measured by a review lens.
    trigger = button('trigger');
    trigger.focus();
    const { unmount } = render(<Dialog />);
    await settle();
    const dialog = document.querySelector('[data-testid="dlg"]');
    dialog.blur();
    expect(document.activeElement, 'precondition: focus is genuinely nowhere before the unmount')
      .toBe(document.body);

    unmount();

    expect(document.activeElement).toBe(trigger);
  });

  describe('⚠️ stranded outside a layer that still claims modality', () => {
    // The regression the first cut of the guard shipped, found by an accessibility lens and
    // MEASURED against the real `Modal`: Plan popup open, `/` opens search (the popup goes
    // `stacked`/`inert`), Tab out onto the page, `Escape`. Search closes, the popup re-claims
    // `aria-modal` — and both guards stood down, leaving the reader outside a dialog that AT is
    // told to treat everything outside of as unavailable.
    let survivor = null;
    afterEach(() => { survivor?.remove(); survivor = null; });

    const layerBelow = () => {
      survivor = document.createElement('div');
      survivor.setAttribute('role', 'dialog');
      survivor.setAttribute('aria-modal', 'true');
      document.body.appendChild(survivor);
      return survivor;
    };

    it('restores INTO it, because being outside it is not a choice', async () => {
      trigger = button('trigger');
      elsewhere = button('elsewhere');
      layerBelow();
      trigger.focus();
      const { unmount } = render(<Dialog />);
      await settle();
      elsewhere.focus();          // Tabbed out onto the page behind the backdrop

      unmount();                  // the covering layer closes; the one below re-claims modality

      expect(document.activeElement, 'orphaned outside an aria-modal layer is not a chosen place')
        .toBe(trigger);
    });

    it('but leaves the reader alone when they are INSIDE it', async () => {
      trigger = button('trigger');
      const below = layerBelow();
      const within = document.createElement('button');
      below.appendChild(within);
      trigger.focus();
      const { unmount } = render(<Dialog />);
      await settle();
      within.focus();

      unmount();

      expect(document.activeElement, 'their position is coherent — leave it').toBe(within);
    });

    it('⚠️ and arm C still holds: nothing claiming modality means nothing to be stranded outside', async () => {
      // The map drilldown's panels are `role="dialog"` WITHOUT `aria-modal`, deliberately. So when
      // the four-day sheet closes over them nothing claims modality and the reader keeps their
      // place — which is the whole of arm C, and what the narrowing had to preserve.
      trigger = button('trigger');
      elsewhere = button('elsewhere');
      const panel = document.createElement('div');
      panel.setAttribute('role', 'dialog');       // no aria-modal, as the real panels have none
      document.body.appendChild(panel);
      try {
        trigger.focus();
        const { unmount } = render(<Dialog />);
        await settle();
        elsewhere.focus();

        unmount();

        expect(document.activeElement, 'arm C: focus must stay where the reader put it')
          .toBe(elsewhere);
      } finally {
        panel.remove();
      }
    });
  });

  it('⚠️ treats the document ROOT as nowhere too, not as a choice', () => {
    // ⚠️ The evidence that this state is reachable is BORROWED, and an earlier version of this
    // comment did not say so. `Modal`'s copy of the guard records `activeElement` landing on the
    // document root after `WindowFirstShell`'s tab-select and the overlay's map hatch — a different
    // mechanism from this hook's cleanup, and nothing establishes it is reachable HERE. The clause
    // is kept because treating the root as "nowhere" cannot swallow a reader's own choice, and
    // pinned because it otherwise survives every other case in this file. jsdom will not focus
    // `<html>` unaided, so the fixture makes it focusable: synthetic in how focus gets there, real
    // in what is then asserted. Without the
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
