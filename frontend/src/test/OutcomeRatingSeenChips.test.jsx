/**
 * The "what did you see" chip row of `components/map/OutcomeRatingBlock.jsx` (valley-mist-plan M3, owner
 * decision Q4) and its admin-list echo: shown only once a star is chosen, one toggle per tag with
 * `aria-pressed`, a tap sets the tag and a re-tap clears it, the Lite lock answers in words and never
 * `disabled`s anything, and a failed tag save says so beside the chips.
 *
 * The block is stateless about the record, so the tests drive it through a small harness that plays
 * `MapCallout` plus `useOutcomeRatings`: it holds `changing` and the record, and settles each save by
 * hand. The hook's own tag behaviour (the PUT body, the optimistic value, the rollback) is in
 * `useOutcomeRatingsSeen.test.js`.
 */
import React, { useState } from 'react';
import { describe, it, expect, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';

import OutcomeRatingBlock, { showsSeenChips } from '../components/map/OutcomeRatingBlock.jsx';
import { SEEN_TAGS, TAG_SAVE_FAILED, seenLabel, LITE_LOCKED } from '../components/map/outcomeRatingCopy.js';

const EVENT = { eventType: 'SUNRISE', dayLabel: 'Yesterday', time: '06:55' };

function Harness({
  mine = { rating: 4, updatedAt: 'now' }, userRole = 'PRO_USER', initialChanging = false,
  tagSaving = false, tagSaveError = false, onTag = async () => true, saving = false,
}) {
  const [changing, setChanging] = useState(initialChanging);
  return (
    <OutcomeRatingBlock
      event={EVENT}
      mine={mine}
      userRole={userRole}
      saving={saving}
      changing={changing}
      onChangingChange={setChanging}
      onRate={async () => true}
      onTag={onTag}
      tagSaving={tagSaving}
      tagSaveError={tagSaveError}
    />
  );
}

const row = () => screen.queryByTestId('outcome-rating-seen');
const chips = () => screen.getAllByTestId('outcome-rating-seen-chip');
const chip = (tag) => chips().find((c) => c.dataset.tag === tag);

describe('the "what did you see" chips — when they are drawn', () => {
  it('is absent from an unrated block: a tag needs a star', () => {
    render(<Harness mine={null} />);
    expect(row()).toBeNull();
  });

  it('is drawn under a rated record, in the done layout', () => {
    render(<Harness />);
    expect(screen.getByTestId('outcome-rating-change')).toBeTruthy();
    expect(row()).not.toBeNull();
    expect(chips().map((c) => c.textContent)).toEqual(['Colour', 'Mist', 'Inversion', 'Flat']);
  });

  it('is absent from a "didn\'t see it" record, which carries NOT_SEEN on its own', () => {
    render(<Harness mine={{ rating: null, updatedAt: 'now', seen: 'NOT_SEEN' }} />);
    expect(row()).toBeNull();
  });

  it('has no NOT_SEEN chip: "Didn\'t see it" is its own button', () => {
    render(<Harness />);
    expect(chips().map((c) => c.dataset.tag)).toEqual(['COLOUR', 'MIST', 'INVERSION', 'FLAT']);
    expect(SEEN_TAGS.some((t) => t.tag === 'NOT_SEEN')).toBe(false);
  });

  it('stays while the reader changes an existing star, so Change does not move it', () => {
    render(<Harness initialChanging />);
    expect(screen.getAllByTestId('outcome-rating-star')).toHaveLength(5);
    expect(row()).not.toBeNull();
  });

  it('does not appear on the saving grid of a FIRST rating (its height must not change mid-flight)', () => {
    render(<Harness mine={{ rating: 4, updatedAt: null }} saving />);
    expect(row()).toBeNull();
  });

  it('is named by showsSeenChips for the callout\'s repaint: done+rated, changing+rated, lite; never unrated', () => {
    const base = { userRole: 'PRO_USER', changing: false, saving: false, saveError: false };
    expect(showsSeenChips({ ...base, mine: { rating: 3 } })).toBe(true);
    expect(showsSeenChips({ ...base, mine: null })).toBe(false);
    expect(showsSeenChips({ ...base, mine: { rating: null } })).toBe(false);
    expect(showsSeenChips({ ...base, mine: { rating: 3 }, changing: true })).toBe(true);
    expect(showsSeenChips({ ...base, mine: { rating: 3 }, saving: true })).toBe(false);
    expect(showsSeenChips({ ...base, mine: null, userRole: 'LITE_USER' })).toBe(true);
  });
});

describe('the "what did you see" chips — pressing', () => {
  it('marks the held tag aria-pressed and the others not', () => {
    render(<Harness mine={{ rating: 4, updatedAt: 'now', seen: 'MIST' }} />);
    expect(chip('MIST').getAttribute('aria-pressed')).toBe('true');
    for (const other of ['COLOUR', 'INVERSION', 'FLAT']) {
      expect(chip(other).getAttribute('aria-pressed')).toBe('false');
    }
  });

  it('is a labelled group of plain buttons, none disabled', () => {
    render(<Harness />);
    expect(row().getAttribute('role')).toBe('group');
    // Labelled by a VISIBLE caption (WCAG 3.3.2), not an aria-label only AT users could read.
    const caption = document.getElementById(row().getAttribute('aria-labelledby'));
    expect(caption.textContent).toBe('What did you see?');
    expect(row().hasAttribute('aria-label')).toBe(false);
    for (const c of chips()) {
      expect(c.tagName).toBe('BUTTON');
      expect(c.hasAttribute('disabled')).toBe(false);
      expect(c.getAttribute('aria-disabled')).toBeNull();
    }
  });

  it('a tap saves that tag', () => {
    const onTag = vi.fn(async () => true);
    render(<Harness onTag={onTag} />);
    fireEvent.click(chip('INVERSION'));
    expect(onTag).toHaveBeenCalledExactlyOnceWith('INVERSION');
  });

  it('a tap on the held tag clears it (null)', () => {
    const onTag = vi.fn(async () => true);
    render(<Harness mine={{ rating: 4, updatedAt: 'now', seen: 'MIST' }} onTag={onTag} />);
    fireEvent.click(chip('MIST'));
    expect(onTag).toHaveBeenCalledExactlyOnceWith(null);
  });

  it('a tap on another tag replaces the held one', () => {
    const onTag = vi.fn(async () => true);
    render(<Harness mine={{ rating: 4, updatedAt: 'now', seen: 'MIST' }} onTag={onTag} />);
    fireEvent.click(chip('FLAT'));
    expect(onTag).toHaveBeenCalledExactlyOnceWith('FLAT');
  });

  it('ignores a tap while a tag save is in flight, and says it is busy with aria-disabled (never disabled)', () => {
    const onTag = vi.fn(async () => true);
    render(<Harness tagSaving onTag={onTag} />);
    for (const c of chips()) {
      expect(c.getAttribute('aria-disabled')).toBe('true');
      expect(c.hasAttribute('disabled')).toBe(false);
    }
    fireEvent.click(chip('MIST'));
    expect(onTag).not.toHaveBeenCalled();
  });

  it('reads aria-busy on the group while a save (a star re-rate or a tag) is in flight, and not otherwise', () => {
    const { unmount } = render(<Harness initialChanging saving />);
    expect(row().getAttribute('aria-busy')).toBe('true');
    unmount();
    const second = render(<Harness tagSaving />);
    expect(row().getAttribute('aria-busy')).toBe('true');
    second.unmount();
    render(<Harness />);
    expect(row().hasAttribute('aria-busy')).toBe(false);
  });

  it('keeps the "You rated" layout while a tag saves: the done row and Change stay', () => {
    render(<Harness tagSaving />);
    expect(screen.getByTestId('outcome-rating-change')).toBeTruthy();
    expect(screen.queryAllByTestId('outcome-rating-star')).toHaveLength(0);
  });

  it('keeps focus on the chip that was pressed', async () => {
    function Live() {
      const [mine, setMine] = useState({ rating: 4, updatedAt: 'now' });
      return (
        <OutcomeRatingBlock
          event={EVENT}
          mine={mine}
          userRole="PRO_USER"
          onChangingChange={() => {}}
          onRate={async () => true}
          onTag={async (tag) => { setMine({ rating: 4, updatedAt: 'now', ...(tag ? { seen: tag } : {}) }); return true; }}
        />
      );
    }
    render(<Live />);
    chip('MIST').focus();
    await act(async () => { fireEvent.click(chip('MIST')); });
    expect(document.activeElement).toBe(chip('MIST'));
    expect(chip('MIST').getAttribute('aria-pressed')).toBe('true');
  });
});

describe('the "what did you see" chips — a failed save', () => {
  it('says so in the status region beside the chips, the done layout kept', () => {
    render(<Harness tagSaveError />);
    expect(screen.getByTestId('outcome-rating-tag-status').textContent).toContain(TAG_SAVE_FAILED);
    expect(screen.getByTestId('outcome-rating-change')).toBeTruthy();
  });

  it('renders that status region EMPTY before there is anything to say, so it exists to be announced into', () => {
    render(<Harness />);
    const status = screen.getByTestId('outcome-rating-tag-status');
    expect(status.getAttribute('role')).toBe('status');
    expect(status.textContent).toBe('');
  });

  it('says it on the changing grid too, in that layout\'s own status line', () => {
    render(<Harness initialChanging tagSaveError />);
    expect(screen.getByTestId('outcome-rating-status').textContent).toContain(TAG_SAVE_FAILED);
  });
});

describe('the "what did you see" chips — the Lite lock', () => {
  it('draws the chips dimmed, aria-disabled and never disabled, none pressed', () => {
    render(<Harness mine={null} userRole="LITE_USER" />);
    expect(row().className).toContain('is-locked');
    for (const c of chips()) {
      expect(c.getAttribute('aria-disabled')).toBe('true');
      expect(c.hasAttribute('disabled')).toBe(false);
      expect(c.getAttribute('aria-pressed')).toBe('false');
    }
  });

  it('a tap saves nothing and answers in words', () => {
    const onTag = vi.fn(async () => true);
    render(<Harness mine={null} userRole="LITE_USER" onTag={onTag} />);
    fireEvent.click(chip('MIST'));
    expect(onTag).not.toHaveBeenCalled();
    expect(screen.getByTestId('outcome-rating-note').textContent).toBe(LITE_LOCKED);
  });

  it('stays locked for a downgraded account that still holds a tagged record', () => {
    const onTag = vi.fn(async () => true);
    render(<Harness mine={{ rating: 5, updatedAt: 'x', seen: 'COLOUR' }} userRole="LITE_USER" onTag={onTag} />);
    expect(chip('COLOUR').getAttribute('aria-pressed')).toBe('false');
    fireEvent.click(chip('FLAT'));
    expect(onTag).not.toHaveBeenCalled();
  });
});

describe('seenLabel', () => {
  it('words the four sightings and nothing else', () => {
    expect(seenLabel('COLOUR')).toBe('Colour');
    expect(seenLabel('MIST')).toBe('Mist');
    expect(seenLabel('INVERSION')).toBe('Inversion');
    expect(seenLabel('FLAT')).toBe('Flat');
    expect(seenLabel('NOT_SEEN')).toBeNull();
    expect(seenLabel(null)).toBeNull();
    expect(seenLabel(undefined)).toBeNull();
    expect(seenLabel('FOG')).toBeNull();
  });
});

describe('the Admin list shows each reader\'s tag', () => {
  function Admin({ rows }) {
    return (
      <OutcomeRatingBlock
        event={EVENT}
        mine={null}
        userRole="ADMIN"
        onChangingChange={() => {}}
        onRate={async () => true}
        admin={{
          open: true, onToggle: () => {}, loading: false, rows, eventTime: null, forecastRunAt: null,
        }}
      />
    );
  }

  it('beside the rating, as a word, and nothing for an untagged row', () => {
    render(<Admin rows={[
      { username: 'zoe', rating: 5, updatedAt: '2026-10-16T10:30:00Z', mine: false, seen: 'MIST' },
      { username: 'mhart', rating: 4, updatedAt: '2026-10-14T18:40:00Z', mine: false },
    ]}
    />);
    const tags = screen.getAllByTestId('outcome-admin-seen');
    expect(tags).toHaveLength(1);
    expect(tags[0].textContent).toBe('Saw: Mist');
    expect(tags[0].querySelector('.sr-only').textContent).toBe('Saw: ');
    expect(tags[0].closest('[data-testid="outcome-admin-row"]').textContent).toContain('zoe');
  });

  it('says "Not seen" once: a NOT_SEEN row adds no tag word', () => {
    render(<Admin rows={[
      { username: 'pat', rating: null, updatedAt: '2026-10-13T17:10:00Z', mine: false, seen: 'NOT_SEEN' },
    ]}
    />);
    expect(screen.queryByTestId('outcome-admin-seen')).toBeNull();
    expect(screen.getByTestId('outcome-admin-row').textContent).toContain('Not seen');
  });
});
