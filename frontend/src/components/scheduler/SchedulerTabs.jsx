import { useRef } from 'react';
import PropTypes from 'prop-types';

/** The two tabs, in order: the query's key and the tab's words. */
const TABS = [
  { key: 'day', label: "Day's jobs" },
  { key: 'all', label: 'All jobs' },
];

/** The id of a tab and of the panel it controls, shared with {@link SchedulerView}'s panel wrapper. */
export const schedulerTabId = (key) => `scheduler-tab-${key}`;
export const SCHEDULER_PANEL_ID = 'scheduler-tab-panel';

/**
 * The tabs under the Scheduler header (Day's jobs, All jobs), underline style like {@code RunsViewTabs}. Each carries
 * a small word: "Today" beside Day's jobs, the job total beside All jobs. A tablist with a roving tab stop: Left and
 * Right (and Home and End) move between the tabs and select them.
 *
 * @param {object} props
 * @param {string} props.view the active tab's key
 * @param {(view: string) => void} props.onChange called with the chosen tab's key
 * @param {number|null} [props.total] the served job total, beside All jobs
 */
export default function SchedulerTabs({ view, onChange, total = null }) {
  const refs = useRef({});

  function handleKeyDown(event) {
    if (event.ctrlKey || event.metaKey || event.altKey) return;
    const index = TABS.findIndex((tab) => tab.key === view);
    let next;
    if (event.key === 'ArrowRight') next = (index + 1) % TABS.length;
    else if (event.key === 'ArrowLeft') next = (index + TABS.length - 1) % TABS.length;
    else if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = TABS.length - 1;
    else return;
    event.preventDefault();
    onChange(TABS[next].key);
    refs.current[TABS[next].key]?.focus();
  }

  return (
    <div role="tablist" aria-label="Scheduler views" className="flex gap-5 border-b border-plex-border" data-testid="scheduler-tabs">
      {TABS.map((tab) => {
        const on = tab.key === view;
        const word = tab.key === 'day' ? 'Today' : (total ?? '');
        return (
          <button
            key={tab.key}
            ref={(node) => { refs.current[tab.key] = node; }}
            id={schedulerTabId(tab.key)}
            type="button"
            role="tab"
            aria-selected={on}
            aria-controls={SCHEDULER_PANEL_ID}
            tabIndex={on ? 0 : -1}
            onClick={() => onChange(tab.key)}
            onKeyDown={handleKeyDown}
            className={`-mb-px cursor-pointer border-b-2 pb-2 text-sm font-semibold transition-colors focus-visible:outline-2 focus-visible:outline-plex-gold max-[639px]:min-h-11 ${
              on ? 'border-plex-text text-plex-text' : 'border-transparent text-plex-text-secondary hover:text-plex-text'
            }`}
            data-testid={`scheduler-tab-${tab.key}`}
          >
            {tab.label}
            {word !== '' && (
              <small className="ml-2 text-[11px] font-normal text-plex-text-muted" data-testid={`scheduler-tab-${tab.key}-word`}>
                {word}
              </small>
            )}
          </button>
        );
      })}
    </div>
  );
}

SchedulerTabs.propTypes = {
  view: PropTypes.oneOf(['day', 'all']).isRequired,
  onChange: PropTypes.func.isRequired,
  total: PropTypes.number,
};
