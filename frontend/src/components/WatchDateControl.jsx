import React from 'react';
import PropTypes from 'prop-types';
import { useFavourites } from '../context/FavouritesContext.jsx';
import FavouriteToggle, {
  FavouriteUpsell, useFavouriteUpsell, WATCH_UPSELL_BODY, WATCH_UPSELL_HEADING,
} from './shared/FavouriteToggle.jsx';
import { formatArrivalDate } from '../utils/comingUpFeed.js';
import { watchKeyOf, watchKeysMatching } from '../utils/watchedDates.js';

/**
 * "Watch this date" under an open Further ahead entry (one-screen-plan.md D16): a real {@code <button>} whose words carry the
 * state ({@code Watch this date} / {@code Watching}, no {@code aria-pressed}), a sibling of the entry's own card and Open / Close button, never inside either (a single-action card is itself a
 * button).
 *
 * <p>Drawn only once the reader's favourites are known: a Pro or Admin reader whose list has landed gets the toggle, a
 * Lite reader gets the same control with the {@code Pro} pill, which opens the date-worded upsell under it. Anyone
 * else (no provider, loading, failed) and any entry that cannot be watched (a warning) gets nothing, because a toggle
 * with a wrong name would be a false statement.
 *
 * @param {object} props
 * @param {object} props.entry a view entry from {@code buildEntryView}
 * @param {string[]} [props.watchedKeys] the stored watches bound to this entry by the host's one-home rule
 *        ({@code watchedKeysFor}); absent, the entry's own matches ({@code watchKeysMatching})
 * @param {function(): void} [props.onWatchChange] called once, synchronously, when a press actually changed the
 *        reader's dates (a ready Pro or Admin reader; never a Lite door)
 */
export default function WatchDateControl({ entry, watchedKeys = undefined, onWatchChange = undefined }) {
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
        matchedKeys={watchedKeys ?? watchKeysMatching(favourites.dates, entry)}
        onChange={onWatchChange}
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
  watchedKeys: PropTypes.arrayOf(PropTypes.string),
  onWatchChange: PropTypes.func,
};
