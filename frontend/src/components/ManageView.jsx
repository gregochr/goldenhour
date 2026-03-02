import React, { useState } from 'react';
import PropTypes from 'prop-types';
import UserManagementView from './UserManagementView.jsx';
import LocationManagementView from './LocationManagementView.jsx';
import RegionManagementView from './RegionManagementView.jsx';
import JobRunsMetricsView from './JobRunsMetricsView.jsx';
import ModelSelectionView from './ModelSelectionView.jsx';
import ModelTestView from './ModelTestView.jsx';

const GROUPS = [
  {
    value: 'data',
    label: 'Data',
    tabs: [
      { value: 'users', label: 'Users' },
      { value: 'locations', label: 'Locations' },
      { value: 'regions', label: 'Regions' },
    ],
  },
  {
    value: 'operations',
    label: 'Operations',
    tabs: [
      { value: 'metrics', label: 'Job Runs' },
      { value: 'models', label: 'Run Config' },
      { value: 'modeltest', label: 'Model Test' },
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
export default function ManageView({ onComplete }) {
  const [activeGroup, setActiveGroup] = useState('data');
  const [activeTab, setActiveTab] = useState('users');

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

      {/* Tab content */}
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

      {activeTab === 'metrics' && (
        <div className="card flex flex-col gap-4">
          <p className="text-sm font-semibold text-plex-text">Job Run Metrics</p>
          <JobRunsMetricsView />
        </div>
      )}

      {activeTab === 'models' && (
        <ModelSelectionView />
      )}

      {activeTab === 'modeltest' && (
        <div className="card flex flex-col gap-4">
          <p className="text-sm font-semibold text-plex-text">Model Comparison Test</p>
          <ModelTestView />
        </div>
      )}
    </div>
  );
}

ManageView.propTypes = {
  onComplete: PropTypes.func.isRequired,
};
