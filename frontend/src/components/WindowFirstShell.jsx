import React from 'react';
import PropTypes from 'prop-types';
import BrandLockup from './shared/BrandLockup.jsx';

/** The design's frame: 1080px, against the v1 arm's 896px `max-w-4xl`. */
const WRAP_MAX_WIDTH = '1080px';

/**
 * The window-first Plan tab's own shell — masthead, tab bar, and the frame both sit in.
 *
 * <h2>It renders its own masthead because it replaces the app's</h2>
 *
 * <p>{@code App} suppresses its {@code <header>} for this arm, so everything that header carried
 * has to exist here or it is simply gone: the wordmark, the settings cog and Sign out. The two
 * buttons take the same handlers, lifted rather than duplicated — this component owns no auth or
 * modal state of its own.
 *
 * <p><b>Not the design's masthead brand, deliberately.</b> The mock draws a conic-gradient disc and
 * a 20px sans wordmark. This app's identity is {@link BrandLockup} — a film-perforation spine and a
 * serif wordmark — and that component's own Javadoc records why the previous {@code logo.png} went:
 * it "belonged to no part of the Kodachrome Field Guide system the rest of the app uses". Drawing
 * the disc would reintroduce exactly that, as the only mark of its kind in the product. Worse, the
 * flag runs both layouts at once so they can be judged against the same night's data (plan §4), and
 * a different wordmark in one arm makes every comparison a brand comparison too. The {@code compact}
 * variant exists for this masthead's height budget. Recorded in plan §7.
 *
 * <h2>No status pill</h2>
 *
 * <p>The design shows {@code ● UP v2.17.7} unconditionally. Build version and service health are
 * not a pilot user's business, and {@code HealthIndicator} is admin-only today — so the pill is
 * dropped rather than reproduced. Plan §7.
 *
 * <h2>The tab bar carries only Plan, and that is not an oversight</h2>
 *
 * <p>The design draws four tabs. Three of their panes do not exist yet: Coming up is P13, and Map
 * and Manage arrive when this subtree takes over view state. A tab that renders nothing is a demo
 * control, and §6 bans those from the shipped build — so each tab lands with its pane. What ships
 * here is the bar itself: geometry, active treatment, and the rule the active tab sits on, which is
 * what those phases slot into.
 *
 * <p>{@code border-bottom-width: 0} on the tab, never {@code border-bottom: none} — the shorthand
 * would also clear the colour the active state needs.
 *
 * @param {object}   props
 * @param {function} props.onExit restores the v1 layout. It does not change the selected tab, so a
 *        user who switched into v2 from the Map tab returns to the Map tab — hence the label says
 *        "Plan", the layout, and not "Plan tab".
 * @param {function} props.onOpenSettings opens the shared settings modal, which owns the flag
 *        toggle — so this is the route back that survives once the temporary exit button goes.
 * @param {function} props.onSignOut ends the session; the same handler the v1 header uses.
 * @param {boolean}  [props.contentDisabled] greys the pane when the backend is DOWN.
 *
 *        <p><b>The pane, never the chrome.</b> In the v1 arm the header sits OUTSIDE the element
 *        carrying that treatment, so a DOWN backend has never been able to disable Settings or
 *        Sign out. Here the masthead is inside the shell, so gating the whole thing would take the
 *        cog, Sign out and the exit hatch with it — leaving a user staring at a greyed page with
 *        no route anywhere, at exactly the moment they most need one.
 */
export default function WindowFirstShell({ onExit, onOpenSettings, onSignOut, contentDisabled }) {
  return (
    <div
      data-testid="window-first-shell"
      className="mx-auto w-full"
      style={{ maxWidth: WRAP_MAX_WIDTH }}
    >
      <div
        data-testid="window-first-masthead"
        className="flex items-center gap-3 border-b border-plex-border"
        style={{ padding: '16px 18px 14px' }}
      >
        <BrandLockup variant="compact" />
        <div className="ml-auto flex items-center gap-2">
          <button
            type="button"
            onClick={onOpenSettings}
            data-testid="window-first-settings"
            aria-label="Settings"
            className="font-mono border border-plex-border text-plex-text-muted hover:text-plex-text hover:border-plex-border-light transition-colors"
            style={{ fontSize: '10.5px', borderRadius: '7px', padding: '5px 10px' }}
          >
            ⚙
          </button>
          <button
            type="button"
            onClick={onSignOut}
            data-testid="window-first-signout"
            className="font-mono border border-plex-border text-plex-text-muted hover:text-plex-text hover:border-plex-border-light transition-colors"
            style={{ fontSize: '10.5px', borderRadius: '7px', padding: '5px 10px' }}
          >
            Sign out
          </button>
        </div>
      </div>

      <div
        data-testid="window-first-tabs"
        role="tablist"
        aria-label="Plan sections"
        className="flex gap-1.5"
        style={{ padding: '12px 18px 0' }}
      >
        <button
          type="button"
          role="tab"
          aria-selected="true"
          data-testid="window-first-tab-plan"
          className="bg-plex-surface text-plex-text font-sans whitespace-nowrap border border-plex-border"
          style={{
            fontSize: '12.5px',
            fontWeight: 600,
            borderBottomWidth: 0,
            borderRadius: '8px 8px 0 0',
            padding: '8px 14px',
            boxShadow: 'inset 0 2px 0 var(--color-close-to-home)',
          }}
        >
          <span aria-hidden="true" style={{ fontSize: '12px', opacity: 0.8 }}>◉</span> Plan
        </button>
      </div>
      <div data-testid="window-first-tabrule" className="h-px bg-plex-border" />

      <div
        data-testid="window-first-pane"
        className={`px-5 py-8 text-center${contentDisabled ? ' opacity-50 pointer-events-none' : ''}`}
      >
        <p className="text-plex-text" style={{ fontSize: '15.5px', fontWeight: 700 }}>
          Window-first Plan
        </p>
        <p
          className="text-plex-text-muted font-mono mx-auto"
          style={{ fontSize: '10.5px', marginTop: '7px', maxWidth: '46ch', lineHeight: 1.6 }}
        >
          The day rail, window cards and spot strips arrive in later phases. The shell around them
          is real, and the flag that put you here works.
        </p>
        <button
          type="button"
          onClick={onExit}
          data-testid="window-first-exit"
          className="font-mono border border-plex-border text-plex-text-secondary hover:text-plex-text hover:border-plex-border-light transition-colors"
          style={{ fontSize: '10.5px', borderRadius: '7px', padding: '6px 11px', marginTop: '16px' }}
        >
          ← Back to the current Plan
        </button>
      </div>
    </div>
  );
}

WindowFirstShell.propTypes = {
  onExit: PropTypes.func.isRequired,
  onOpenSettings: PropTypes.func.isRequired,
  onSignOut: PropTypes.func.isRequired,
  contentDisabled: PropTypes.bool,
};
