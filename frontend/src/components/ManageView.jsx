import React, { useState, useCallback, useEffect, useRef } from 'react';
import PropTypes from 'prop-types';
import { getWaitlist } from '../api/waitlistApi.js';
import { fetchLocations } from '../api/forecastApi.js';
import { fetchRuns } from '../api/runsApi.js';
import UserManagementView from './UserManagementView.jsx';
import LocationManagementView from './LocationManagementView.jsx';
import RegionManagementView from './RegionManagementView.jsx';
import AreaManagementView from './AreaManagementView.jsx';
import RunsView from './runs/RunsView.jsx';
import ModelSelectionView from './ModelSelectionView.jsx';
import SchedulerView from './SchedulerView.jsx';
import TravelDaysView from './TravelDaysView.jsx';
import SkyRatingEvalView from './SkyRatingEvalView.jsx';
import HotTopicSimulation from './HotTopicSimulation.jsx';
import RewindView from './RewindView.jsx';
import TideManagementView from './TideManagementView.jsx';
import WaitlistManagementView from './WaitlistManagementView.jsx';
import { useIsMobile } from '../hooks/useIsMobile.js';
import { useIsWide } from '../hooks/useIsWide.js';
import { useVisiblePoll } from '../hooks/useVisiblePoll.js';
import { needsAttention } from '../utils/locationAdmin.js';

const GROUPS = [
  {
    value: 'runs',
    label: 'Runs',
    tabs: [
      { value: 'runs', label: 'Runs' },
      { value: 'scheduler', label: 'Scheduler' },
      { value: 'traveldays', label: 'Travel Days' },
    ],
  },
  {
    value: 'tuning',
    label: 'Tuning',
    tabs: [
      { value: 'models', label: 'Run Config' },
      { value: 'skyeval', label: 'Sky Eval' },
    ],
  },
  {
    value: 'places',
    label: 'Places',
    tabs: [
      { value: 'locations', label: 'Locations' },
      { value: 'regions', label: 'Regions' },
      { value: 'areas', label: 'Areas' },
      { value: 'tides', label: 'Tides' },
    ],
  },
  {
    value: 'people',
    label: 'People',
    tabs: [
      { value: 'users', label: 'Users' },
      { value: 'waitlist', label: 'Waitlist' },
    ],
  },
  {
    value: 'testing',
    label: 'Testing',
    tabs: [
      { value: 'hottopics', label: 'Hot Topics' },
      { value: 'rewind', label: 'Rewind' },
    ],
  },
];

const WAITLIST_BADGE_CLASSES =
  'ml-1.5 inline-flex items-center justify-center min-w-[18px] h-[18px] px-1 text-[10px] font-semibold rounded-full bg-plex-gold/20 text-plex-gold';

/** The Locations sub-tab's count of places that need attention: plain amber text after the label, not a pill. */
const ATTENTION_BADGE_CLASSES =
  'ml-1.5 text-[11px] font-semibold text-verdict-marginal';

/** The live count beside Runs: tide text after the label, not a pill (the same shape as the Locations count). */
const LIVE_COUNT_CLASSES = 'ml-1.5 text-[11px] font-semibold text-tide';

/** How often ManageView asks the server how many runs are in flight, so the count shows before Runs is opened. */
const LIVE_COUNT_POLL_MS = 60 * 1000;

/**
 * Rewrites an old Runs hash to its new home, in place and without a history entry (runs-redesign-plan.md §4):
 * {@code #manage/metrics[/…][?…]} is now {@code #manage/runs[/…][?…]} (the Job Runs tab became Runs), and
 * {@code #manage/pipelineruns[/{id}][?…]} is now {@code #manage/runs[/cycle/{id}][?…]} (Pipeline Runs folded into
 * it; a non-numeric id names no cycle). It lives in
 * {@link manageTabFromHash} so mount and {@code hashchange} both see it, and runs before any child reads the hash.
 * {@code replaceState} fires no {@code hashchange}, so callers set their state directly.
 */
function redirectOldRunsHash() {
  const hash = window.location.hash;
  const metrics = /^#manage\/metrics(?=$|[/?])(.*)$/.exec(hash);
  if (metrics) {
    window.history.replaceState(window.history.state, '', `#manage/runs${metrics[1]}`);
    return true;
  }
  const pipeline = /^#manage\/pipelineruns(?=$|[/?])([^?]*)(\?.*)?$/.exec(hash);
  if (!pipeline) return false;
  const id = /^\/(\d+)\/?$/.exec(pipeline[1])?.[1];
  window.history.replaceState(window.history.state, '', `#manage/runs${id ? `/cycle/${id}` : ''}${pipeline[2] ?? ''}`);
  return true;
}

/**
 * Resolve group and tab from the current hash ("manage/skyeval", "manage/waitlist/12"), or null when it is not
 * {@code manage/<known tab>}. A hash that is not a Manage tab (a one-time token the app strips, another area's
 * fragment) must never move the tabs.
 */
function manageTabFromHash() {
  redirectOldRunsHash();
  const parts = window.location.hash.replace('#', '').split('/');
  if (parts[0] !== 'manage' || !parts[1]) return null;
  // A cross-tab link may carry a query (`manage/locations?region=3`); the tab is what precedes it.
  const tab = parts[1].split('?')[0];
  const group = GROUPS.find((g) => g.tabs.some((t) => t.value === tab));
  return group ? { group: group.value, tab } : null;
}

/** The landing group and tab: the hash's, or Runs. */
function parseHash() {
  return manageTabFromHash() ?? { group: 'runs', tab: 'runs' };
}

/**
 * Management screen shell — routes to the active tab component. Exactly one navigation is
 * rendered: a section menu on phones, a left sidebar from 1280px, and the two-level tab bar
 * (group + sub-tab) in between. The content slot keeps one position in the tree in all three
 * layouts, so crossing a breakpoint never remounts the sub-view.
 *
 * @param {object}   props
 * @param {function} props.onComplete - Called after any change so other views refresh.
 */
export default function ManageView({ onComplete }) {
  const initial = parseHash();
  const [activeGroup, setActiveGroup] = useState(initial.group);
  const [activeTab, setActiveTabState] = useState(initial.tab);
  const [activeRunId, setActiveRunId] = useState(null);
  const clearActiveRun = useCallback(() => setActiveRunId(null), []);
  const isMobile = useIsMobile();
  const isWide = useIsWide();
  const rootRef = useRef(null);
  const [waitlistCount, setWaitlistCount] = useState(null);
  // How many runs are in flight (the server's runningCount, which follows no range or kind), for the Runs sub-tab.
  // Read here on a minute poll so the count shows before the tab is opened; the Runs view reports it on each of its
  // own refreshes while it is open.
  const [runningCount, setRunningCount] = useState(0);
  const handleLiveCount = useCallback((count) => setRunningCount(count), []);
  const liveCountLoaded = useRef(false);
  const pollLiveCount = useCallback(() => {
    // The pane stays mounted, hidden, on the Map and Plan tabs: no polling there once a count is in hand.
    if (liveCountLoaded.current && rootRef.current?.closest('[hidden]')) return;
    fetchRuns({ size: 1 })
      .then((page) => {
        liveCountLoaded.current = true;
        setRunningCount(page?.runningCount ?? 0);
      })
      .catch(() => { /* the count stays as it was */ });
  }, []);
  useVisiblePoll(pollLiveCount, LIVE_COUNT_POLL_MS);
  const handleWaitlistCount = useCallback((count) => setWaitlistCount(count), []);
  // How many locations need attention (failing, or turned off by the failure tracking), for the Locations sub-tab.
  // Read once here so the count shows before the tab is opened; the Locations view keeps it current while it is open.
  const [attentionCount, setAttentionCount] = useState(null);
  const handleAttentionCount = useCallback((count) => setAttentionCount(count), []);

  useEffect(() => {
    getWaitlist()
      .then((list) => setWaitlistCount(list.length))
      .catch(() => { /* badge stays hidden on failure */ });
    // On the Locations tab the view reports the count itself once it has read the list, so a second read would only
    // race it.
    if (initial.tab !== 'locations') {
      fetchLocations()
        .then((list) => setAttentionCount(list.filter(needsAttention).length))
        .catch(() => { /* badge stays hidden on failure */ });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // A cross-tab link (Full tide stats, Open in Locations, Show them) sets `#manage/{tab}[/{id}]` while Manage is
  // already mounted, so follow the hash. State is set DIRECTLY, never through `setActiveTab`: that rewrites the
  // hash to `manage/{tab}` and would strip the `/{id}` the tab's own `useHashSelection` reads. A hash that is not
  // `manage/<known tab>` is ignored (it never falls back to Pipeline Runs).
  useEffect(() => {
    const onHashChange = () => {
      const redirected = redirectOldRunsHash();
      const target = manageTabFromHash();
      if (!target) return;
      setActiveGroup(target.group);
      setActiveTabState(target.tab);
      // `replaceState` fires no hashchange, and the Runs view (listening before this handler) has just read the old
      // old address as no selection. Tell it the address it now has.
      if (redirected) window.dispatchEvent(new HashChangeEvent('hashchange'));
    };
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);

  /** Update tab and sync to URL hash. */
  const setActiveTab = (tab) => {
    setActiveTabState(tab);
    // Intentional browser navigation side effect in an event handler: sync the
    // selected tab to the URL hash so it survives reloads and back/forward.
    // eslint-disable-next-line react-hooks/immutability
    window.location.hash = `manage/${tab}`;
  };

  const currentGroup = GROUPS.find((g) => g.value === activeGroup);

  function handleGroupChange(groupValue) {
    setActiveGroup(groupValue);
    const group = GROUPS.find((g) => g.value === groupValue);
    setActiveTab(group.tabs[0].value);
  }

  /** Select a tab from the sidebar or the menu: set its group and tab together. */
  function handleSelectTab(tabValue) {
    const group = GROUPS.find((g) => g.tabs.some((t) => t.value === tabValue));
    setActiveGroup(group.value);
    setActiveTab(tabValue);
  }

  const waitlistShown = waitlistCount != null && waitlistCount > 0;
  const attentionShown = attentionCount != null && attentionCount > 0;
  const liveShown = runningCount > 0;

  /** A tab's name with its count in brackets, for the phone menu's options. */
  function tabLabelWithCount(tab) {
    if (tab.value === 'waitlist' && waitlistShown) return `${tab.label} (${waitlistCount})`;
    if (tab.value === 'locations' && attentionShown) return `${tab.label} (${attentionCount})`;
    if (tab.value === 'runs' && liveShown) return `${tab.label} (${runningCount})`;
    return tab.label;
  }

  const phoneMenu = (
    <div>
      <label htmlFor="manage-section-select" className="sr-only">Section</label>
      <select
        id="manage-section-select"
        className="field"
        value={activeTab}
        onChange={(e) => handleSelectTab(e.target.value)}
        data-testid="manage-section-select"
      >
        {GROUPS.map((group) => (
          <optgroup key={group.value} label={group.label}>
            {group.tabs.map((tab) => (
              <option key={tab.value} value={tab.value}>
                {tabLabelWithCount(tab)}
              </option>
            ))}
          </optgroup>
        ))}
      </select>
    </div>
  );

  const sidebar = (
    <nav
      aria-label="Operations sections"
      className="w-44 shrink-0 flex flex-col gap-4"
      data-testid="manage-sidebar"
    >
      {GROUPS.map((group) => (
        <div
          key={group.value}
          role="group"
          aria-labelledby={`manage-sidebar-heading-${group.value}`}
          className="flex flex-col gap-1"
          data-testid={`manage-sidebar-group-${group.value}`}
        >
          <p
            id={`manage-sidebar-heading-${group.value}`}
            className="text-[10px] font-semibold uppercase tracking-wider text-plex-text-muted px-2"
          >
            {group.label}
          </p>
          {group.tabs.map((tab) => (
            <button
              key={tab.value}
              onClick={() => handleSelectTab(tab.value)}
              aria-current={activeTab === tab.value ? 'page' : undefined}
              className={`flex items-center text-left px-2 py-1 text-sm rounded transition-colors border-l-2 ${
                activeTab === tab.value
                  ? 'text-plex-gold border-plex-gold bg-plex-gold/10 font-medium'
                  : 'text-plex-text-secondary hover:text-plex-text border-transparent'
              }`}
              data-testid={`manage-tab-${tab.value}`}
            >
              {tab.label}
              {tab.value === 'waitlist' && waitlistShown && (
                <span className={WAITLIST_BADGE_CLASSES} data-testid="waitlist-count-badge">
                  {waitlistCount}
                </span>
              )}
              {tab.value === 'locations' && attentionShown && (
                <span className={ATTENTION_BADGE_CLASSES} data-testid="locations-attention-badge">
                  {attentionCount}
                </span>
              )}
              {tab.value === 'runs' && liveShown && (
                <span className={LIVE_COUNT_CLASSES} data-testid="runs-live-count">
                  {runningCount}
                </span>
              )}
            </button>
          ))}
        </div>
      ))}
    </nav>
  );

  const tabBars = (
    <>

      {/* Group tabs */}
      <div
        className="flex flex-wrap gap-x-6 gap-y-2 border-b border-plex-border"
        data-testid="manage-group-bar"
      >
        {GROUPS.map((group) => (
          <button
            key={group.value}
            onClick={() => handleGroupChange(group.value)}
            className={`pb-2 text-sm font-semibold transition-colors border-b-2 ${
              activeGroup === group.value
                ? 'text-plex-gold border-plex-gold'
                : 'text-plex-text-secondary hover:text-plex-text border-transparent'
            }`}
            data-testid={`manage-group-${group.value}`}
          >
            {group.label}
            {group.value === 'people' && waitlistCount != null && waitlistCount > 0 && (
              <span className={WAITLIST_BADGE_CLASSES} data-testid="waitlist-group-badge">
                {waitlistCount}
              </span>
            )}
          </button>
        ))}
      </div>

      {/* Sub-tabs */}
      <div className="flex flex-wrap gap-x-5 gap-y-2" data-testid="manage-tab-bar">
        {currentGroup.tabs.map((tab) => (
          <button
            key={tab.value}
            onClick={() => setActiveTab(tab.value)}
            className={`pb-1.5 text-xs font-medium transition-colors border-b-2 ${
              activeTab === tab.value
                ? 'text-plex-text border-plex-text-secondary'
                : 'text-plex-text-muted hover:text-plex-text-secondary border-transparent'
            }`}
            data-testid={`manage-tab-${tab.value}`}
          >
            {tab.label}
            {tab.value === 'waitlist' && waitlistCount != null && waitlistCount > 0 && (
              <span className={WAITLIST_BADGE_CLASSES} data-testid="waitlist-count-badge">
                {waitlistCount}
              </span>
            )}
            {tab.value === 'locations' && attentionShown && (
              <span className={ATTENTION_BADGE_CLASSES} data-testid="locations-attention-badge">
                {attentionCount}
              </span>
            )}
            {tab.value === 'runs' && liveShown && (
              <span className={LIVE_COUNT_CLASSES} data-testid="runs-live-count">
                {runningCount}
              </span>
            )}
          </button>
        ))}
      </div>
    </>
  );

  let navigation = tabBars;
  if (isMobile) navigation = phoneMenu;
  else if (isWide) navigation = sidebar;

  return (
    <div ref={rootRef} className={isWide && !isMobile ? 'flex items-start gap-6' : 'flex flex-col gap-4'}>
      {navigation}

      {/* Tab content — keyed wrapper replays fade-in on every tab switch */}
      <div key={activeTab} className="min-w-0 flex-1 animate-popup-refresh w-full">
        {activeTab === 'users' && (
          <div className="card">
            <UserManagementView />
          </div>
        )}

        {activeTab === 'locations' && (
          <div className="card">
            <LocationManagementView onLocationsChanged={onComplete} onAttentionCountChange={handleAttentionCount} />
          </div>
        )}

        {activeTab === 'regions' && (
          <div className="card">
            <RegionManagementView />
          </div>
        )}

        {activeTab === 'areas' && (
          <div className="card">
            <AreaManagementView />
          </div>
        )}

        {activeTab === 'tides' && (
          // Phone (639px and under): the card is edge to edge, with no border, background or padding of its own.
          <div className="card max-[639px]:rounded-none max-[639px]:border-0 max-[639px]:bg-transparent max-[639px]:p-0">
            <TideManagementView />
          </div>
        )}

        {activeTab === 'waitlist' && (
          // Phone (639px and under): the card is edge to edge, with no border, background or padding of its own.
          <div className="card max-[639px]:rounded-none max-[639px]:border-0 max-[639px]:bg-transparent max-[639px]:p-0">
            <WaitlistManagementView onCountChange={handleWaitlistCount} />
          </div>
        )}

        {activeTab === 'runs' && (
          <div className="card">
            <RunsView
              activeRunId={activeRunId}
              onActiveRunChange={setActiveRunId}
              onActiveRunClear={clearActiveRun}
              onLiveCountChange={handleLiveCount}
            />
          </div>
        )}

        {activeTab === 'models' && (
          <ModelSelectionView />
        )}

        {activeTab === 'skyeval' && (
          <div className="card flex flex-col gap-4">
            <p className="text-sm font-semibold text-plex-text">Sky-Rating Calibration Eval</p>
            <SkyRatingEvalView />
          </div>
        )}

        {activeTab === 'scheduler' && (
          <div className="card flex flex-col gap-4">
            <p className="text-sm font-semibold text-plex-text">Scheduler</p>
            <SchedulerView />
          </div>
        )}

        {activeTab === 'traveldays' && (
          <div className="card flex flex-col gap-4">
            <p className="text-sm font-semibold text-plex-text">Travel Days</p>
            <TravelDaysView />
          </div>
        )}

        {activeTab === 'hottopics' && (
          <div className="card flex flex-col gap-4">
            <p className="text-sm font-semibold text-plex-text">Hot topic simulation</p>
            <HotTopicSimulation />
          </div>
        )}

        {activeTab === 'rewind' && (
          <div className="card flex flex-col gap-4">
            <p className="text-sm font-semibold text-plex-text">Rewind</p>
            <RewindView />
          </div>
        )}
      </div>
    </div>
  );
}

ManageView.propTypes = {
  onComplete: PropTypes.func.isRequired,
};
