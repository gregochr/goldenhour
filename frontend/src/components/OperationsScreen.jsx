import { useId, useLayoutEffect, useRef } from 'react';
import PropTypes from 'prop-types';

/**
 * Operations as a full-screen view (`docs/engineering/one-screen-plan.md` D9): the admin's console, reached from the
 * ☰ menu and open exactly while the page's address says {@code #manage...}.
 *
 * <p>It is a labelled region with a "‹ PhotoCast" button and a heading, around whatever {@code children} holds
 * ({@code App} hands the lazy {@code ManageView} to admins only, so this component never sees a role). It is
 * deliberately <b>not</b> a Back layer: the Operations tabs already write {@code #manage/<tab>} hashes, so Back steps
 * through them and past the first one out of the view, and registering a {@code #manage} panel with the dialog history
 * is banned (`docs/engineering/reference/plan-tab.md`, "Back").
 *
 * <p>Focus: the heading takes focus when the view mounts (it is {@code tabIndex=-1}), so a keyboard or screen-reader
 * reader lands on the view they asked for. The shell returns focus to the ☰ button when it closes.
 *
 * @param {object} props
 * @param {Function} props.onClose "‹ PhotoCast": leave Operations for the screen
 * @param {React.ReactNode} props.children the Operations content
 */
export default function OperationsScreen({ onClose, children }) {
  const headingId = useId();
  const headingRef = useRef(null);
  useLayoutEffect(() => { headingRef.current?.focus({ preventScroll: true }); }, []);
  return (
    <section
      aria-labelledby={headingId}
      data-testid="operations-screen"
      className="wf-ops-screen mx-auto w-full max-w-[1080px]"
    >
      <div className="wf-ops-head">
        <button
          type="button"
          data-testid="operations-back"
          className="wf-ops-back font-mono"
          onClick={() => onClose?.()}
        >
          <span aria-hidden="true">‹ </span>
          PhotoCast
        </button>
        <h2
          id={headingId}
          ref={headingRef}
          tabIndex={-1}
          data-testid="operations-title"
          className="wf-ops-title font-serif"
        >
          Operations
        </h2>
      </div>
      <div className="wf-body" data-testid="operations-body">{children}</div>
    </section>
  );
}

OperationsScreen.propTypes = {
  onClose: PropTypes.func,
  children: PropTypes.node,
};
