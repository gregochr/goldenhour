import React from 'react';
import PropTypes from 'prop-types';

/**
 * The window-first Plan tab's root — a placeholder until P4 builds the real shell.
 *
 * <p><b>Why it exists at P0.</b> The flag has to be provable before there is anything to show, or
 * the switch itself goes unverified until the phase that needs it most. This is a placeholder
 * <em>card</em> — `--plex-panel`, 12px radius — and deliberately carries no width of its own, so it
 * inherits whatever column `<main>` gives it.
 *
 * <p><b>P4 must move this branch; it is not already in the right place.</b> The branch sits inside
 * `<main className="max-w-4xl …">` (`App.jsx`), which is 896px — about 200px under the design's
 * 1080px frame — and below the app's own unconditional `<header>`. A masthead grown here would
 * render beneath that header inside the narrower column. So P4 owns two moves: lifting the branch
 * out of `<main>`, and suppressing the app header for v2 once the design's masthead (which carries
 * its own ⚙ and Sign out) lands. The frame/page contrast is an open question at that point, not a
 * solved one — the design separates frame from page with `body{background:#0e0b09}`, which the plan
 * rules out as a product token, while this app's page is already `--plex-bg`.
 *
 * <p><b>Why the branch is above the tab bar rather than inside the Plan pane.</b> The design's tabs
 * are a different control from `ViewToggle`, and the v2 subtree owns its own shell and tab state.
 * Branching inside the pane would mean retrofitting that at P4 and touching `DailyBriefing` on the
 * way — the one thing the flag exists to avoid.
 *
 * <p><b>This is the second route back, not the only one.</b> The app header renders in both arms,
 * so its ⚙ still reaches the toggle from inside v2. This control is the shorter route, and it earns
 * its place while the arm below it is empty. It goes when P4 lands the real masthead.
 *
 * @param {object}   props
 * @param {function} props.onExit restores the v1 layout. It does not change the selected tab, so a
 *        user who switched into v2 from the Map tab returns to the Map tab — hence the label says
 *        "Plan", the layout, and not "Plan tab".
 */
export default function WindowFirstShell({ onExit }) {
  return (
    <div
      data-testid="window-first-shell"
      className="bg-plex-panel border border-plex-border overflow-hidden"
      style={{ borderRadius: '12px' }}
    >
      <div className="px-5 py-8 text-center">
        <p className="text-plex-text" style={{ fontSize: '15.5px', fontWeight: 700 }}>
          Window-first Plan
        </p>
        <p
          className="text-plex-text-muted font-mono mx-auto"
          style={{ fontSize: '10.5px', marginTop: '7px', maxWidth: '46ch', lineHeight: 1.6 }}
        >
          The shell, window cards and spot strips arrive in later phases. This is the mount point,
          and the flag that put you here works.
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
};
