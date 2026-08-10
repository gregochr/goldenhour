import React from 'react';
import PropTypes from 'prop-types';
import BrandLockup from './shared/BrandLockup.jsx';

/**
 * Catches a render failure in one Plan arm and offers the route to the other.
 *
 * <h2>Why this exists at all, and why it is not general error handling</h2>
 *
 * <p>Two Plan layouts ship in one build and a localStorage flag chooses between them. That flag is
 * what makes an ordinary React crash into a trap rather than a bad afternoon: {@code App} suppresses
 * the v1 header entirely for the window-first arm, so the cog and Sign out live <em>inside</em> the
 * arm that just died; the settings modal is a sibling of the flag branch and dies with the tree; and
 * the flag survives the reload, so the next paint throws in the same place. Blank page, reload, blank
 * page, with no route back that does not involve devtools.
 *
 * <p>Before this, there was no error boundary anywhere in the app. React's own fallback for an
 * uncaught render error is to unmount the <b>entire</b> tree — footer, modals and all — so the arm
 * taking the page down with it was the default behaviour, not an edge case.
 *
 * <h2>It does not reset the flag by itself</h2>
 *
 * <p>Deliberate, and the argument is not "the developer wants to see failures" — it is that catching
 * the error has <em>already</em> closed the reload trap. The flag stays, the arm re-renders, it
 * throws again, and this catches it again: the reader sees an explanation rather than a white
 * screen. An automatic write buys nothing beyond that, and costs two things — it makes a real crash
 * indistinguishable from a mis-click, and it is a control acting without being asked, which is the
 * same defect the shell's own exit button was moved outside the greyed pane to avoid.
 *
 * <h2>A class, because there is no alternative</h2>
 *
 * <p>React 19 has no hook form of an error boundary; {@code getDerivedStateFromError} on a class is
 * the only construct the reconciler will absorb an error into. This is the codebase's only class
 * component, and it is not a style regression — it is the API.
 *
 * <h2>What it does not catch</h2>
 *
 * <p>Event handlers, {@code setTimeout} and promise callbacks, and SSE {@code onmessage} — none of
 * those are render, so none reach a boundary. It catches render, lifecycle and constructor throws in
 * its subtree, which is where the flag trap lives.
 *
 * <h2>⚠️ It relies on being INSIDE the flag branch</h2>
 *
 * <p>Recovery needs no reset logic: {@code onRecover} flips the flag, the branch renders its other
 * side, the element at this position changes type, and React discards this instance and its error
 * state with it. Moving this outside the ternary would keep one instance alive across the flip, so
 * the fallback would persist over a healthy arm — at which point it needs {@code key={planLayout}}.
 *
 * <p>The arms are deliberately not both wrapped, because the honest recovery differs: this arm's is
 * "go back to the other one", and the current Plan has no better arm to offer. ⚠️ That inverts the
 * day the default flips — the toggle-reached arm becomes the one holding the only route to the flag
 * — which is why the label and the action are props rather than baked in.
 *
 * @param {object}   props
 * @param {React.ReactNode} props.children  the arm this guards
 * @param {function} props.onRecover        flips the layout flag; called only from the button
 * @param {string}   [props.recoverLabel]   the button's words, so the escape reads the same as the
 *                                          one the healthy arm offers
 */
class PlanLayoutErrorBoundary extends React.Component {
  constructor(props) {
    super(props);
    this.state = { error: null };
  }

  static getDerivedStateFromError(error) {
    return { error };
  }

  componentDidCatch(error, info) {
    // The stack goes here rather than on screen: it is the thing worth having in a bug report and
    // the thing nobody can act on in a panel.
    console.error('Plan layout crashed:', error, info?.componentStack);
  }

  render() {
    // Defaulted here rather than through `defaultProps`, which React 19 warns on for function
    // components and which this codebase removed everywhere at the React 19 migration. A class
    // cannot use a JS default parameter, so the destructure is where it goes.
    const { children, onRecover, recoverLabel = '← Back to the current Plan' } = this.props;
    const { error } = this.state;
    if (!error) return children;

    return (
      <div data-testid="plan-layout-error" className="card border-red-900/50 text-center py-8" role="alert">
        {/* The arm that died normally supplies the page's only <h1>. Without this the fallback is a
            page with no heading at all, and the signed-in app stops being identifiable by it. */}
        <div className="flex justify-center mb-4">
          <BrandLockup variant="compact" />
        </div>
        <h2 className="text-red-400 font-medium mb-2">This Plan layout stopped working</h2>
        {/* The message, not the stack. Enough to quote into a bug report; the rest is in the console. */}
        <p data-testid="plan-layout-error-detail" className="text-plex-text-secondary text-sm mb-4">
          {error.message || String(error)}
        </p>
        {/* One action. A "Try again" would re-render the same broken subtree and land straight back
            here — a control whose only effect is to return you to itself. */}
        <button type="button" className="btn-primary" data-testid="plan-layout-error-recover" onClick={onRecover}>
          {recoverLabel}
        </button>
      </div>
    );
  }
}

PlanLayoutErrorBoundary.propTypes = {
  children: PropTypes.node,
  onRecover: PropTypes.func.isRequired,
  recoverLabel: PropTypes.string,
};

export default PlanLayoutErrorBoundary;
