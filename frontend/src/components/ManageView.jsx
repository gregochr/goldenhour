import React, { useState, useCallback } from 'react';
import PropTypes from 'prop-types';
import UserManagementView from './UserManagementView.jsx';
import LocationManagementView from './LocationManagementView.jsx';
import RegionManagementView from './RegionManagementView.jsx';
import JobRunsMetricsView from './JobRunsMetricsView.jsx';
import ModelSelectionView from './ModelSelectionView.jsx';
import ModelTestView from './ModelTestView.jsx';
import BriefingModelTestView from './BriefingModelTestView.jsx';
import PromptTestView from './PromptTestView.jsx';
import TideManagementView from './TideManagementView.jsx';

const GROUPS = [
  {
    value: 'data',
    label: 'Data',
    tabs: [
      { value: 'users', label: 'Users' },
      { value: 'locations', label: 'Locations' },
      { value: 'regions', label: 'Regions' },
      { value: 'tides', label: 'Tides' },
    ],
  },
  {
    value: 'operations',
    label: 'Operations',
    tabs: [
      { value: 'metrics', label: 'Job Runs' },
      { value: 'models', label: 'Run Config' },
      { value: 'modeltest', label: 'Location Model Test' },
      { value: 'briefingmodeltest', label: 'Briefing Model Test' },
      { value: 'prompttest', label: 'Prompt Test' },
    ],
  },
];

/**
 * Management screen shell — renders a two-level tab bar (group + sub-tab)
 * and routes to the active tab component.
 *
 * @param {object}   props
 * @param {function} props.onComplete - Called after any change so other views refresh.
 */
/** Resolve group and tab from a hash fragment like "manage/prompttest". */
function parseHash() {
  const hash = window.location.hash.replace('#', '');
  const parts = hash.split('/');
  if (parts[0] === 'manage' && parts[1]) {
    const tab = parts[1];
    const group = GROUPS.find((g) => g.tabs.some((t) => t.value === tab));
    if (group) return { group: group.value, tab };
  }
  return { group: 'data', tab: 'users' };
}

export default function ManageView({ onComplete }) {
  const initial = parseHash();
  const [activeGroup, setActiveGroup] = useState(initial.group);
  const [activeTab, setActiveTabState] = useState(initial.tab);
  const [activeRunId, setActiveRunId] = useState(null);
  const clearActiveRun = useCallback(() => setActiveRunId(null), []);

  /** Update tab and sync to URL hash. */
  const setActiveTab = (tab) => {
    setActiveTabState(tab);
    window.location.hash = `manage/${tab}`;
  };

  const currentGroup = GROUPS.find((g) => g.value === activeGroup);

  function handleGroupChange(groupValue) {
    setActiveGroup(groupValue);
    const group = GROUPS.find((g) => g.value === groupValue);
    setActiveTab(group.tabs[0].value);
  }

  return (
    <div className="flex flex-col gap-4">

      {/* Group tabs */}
      <div className="flex gap-6 border-b border-plex-border">
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
          </button>
        ))}
      </div>

      {/* Sub-tabs */}
      <div className="flex gap-5">
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
          </button>
        ))}
      </div>

      {/* Tab content — keyed wrapper replays fade-in on every tab switch */}
      <div key={activeTab} className="animate-popup-refresh">
        {activeTab === 'users' && (
          <div className="card">
            <UserManagementView />
          </div>
        )}

        {activeTab === 'locations' && (
          <div className="card">
            <LocationManagementView onLocationsChanged={onComplete} />
          </div>
        )}

        {activeTab === 'regions' && (
          <div className="card">
            <RegionManagementView />
          </div>
        )}

        {activeTab === 'tides' && (
          <div className="card">
            <TideManagementView />
          </div>
        )}

        {activeTab === 'metrics' && (
          <div className="card flex flex-col gap-4">
            <p className="text-sm font-semibold text-plex-text">Job Run Metrics</p>
            <JobRunsMetricsView
              activeRunId={activeRunId}
              onActiveRunChange={setActiveRunId}
              onActiveRunClear={clearActiveRun}
            />
          </div>
        )}

        {activeTab === 'models' && (
          <ModelSelectionView />
        )}

        {activeTab === 'modeltest' && (
          <div className="card flex flex-col gap-4">
            <p className="text-sm font-semibold text-plex-text">Location Model Comparison Test</p>
            <ModelTestView />
          </div>
        )}

        {activeTab === 'briefingmodeltest' && (
          <div className="card flex flex-col gap-4">
            <p className="text-sm font-semibold text-plex-text">Briefing Model Comparison Test</p>
            <BriefingModelTestView />
          </div>
        )}

        {activeTab === 'prompttest' && (
          <div className="card flex flex-col gap-4">
            <p className="text-sm font-semibold text-plex-text">Prompt Regression Test</p>
            <PromptTestView />
          </div>
        )}
      </div>
    </div>
  );
}

ManageView.propTypes = {
  onComplete: PropTypes.func.isRequired,
};
