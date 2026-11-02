import { useRef } from 'react';
import PropTypes from 'prop-types';

/** The three views, in order: the hash's key and the tab's words. */
const TABS = [
  { key: 'day', label: 'Day' },
  { key: 'cost', label: 'Cost trends' },
  { key: 'timing', label: 'Timing' },
];

/** The id of a tab and of the panel it controls, shared with {@link RunsView}'s panel wrapper. */
export const tabId = (key) => `runs-view-tab-${key}`;
export const PANEL_ID = 'runs-view-panel';

/**
 * The view tabs under the Runs header (Day, Cost trends, Timing), underline style: the active one in ink with a 2px
 * ink underline. The Day tab carries a small word for the day it shows ("Today", "Yesterday", "Mon 26 Oct").
 * A tablist with a roving tab stop: Left and Right (and Home and End) move between the tabs and select them,
 * and say so with {@code preventDefault}, so the Day view's own Left/Right stepping never also fires.
 *
 * @param {object} props
 * @param {string} props.view the active view's key
 * @param {(view: string) => void} props.onChange called with the chosen view's key
 * @param {string} [props.dayWord] the small word beside Day
 */
export default function RunsViewTabs({ view, onChange, dayWord = '' }) {
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
    <div
      role="tablist"
      aria-label="Runs views"
      className="flex gap-5 border-b border-plex-border"
      data-testid="runs-view-tabs"
    >
      {TABS.map((tab) => {
        const on = tab.key === view;
        return (
          <button
            key={tab.key}
            ref={(node) => { refs.current[tab.key] = node; }}
            id={tabId(tab.key)}
            type="button"
            role="tab"
            aria-selected={on}
            aria-controls={PANEL_ID}
            tabIndex={on ? 0 : -1}
            onClick={() => onChange(tab.key)}
            onKeyDown={handleKeyDown}
            className={`-mb-px cursor-pointer border-b-2 pb-2 text-sm font-semibold transition-colors focus-visible:outline-2 focus-visible:outline-plex-gold max-[639px]:min-h-11 ${
              on ? 'border-plex-text text-plex-text' : 'border-transparent text-plex-text-secondary hover:text-plex-text'
            }`}
            data-testid={`runs-view-tab-${tab.key}`}
          >
            {tab.label}
            {tab.key === 'day' && dayWord !== '' && (
              <small className="ml-2 text-[11px] font-normal text-plex-text-muted" data-testid="runs-view-tab-day-word">
                {dayWord}
              </small>
            )}
          </button>
        );
      })}
    </div>
  );
}

RunsViewTabs.propTypes = {
  view: PropTypes.oneOf(['day', 'cost', 'timing']).isRequired,
  onChange: PropTypes.func.isRequired,
  dayWord: PropTypes.string,
};
