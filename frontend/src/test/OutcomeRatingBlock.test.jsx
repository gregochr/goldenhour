/**
 * `components/map/OutcomeRatingBlock.jsx` — "How was it?" (docs/design/outcome-rating/README.md §3,
 * outcome-rating-plan.md §2 #5). One test per row of the spec's states table, then the transitions
 * between them, the error line's lifetime, the Lite lock, the focus handoffs and the accessibility
 * contract (one status line, one spoken phrase, `aria-pressed` on the current choice only while
 * changing, `aria-disabled` and never `disabled`).
 *
 * The block is stateless about the record, so these tests drive it through a harness that plays the
 * part of `MapCallout` plus `useOutcomeRatings`: it owns `changing` (the callout's state) and the
 * record, and settles each save by hand so the Saving and Save-failed states can be observed.
 */
import React, { useEffect, useRef, useState } from 'react';
import { describe, it, expect, vi } from 'vitest';
import { act, fireEvent, render, screen, within } from '@testing-library/react';

import OutcomeRatingBlock, { ratingView } from '../components/map/OutcomeRatingBlock.jsx';
import {
  unratedMeta, replaceMeta, starLabel, spokenRating, ratedNumeral,
} from '../components/map/outcomeRatingCopy.js';

const SUNSET_TODAY = {
  eventType: 'SUNSET', dayLabel: 'Today', time: '18:10',
};
const SUNRISE_YESTERDAY = {
  eventType: 'SUNRISE', dayLabel: 'Yesterday', time: '06:55',
};
const SUNSET_OLD = {
  eventType: 'SUNSET', dayLabel: 'Sat 11', time: '18:17',
};

/**
 * `MapCallout` + `useOutcomeRatings` in miniature. `rate` returns a promise the test settles through
 * `controls.succeed(value)` / `controls.fail()`, the way the hook's optimistic write and rollback
 * would move the record.
 */
function Harness({
  event = SUNSET_TODAY, userRole = 'PRO_USER', initialMine = null, initialChanging = false,
  initialSaveError = false, controls, onClearError = () => {}, extras = null,
}) {
  const [mine, setMine] = useState(initialMine);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState(initialSaveError);
  const [changing, setChanging] = useState(initialChanging);
  const pending = useRef(null);
  useEffect(() => {
    controls.publish({
      succeed: async (value) => {
        await act(async () => {
          setMine({ rating: value, updatedAt: 'now' });
          setSaving(false);
          setSaveError(false);
          pending.current.resolve(true);
        });
      },
      fail: async (previous) => {
        await act(async () => {
          setMine(previous);
          setSaving(false);
          setSaveError(true);
          pending.current.resolve(false);
        });
      },
      state: () => ({ mine, saving, saveError, changing }),
      // A record arriving from outside the block (a refresh re-read, another device).
      deliver: async (record) => { await act(async () => { setMine(record); }); },
      // The owner clearing the failed-save line (the hook's `clearSaveError`).
      clearError: async () => { await act(async () => { setSaveError(false); }); },
    });
  });
  const onRate = (value) => {
    controls.rates.push(value);
    setSaving(true);
    setMine({ rating: value, updatedAt: null });
    return new Promise((resolve) => { pending.current = { resolve }; });
  };
  return (
    <div>
      <OutcomeRatingBlock
        event={event}
        mine={mine}
        userRole={userRole}
        saving={saving}
        saveError={saveError}
        changing={changing}
        onChangingChange={setChanging}
        onRate={onRate}
        onClearError={onClearError}
      />
      {extras}
    </div>
  );
}

function mount(props = {}) {
  const controls = {
    current: null,
    rates: [],
    publish(next) { this.current = next; },
  };
  const utils = render(<Harness controls={controls} {...props} />);
  return { controls, ...utils };
}

const stars = () => screen.getAllByTestId('outcome-rating-star');
const notSeen = () => screen.getByTestId('outcome-rating-not-seen');
const block = () => screen.getByTestId('outcome-rating-block');
const status = () => screen.getByTestId('outcome-rating-status');

describe('OutcomeRatingBlock — Unrated', () => {
  it('asks "How was it?" with the window\'s own meta, five labelled stars and "Didn\'t see it"', () => {
    mount();
    expect(block()).toHaveAttribute('data-state', 'unrated');
    expect(within(block()).getByText('How was it?')).toBeInTheDocument();
    expect(screen.getByTestId('outcome-rating-meta')).toHaveTextContent('Sunset was today 18:10');
    expect(stars()).toHaveLength(5);
    expect(stars().map((s) => s.getAttribute('aria-label')))
      .toEqual([1, 2, 3, 4, 5].map((n) => `Rate the sunset ${n} out of 5`));
    expect(notSeen()).toHaveTextContent('Didn\'t see it');
    // The numerals are drawn, decoratively: the label already says the number.
    expect(stars()[2].querySelector('.wf-rate-n')).toHaveTextContent('3');
    expect(stars()[2].querySelector('.wf-rate-n')).toHaveAttribute('aria-hidden', 'true');
  });

  it('draws no error line and no rated row, and renders the status region EMPTY', () => {
    mount();
    expect(status()).toHaveAttribute('role', 'status');
    expect(status()).toBeEmptyDOMElement();
    expect(screen.queryByTestId('outcome-rating-change')).toBeNull();
  });

  it('is a plain set of buttons, not a toggle group: no aria-pressed, nothing disabled', () => {
    mount();
    for (const button of [...stars(), notSeen()]) {
      expect(button).not.toHaveAttribute('aria-pressed');
      expect(button).not.toHaveAttribute('aria-disabled');
      expect(button).not.toBeDisabled();
    }
  });

  it('words the meta for yesterday and for an older day, per the spec', () => {
    const { unmount } = mount({ event: SUNRISE_YESTERDAY });
    expect(screen.getByTestId('outcome-rating-meta')).toHaveTextContent('Sunrise was yesterday 06:55');
    expect(stars()[0]).toHaveAttribute('aria-label', 'Rate the sunrise 1 out of 5');
    unmount();
    mount({ event: SUNSET_OLD });
    expect(screen.getByTestId('outcome-rating-meta')).toHaveTextContent('Sunset was Sat 11, 18:17');
  });

  it('a star press rates exactly that star; "Didn\'t see it" rates null', () => {
    const { controls, unmount } = mount();
    fireEvent.click(stars()[3]);
    expect(controls.rates).toEqual([4]);
    unmount();
    const second = mount();
    fireEvent.click(notSeen());
    expect(second.controls.rates).toEqual([null]);
  });
});

describe('OutcomeRatingBlock — Saving', () => {
  it('dims the grid and "Didn\'t see it", marks them aria-disabled, highlights the tapped star', () => {
    mount();
    fireEvent.click(stars()[2]);
    expect(block()).toHaveAttribute('data-state', 'saving');
    for (const button of [...stars(), notSeen()]) {
      expect(button).toHaveAttribute('aria-disabled', 'true');
      // Never `disabled`: a disabled control that held focus drops it to the body.
      expect(button).not.toBeDisabled();
    }
    expect(stars()[2]).toHaveAttribute('data-selected');
    expect(stars()[1]).not.toHaveAttribute('data-selected');
    expect(document.querySelector('.wf-rate-grid')).toHaveClass('is-saving');
    expect(notSeen()).toHaveClass('is-saving');
  });

  it('ignores a second press while a save is in flight', () => {
    const { controls } = mount();
    fireEvent.click(stars()[2]);
    fireEvent.click(stars()[4]);
    fireEvent.click(notSeen());
    expect(controls.rates).toEqual([3]);
  });

  it('highlights "Didn\'t see it" while a not-seen save is in flight', () => {
    mount();
    fireEvent.click(notSeen());
    expect(notSeen()).toHaveAttribute('data-selected');
  });
});

describe('OutcomeRatingBlock — Rated', () => {
  it('shows "You rated ★★★☆☆ 3★" with Change, and hides the grid', async () => {
    const { controls } = mount();
    fireEvent.click(stars()[2]);
    await controls.current.succeed(3);

    expect(block()).toHaveAttribute('data-state', 'rated');
    expect(block()).toHaveTextContent('You rated');
    expect(block().querySelector('.wf-rate-on')).toHaveTextContent('★★★');
    expect(block().querySelector('.wf-rate-off')).toHaveTextContent('☆☆');
    expect(block().querySelector('.wf-rate-glyphs')).toHaveAttribute('aria-hidden', 'true');
    expect(block().querySelector('.wf-rate-numeral')).toHaveTextContent('3★');
    expect(block().querySelector('.wf-rate-numeral')).toHaveAttribute('aria-hidden', 'true');
    expect(screen.getByTestId('outcome-rating-change')).toHaveTextContent('Change');
    expect(screen.queryAllByTestId('outcome-rating-star')).toHaveLength(0);
    expect(screen.queryByTestId('outcome-rating-not-seen')).toBeNull();
  });

  it('speaks the rating ONCE: one visually hidden "3 out of 5", and Change is described by it', async () => {
    mount({ initialMine: { rating: 3, updatedAt: 'x' } });
    const hidden = block().querySelectorAll('.sr-only');
    expect(hidden).toHaveLength(1);
    expect(hidden[0]).toHaveTextContent('3 out of 5');
    // The sentence the description points at is the whole "You rated … 3 out of 5" reading.
    expect(screen.getByTestId('outcome-rating-change')).toHaveAccessibleDescription('You rated 3 out of 5');
  });

  it('draws the five glyphs for a 5★ rating with no empty ones', () => {
    mount({ initialMine: { rating: 5, updatedAt: 'x' } });
    expect(block().querySelector('.wf-rate-on')).toHaveTextContent('★★★★★');
    expect(block().querySelector('.wf-rate-off')).toBeEmptyDOMElement();
  });
});

describe('OutcomeRatingBlock — Not seen', () => {
  it('says "You marked this not seen" with Change, and no numeral', () => {
    mount({ initialMine: { rating: null, updatedAt: 'x' } });
    expect(block()).toHaveAttribute('data-state', 'not-seen');
    expect(block()).toHaveTextContent('You marked this not seen');
    expect(screen.getByTestId('outcome-rating-change')).toBeInTheDocument();
    expect(block().querySelector('.wf-rate-glyphs')).toBeNull();
    expect(screen.queryAllByTestId('outcome-rating-star')).toHaveLength(0);
  });
});

describe('OutcomeRatingBlock — Changing', () => {
  it('Change brings the grid back with "Tap to replace your 3★", the current star pressed', () => {
    mount({ initialMine: { rating: 3, updatedAt: 'x' } });
    fireEvent.click(screen.getByTestId('outcome-rating-change'));

    expect(block()).toHaveAttribute('data-state', 'changing');
    expect(screen.getByTestId('outcome-rating-meta')).toHaveTextContent('Tap to replace your 3★');
    expect(stars().map((s) => s.getAttribute('aria-pressed'))).toEqual(['false', 'false', 'true', 'false', 'false']);
    expect(notSeen()).toHaveAttribute('aria-pressed', 'false');
    expect(stars()[2]).toHaveAttribute('data-selected');
    expect(screen.queryByTestId('outcome-rating-change')).toBeNull();
  });

  it('reads Tap to replace "not seen" and presses "Didn\'t see it" when the record is not-seen', () => {
    mount({ initialMine: { rating: null, updatedAt: 'x' } });
    fireEvent.click(screen.getByTestId('outcome-rating-change'));
    expect(screen.getByTestId('outcome-rating-meta')).toHaveTextContent('Tap to replace "not seen"');
    expect(notSeen()).toHaveAttribute('aria-pressed', 'true');
    expect(notSeen()).toHaveAttribute('data-selected');
    expect(stars().every((s) => s.getAttribute('aria-pressed') === 'false')).toBe(true);
  });

  it('one tap replaces the record, and the block goes back to Rated', async () => {
    const { controls } = mount({ initialMine: { rating: 3, updatedAt: 'x' } });
    fireEvent.click(screen.getByTestId('outcome-rating-change'));
    fireEvent.click(stars()[4]);
    expect(controls.rates).toEqual([5]);
    await controls.current.succeed(5);
    expect(block()).toHaveAttribute('data-state', 'rated');
    expect(block().querySelector('.wf-rate-numeral')).toHaveTextContent('5★');
    expect(controls.current.state().changing).toBe(false);
  });

  it('can replace a rating with "Didn\'t see it", and back', async () => {
    const { controls } = mount({ initialMine: { rating: 3, updatedAt: 'x' } });
    fireEvent.click(screen.getByTestId('outcome-rating-change'));
    fireEvent.click(notSeen());
    await controls.current.succeed(null);
    expect(block()).toHaveAttribute('data-state', 'not-seen');
    fireEvent.click(screen.getByTestId('outcome-rating-change'));
    fireEvent.click(stars()[0]);
    await controls.current.succeed(1);
    expect(block().querySelector('.wf-rate-numeral')).toHaveTextContent('1★');
  });
});

describe('OutcomeRatingBlock — Save failed', () => {
  it('a first rating that fails keeps the grid and says "Not saved. Tap a star again." in the status region', async () => {
    const { controls } = mount();
    fireEvent.click(stars()[1]);
    await controls.current.fail(null);

    expect(block()).toHaveAttribute('data-state', 'unrated');
    expect(status()).toHaveTextContent('Not saved. Tap a star again.');
    // Same element as before: a live region that existed empty before it filled.
    expect(status()).toHaveAttribute('role', 'status');
    expect(stars()).toHaveLength(5);
    expect(stars().every((s) => !s.hasAttribute('aria-disabled'))).toBe(true);
    expect(stars()[1]).not.toHaveAttribute('data-selected');
  });

  it('the dot is decorative and amber; the sentence is the status\' only text', async () => {
    const { controls } = mount();
    fireEvent.click(stars()[1]);
    await controls.current.fail(null);
    const dot = status().querySelector('i');
    expect(dot).toHaveAttribute('aria-hidden', 'true');
    expect(status().textContent).toBe('Not saved. Tap a star again.');
  });

  it('a failed replacement returns to Changing, with the previous choice still highlighted', async () => {
    const previous = { rating: 3, updatedAt: 'x' };
    const { controls } = mount({ initialMine: previous });
    fireEvent.click(screen.getByTestId('outcome-rating-change'));
    fireEvent.click(stars()[4]);
    await controls.current.fail(previous);

    expect(block()).toHaveAttribute('data-state', 'changing');
    expect(screen.getByTestId('outcome-rating-meta')).toHaveTextContent('Tap to replace your 3★');
    expect(stars()[2]).toHaveAttribute('aria-pressed', 'true');
    expect(status()).toHaveTextContent('Not saved. Tap a star again.');
    expect(controls.current.state().changing).toBe(true);
  });

  it('the line stays while the retry is saving and clears on the next successful save', async () => {
    const { controls } = mount();
    fireEvent.click(stars()[1]);
    await controls.current.fail(null);
    fireEvent.click(stars()[1]);
    expect(block()).toHaveAttribute('data-state', 'saving');
    expect(status()).toHaveTextContent('Not saved. Tap a star again.');
    await controls.current.succeed(2);
    expect(block()).toHaveAttribute('data-state', 'rated');
    expect(screen.queryByText('Not saved. Tap a star again.')).toBeNull();
  });

  it('Change tells the owner to clear the line', () => {
    const onClearError = vi.fn();
    mount({ initialMine: { rating: 3, updatedAt: 'x' }, onClearError });
    fireEvent.click(screen.getByTestId('outcome-rating-change'));
    expect(onClearError).toHaveBeenCalledTimes(1);
  });

  it('unmounting (a new selection, a closed callout) tells the owner to clear it', () => {
    const onClearError = vi.fn();
    const { unmount } = mount({ initialSaveError: true, onClearError });
    expect(onClearError).not.toHaveBeenCalled();
    unmount();
    expect(onClearError).toHaveBeenCalledTimes(1);
  });
});

describe('OutcomeRatingBlock — Lite', () => {
  it('shows the question with the Pro pill, the Pro line, and no meta', () => {
    mount({ userRole: 'LITE_USER' });
    expect(block()).toHaveAttribute('data-state', 'lite');
    expect(within(block()).getByText('How was it?')).toBeInTheDocument();
    expect(screen.getByTestId('pro-pill')).toBeInTheDocument();
    expect(screen.getByTestId('outcome-rating-note')).toHaveTextContent('Rate how the sky turned out.');
    expect(screen.queryByTestId('outcome-rating-meta')).toBeNull();
  });

  it('dims the five stars to .45 and leaves "Didn\'t see it" at full strength — both aria-disabled', () => {
    mount({ userRole: 'LITE_USER' });
    expect(document.querySelector('.wf-rate-grid')).toHaveClass('is-locked');
    expect(notSeen()).not.toHaveClass('is-locked');
    expect(notSeen()).not.toHaveClass('is-saving');
    for (const button of [...stars(), notSeen()]) {
      expect(button).toHaveAttribute('aria-disabled', 'true');
      expect(button).not.toBeDisabled();
    }
  });

  it('a tap on a star saves nothing and swaps the line for "Rating is part of Pro."', () => {
    const { controls } = mount({ userRole: 'LITE_USER' });
    fireEvent.click(stars()[3]);
    expect(controls.rates).toEqual([]);
    expect(screen.getByTestId('outcome-rating-note')).toHaveTextContent('Rating is part of Pro.');
    expect(block()).toHaveAttribute('data-state', 'lite');
  });

  it('a tap on "Didn\'t see it" does the same', () => {
    const { controls } = mount({ userRole: 'LITE_USER' });
    fireEvent.click(notSeen());
    expect(controls.rates).toEqual([]);
    expect(screen.getByTestId('outcome-rating-note')).toHaveTextContent('Rating is part of Pro.');
  });

  it('the line is a status region that exists BEFORE it changes, so the swap is announced', () => {
    mount({ userRole: 'LITE_USER' });
    const note = screen.getByTestId('outcome-rating-note');
    expect(note).toHaveAttribute('role', 'status');
    fireEvent.click(stars()[0]);
    expect(screen.getByTestId('outcome-rating-note')).toBe(note);
  });

  it('stays locked even when a record exists (a downgraded account)', () => {
    mount({ userRole: 'LITE_USER', initialMine: { rating: 4, updatedAt: 'x' } });
    expect(block()).toHaveAttribute('data-state', 'lite');
    expect(screen.queryByTestId('outcome-rating-change')).toBeNull();
  });
});

describe('OutcomeRatingBlock — focus', () => {
  it('a star press leaves focus on that star while saving, then moves it to Change when the done row replaces the grid', async () => {
    const { controls } = mount();
    const star = stars()[2];
    star.focus();
    fireEvent.click(star);
    expect(document.activeElement).toBe(star);
    expect(star).toHaveAttribute('aria-disabled', 'true');

    await controls.current.succeed(3);
    expect(document.activeElement).toBe(screen.getByTestId('outcome-rating-change'));
  });

  it('the same for "Didn\'t see it"', async () => {
    const { controls } = mount();
    notSeen().focus();
    fireEvent.click(notSeen());
    await controls.current.succeed(null);
    expect(document.activeElement).toBe(screen.getByTestId('outcome-rating-change'));
  });

  it('a FAILED save keeps focus where it was — on the star the reader pressed', async () => {
    const { controls } = mount();
    const star = stars()[2];
    star.focus();
    fireEvent.click(star);
    await controls.current.fail(null);
    expect(document.activeElement).toBe(stars()[2]);
  });

  it('Change moves focus to the current choice\'s star', () => {
    mount({ initialMine: { rating: 4, updatedAt: 'x' } });
    const change = screen.getByTestId('outcome-rating-change');
    change.focus();
    fireEvent.click(change);
    expect(document.activeElement).toBe(stars()[3]);
  });

  it('Change moves focus to "Didn\'t see it" when that is the current choice', () => {
    mount({ initialMine: { rating: null, updatedAt: 'x' } });
    const change = screen.getByTestId('outcome-rating-change');
    change.focus();
    fireEvent.click(change);
    expect(document.activeElement).toBe(notSeen());
  });

  it('keeps focus where the reader has moved it meanwhile — a save landing does not steal it', async () => {
    const { controls } = mount({ extras: <button type="button" data-testid="elsewhere">elsewhere</button> });
    stars()[0].focus();
    fireEvent.click(stars()[0]);
    screen.getByTestId('elsewhere').focus();
    await controls.current.succeed(1);
    expect(document.activeElement).toBe(screen.getByTestId('elsewhere'));
  });

  it('does not take focus when a record simply arrives for a slot the reader never touched', async () => {
    const { controls } = mount({ extras: <button type="button" data-testid="elsewhere">elsewhere</button> });
    screen.getByTestId('elsewhere').focus();
    await controls.current.deliver({ rating: 2, updatedAt: 'x' });
    expect(block()).toHaveAttribute('data-state', 'rated');
    expect(document.activeElement).toBe(screen.getByTestId('elsewhere'));
  });

  it('a FAILED save leaves no focus intent behind: a later record arriving with focus on the body does not pull focus to Change', async () => {
    const { controls } = mount();
    stars()[1].focus();
    fireEvent.click(stars()[1]);
    await controls.current.fail(null);
    stars()[1].blur();
    expect(document.activeElement).toBe(document.body);

    await controls.current.clearError();
    await controls.current.deliver({ rating: 4, updatedAt: 'x' });
    expect(block()).toHaveAttribute('data-state', 'rated');
    expect(document.activeElement).toBe(document.body);
  });
});

describe('OutcomeRatingBlock — keys and Escape', () => {
  it('handles no keys of its own: Escape is left to the pane, arrows do nothing', () => {
    const { controls } = mount();
    const star = stars()[2];
    star.focus();
    // `fireEvent` returns false when the event was default-prevented.
    expect(fireEvent.keyDown(star, { key: 'Escape' })).toBe(true);
    expect(fireEvent.keyDown(star, { key: 'ArrowRight' })).toBe(true);
    expect(document.activeElement).toBe(star);
    expect(controls.rates).toEqual([]);
  });

  it('is a plain div with no group or radiogroup role of its own', () => {
    mount();
    expect(block().getAttribute('role')).toBeNull();
    expect(document.querySelector('[role="radiogroup"], [role="group"]')).toBeNull();
  });
});

describe('ratingView', () => {
  const base = {
    userRole: 'PRO_USER', mine: null, changing: false, saving: false, saveError: false,
  };
  const rated = { rating: 3 };

  it.each([
    ['LITE wins over everything', { ...base, userRole: 'LITE_USER', mine: rated }, 'lite'],
    ['no record is the grid', base, 'grid'],
    ['a record, settled, is the done row', { ...base, mine: rated }, 'done'],
    ['"not seen" (rating null) is still a record', { ...base, mine: { rating: null } }, 'done'],
    ['changing keeps the grid', { ...base, mine: rated, changing: true }, 'grid'],
    ['saving keeps the grid', { ...base, mine: rated, saving: true }, 'grid'],
    ['a failed save keeps the grid', { ...base, mine: rated, saveError: true }, 'grid'],
  ])('%s', (_name, state, expected) => {
    expect(ratingView(state)).toBe(expected);
  });
});

describe('outcomeRatingCopy', () => {
  it('words the meta for today, yesterday and an older day exactly as the spec does', () => {
    expect(unratedMeta('SUNSET', 'Today', '18:10')).toBe('Sunset was today 18:10');
    expect(unratedMeta('SUNRISE', 'Yesterday', '06:55')).toBe('Sunrise was yesterday 06:55');
    expect(unratedMeta('SUNSET', 'Sat 11', '18:17')).toBe('Sunset was Sat 11, 18:17');
  });

  it('degrades without a clock time or a day label rather than printing "undefined"', () => {
    expect(unratedMeta('SUNSET', 'Today', null)).toBe('Sunset was today');
    expect(unratedMeta('SUNSET', 'Sat 11', '')).toBe('Sunset was Sat 11');
    expect(unratedMeta('SUNRISE', null, '06:55')).toBe('Sunrise was 06:55');
    expect(unratedMeta('SUNRISE', null, null)).toBe('Sunrise');
  });

  it('builds the rest of the spec\'s strings', () => {
    expect(replaceMeta(4)).toBe('Tap to replace your 4★');
    expect(starLabel('SUNRISE', 2)).toBe('Rate the sunrise 2 out of 5');
    expect(spokenRating(5)).toBe('5 out of 5');
    expect(ratedNumeral(1)).toBe('1★');
  });
});
