import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import HotTopicStrip from '../components/HotTopicStrip.jsx';

const kingTide = {
  type: 'KING_TIDE',
  label: 'King tide',
  detail: 'Highest water of the year',
  date: '2026-06-17',
  priority: 1,
  facts: [
    { key: 'high water', value: '5.8 m', emphasis: true, optional: false },
    { value: '+0.7 m over spring', emphasis: false, optional: false },
    { key: 'HW', value: '06:42', emphasis: true, optional: true },
    { key: 'seas', value: '4.2 m · very rough', emphasis: true, optional: true },
  ],
  note: 'causeways & foreshore submerged — shoot reflections',
};

describe('HotTopicStrip enriched facts line', () => {
  it('renders the fact chips, values and the where-to-look note', () => {
    render(<HotTopicStrip hotTopics={[kingTide]} />);
    expect(screen.getByTestId('topic-facts-KING_TIDE')).toBeInTheDocument();
    expect(screen.getByText('5.8 m')).toBeInTheDocument();
    expect(screen.getByText('+0.7 m over spring')).toBeInTheDocument();
    expect(screen.getByText('4.2 m · very rough')).toBeInTheDocument();
    expect(screen.getByTestId('topic-fact-note')).toHaveTextContent(
      'causeways & foreshore submerged',
    );
  });

  it('marks the least-critical chips optional (.opt) and leaves essentials un-marked', () => {
    render(<HotTopicStrip hotTopics={[kingTide]} />);
    // "high water 5.8 m" is essential — never dropped on mobile.
    expect(screen.getByText('5.8 m').closest('[data-testid="topic-fact"]')).not.toHaveClass('opt');
    // "seas …" is the bonus chip — dropped on narrow viewports.
    expect(screen.getByText('4.2 m · very rough').closest('[data-testid="topic-fact"]'))
      .toHaveClass('opt');
  });

  it('renders emphasised values bold and context values regular', () => {
    render(<HotTopicStrip hotTopics={[kingTide]} />);
    expect(screen.getByText('5.8 m')).toHaveStyle({ fontWeight: '600' });
    expect(screen.getByText('+0.7 m over spring')).toHaveStyle({ fontWeight: '400' });
  });

  it('blurs the facts for Lite users (paywall tease) but still renders them', () => {
    render(<HotTopicStrip hotTopics={[kingTide]} isLiteUser />);
    const facts = screen.getByTestId('topic-facts-KING_TIDE');
    expect(facts).toBeInTheDocument();
    expect(facts).toHaveStyle({ filter: 'blur(3.5px)' });
  });

  it('renders no facts line when the topic carries none', () => {
    const bare = { ...kingTide, facts: undefined, note: undefined };
    render(<HotTopicStrip hotTopics={[bare]} />);
    expect(screen.queryByTestId('topic-facts-KING_TIDE')).not.toBeInTheDocument();
  });
});
