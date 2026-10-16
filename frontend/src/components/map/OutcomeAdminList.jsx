import { useId } from 'react';
import PropTypes from 'prop-types';
import {
  ADMIN_EMPTY, ADMIN_LOAD_FAILED, ADMIN_LOADING, ADMIN_NOT_SEEN, adminFooter, adminHeader,
  adminStamp, spokenRating,
} from './outcomeRatingCopy.js';

/**
 * The caller's own row first, then the rest in the order the server sent (newest first). Stable, and
 * a copy: the rows belong to the hook.
 *
 * @param {Array<{mine: boolean}>} rows
 * @returns {Array}
 */
export function orderAdminRows(rows) {
  return [...rows.filter((r) => r.mine), ...rows.filter((r) => !r.mine)];
}

/** A star count the glyph runs can always draw: a whole number 1..5 (the server validates, a render must not throw). */
const stars = (rating) => Math.min(5, Math.max(1, Math.round(rating)));

/**
 * "All ratings · {n}" — the Admin's collapsible list of every reader's rating of one slot, under the
 * reader's own block (`docs/design/outcome-rating/README.md` §3 Admin row;
 * `docs/engineering/outcome-rating-plan.md` §2 #7).
 *
 * <p>Stateless: whether it is open, the rows and whether they are loading belong to `MapCallout`,
 * which places the card from its own dependency list and cannot see inside this (plan §1 #3), and the
 * fetch is `hooks/useSlotRatings.js`'s. The panel is always rendered and {@code hidden} while collapsed,
 * so the toggle's {@code aria-controls} always names an element; the one status line inside it is
 * mounted with the panel, so the loading and failure sentences arrive in a region that already exists.
 *
 * <p>Each rating is a decorative glyph run with a visually hidden "3 out of 5" beside it (a row never
 * says its star count twice), or "Not seen". The caller's row leads; the server's order is kept below it.
 *
 * @param {object} props
 * @param {'SUNRISE'|'SUNSET'} props.eventType the window's event, for the footer's wording
 * @param {object} props.admin
 * @param {boolean} props.admin.open
 * @param {function(): void} props.admin.onToggle
 * @param {boolean} props.admin.loading the first fetch is out
 * @param {boolean} [props.admin.failed] the latest fetch failed
 * @param {?Array<{username: string, rating: ?number, updatedAt: ?string, mine: boolean}>} props.admin.rows
 *        null until a fetch has answered
 * @param {?string} props.admin.eventTime the event instant the ratings are for
 * @param {?string} props.admin.forecastRunAt the run they were recorded against, or null
 */
export default function OutcomeAdminList({ eventType, admin }) {
  const {
    open, onToggle, loading, failed = false, rows, eventTime, forecastRunAt,
  } = admin;
  const panelId = useId();
  const ordered = rows ? orderAdminRows(rows) : null;
  // Said only while the box is open: collapsed, the sentence would sit under a closed header.
  let state = null;
  if (open && loading) state = ADMIN_LOADING;
  else if (open && failed) state = ADMIN_LOAD_FAILED;

  return (
    <div className="wf-adm" data-testid="outcome-admin-list" data-open={open ? '' : undefined}>
      <button
        type="button"
        className="wf-adm-h"
        data-testid="outcome-admin-toggle"
        aria-expanded={open}
        aria-controls={panelId}
        onClick={onToggle}
      >
        <span>{adminHeader(rows ? rows.length : null)}</span>
        <span className="wf-adm-caret" aria-hidden="true">{open ? '▴' : '▾'}</span>
      </button>
      {/* Outside the `hidden` panel on purpose: a live region inside a collapsed panel is not in the
          accessibility tree, and one that appears already holding its text is announced by almost nobody.
          Here it exists (empty, 0 high) before the box opens, so the text that arrives is announced. */}
      <div className="wf-adm-state" role="status" data-testid="outcome-admin-state">{state}</div>
      <div id={panelId} hidden={!open} aria-busy={loading ? 'true' : undefined} data-testid="outcome-admin-panel">
        {ordered && (
          <>
            {ordered.length === 0 ? (
              <p className="wf-adm-r wf-adm-empty" data-testid="outcome-admin-empty">
                <span className="wf-adm-nsv">{ADMIN_EMPTY}</span>
              </p>
            ) : (
              <ul className="wf-adm-rows">
                {ordered.map((row) => (
                  <li
                    key={row.username}
                    className="wf-adm-r"
                    data-testid="outcome-admin-row"
                    data-mine={row.mine ? '' : undefined}
                  >
                    <span className="wf-adm-u">{row.username}</span>
                    {row.rating == null ? (
                      <span className="wf-adm-nsv">{ADMIN_NOT_SEEN}</span>
                    ) : (
                      <span className="wf-adm-g">
                        <span aria-hidden="true">
                          <span className="wf-rate-on">{'★'.repeat(stars(row.rating))}</span>
                          <span className="wf-rate-off">{'☆'.repeat(5 - stars(row.rating))}</span>
                        </span>
                        <span className="sr-only">{spokenRating(stars(row.rating))}</span>
                      </span>
                    )}
                    <span className="wf-adm-t">{adminStamp(row.updatedAt)}</span>
                  </li>
                ))}
              </ul>
            )}
            <div className="wf-adm-run" data-testid="outcome-admin-footer">
              {adminFooter(forecastRunAt, eventTime, eventType)}
            </div>
          </>
        )}
      </div>
    </div>
  );
}

OutcomeAdminList.propTypes = {
  eventType: PropTypes.string.isRequired,
  admin: PropTypes.shape({
    open: PropTypes.bool.isRequired,
    onToggle: PropTypes.func.isRequired,
    loading: PropTypes.bool,
    failed: PropTypes.bool,
    rows: PropTypes.arrayOf(PropTypes.shape({
      username: PropTypes.string.isRequired,
      rating: PropTypes.number,
      updatedAt: PropTypes.string,
      mine: PropTypes.bool,
    })),
    eventTime: PropTypes.string,
    forecastRunAt: PropTypes.string,
  }).isRequired,
};
