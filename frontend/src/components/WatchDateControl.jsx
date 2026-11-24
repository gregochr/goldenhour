import React from 'react';
import PropTypes from 'prop-types';
import { useFavourites } from '../context/FavouritesContext.jsx';
import FavouriteToggle, {
  FavouriteUpsell, useFavouriteUpsell, WATCH_UPSELL_BODY, WATCH_UPSELL_HEADING,
} from './shared/FavouriteToggle.jsx';
import { formatArrivalDate } from '../utils/comingUpFeed.js';
import { watchKeyOf, watchKeysMatching } from '../utils/watchedDates.js';

/**
 * "Watch this date" under an open Further ahead entry (one-screen-plan.md D16): a real {@code <button aria-pressed>}, a
 * sibling of the entry's own card and Open / Close button, never inside either (a single-action card is itself a
 * button).
 *
 * <p>Drawn only once the reader's favourites are known: a Pro or Admin reader whose list has landed gets the toggle, a
 * Lite reader gets the same control with the {@code Pro} pill, which opens the date-worded upsell under it. Anyone
 * else (no provider, loading, failed) and any entry that cannot be watched (a warning) gets nothing, because a toggle
 * with a wrong {@code aria-pressed} would be a false statement.
 *
 * @param {object} props
 * @param {object} props.entry a view entry from {@code buildEntryView}
 */
export default function WatchDateControl({ entry }) {
  const favourites = useFavourites();
  const upsell = useFavouriteUpsell();
  const key = watchKeyOf(entry);
  if (key == null || (favourites.status !== 'ready' && favourites.status !== 'locked')) return null;
  return (
    <div className="wf-cu-watch" data-testid="coming-up-watch">
      <FavouriteToggle
        kind="date"
        id={key}
        name={`${entry.title} · ${formatArrivalDate(entry.startDate)}`}
        upsell={upsell}
        testId="coming-up-watch-toggle"
        matchedKeys={watchKeysMatching(favourites.dates, entry)}
      />
      <FavouriteUpsell
        upsell={upsell}
        testIdPrefix="coming-up-watch-upsell"
        heading={WATCH_UPSELL_HEADING}
        body={WATCH_UPSELL_BODY}
      />
    </div>
  );
}

WatchDateControl.propTypes = {
  entry: PropTypes.shape({
    title: PropTypes.string.isRequired,
    type: PropTypes.string,
    startDate: PropTypes.string,
  }).isRequired,
};
