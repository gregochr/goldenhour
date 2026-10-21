import { useId, useLayoutEffect, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import ProPill from '../shared/ProPill.jsx';
import OutcomeAdminList from './OutcomeAdminList.jsx';
import {
  CHANGE, LITE_LOCKED, LITE_PROMPT, NOT_SEEN_BUTTON, NOT_SEEN_DONE, QUESTION, RATED_PREFIX,
  REPLACE_NOT_SEEN, SAVE_FAILED, SEEN_GROUP_LABEL, SEEN_TAGS, TAG_SAVE_FAILED, ratedNumeral, replaceMeta,
  spokenRating, starLabel, unratedMeta,
} from './outcomeRatingCopy.js';

const STARS = [1, 2, 3, 4, 5];

/**
 * Which of the block's three layouts is showing — exported so the callout, which owns `changing`,
 * can name the layout in its repaint dependencies (the card's height changes between them).
 *
 * <ul>
 *   <li>{@code 'lite'} — a LITE reader: the locked grid and the Pro line, whatever else is true.</li>
 *   <li>{@code 'done'} — a record exists, the reader is not changing it, nothing is in flight and
 *       nothing failed: "You rated ★★★☆☆ 3★" and Change.</li>
 *   <li>{@code 'grid'} — everything else: unrated, saving (the grid stays, dimmed), changing, and a
 *       failed save (the grid stays, with the line).</li>
 * </ul>
 *
 * @param {object} state
 * @param {?string} state.userRole
 * @param {?{rating: ?number}} state.mine
 * @param {boolean} state.changing
 * @param {boolean} state.saving
 * @param {boolean} state.saveError
 * @returns {'lite'|'done'|'grid'}
 */
export function ratingView({
  userRole, mine, changing, saving, saveError,
}) {
  if (userRole === 'LITE_USER') return 'lite';
  if (mine && !changing && !saving && !saveError) return 'done';
  return 'grid';
}

/**
 * Whether the "what did you see" chip row is drawn — exported for the same reason {@link ratingView}
 * is: the row changes the card's height, and the callout (which places itself from its own dependency
 * list) must be able to name it.
 *
 * <ul>
 *   <li>{@code 'done'} — only for a record that HAS a star ({@code mine.rating != null}): a "didn't see
 *       it" record carries NOT_SEEN on its own and has nothing to tag.</li>
 *   <li>{@code 'grid'} — only while changing an existing star (the row stays where it was in the done
 *       layout, so Change does not move it); never for the first rating, whose saving grid must not
 *       grow a row mid-flight.</li>
 *   <li>{@code 'lite'} — always, dimmed: tagging is part of Pro, and the lock is shown, not hidden.</li>
 * </ul>
 *
 * @param {object} state the same fields {@link ratingView} reads
 * @returns {boolean}
 */
export function showsSeenChips(state) {
  const view = ratingView(state);
  if (view === 'lite') return true;
  if (state.mine?.rating == null) return false;
  return view === 'done' || (view === 'grid' && Boolean(state.changing));
}

/**
 * The chip row: four toggle buttons (Colour · Mist · Inversion · Flat) in a labelled group, the
 * current tag {@code aria-pressed}. Tapping a chip sets it; tapping the pressed one clears it. A busy
 * or locked row is {@code aria-disabled}, never {@code disabled} (a disabled control that held focus
 * drops it to the body), and a locked press answers in words through {@code onLocked}.
 */
function SeenChips({
  current, locked, busy, onPick,
}) {
  const captionId = useId();
  return (
    <div
      className={`wf-seen${locked ? ' is-locked' : ''}${busy ? ' is-saving' : ''}`}
      role="group"
      aria-labelledby={captionId}
      aria-busy={busy ? 'true' : undefined}
      data-testid="outcome-rating-seen"
    >
      <span className="wf-seen-q" id={captionId}>{SEEN_GROUP_LABEL}</span>
      {SEEN_TAGS.map(({ tag, label }) => (
        <button
          key={tag}
          type="button"
          className="wf-seen-chip"
          data-testid="outcome-rating-seen-chip"
          data-tag={tag}
          aria-pressed={locked ? false : current === tag}
          aria-disabled={locked || busy ? 'true' : undefined}
          onClick={() => onPick(tag)}
        >
          {label}
        </button>
      ))}
    </div>
  );
}

SeenChips.propTypes = {
  current: PropTypes.string,
  locked: PropTypes.bool.isRequired,
  busy: PropTypes.bool.isRequired,
  onPick: PropTypes.func.isRequired,
};

/** `★★★☆☆` as two runs, so the filled and the empty carry their own colours. Decorative. */
function Glyphs({ rating }) {
  return (
    <span className="wf-rate-glyphs" aria-hidden="true">
      <span className="wf-rate-on">{'★'.repeat(rating)}</span>
      <span className="wf-rate-off">{'☆'.repeat(5 - rating)}</span>
    </span>
  );
}

Glyphs.propTypes = { rating: PropTypes.number.isRequired };

/**
 * "How was it?" — the one-tap rating of a sunrise or sunset that has already happened
 * (`docs/design/outcome-rating/README.md` §3, `docs/engineering/outcome-rating-plan.md` §2 #5),
 * mounted by `MapCallout` inside its scrolling body on a past window.
 *
 * <h2>Stateless about the record, owned-state about the layout</h2>
 * <p>The record ({@code mine}, {@code saving}, {@code saveError}) and the save ({@code onRate}) come
 * from `hooks/useOutcomeRatings.js` through the callout. {@code changing} is the callout's, not this
 * component's: the block swaps a 44px star grid for a 40px "You rated" row, and the callout places
 * itself from a dependency list of ITS OWN props and state, so a layout switch it cannot see would
 * leave the card anchored for the other height (plan §1 #3). The callout also mounts this with a
 * {@code key} of its selection (location and window), so a new selection starts a fresh block — no
 * leftover focus intent, no Lite "tapped" line — and unmounting is what clears a leftover save error.
 *
 * <h2>One status line, one spoken phrase</h2>
 * <p>State is voiced once. The failed-save sentence is the block's only live region
 * ({@code role="status"}), rendered empty so it exists BEFORE its content does (a region mounted with
 * its text announces nothing); the Lite line is the same kind of region. A rating is spoken by one
 * visually hidden "3 out of 5" beside a decorative numeral and glyphs, and the Change button is
 * described by that phrase so a reader landing on it hears what it changes. Locked and saving
 * buttons are {@code aria-disabled}, never {@code disabled}: a disabled control that held focus drops
 * it to the body.
 *
 * <h2>Focus</h2>
 * <p>A press that unmounts its own button must hand focus to a survivor — the rule the Map tab has had
 * to relearn five times (map-landing-plan.md §3 L4–L6). A star or "Didn't see it" press leaves its
 * button focused while the save is in flight (it is only {@code aria-disabled}) and focus moves to
 * Change when the done row replaces the grid; Change moves focus to the current choice (star 1 when
 * there is none). Both go through a layout effect keyed on the layout, which runs before paint, and
 * only when focus is still inside the block or has already fallen to the body — a reader who has
 * moved on keeps their place. Escape is left to the pane (it deselects the callout).
 *
 * @param {object} props
 * @param {{eventType: string, dayLabel: ?string, time: ?string}} props.event the past window
 * @param {?{rating: ?number, updatedAt: ?string}} props.mine the reader's record, or null (unrated);
 *        {@code rating: null} is "didn't see it"
 * @param {?string} props.userRole `'ADMIN' | 'PRO_USER' | 'LITE_USER'` — named so it is never read as an ARIA role
 * @param {boolean} props.saving a save for this slot is in flight
 * @param {boolean} props.saveError the last save for this slot failed
 * @param {boolean} props.changing the reader pressed Change (owned by `MapCallout`)
 * @param {function(boolean): void} props.onChangingChange
 * @param {function(?number): Promise<boolean>} props.onRate 1..5, or null for "didn't see it";
 *        resolves true when the save landed
 * @param {function(?string): Promise<boolean>} [props.onTag] saves the "what did you see" tag
 *        ({@code COLOUR|MIST|INVERSION|FLAT}, or null to clear) against the current star
 * @param {boolean} [props.tagSaving] a tag save for this slot is in flight
 * @param {boolean} [props.tagSaveError] the last tag save for this slot failed
 * @param {function(): void} [props.onClearError] clears this slot's save error — pressed Change, and on unmount
 * @param {?object} [props.admin] the Admin's "All ratings" box (O5a, plan §2 #7), drawn under the
 *        reader's own block in either layout; null for every other role, which never mounts it. Its
 *        state ({@code open}, the rows, {@code loading}) is the callout's, for the same reason
 *        {@code changing} is: the card cannot place itself from state it cannot see. See
 *        {@code OutcomeAdminList} for the shape.
 */
export default function OutcomeRatingBlock({
  event, mine = null, userRole = null, saving = false, saveError = false, changing = false,
  onChangingChange, onRate, onClearError = null, admin = null, onTag = null, tagSaving = false,
  tagSaveError = false,
}) {
  const view = ratingView({
    userRole, mine, changing, saving, saveError,
  });
  const doneId = useId();
  const rootRef = useRef(null);
  const changeRef = useRef(null);
  const starRefs = useRef([]);
  const notSeenRef = useRef(null);
  const aliveRef = useRef(true);
  /** Where focus should go once the layout has changed: 'change' (after a save) or 'choice'. */
  const pendingFocusRef = useRef(null);
  const clearErrorRef = useRef(onClearError);
  const [tapped, setTapped] = useState(false);

  useLayoutEffect(() => {
    clearErrorRef.current = onClearError;
  }, [onClearError]);

  // Unmount is a re-selection (the callout keys this block on it) or the callout closing: either way a
  // stale "Not saved" must not be waiting the next time this slot is shown.
  useLayoutEffect(() => {
    aliveRef.current = true;
    return () => {
      aliveRef.current = false;
      clearErrorRef.current?.();
    };
  }, []);

  useLayoutEffect(() => {
    const target = pendingFocusRef.current;
    if (!target) return;
    const wanted = (target === 'change' && view === 'done') || (target === 'choice' && view === 'grid');
    if (!wanted) return;
    pendingFocusRef.current = null;
    const active = document.activeElement;
    const free = !active || active === document.body || rootRef.current?.contains(active);
    if (!free) return;
    if (target === 'change') {
      changeRef.current?.focus();
    } else if (mine && mine.rating == null) {
      notSeenRef.current?.focus();
    } else {
      (starRefs.current[(mine?.rating ?? 1) - 1] ?? starRefs.current[0])?.focus();
    }
  }, [view, mine]);

  const press = async (value) => {
    if (saving) return;
    pendingFocusRef.current = 'change';
    const saved = await onRate(value);
    // A failed save keeps focus on the star that was pressed; leaving the intent standing would let a
    // later read that turns this slot into a record yank focus to Change.
    if (!saved) pendingFocusRef.current = null;
    if (saved && aliveRef.current) onChangingChange(false);
  };
  const pressChange = () => {
    pendingFocusRef.current = 'choice';
    onClearError?.();
    onChangingChange(true);
  };

  const kindAttr = event.eventType === 'SUNRISE' ? 'sunrise' : 'sunset';
  const withChips = showsSeenChips({
    userRole, mine, changing, saving, saveError,
  });
  /** A chip press: a locked one answers in words, a live one saves (pressing the held tag clears it). */
  const pressTag = (tag) => {
    if (view === 'lite') { setTapped(true); return; }
    if (saving || tagSaving || !onTag) return;
    onTag(mine?.seen === tag ? null : tag);
  };
  const chips = withChips ? (
    <SeenChips
      current={mine?.seen ?? null}
      locked={view === 'lite'}
      busy={saving || tagSaving}
      onPick={pressTag}
    />
  ) : null;
  // ADMIN only: the role is checked HERE as well as by the callout that builds the prop, so a caller
  // that passed `admin` for another role would still draw nothing; and never beside the Lite lock.
  // The fixed `key` is load-bearing: the list sits at a different child index in the 'done' and 'grid'
  // layouts, and without it React would unmount and remount it when a save lands, taking focus off a
  // toggle the admin was using.
  const adminList = admin && userRole === 'ADMIN' && view !== 'lite'
    ? <OutcomeAdminList key="admin" eventType={event.eventType} admin={admin} /> : null;

  if (view === 'done') {
    const rated = mine.rating != null;
    return (
      <div
        ref={rootRef}
        className="wf-rate"
        data-testid="outcome-rating-block"
        data-state={rated ? 'rated' : 'not-seen'}
        data-kind={kindAttr}
      >
        <div className="wf-rate-done">
          <span className="wf-rate-you" id={doneId}>
            {rated ? (
              <>
                {RATED_PREFIX}
                {' '}
                <Glyphs rating={mine.rating} />
                {' '}
                <b className="wf-rate-numeral" aria-hidden="true">{ratedNumeral(mine.rating)}</b>
                <span className="sr-only">{spokenRating(mine.rating)}</span>
              </>
            ) : NOT_SEEN_DONE}
          </span>
          <button
            ref={changeRef}
            type="button"
            className="wf-rate-change"
            data-testid="outcome-rating-change"
            aria-describedby={doneId}
            onClick={pressChange}
          >
            {CHANGE}
          </button>
        </div>
        {chips}
        {chips && (
          <div className="wf-rate-status" role="status" data-testid="outcome-rating-tag-status">
            {tagSaveError ? (
              <>
                <i aria-hidden="true" />
                {TAG_SAVE_FAILED}
              </>
            ) : null}
          </div>
        )}
        {adminList}
      </div>
    );
  }

  const locked = view === 'lite';
  const current = mine ? (mine.rating ?? 'ns') : null;
  const showSelected = !locked && (changing || saving) && current != null;
  const replacing = !locked && changing && mine;
  let meta = unratedMeta(event.eventType, event.dayLabel, event.time);
  if (replacing) meta = mine.rating == null ? REPLACE_NOT_SEEN : replaceMeta(mine.rating);
  let state = 'unrated';
  if (locked) state = 'lite';
  else if (saving) state = 'saving';
  else if (replacing) state = 'changing';

  /** A locked press answers with the sentence and calls nothing; a live one saves. */
  const onPress = (value) => {
    if (locked) { setTapped(true); return; }
    press(value);
  };
  const dimmed = locked || saving;

  return (
    <div
      ref={rootRef}
      className="wf-rate"
      data-testid="outcome-rating-block"
      data-state={state}
      data-kind={kindAttr}
    >
      <div className="wf-rate-q">
        <b>{QUESTION}</b>
        {locked ? <ProPill /> : <span className="wf-rate-meta" data-testid="outcome-rating-meta">{meta}</span>}
      </div>
      <div className={`wf-rate-grid${locked ? ' is-locked' : ''}${saving ? ' is-saving' : ''}`}>
        {STARS.map((n, i) => (
          <button
            key={n}
            ref={(node) => { starRefs.current[i] = node; }}
            type="button"
            className="wf-rate-star"
            data-testid="outcome-rating-star"
            data-selected={showSelected && current === n ? '' : undefined}
            aria-label={starLabel(event.eventType, n)}
            aria-pressed={replacing ? current === n : undefined}
            aria-disabled={dimmed ? 'true' : undefined}
            onClick={() => onPress(n)}
          >
            <span className="wf-rate-glyph" aria-hidden="true">★</span>
            <span className="wf-rate-n" aria-hidden="true">{n}</span>
          </button>
        ))}
      </div>
      <button
        ref={notSeenRef}
        type="button"
        className={`wf-rate-ns${saving ? ' is-saving' : ''}`}
        data-testid="outcome-rating-not-seen"
        data-selected={showSelected && current === 'ns' ? '' : undefined}
        aria-pressed={replacing ? current === 'ns' : undefined}
        aria-disabled={dimmed ? 'true' : undefined}
        onClick={() => onPress(null)}
      >
        {NOT_SEEN_BUTTON}
      </button>
      {chips}
      {locked ? (
        <p className="wf-rate-note" role="status" data-testid="outcome-rating-note">
          {tapped ? LITE_LOCKED : LITE_PROMPT}
        </p>
      ) : (
        <div className="wf-rate-status" role="status" data-testid="outcome-rating-status">
          {saveError || tagSaveError ? (
            <>
              <i aria-hidden="true" />
              {saveError ? SAVE_FAILED : TAG_SAVE_FAILED}
            </>
          ) : null}
        </div>
      )}
      {adminList}
    </div>
  );
}

OutcomeRatingBlock.propTypes = {
  event: PropTypes.shape({
    eventType: PropTypes.string.isRequired,
    dayLabel: PropTypes.string,
    time: PropTypes.string,
  }).isRequired,
  mine: PropTypes.shape({
    rating: PropTypes.number, updatedAt: PropTypes.string, seen: PropTypes.string,
  }),
  userRole: PropTypes.string,
  saving: PropTypes.bool,
  saveError: PropTypes.bool,
  onTag: PropTypes.func,
  tagSaving: PropTypes.bool,
  tagSaveError: PropTypes.bool,
  changing: PropTypes.bool,
  onChangingChange: PropTypes.func.isRequired,
  onRate: PropTypes.func.isRequired,
  onClearError: PropTypes.func,
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
      seen: PropTypes.string,
    })),
    eventTime: PropTypes.string,
    forecastRunAt: PropTypes.string,
  }),
};
