import React from 'react';
import PropTypes from 'prop-types';
import BrandLockup from './shared/BrandLockup.jsx';
import { clearSwrCache } from '../utils/swrCache.js';
import { PLAN_REACH_KEY } from '../utils/reachLens.js';
import { PLAN_RATING_KEY } from '../utils/ratingLens.js';
import { PLAN_DOORS_KEY } from '../utils/planDoors.js';

/**
 * Catches a render failure anywhere in the Plan and offers a way out of it.
 *
 * <h2>Why this exists at all, and why it is not general error handling</h2>
 *
 * <p>The Plan shell hydrates persisted state on every mount — a ~1.3 MB briefing payload from
 * {@code photocast_swr:v2:} ({@code WindowFirstBriefingContext.jsx}), the forecast cache
 * {@code useForecasts} keeps, the reach lens, the rating floor and the doors' open/closed state —
 * and the cog and Sign out live INSIDE the subtree this boundary guards, with no header outside it.
 * A crash reproduced from any of that survives a plain reload: blank page, reload, blank page, with
 * no route back that does not involve devtools.
 *
 * <p>Before this, there was no error boundary anywhere in the app. React's own fallback for an
 * uncaught render error is to unmount the <b>entire</b> tree — footer, modals and all.
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
 * its subtree, which is where the persisted-state trap lives.
 *
 * <h2>Two controls, not a "Try again"</h2>
 *
 * <p>A plain "Reload" would return a reader whose crash reproduces from persisted state straight
 * back to the same panel — a control whose only effect is itself. "Clear cached data and reload"
 * removes exactly the persisted **selections** that would re-select the same broken render path
 * (see {@code handleClearAndReload} below) and then reloads; "Sign out" ends the session the
 * subtree took down with it. Neither acts on its own — the reader chooses.
 *
 * @param {object}   props
 * @param {React.ReactNode} props.children   the subtree this guards
 * @param {function} props.onSignOut         ends the session; the button's handler
 * @param {function} [props.reload]          defaults to reloading the page. Overridable because
 *        jsdom 30 cannot stub `window.location.reload` directly (own non-configurable property).
 */
class PlanErrorBoundary extends React.Component {
  constructor(props) {
    super(props);
    this.state = { error: null };
    this.handleClearAndReload = this.handleClearAndReload.bind(this);
  }

  static getDerivedStateFromError(error) {
    return { error };
  }

  componentDidCatch(error, info) {
    // The stack goes here rather than on screen: it is the thing worth having in a bug report and
    // the thing nobody can act on in a panel.
    console.error('Plan crashed:', error, info?.componentStack);
  }

  handleClearAndReload() {
    // Not because a malformed key can throw — the readers here are all fail-soft — but because a
    // VALID stored selection re-selects the same render path on every reload, so clearing the
    // hydrated payload cache alone can land the reader straight back in the crash. The auth keys
    // are untouched: the reader stays signed in, and the button's copy says so.
    clearSwrCache();
    localStorage.removeItem(PLAN_REACH_KEY);
    localStorage.removeItem(PLAN_RATING_KEY);
    sessionStorage.removeItem(PLAN_DOORS_KEY);
    const { reload = () => window.location.reload() } = this.props;
    reload();
  }

  render() {
    const { children, onSignOut } = this.props;
    const { error } = this.state;
    if (!error) return children;

    return (
      <div data-testid="plan-error" className="card border-red-900/50 text-center py-8" role="alert">
        {/* The subtree that died normally supplies the page's only <h1>. Without this the fallback
            is a page with no heading at all. */}
        <div className="flex justify-center mb-4">
          <BrandLockup variant="compact" />
        </div>
        <h2 className="text-red-400 font-medium mb-2">The Plan stopped working</h2>
        {/* The message, not the stack. Enough to quote into a bug report; the rest is in the console. */}
        <p data-testid="plan-error-detail" className="text-plex-text-secondary text-sm mb-4">
          {error.message || String(error)}
        </p>
        <p className="text-plex-text-muted text-xs mb-4">You will stay signed in.</p>
        <div className="flex justify-center gap-3">
          <button
            type="button"
            className="btn-primary"
            data-testid="plan-error-clear-reload"
            onClick={this.handleClearAndReload}
          >
            Clear cached data and reload
          </button>
          <button
            type="button"
            className="btn-secondary"
            data-testid="plan-error-signout"
            onClick={onSignOut}
          >
            Sign out
          </button>
        </div>
      </div>
    );
  }
}

PlanErrorBoundary.propTypes = {
  children: PropTypes.node,
  onSignOut: PropTypes.func.isRequired,
  reload: PropTypes.func,
};

export default PlanErrorBoundary;
