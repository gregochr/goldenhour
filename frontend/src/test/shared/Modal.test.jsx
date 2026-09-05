import React, { useState } from 'react';
import PropTypes from 'prop-types';
import { describe, it, expect, vi } from 'vitest';
import { act, render, screen, fireEvent, waitFor } from '@testing-library/react';
import Modal from '../../components/shared/Modal.jsx';

/**
 * A trigger that opens a dialog — the shape every real consumer has, and the only way to observe
 * focus RESTORE, which needs something to restore to. `removeTriggerOnClose` reproduces the case
 * where the dialog outlives the element that opened it.
 */
function TriggerAndDialog({ removeTriggerOnClose = false, ...modalProps }) {
  const [open, setOpen] = useState(false);
  const [triggerGone, setTriggerGone] = useState(false);
  const close = () => {
    setOpen(false);
    if (removeTriggerOnClose) setTriggerGone(true);
  };
  return (
    <div>
      {!triggerGone && (
        <button type="button" onClick={() => setOpen(true)}>Open</button>
      )}
      {open && (
        <Modal label="Test dialog" onClose={close} data-testid="m" {...modalProps}>
          <button type="button">Inside</button>
        </Modal>
      )}
    </div>
  );
}

describe('Modal', () => {
  it('renders children', () => {
    render(<Modal label="Test"><p>Hello</p></Modal>);
    expect(screen.getByText('Hello')).toBeInTheDocument();
  });

  it('defaults to max-w-md', () => {
    render(<Modal label="Test" data-testid="m"><p>Content</p></Modal>);
    const panel = screen.getByTestId('m').querySelector('[class*="max-w-"]');
    expect(panel.className).toContain('max-w-md');
  });

  it('applies max-w-sm', () => {
    render(<Modal label="Test" maxWidth="sm" data-testid="m"><p>Content</p></Modal>);
    const panel = screen.getByTestId('m').querySelector('[class*="max-w-"]');
    expect(panel.className).toContain('max-w-sm');
  });

  it('applies max-w-lg', () => {
    render(<Modal label="Test" maxWidth="lg" data-testid="m"><p>Content</p></Modal>);
    const panel = screen.getByTestId('m').querySelector('[class*="max-w-"]');
    expect(panel.className).toContain('max-w-lg');
  });

  it('sets aria attributes', () => {
    render(<Modal label="My Dialog"><p>Content</p></Modal>);
    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    expect(dialog).toHaveAttribute('aria-label', 'My Dialog');
  });

  it('forwards data-testid', () => {
    render(<Modal label="Test" data-testid="my-modal"><p>Content</p></Modal>);
    expect(screen.getByTestId('my-modal')).toBeInTheDocument();
  });

  it('calls onClose on backdrop click', () => {
    const onClose = vi.fn();
    render(<Modal label="Test" onClose={onClose} data-testid="m"><p>Content</p></Modal>);
    fireEvent.click(screen.getByTestId('m-backdrop'));
    expect(onClose).toHaveBeenCalledOnce();
  });

  it('does not crash when onClose is omitted and backdrop is clicked', () => {
    render(<Modal label="Test" data-testid="m"><p>Content</p></Modal>);
    // backdrop click with no onClose should not throw
    fireEvent.click(screen.getByTestId('m-backdrop'));
  });

  it('merges className onto panel', () => {
    render(<Modal label="Test" className="extra-class" data-testid="m"><p>Content</p></Modal>);
    const panel = screen.getByTestId('m').querySelector('[class*="max-w-"]');
    expect(panel.className).toContain('extra-class');
  });

  it('renders children directly when bare is true (no panel wrapper)', () => {
    render(
      <Modal label="Test" bare data-testid="m">
        <div data-testid="custom-panel">Custom panel</div>
      </Modal>,
    );
    expect(screen.getByTestId('custom-panel')).toBeInTheDocument();
    // No standard panel wrapper
    expect(screen.getByTestId('m').querySelector('[class*="max-w-"]')).toBeNull();
  });

  it('still renders backdrop in bare mode', () => {
    const onClose = vi.fn();
    render(
      <Modal label="Test" bare onClose={onClose} data-testid="m">
        <div>Custom</div>
      </Modal>,
    );
    fireEvent.click(screen.getByTestId('m-backdrop'));
    expect(onClose).toHaveBeenCalledOnce();
  });

  describe('focus', () => {
    it('takes focus when it opens', async () => {
      // `role="dialog"` and `aria-modal` are semantics; neither moves focus. Without this a
      // keyboard user had to Tab through the whole page behind the backdrop to reach a dialog that
      // had just opened over it.
      render(<TriggerAndDialog />);
      fireEvent.click(screen.getByRole('button', { name: 'Open' }));

      await waitFor(() => expect(screen.getByTestId('m')).toHaveFocus());
    });

    it('focuses the container, not the first control inside it', async () => {
      // Deliberate: focusing the container works when the dialog has nothing focusable yet (content
      // still loading) or nothing focusable at all, and it leaves a consumer free to autofocus its
      // own field afterwards. A screen reader reads the dialog's accessible name on landing.
      render(<TriggerAndDialog />);
      fireEvent.click(screen.getByRole('button', { name: 'Open' }));

      await waitFor(() => expect(screen.getByTestId('m')).toHaveFocus());
      expect(screen.getByRole('button', { name: 'Inside' })).not.toHaveFocus();
    });

    it('yields to a consumer that has already focused one of its own controls', async () => {
      // The container focus is deferred by a frame, so a consumer that focuses synchronously on
      // mount would otherwise have it taken straight back. Which effect lands first depends on how
      // fast that consumer's fetch is, so both orders have to end in the same place. This is not
      // hypothetical: the settings modal focuses its postcode field, and it regressed on the first
      // cut of this hook.
      // The self-focusing element is a CHILD COMPONENT of Modal, not its parent — and that is what
      // makes this test bite. React runs child effects before parent ones, so this focuses the
      // field BEFORE Modal's own effect runs. Written the other way round (the effect on a wrapper
      // around Modal) it runs after, the consumer wins by ordering alone, and deleting the guard
      // leaves the test green.
      function SelfFocusingChild() {
        const ref = React.useRef(null);
        React.useEffect(() => { ref.current?.focus(); }, []);
        return <input ref={ref} data-testid="own-field" />;
      }
      render(
        <Modal label="Test dialog" data-testid="m">
          <SelfFocusingChild />
        </Modal>,
      );

      await waitFor(() => expect(screen.getByTestId('own-field')).toHaveFocus());
      // And it stays there once the deferred container focus has had its chance to fire.
      await new Promise((r) => { requestAnimationFrame(() => requestAnimationFrame(r)); });
      expect(screen.getByTestId('own-field')).toHaveFocus();
    });

    it('gives focus back to whatever opened it', async () => {
      render(<TriggerAndDialog closeOnEscape />);
      const trigger = screen.getByRole('button', { name: 'Open' });
      trigger.focus();
      fireEvent.click(trigger);
      await waitFor(() => expect(screen.getByTestId('m')).toHaveFocus());

      fireEvent.keyDown(document, { key: 'Escape' });
      await waitFor(() => expect(trigger).toHaveFocus());
    });

    it('does not throw when the thing that opened it has since been removed', async () => {
      // A dialog can be closed by something other than the user — a poll, an SSE event, a parent
      // re-render that drops the row the trigger lived on. Focusing a detached node throws the
      // user's place away rather than returning it, so the restore is skipped instead.
      render(<TriggerAndDialog closeOnEscape removeTriggerOnClose />);
      fireEvent.click(screen.getByRole('button', { name: 'Open' }));
      await waitFor(() => expect(screen.getByTestId('m')).toHaveFocus());

      expect(() => fireEvent.keyDown(document, { key: 'Escape' })).not.toThrow();
      expect(screen.queryByTestId('m')).toBeNull();
    });
  });

  /**
   * {@code stacked} — M5's containment opt-in.
   *
   * <p>⚠️ Everything here asserts the ATTRIBUTES, never the behaviour, and that is a limit of the
   * environment rather than a gap in the suite: {@code 'inert' in HTMLElement.prototype} is
   * {@code false} in this project's jsdom, so a test claiming "Tab cannot leave" would pass against
   * an element carrying no guard at all. The behaviour was measured in a real browser (Chromium,
   * `visibilityState: 'visible'`) and the numbers are in the plan's M5 row: with two layers open, a
   * 24-press Tab walk from the top one never entered the layer beneath.
   */
  describe('stacked', () => {
    it('emits NEITHER attribute by default, which is what keeps every other caller unchanged', () => {
      // The pinning half of plan §3 rule 10. Only the Plan-screen dialogs (window popup, sheet,
      // search) pass this prop; every other one — the outcome modal, the settings modal, the
      // aurora modals, the admin views — must render exactly what it rendered before.
      render(<Modal label="Test"><p>Content</p></Modal>);
      const dialog = screen.getByRole('dialog');
      expect(dialog).toHaveAttribute('aria-modal', 'true');
      expect(dialog).not.toHaveAttribute('inert');
    });

    it('takes inert and DROPS aria-modal when something is over it', () => {
      // Both halves, because they fix different readers. `inert` takes the layer out of the tab
      // order and out of the accessibility tree; dropping `aria-modal` is what stops two elements
      // claiming to be the one modal, which is what a screen reader resolves the stack from.
      render(<Modal label="Test" stacked><p>Content</p></Modal>);
      const dialog = screen.getByRole('dialog');
      expect(dialog).toHaveAttribute('inert');
      expect(dialog).not.toHaveAttribute('aria-modal');
      expect(dialog).toHaveAttribute('aria-label', 'Test');
    });

    it('⚠️ puts focus back inside itself when the layer over it goes', async () => {
      // The regression the first cut shipped, caught in a browser: `inert` blurs whatever inside it
      // had focus, and because `stacked` is a PROP that lands in React's mutation phase the blur
      // beats every effect in the commit — including `useDialogFocus`'s reading of
      // `document.activeElement`. So the arriving dialog recorded `<body>` as the place to go back
      // to, and Escape dropped the reader at the top of the document instead of on the chip they
      // opened the sheet from.
      //
      // jsdom does not implement `inert`, so it cannot reproduce the blur — the blur is simulated
      // here, which is what makes this a test of the REMEDY (does the dialog remember for itself?)
      // rather than of the environment.
      const Host = ({ stacked }) => (
        <Modal label="Under" stacked={stacked} data-testid="under">
          <button type="button" data-testid="chip">Chip</button>
        </Modal>
      );
      Host.propTypes = { stacked: PropTypes.bool.isRequired };

      const { rerender } = render(<Host stacked={false} />);
      const chip = screen.getByTestId('chip');
      chip.focus();
      expect(document.activeElement).toBe(chip);

      // Stack something over it, and blur exactly as `inert` would in a browser.
      rerender(<Host stacked />);
      expect(screen.getByTestId('under')).toHaveAttribute('inert');
      chip.blur();
      expect(document.activeElement).toBe(document.body);

      // Unstack: the layer underneath restores its own last-focused control.
      rerender(<Host stacked={false} />);
      await act(async () => {});
      expect(document.activeElement).toBe(chip);
    });

    it('⚠️ records the opener on POINTERDOWN too, because Safari does not focus a button on click', () => {
      // The engine assumption the first cut made, found by an adversarial review. macOS and iOS
      // Safari do not move focus to a `<button>` on a click unless Full Keyboard Access is on, and
      // that is the default — so on the very gesture this mechanism was measured against (click a
      // chip on the popup's map, press Escape) no focus event fires, `lastInside` stays null, and
      // the fix degrades silently to the `<body>` behaviour it exists to replace. Both browser
      // measurements behind it were headless Chromium.
      //
      // Simulated by firing `pointerDown` WITHOUT a focus event, which is exactly what that engine
      // does — and which is why this cannot be folded into the test above.
      const Host = ({ stacked }) => (
        <Modal label="Under" stacked={stacked} data-testid="under">
          <button type="button" data-testid="chip">Chip</button>
        </Modal>
      );
      Host.propTypes = { stacked: PropTypes.bool.isRequired };

      const { rerender } = render(<Host stacked={false} />);
      const chip = screen.getByTestId('chip');
      fireEvent.pointerDown(chip);
      expect(document.activeElement).not.toBe(chip);

      rerender(<Host stacked />);
      rerender(<Host stacked={false} />);
      expect(document.activeElement).toBe(chip);
    });

    it('records nothing from a press on the backdrop, which is not a control inside it', () => {
      // `pointerdown` fires for the scrim as well, and the scrim's job is to CLOSE. Recording it
      // would make the restore focus a `role="presentation"` div — a focus target that announces
      // nothing and that a reader cannot leave by any route they would guess.
      const Host = ({ stacked }) => (
        <Modal label="Under" stacked={stacked} data-testid="under">
          <button type="button" data-testid="chip">Chip</button>
        </Modal>
      );
      Host.propTypes = { stacked: PropTypes.bool.isRequired };

      const { rerender } = render(<Host stacked={false} />);
      const chip = screen.getByTestId('chip');
      fireEvent.pointerDown(chip);
      // The press that must not overwrite it. Asserted as "the chip is still what comes back"
      // rather than "the backdrop is not focused": a `role="presentation"` div takes no focus, so
      // the weaker form passes against a version that records the backdrop and then silently fails
      // to focus it — measured.
      fireEvent.pointerDown(screen.getByTestId('under-backdrop'));

      rerender(<Host stacked />);
      rerender(<Host stacked={false} />);
      expect(document.activeElement).toBe(chip);
    });

    it('⚠️ records a CONTROL inside it, never the dialog root itself', () => {
      // `useDialogFocus` focuses the ROOT when a dialog opens (deliberately — see its header), and
      // that focus event bubbles through this recorder. Without the guard the root becomes the thing
      // restored, so an uncovered dialog puts focus on its own container rather than on the chip the
      // reader was on: a weaker form of the exact defect this effect exists to fix, and one no other
      // test could see.
      const Host = ({ stacked }) => (
        <Modal label="Under" stacked={stacked} data-testid="under">
          <button type="button" data-testid="chip">Chip</button>
        </Modal>
      );
      Host.propTypes = { stacked: PropTypes.bool.isRequired };

      const { rerender } = render(<Host stacked={false} />);
      const chip = screen.getByTestId('chip');
      chip.focus();
      // The root takes focus AFTER the control, which is the real ordering on a fast pointer route:
      // `useDialogFocus` focuses the root a frame after mount, by which time a click has already
      // moved focus to a control inside.
      screen.getByTestId('under').focus();

      rerender(<Host stacked />);
      // `inert` blurs whatever inside had focus; jsdom does not implement it, so it is simulated.
      document.activeElement.blur();
      rerender(<Host stacked={false} />);
      expect(document.activeElement).toBe(chip);
    });

    it('does NOT yank focus back from wherever the reader has since moved to', async () => {
      // The guard on the restore. A keyboard reader can Tab out of the topmost dialog into the page
      // — that is the app's settled, unchanged behaviour — and a dialog underneath that grabbed
      // focus the moment it was uncovered would fight them for it.
      //
      // ⚠️ This test was load-sensitive until the settle below. #776 recorded it here as unfixed
      // and warned that awaiting the frame would make the failure deterministic; that is true of
      // awaiting it beside the ASSERTION and false of awaiting it before the reader moves, which
      // is what the settle does. Measured both ways — see there.
      const Host = ({ stacked }) => (
        <div>
          <button type="button" data-testid="outside">Outside</button>
          <Modal label="Under" stacked={stacked} data-testid="under">
            <button type="button" data-testid="chip">Chip</button>
          </Modal>
        </div>
      );
      Host.propTypes = { stacked: PropTypes.bool.isRequired };

      const { rerender } = render(<Host stacked={false} />);
      // ⚠️ PRECONDITION, not the subject: let the dialog finish OPENING before playing a reader
      // who has been inside it a while. `useDialogFocus` moves focus to the dialog root on a frame
      // scheduled at MOUNT — the only frame `Modal`'s own code schedules, since its effect's dep is
      // the literal `true` Modal passes (a fact about THIS caller — the hook re-opens happily for
      // the dynamic `active` that `BottomSheet` and `RegionsJump` pass it), so neither stacking nor
      // unstacking re-runs it and nothing here is a second open. Without this settle that frame
      // stayed in flight for the rest of the test and, on a starved machine, fired at whatever
      // `await` came next, so the assertion found the dialog ROOT rather than `outside`. The hook's
      // own guard could not help: it stands down only when focus is on a DESCENDANT
      // (`contains(active) && active !== dialog`), and this is the one test in the block that
      // deliberately ends with focus outside the dialog. (Three siblings are immune for a plainer
      // reason — they are synchronous and offer the frame no `await` to land on at all.)
      //
      // ⚠️ Do not reach for the failure RATE to reason about this. It was seen at 1 of 10 runs of
      // this file under a 24-process load on an 8-core Mac, 2 of 10 re-measuring the same way, and
      // 2 of 6 concurrent full-suite runs — but an interleaved A/B under comparable load saw 0 of
      // 12. Load is not the variable; PHASE against jsdom's rAF interval is, so those counts are
      // existence proofs and nothing more. What pins the mechanism is a delay sweep — busy-spin
      // N ms between `render()` and the rest of this body, 8 reps each, pre-settle:
      //
      //     0ms 1/8   2ms 0/8   4ms 1/8   6ms 2/8   8ms 3/8
      //     10ms 5/8  12ms 6/8  16ms 8/8  24ms 8/8
      //
      // A ramp saturating at 16 ms — the frame interval itself. The frame lands one interval after
      // mount at arbitrary phase, so a delay of d steals with probability ≈ min(1, d/16), and at
      // d = 0 the residue is the ~1-in-10 seen in the wild. Nothing here is a step function, and a
      // sweep of one rep per step will look like one; run reps.
      //
      // ⚠️ The settle belongs HERE and nowhere later. This flake is shaped the other way up from
      // the usual one: the test passed while the frame had *not* yet fired, so settling it beside
      // the assertion fails every time. Settling before the reader moves is what removes the race,
      // and is the real sequence — a dialog is open far longer than a frame before anyone tabs.
      // `WindowFirstShellSheet` settles the same frame ahead of its hover for a related reason (a
      // mutation sweep caught it pinning the focus rule); it does so with fake timers and asserts
      // nothing, so the precedent is "settle first", not "settle by asserting where focus went".
      //
      // ⚠️ This settles by PROXY: it waits for the root to hold focus, which is not the same claim
      // as "the frame has fired". They coincide only while that frame is the sole thing that
      // focuses this root — true today (the restore effect can only focus a control, guarded by
      // `e.target !== e.currentTarget`). Give the root another route to focus and this line is
      // satisfied with the frame still pending, and the flake returns with the suite green.
      await waitFor(() => expect(screen.getByTestId('under')).toHaveFocus());

      screen.getByTestId('chip').focus();
      rerender(<Host stacked />);
      expect(screen.getByTestId('under')).toHaveAttribute('inert');

      // The reader Tabs out into the page while the top layer is up — the app's settled behaviour.
      const outside = screen.getByTestId('outside');
      outside.focus();
      rerender(<Host stacked={false} />);
      expect(document.activeElement).toBe(outside);
      // ⚠️ And it STAYS there. Not belt-and-braces: the settle above removes the race from today's
      // code, and without this the test also stops being able to SEE it come back. Measured — flip
      // `Modal`'s `useDialogFocus(true)` to `useDialogFocus(!stacked)` (the "treat uncovering as an
      // open" change) and the assertion above passes 20 of 20 idle while this one fails 20 of 20,
      // because that mutant schedules a SECOND frame at the uncover which only a forced frame
      // reveals. `rerender` is act-wrapped, so the restore effect has already run by the line
      // above; this is the same double-rAF idiom the self-focusing-child test uses.
      await new Promise((r) => { requestAnimationFrame(() => requestAnimationFrame(r)); });
      expect(document.activeElement).toBe(outside);
    });

    it('⚠️ lands on the ROOT when nothing inside was ever recorded, rather than stranding the reader', async () => {
      // The cell no other test in this block reaches: `lastInside` null AND focus orphaned. Every
      // sibling populates `lastInside` first, so the uncover's `!node` early return was never
      // exercised with focus on `<body>` — and it did nothing, leaving the reader at the top of the
      // document beneath a layer still claiming `aria-modal="true"`, their next Tab walking the
      // page behind the backdrop. That is the defect `useDialogFocus` exists to prevent, reproduced
      // by the effect written to fix it.
      //
      // It is not a contrived cell. A reader who opens the popup and presses `/` without touching
      // anything inside reaches it on any engine; on macOS and iOS Safari, which do not focus a
      // `<button>` on click, the ordinary POINTER route reaches it too, because nothing inside is
      // ever recorded there.
      const Host = ({ stacked }) => (
        <Modal label="Under" stacked={stacked} data-testid="under">
          <button type="button" data-testid="chip">Chip</button>
        </Modal>
      );
      Host.propTypes = { stacked: PropTypes.bool.isRequired };

      const { rerender } = render(<Host stacked={false} />);
      // The dialog opens onto its own root — which `onFocus` deliberately refuses to record, so
      // `lastInside` is still null. Settled rather than assumed; see the sibling below on why.
      await waitFor(() => expect(screen.getByTestId('under')).toHaveFocus());

      rerender(<Host stacked />);
      // `inert` blurs whatever inside had focus; jsdom does not implement it, so it is simulated.
      document.activeElement.blur();
      expect(document.activeElement).toBe(document.body);

      rerender(<Host stacked={false} />);
      // ⚠️ BY ROLE AND NAME, not by test-id. The justification for landing here is that the thing
      // taking focus announces itself, so the test has to pin that it CAN: queried by test-id this
      // passes just as happily against a dialog whose `aria-label` has been dropped, which would
      // land the reader somewhere that announces nothing. Found by an adversarial review.
      expect(document.activeElement).toBe(screen.getByRole('dialog', { name: 'Under' }));
    });

    it('⚠️ falls through to the root when the recorded control REFUSES the focus', () => {
      // `focus()` is a silent no-op on an attached but unfocusable node, so `document.contains` is
      // not enough to know a restore worked. The old code returned the moment it found a node and
      // could believe it had restored a reader it had in fact left on `<body>` — this defect again,
      // one step further on. A disabled control is the cheap reproduction (jsdom agrees with the
      // browser here: focusing one leaves `activeElement` on `<body>`); an `inert` ancestor is the
      // route that would matter in a real browser, which jsdom cannot model at all.
      const Host = ({ stacked, chipDisabled }) => (
        <Modal label="Under" stacked={stacked} data-testid="under">
          <button type="button" data-testid="chip" disabled={chipDisabled}>Chip</button>
        </Modal>
      );
      Host.propTypes = {
        stacked: PropTypes.bool.isRequired,
        chipDisabled: PropTypes.bool.isRequired,
      };

      const { rerender } = render(<Host stacked={false} chipDisabled={false} />);
      const chip = screen.getByTestId('chip');
      chip.focus();
      expect(document.activeElement).toBe(chip);

      // Cover it, and blur as `inert` would — while the chip is still enabled. ⚠️ The order
      // matters and the first cut got it wrong: jsdom does not blur a focused element when it
      // becomes disabled (browsers do), and `blur()` on an already-disabled node is itself a
      // no-op, so disabling first left focus stuck on the chip and the test failed at this line
      // rather than exercising anything.
      rerender(<Host stacked chipDisabled={false} />);
      document.activeElement.blur();
      expect(document.activeElement).toBe(document.body);

      // Now the recorded control stops accepting focus, which is what a re-render underneath a
      // covering layer can plausibly do, and the uncover arrives to find it unfocusable.
      rerender(<Host stacked={false} chipDisabled />);
      expect(chip).toBeDisabled();
      expect(document.activeElement).toBe(screen.getByRole('dialog', { name: 'Under' }));
    });

    it('⚠️ with a REAL covering dialog, whichever layer gets there first, focus ends up inside', async () => {
      // Every other fixture in this block drives `stacked` as a bare prop on a lone `Modal`. In the
      // app there is always a covering dialog, and it is not a bystander: its `useDialogFocus`
      // cleanup calls `previous.focus()` in the same passive-destroy pass, immediately BEFORE this
      // effect's create. Which of the two moves the reader depends on what the cover captured, and
      // that in turn depends on whether `inert`'s blur had landed by then — measured through
      // Playwright as two to four frames late, in both Chromium and WebKit, so BOTH orders happen
      // in the wild. This pins the outcome rather than the winner.
      //
      // Without it the suite pins the branch's logic but not the interaction that decides whether
      // the branch is reached at all — a change to the hook's cleanup could make the fallback dead
      // code with nothing here turning red.
      const Stack = ({ covered }) => (
        <div>
          <Modal label="Under" stacked={covered} data-testid="under">
            <button type="button" data-testid="chip">Chip</button>
          </Modal>
          {covered && (
            <Modal label="Cover" data-testid="cover">
              <button type="button" data-testid="cover-chip">Cover chip</button>
            </Modal>
          )}
        </div>
      );
      Stack.propTypes = { covered: PropTypes.bool.isRequired };

      // ORDER A — the blur lands LATE, so the cover mounts while the under-root still holds focus
      // and captures it as its own return address. Here the cover hands the reader back on unmount
      // and the fallback never runs. Kept because it is the common Chromium case, and because it
      // pins that the two mechanisms do not fight.
      const { rerender, unmount } = render(<Stack covered={false} />);
      await waitFor(() => expect(screen.getByRole('dialog', { name: 'Under' })).toHaveFocus());
      rerender(<Stack covered />);
      // `inert` blurs; jsdom implements none, so it is simulated — after the cover mounted.
      document.activeElement.blur();
      expect(document.activeElement).toBe(document.body);
      rerender(<Stack covered={false} />);
      expect(document.activeElement).toBe(screen.getByRole('dialog', { name: 'Under' }));
      unmount();

      // ORDER B — the blur lands EARLY, before the cover mounts, so the cover captures `<body>` and
      // its cleanup's `body.focus()` is a no-op. Nothing upstairs can help, and this fallback is the
      // only thing standing between the reader and the top of the document. ⚠️ This half is the one
      // that fails when the fallback is deleted; order A passes either way, which is exactly why
      // both are here.
      const second = render(<Stack covered={false} />);
      await waitFor(() => expect(screen.getByRole('dialog', { name: 'Under' })).toHaveFocus());
      document.activeElement.blur();
      expect(document.activeElement).toBe(document.body);
      second.rerender(<Stack covered />);
      second.rerender(<Stack covered={false} />);

      const under = screen.getByRole('dialog', { name: 'Under' });
      expect(document.activeElement).toBe(under);
      expect(screen.queryByTestId('cover')).toBeNull();
    });

    it('⚠️ still does nothing on a dialog that MOUNTS unstacked with focus already orphaned', async () => {
      // The pin on `wasStacked`. Until the root fallback above, "this is a mount, not an uncover"
      // was inferred from `lastInside` being null — sound only while a null recording meant "do
      // nothing". Make it actionable and that inference silently becomes "focus the root on mount",
      // in a PASSIVE EFFECT: ahead of `useDialogFocus`'s deliberate frame, and ahead of the
      // consumer-autofocus yield that only exists because of it.
      //
      // Focus is deliberately parked on `<body>` here, because that is the one starting state in
      // which every other guard stands aside and `wasStacked` is the only thing left holding the
      // line. Delete that ref and this test fails while the whole block above stays green.
      document.body.focus();
      expect(document.activeElement).toBe(document.body);

      render(
        <Modal label="Never stacked" data-testid="solo">
          <button type="button" data-testid="solo-chip">Chip</button>
        </Modal>,
      );
      // Nothing has moved focus yet — the restore effect has run and stood down, and the only
      // thing that may take the root is the hook's frame, which has not fired.
      expect(document.activeElement).toBe(document.body);

      // And when it does fire, the root takes focus on the OPEN path exactly as it always did.
      await waitFor(() => expect(screen.getByRole('dialog', { name: 'Never stacked' })).toHaveFocus());
    });

    it('leaves the unstacked path exactly as it was — the container takes focus, nothing else moves', async () => {
      // ⚠️ Named for what it can actually see. An adversarial review disabled the whole restore
      // effect and this test still passed, because `lastInside` is null on a dialog that is never
      // stacked and the effect returns at its first guard — so what is asserted is
      // `useDialogFocus`'s own behaviour, unchanged. That is worth an assertion (it is the
      // guarantee rule 10 asks for) and it is NOT a pin on the new effect; the three tests above
      // are.
      render(<TriggerAndDialog />);
      const trigger = screen.getByText('Open');
      trigger.focus();
      fireEvent.click(trigger);
      await waitFor(() => expect(screen.getByTestId('m')).toHaveFocus());
      expect(screen.getByTestId('m')).not.toHaveAttribute('inert');
      expect(screen.getByTestId('m')).toHaveAttribute('aria-modal', 'true');
    });
  });

  describe('Escape', () => {
    it('closes the dialog when the caller opted in', () => {
      const onClose = vi.fn();
      render(<Modal label="Test" onClose={onClose} closeOnEscape><p>Content</p></Modal>);

      fireEvent.keyDown(document, { key: 'Escape' });
      expect(onClose).toHaveBeenCalledOnce();
    });

    it('does nothing by default, because most dialogs here hold something to lose', () => {
      // Most render sites would lose state: some carry unsaved forms, one holds a generated
      // password that has to be copied before it is gone. Turning dismissal on everywhere would
      // have converted a dead handler into a data-loss handler.
      const onClose = vi.fn();
      render(<Modal label="Test" onClose={onClose}><p>Content</p></Modal>);

      fireEvent.keyDown(document, { key: 'Escape' });
      expect(onClose).not.toHaveBeenCalled();
    });

    it('does nothing on a dialog with no onClose, even when asked', () => {
      // The settings modal's refresh spinner is deliberately unclosable and passes no handler.
      expect(() => {
        render(<Modal label="Test" closeOnEscape><p>Content</p></Modal>);
        fireEvent.keyDown(document, { key: 'Escape' });
      }).not.toThrow();
    });

    it('stops listening once it has closed', () => {
      const onClose = vi.fn();
      const { unmount } = render(
        <Modal label="Test" onClose={onClose} closeOnEscape><p>Content</p></Modal>,
      );
      unmount();

      fireEvent.keyDown(document, { key: 'Escape' });
      expect(onClose).not.toHaveBeenCalled();
    });

    it('ignores every other key', () => {
      const onClose = vi.fn();
      render(<Modal label="Test" onClose={onClose} closeOnEscape><p>Content</p></Modal>);

      fireEvent.keyDown(document, { key: 'Enter' });
      fireEvent.keyDown(document, { key: ' ' });
      fireEvent.keyDown(document, { key: 'Tab' });
      expect(onClose).not.toHaveBeenCalled();
    });
  });
});
