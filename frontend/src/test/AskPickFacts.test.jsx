import { describe, it, expect } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { PickScore, PickTide, pickWhen } from '../components/ask/AskPickFacts.jsx';
import { starsWord } from '../utils/askModel.js';

/**
 * The facts the pick card and the "Plan this" view share (`components/ask/AskPickFacts.jsx`). The hosts'
 * own tests pin that each one mounts them; THIS file is the one place that pins what the spoken text
 * says, so the card and the plan view cannot drift apart in how a screen reader hears a pick.
 */

const card = (over = {}) => ({
  rank: 1, verdict: 'WORTH_IT', verdictLabel: 'Worth it', rating: 5, dayWord: 'Mon', eventTime: '18:41', ...over,
});

const MATCH = { tier: 'match', state: 'HIGH', shortfall: null, clause: 'high water, right here' };
const MISS = { tier: 'miss', state: 'MID', shortfall: 'DOWN', clause: 'wants the water lower' };

describe('pickWhen', () => {
  it('is the weekday and the served time', () => {
    expect(pickWhen(card())).toBe('Mon 18:41');
  });

  it('is the weekday alone when the slot carries no time', () => {
    expect(pickWhen(card({ eventTime: null }))).toBe('Mon');
  });
});

describe('starsWord', () => {
  it.each([[1, 'star'], [2, 'stars'], [5, 'stars']])('says %i as "%s"', (n, word) => {
    expect(starsWord(n)).toBe(word);
  });
});

describe('PickScore', () => {
  it('draws the verdict, the visible star and the spoken star, in the verdict’s own tier', () => {
    render(<PickScore card={card()} testId="score" />);

    const score = screen.getByTestId('score');
    expect(score).toHaveAttribute('data-tier', 'WORTH_IT');
    expect(score).toHaveClass('wf-ask-sc');
    expect(within(score).getByText('· 5')).toHaveAttribute('aria-hidden', 'true');
    expect(score).toHaveTextContent('Worth it · 5, 5 stars');
  });

  it('says "1 star" in the singular', () => {
    render(<PickScore card={card({ rating: 1 })} testId="score" />);

    expect(screen.getByTestId('score')).toHaveTextContent(', 1 star');
    expect(screen.getByTestId('score')).not.toHaveTextContent('1 stars');
  });

  it('prints the verdict word alone with no star, and says nothing of stars', () => {
    render(<PickScore card={card({ rating: null, verdict: 'AWAITING', verdictLabel: 'Not scored' })} testId="score" />);

    expect(screen.getByTestId('score')).toHaveTextContent(/^Not scored$/);
  });

  it('adds a host’s class after its own', () => {
    render(<PickScore card={card()} testId="score" className="wf-ask-plh-sc" />);

    expect(screen.getByTestId('score').className).toBe('wf-ask-sc wf-ask-plh-sc');
  });
});

describe('PickTide', () => {
  it('leads the spoken clause with "Tide: " by default — the card has no visible label of its own', () => {
    const { container } = render(<PickTide tide={MISS} />);

    expect(container.querySelector('.sr-only')).toHaveTextContent(/^Tide: wants the water lower$/);
  });

  it('leads a matched tide’s clause the same way', () => {
    const { container } = render(<PickTide tide={MATCH} />);

    expect(container.querySelector('.sr-only')).toHaveTextContent(/^Tide: high water, right here$/);
  });

  it('reads the clause bare when labelled is false — the plan view’s <dt> already says Tide, once', () => {
    const { container } = render(<PickTide tide={MATCH} labelled={false} />);

    expect(container.querySelector('.sr-only')).toHaveTextContent(/^high water, right here$/);
  });

  it('hides the visible state word from assistive technology, so the fact is said once', () => {
    render(<PickTide tide={MATCH} />);

    expect(screen.getByText('high water')).toHaveAttribute('aria-hidden', 'true');
  });

  it('draws no spoken clause when the fact carries none, and no word for a state it does not know', () => {
    const { container } = render(<PickTide tide={{ tier: 'match', state: null, clause: null }} />);

    expect(container.querySelector('.sr-only')).toBeNull();
    expect(screen.queryByText(/water|tide/i)).toBeNull();
  });
});
