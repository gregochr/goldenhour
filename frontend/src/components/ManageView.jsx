import React, { useState } from 'react';
import PropTypes from 'prop-types';
import UserManagementView from './UserManagementView.jsx';
import LocationManagementView from './LocationManagementView.jsx';
import RegionManagementView from './RegionManagementView.jsx';
import JobRunsMetricsView from './JobRunsMetricsView.jsx';
import ModelSelectionView from './ModelSelectionView.jsx';
import ModelTestView from './ModelTestView.jsx';

/**
 * Management screen shell — renders sub-tab bar and routes to the active tab component.
 *
 * @param {object}   props
 * @param {function} props.onComplete - Called after any change so other views refresh.
 */
export default function ManageView({ onComplete }) {
  const [manageTab, setManageTab] = useState('users');

  return (
    <div className="flex flex-col gap-5">

      {/* Sub-tabs */}
      <div className="flex gap-6 border-b border-plex-border">
        {[
          { value: 'users', label: 'Users' },
          { value: 'locations', label: 'Locations' },
          { value: 'regions', label: 'Regions' },
          { value: 'metrics', label: 'Job Runs' },
          { value: 'models', label: 'Run Config' },
          { value: 'modeltest', label: 'Model Test' },
        ].map((tab) => (
          <button
            key={tab.value}
            onClick={() => setManageTab(tab.value)}
            className={`pb-2 text-sm font-medium transition-colors border-b-2 ${
              manageTab === tab.value
                ? 'text-plex-gold border-plex-gold'
                : 'text-plex-text-secondary hover:text-plex-text border-transparent'
            }`}
            data-testid={`manage-tab-${tab.value}`}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {/* Tab content */}
      {manageTab === 'users' && (
        <div className="card">
          <UserManagementView />
        </div>
      )}

      {manageTab === 'locations' && (
        <div className="card">
          <LocationManagementView onLocationsChanged={onComplete} />
        </div>
      )}

      {manageTab === 'regions' && (
        <div className="card">
          <RegionManagementView />
        </div>
      )}

      {manageTab === 'metrics' && (
        <div className="card flex flex-col gap-4">
          <p className="text-sm font-semibold text-plex-text">Job Run Metrics</p>
          <JobRunsMetricsView />
        </div>
      )}

      {manageTab === 'models' && (
        <ModelSelectionView />
      )}

      {manageTab === 'modeltest' && (
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
