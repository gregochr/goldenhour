import React, { useEffect, useState } from 'react';
import PropTypes from 'prop-types';
import BrandLockup from './shared/BrandLockup.jsx';
import WindowFirstDayRail from './WindowFirstDayRail.jsx';
import WindowFirstLensBar from './WindowFirstLensBar.jsx';
import WindowFirstWindowCard from './WindowFirstWindowCard.jsx';
import WindowPickDialog from './WindowPickDialog.jsx';
import { useWindowFirstBriefing } from '../context/WindowFirstBriefingContext.jsx';
import { formatRelativeAge } from '../utils/relativeTime.js';

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
 * <h2>The day rail sits above the tabs, not inside the Plan pane</h2>
 *
 * <p>That is where the design puts it, and the reason is that the rail is the whole screen's date
 * context rather than one pane's content: Coming up and Map answer questions about the same days.
 * Putting it inside the pane would make it disappear on a tab that still needs it, and would
 * re-mount it on every tab change.
 *
 * <p>It takes the {@code contentDisabled} greying with the pane, because it is forecast data and
 * data from a DOWN backend is exactly what that treatment exists to mark. The tab bar does not:
 * it is navigation, and so is the masthead.
 *
 * <h2>The rail footer's two halves, both of them now</h2>
 *
 * <p>The left one is the reach lens's prompt, and it lands here rather than on the bar itself for
 * the reason plan §2.5 gives: the bar is {@code position: sticky} and must not be suppressed for a
 * user with no home, so the thing that <em>varies</em> per user goes in the slot the design already
 * reserves for it. {@code Home · <place>} when one is set, "Home not set" when the settings response
 * says there is none, and <b>nothing at all</b> while that is still unknown — telling a user who has
 * a home that they have not set one, on the strength of a dropped request, is worse than silence.
 * "Edit reach" opens the same settings modal the cog does, which is where a postcode is entered.
 *
 * <p>The right half is {@code generatedAt} formatted on the client (§2.8 — a server-rendered
 * relative string would mutate the ETagged body on every request) through the shared
 * {@code formatRelativeAge}, which already knows the instant is UTC. The design's {@code by Sonnet}
 * is dropped: the model name is admin-only today and is not a pilot user's business (§7). Its
 * "· reach set per day" is dropped too — the bar's own "today only" pill and its named reset state
 * that policy exactly when it applies, and §2.7's rule against marking one fact twice holds here as
 * well as it does for confidence.
 *
 * <p>The footer's ink moved from muted to secondary in the same change. Measured on the running
 * app: at 10px on {@code --color-plex-bg} muted is <b>3.55:1</b> and fails AA, secondary is
 * <b>7.09:1</b>. This is the fifth time the redesign has had to make that correction; leaving one
 * span of the row on the old tone to keep the diff smaller would have put two greys in one line
 * for no reason.
 *
 * <h2>The lens bar sits between the tab rule and the pane, and is never dimmed</h2>
 *
 * <p>Where the design puts it, and outside the {@code contentDisabled} treatment on purpose. The
 * lens is a pure client-side filter over data already in memory, so it keeps working when the
 * backend does not — and {@code pointer-events: none} on a sticky bar would make a live control
 * look broken to say nothing true. The tab bar and the masthead are excluded for the same reason.
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
export default function WindowFirstShell({
  onExit, onOpenSettings, onSignOut, contentDisabled, onShowOnMap, onEvaluationScoresChange,
}) {
  const {
    railTiles, windowCards, loading, briefing, evaluationScores, todayStr, reachLens, homePlace,
  } = useWindowFirstBriefing();
  const [openPick, setOpenPick] = useState(null);

  // Lifted to App for the map overlay, exactly as DailyBriefing does it in the v1 arm. Without this
  // a tile handed to the map opens an overlay with no narrative over a map that has filtered out
  // every unrated pin — see the provider's note on why this arm fetches them at all.
  useEffect(() => {
    onEvaluationScoresChange?.(evaluationScores);
  }, [evaluationScores, onEvaluationScoresChange]);

  const dimmed = contentDisabled ? ' opacity-50 pointer-events-none' : '';
  // The shared tiers, not a local copy: `generatedAt` is a zone-less UTC instant, and the one
  // formatter that already knows that is the one that appends the Z. Hand-rolling it here read an
  // hour young in BST — parsing bare takes the string as local, so a 34-minute-old forecast said
  // "1h ago". Caught by looking at the running app, not by a test.
  const age = formatRelativeAge(briefing?.generatedAt);
  // The map handoff's object form, matching the v1 strip's region chips exactly — `onShowOnMap`
  // reads a positional (date, eventType) or a `{region, …}` object, and a region chip is the
  // latter. Passing the tile handler for both would open the map on the day, not the region.
  const handleRegion = (regionName, date, targetType) => (
    onShowOnMap?.({ region: regionName, date, eventType: targetType })
  );
  // The POSITIONAL form, which centres the map on one location — the same call the pick dialog's
  // "show location" already makes. The object form above opens a whole region, which is a different
  // destination: a spot card names one place and must land on it.
  const handleSpot = (card, spot) => (
    onShowOnMap?.(card.date, card.targetType, spot.locationName)
  );
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

      <div data-testid="window-first-rail-region" className={dimmed.trim() || undefined}>
        <WindowFirstDayRail tiles={railTiles} onTileClick={onShowOnMap} onRegionClick={handleRegion} />
        {!loading && railTiles.length === 0 && (
          <p
            data-testid="window-first-rail-empty"
            className="font-mono text-plex-text-muted"
            style={{ fontSize: '10.5px', padding: '13px 18px 0' }}
          >
            No forecast days to show yet.
          </p>
        )}
      </div>

      {/* OUTSIDE the greyed rail region, and that is a fix rather than a placement preference.
          Everything in this row survives a dead backend: the home is a per-user setting, "Edit
          reach" is the only route to fixing an empty lens — the same trap P4a fixed for the
          masthead and this file fixed again for the exit button — and the forecast's AGE is the
          one fact that becomes more useful when the backend is down, not less. Nothing here is
          forecast content, so nothing here takes the treatment that marks it. */}
      <div
        data-testid="window-first-railfoot"
        className="flex items-center font-mono text-plex-text-secondary"
        style={{ fontSize: '10px', padding: '6px 18px 0', gap: '8px' }}
      >
        {/* Undefined is "we do not know yet", and it renders nothing. Only a settings response
            that came back without a home says so out loud. */}
        {homePlace !== undefined && (
          <span data-testid="window-first-home">
            {homePlace ? `Home · ${homePlace}` : 'Home not set'}
          </span>
        )}
        <button
          type="button"
          onClick={onOpenSettings}
          data-testid="window-first-edit-reach"
          className="ml-auto hover:text-plex-text transition-colors"
          style={{ textDecoration: 'underline', textUnderlineOffset: '2px' }}
        >
          Edit reach
        </button>
        {age && <span data-testid="window-first-age">forecast {age}</span>}
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

      {reachLens && (
        <WindowFirstLensBar
          lens={reachLens}
          spotCount={windowCards.reduce((total, card) => total + card.spots.length, 0)}
          windowCount={windowCards.length}
        />
      )}

      <div
        data-testid="window-first-pane"
        className={`flex flex-col${dimmed}`}
        style={{ padding: '14px 18px 20px', gap: '10px' }}
      >
        {windowCards.map((card) => (
          <WindowFirstWindowCard
            key={card.key}
            card={card}
            todayStr={todayStr}
            reachLabel={reachLens?.tier?.label}
            onOpenPick={setOpenPick}
            onOpenSpot={handleSpot}
          />
        ))}
        {!loading && windowCards.length === 0 && (
          <p
            data-testid="window-first-pane-empty"
            className="font-mono text-plex-text-muted"
            style={{ fontSize: '10.5px' }}
          >
            No windows to show.
          </p>
        )}
      </div>

      {/* OUTSIDE the pane, and that is a fix rather than a placement preference. The DOWN treatment
          is `pointer-events: none`, so while the exit button lived inside the pane a dead backend
          made the visible way back inert — the same trap P4a fixed for the masthead, re-created one
          level down. The cog still opens the settings modal that owns the durable toggle, but the
          button that names the route must work too. */}
      <div style={{ padding: '0 18px 20px' }}>
        <button
          type="button"
          onClick={onExit}
          data-testid="window-first-exit"
          className="font-mono border border-plex-border text-plex-text-secondary hover:text-plex-text hover:border-plex-border-light transition-colors"
          style={{ fontSize: '10.5px', borderRadius: '7px', padding: '6px 11px' }}
        >
          ← Back to the current Plan
        </button>
      </div>

      {openPick?.pick && (
        <WindowPickDialog
          pick={openPick.pick}
          when={openPick.when}
          time={openPick.time}
          onClose={() => setOpenPick(null)}
          onShowRegion={() => {
            onShowOnMap?.({
              region: openPick.pick.regionName, date: openPick.date, eventType: openPick.targetType,
            });
            setOpenPick(null);
          }}
          onShowLocation={() => {
            onShowOnMap?.(openPick.date, openPick.targetType, openPick.pick.locationName);
            setOpenPick(null);
          }}
        />
      )}
    </div>
  );
}

WindowFirstShell.propTypes = {
  onExit: PropTypes.func.isRequired,
  onOpenSettings: PropTypes.func.isRequired,
  onSignOut: PropTypes.func.isRequired,
  contentDisabled: PropTypes.bool,
  onShowOnMap: PropTypes.func,
  onEvaluationScoresChange: PropTypes.func,
};
