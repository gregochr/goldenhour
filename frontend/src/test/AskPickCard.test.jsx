import { describe, it, expect, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import AskPickCard from '../components/ask/AskPickCard.jsx';
import { buildPickCards } from '../utils/askModel.js';
import { briefing, pick, ROSEBERRY, SALTBURN, WHITBY } from './askFixtures.js';

/** A real card from the real join — the component is only ever handed these. */
const cardFor = (over = {}, reach = new Map([[WHITBY, { driveMinutes: 95 }]])) => (
  buildPickCards([pick(over)], briefing().days, reach)[0]
);

const renderCard = (card, props = {}) => render(<ol><AskPickCard card={card} {...props} /></ol>);

describe('AskPickCard — the three lines', () => {
  it('line 1: the place, and the verdict and star in the verdict’s own tier', () => {
    renderCard(cardFor());

    expect(screen.getByTestId('ask-pick-name-1')).toHaveTextContent('Whitby');
    const score = screen.getByTestId('ask-pick-score-1');
    expect(score).toHaveAttribute('data-tier', 'WORTH_IT');
    // The VISIBLE star: the text a sighted reader sees, apart from the screen-reader-only ", 5 stars".
    expect(within(score).getByText('· 5')).toHaveAttribute('aria-hidden', 'true');
    expect(score).toHaveTextContent(/^Worth it/);
  });

  it('line 2: the event badge, the UK day and time, the HOME drive and the tide', () => {
    renderCard(cardFor());

    const card = screen.getByTestId('ask-pick-1');
    expect(within(card).getByText('SUNSET')).toHaveAttribute('data-target', 'SUNSET');
    expect(screen.getByTestId('ask-pick-when-1')).toHaveTextContent('Mon 18:41');
    expect(screen.getByTestId('ask-pick-drive-1')).toHaveTextContent('1h 35min');
    expect(screen.getByTestId('ask-pick-drive-1')).toHaveTextContent('Drive from home');
    expect(screen.getByTestId('ask-pick-tide-1')).toHaveAttribute('data-tier', 'match');
    // The visible words, in their own aria-hidden span — not the sr-only clause that also says it.
    expect(within(screen.getByTestId('ask-pick-tide-1')).getByText('high water'))
      .toHaveAttribute('aria-hidden', 'true');
  });

  it('line 3: why', () => {
    renderCard(cardFor());

    expect(screen.getByTestId('ask-pick-select-1'))
      .toHaveTextContent('Clear to the west and the tide is in at the light.');
  });

  it('prints no drive line when no drive is known, rather than a dash that reads as 0', () => {
    renderCard(cardFor({}, null));

    expect(screen.queryByTestId('ask-pick-drive-1')).toBeNull();
  });

  it('prints no tide for an inland place', () => {
    renderCard(cardFor({ locationId: ROSEBERRY, locationName: 'Roseberry Topping' }));

    expect(screen.queryByTestId('ask-pick-tide-1')).toBeNull();
  });

  it('says a missed tide is a miss, in words a screen reader gets', () => {
    renderCard(cardFor({ locationId: SALTBURN, locationName: 'Saltburn' }));

    const tide = screen.getByTestId('ask-pick-tide-1');
    expect(tide).toHaveAttribute('data-tier', 'miss');
    expect(tide).toHaveTextContent('Tide: wants the water lower');
  });

  it('omits "Plan this" — that is F5 — and "Add to Coming up", which was removed', () => {
    renderCard(cardFor());

    expect(screen.queryByText(/plan this/i)).toBeNull();
    expect(screen.queryByText(/add to coming up/i)).toBeNull();
  });

  it('wraps a long name and a long reason rather than dropping them', () => {
    const name = 'The Very Long Named Headland Overlooking The Whole Of The North Sea Coast';
    const card = { ...cardFor(), name, why: 'x'.repeat(300) };

    renderCard(card);

    expect(screen.getByTestId('ask-pick-name-1')).toHaveTextContent(name);
    expect(screen.getByTestId('ask-pick-select-1')).toHaveTextContent('x'.repeat(300));
  });

  it('prints the verdict word alone when there is no star, and says nothing of stars', () => {
    renderCard({ ...cardFor(), rating: null, verdict: 'AWAITING', verdictLabel: 'Not scored' });

    const score = screen.getByTestId('ask-pick-score-1');
    expect(score).toHaveTextContent(/^Not scored$/);
  });
});

describe('AskPickCard — accessibility', () => {
  it('gives the rank circle the name the map’s marker will carry', () => {
    renderCard(cardFor());

    expect(screen.getByRole('img', { name: 'Pick 1, Whitby, Monday sunset, 5 stars' }))
      .toHaveTextContent('1');
  });

  it('reads the star in words for a screen reader', () => {
    renderCard(cardFor());

    expect(screen.getByTestId('ask-pick-score-1')).toHaveTextContent(', 5 stars');
  });

  it('is one button, whose state says whether the pick is the current one — not a toggle', () => {
    const onSelect = vi.fn();
    const { rerender } = renderCard(cardFor(), { onSelect });

    const button = screen.getByRole('button', { name: /Whitby/ });
    expect(button).not.toHaveAttribute('aria-current');
    expect(button).not.toHaveAttribute('aria-pressed');
    fireEvent.click(button);
    expect(onSelect).toHaveBeenCalledWith(1);

    rerender(<ol><AskPickCard card={cardFor()} selected onSelect={onSelect} /></ol>);
    expect(screen.getByRole('button', { name: /Whitby/ })).toHaveAttribute('aria-current', 'true');
    expect(screen.getByRole('button', { name: /Whitby/ })).not.toHaveAttribute('aria-pressed');
    expect(screen.getByTestId('ask-pick-1')).toHaveAttribute('data-selected', 'true');
  });

  it('puts the rank in the button’s own name too, because the circle is not focusable', () => {
    renderCard(cardFor());

    // `\s*`: jsdom has no layout, so it glues what a browser spaces (every child of the button is a
    // flex item there, which is why the pieces carry no separators of their own).
    expect(screen.getByRole('button', { name: /^Pick 1,\s*Whitby/ })).toBeInTheDocument();
  });

  it('gives later phases stable, non-test hooks to find the card and its button', () => {
    renderCard(cardFor());

    expect(screen.getByTestId('ask-pick-1')).toHaveAttribute('data-ask-pick', '1');
    expect(screen.getByTestId('ask-pick-select-1')).toHaveAttribute('data-ask-pick-select', '1');
  });

  it('renders the card’s own row beside nothing when there are no actions, and a sibling of the button when there are', () => {
    const { rerender } = renderCard(cardFor());
    expect(screen.queryByTestId('ask-pick-actions-1')).toBeNull();

    rerender(<ol><AskPickCard card={cardFor()} actions={<button type="button">Show on map ›</button>} /></ol>);

    const row = screen.getByTestId('ask-pick-actions-1');
    expect(within(row).getByRole('button', { name: 'Show on map ›' })).toBeInTheDocument();
    // A button inside a button is invalid markup; the row must be outside the select button.
    expect(screen.getByTestId('ask-pick-select-1')).not.toContainElement(row);
  });

  it('does not throw when pressed with no handler', () => {
    renderCard(cardFor());

    expect(() => fireEvent.click(screen.getByRole('button', { name: /Whitby/ }))).not.toThrow();
  });
});
