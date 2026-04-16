import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import HotTopicStrip from '../components/HotTopicStrip.jsx';

const baseTopic = {
  type: 'BLUEBELL',
  label: 'BLUEBELL CONDITIONS',
  detail: 'Misty and still — perfect morning conditions',
  date: '2026-04-16',
  priority: 1,
  regions: ['Northumberland', 'The Lake District'],
  description: 'Bluebells peak in sheltered woodland glades',
};

function buildTopic(overrides = {}) {
  return { ...baseTopic, ...overrides };
}

describe('HotTopicStrip', () => {
  it('renders nothing when hotTopics is empty', () => {
    const { container } = render(<HotTopicStrip hotTopics={[]} />);
    expect(container.innerHTML).toBe('');
  });

  it('renders nothing when hotTopics is null', () => {
    const { container } = render(<HotTopicStrip hotTopics={null} />);
    expect(container.innerHTML).toBe('');
  });

  it('renders exactly one pill per topic', () => {
    const topics = [
      buildTopic(),
      buildTopic({ type: 'FOG', label: 'FOG CONDITIONS', date: '2026-04-17' }),
    ];
    render(<HotTopicStrip hotTopics={topics} />);
    expect(screen.getByTestId('hot-topic-pill-BLUEBELL')).toBeInTheDocument();
    expect(screen.getByTestId('hot-topic-pill-FOG')).toBeInTheDocument();
    const strip = screen.getByTestId('hot-topic-strip');
    const pills = strip.querySelectorAll('button[data-testid^="hot-topic-pill-"]');
    expect(pills).toHaveLength(2);
  });

  it('renders the strip container with horizontal scroll', () => {
    render(<HotTopicStrip hotTopics={[buildTopic()]} />);
    const strip = screen.getByTestId('hot-topic-strip');
    expect(strip).toBeInTheDocument();
    expect(strip.style.overflowX).toBe('auto');
    expect(strip.style.display).toBe('flex');
  });

  describe('title row layout', () => {
    it('renders title text', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      expect(screen.getByText('BLUEBELL CONDITIONS')).toBeInTheDocument();
    });

    it('renders regions on the same row as the title', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      const title = screen.getByText('BLUEBELL CONDITIONS');
      const regions = screen.getByText('Northumberland, The Lake District');
      // Both should share the same parent row div
      expect(title.closest('div').parentElement).toBe(regions.parentElement);
    });

    it('renders regions as comma-separated text', () => {
      render(
        <HotTopicStrip
          hotTopics={[buildTopic({ regions: ['A', 'B', 'C'] })]}
        />,
      );
      expect(screen.getByText('A, B, C')).toBeInTheDocument();
    });

    it('does not render regions when regions array is empty', () => {
      render(<HotTopicStrip hotTopics={[buildTopic({ regions: [] })]} />);
      expect(
        screen.queryByText('Northumberland, The Lake District'),
      ).not.toBeInTheDocument();
    });

    it('does not render regions when regions is null', () => {
      render(<HotTopicStrip hotTopics={[buildTopic({ regions: null })]} />);
      expect(
        screen.queryByText('Northumberland, The Lake District'),
      ).not.toBeInTheDocument();
    });

    it('renders detail text below the title row', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      expect(
        screen.getByText('Misty and still — perfect morning conditions'),
      ).toBeInTheDocument();
    });
  });

  describe('infotip', () => {
    it('renders infotip when description is provided', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      expect(screen.getByTestId('infotip-trigger')).toBeInTheDocument();
    });

    it('does not render infotip when description is absent', () => {
      render(
        <HotTopicStrip
          hotTopics={[buildTopic({ description: undefined })]}
        />,
      );
      expect(screen.queryByTestId('infotip-trigger')).not.toBeInTheDocument();
    });

    it('infotip sits next to the title (same parent group)', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      const title = screen.getByText('BLUEBELL CONDITIONS');
      const infotip = screen.getByTestId('infotip-trigger');
      // The title-group div contains both the title span and the infotip wrapper
      const titleGroup = title.parentElement;
      expect(titleGroup.contains(infotip)).toBe(true);
    });

    it('infotip click does not trigger pill onTopicTap', () => {
      const onTap = vi.fn();
      render(
        <HotTopicStrip hotTopics={[buildTopic()]} onTopicTap={onTap} />,
      );
      fireEvent.click(screen.getByTestId('infotip-trigger'));
      expect(onTap).not.toHaveBeenCalled();
    });
  });

  describe('interactions', () => {
    it('calls onTopicTap when a pill is clicked', () => {
      const onTap = vi.fn();
      const topic = buildTopic();
      render(<HotTopicStrip hotTopics={[topic]} onTopicTap={onTap} />);
      fireEvent.click(screen.getByTestId('hot-topic-pill-BLUEBELL'));
      expect(onTap).toHaveBeenCalledWith(topic);
    });

    it('does not call onTopicTap when isLiteUser', () => {
      const onTap = vi.fn();
      render(
        <HotTopicStrip
          hotTopics={[buildTopic()]}
          onTopicTap={onTap}
          isLiteUser
        />,
      );
      fireEvent.click(screen.getByTestId('hot-topic-pill-BLUEBELL'));
      expect(onTap).not.toHaveBeenCalled();
    });

    it('pill is disabled for lite users', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} isLiteUser />);
      expect(screen.getByTestId('hot-topic-pill-BLUEBELL')).toBeDisabled();
    });

    it('pill is not disabled for regular users', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      expect(screen.getByTestId('hot-topic-pill-BLUEBELL')).not.toBeDisabled();
    });
  });

  describe('lite user upsell', () => {
    it('shows upsell badge when isLiteUser', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} isLiteUser />);
      expect(screen.getByTestId('hot-topic-upsell')).toBeInTheDocument();
      expect(screen.getByText('Upgrade to Pro')).toBeInTheDocument();
    });

    it('does not show upsell badge for regular users', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      expect(screen.queryByTestId('hot-topic-upsell')).not.toBeInTheDocument();
    });
  });

  describe('accent colours', () => {
    it('applies BLUEBELL accent border, background, and label colour', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      const pill = screen.getByTestId('hot-topic-pill-BLUEBELL');
      expect(pill.style.borderColor).toBe('rgba(99, 102, 241, 0.3)');
      expect(pill.style.background).toBe('rgba(99, 102, 241, 0.06)');
      const label = screen.getByText('BLUEBELL CONDITIONS');
      expect(label.style.color).toBe('rgb(129, 140, 248)');
    });

    it('applies default accent border, background, and label colour for unknown types', () => {
      render(
        <HotTopicStrip
          hotTopics={[buildTopic({ type: 'UNKNOWN', label: 'UNKNOWN TOPIC' })]}
        />,
      );
      const pill = screen.getByTestId('hot-topic-pill-UNKNOWN');
      expect(pill.style.borderColor).toBe('rgba(255, 255, 255, 0.1)');
      expect(pill.style.background).toBe('rgba(255, 255, 255, 0.04)');
      const label = screen.getByText('UNKNOWN TOPIC');
      expect(label.style.color).toBe('rgba(255, 255, 255, 0.7)');
    });
  });

  describe('lite user visual styling', () => {
    it('applies reduced opacity for lite users', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} isLiteUser />);
      const pill = screen.getByTestId('hot-topic-pill-BLUEBELL');
      expect(pill.style.opacity).toBe('0.45');
    });

    it('applies full opacity for regular users', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      const pill = screen.getByTestId('hot-topic-pill-BLUEBELL');
      expect(pill.style.opacity).toBe('1');
    });

    it('disables pointer events for lite users', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} isLiteUser />);
      const pill = screen.getByTestId('hot-topic-pill-BLUEBELL');
      expect(pill.style.pointerEvents).toBe('none');
    });

    it('enables pointer events for regular users', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      const pill = screen.getByTestId('hot-topic-pill-BLUEBELL');
      expect(pill.style.pointerEvents).toBe('auto');
    });

    it('uses default cursor for lite users', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} isLiteUser />);
      const pill = screen.getByTestId('hot-topic-pill-BLUEBELL');
      expect(pill.style.cursor).toBe('default');
    });

    it('uses pointer cursor for regular users', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      const pill = screen.getByTestId('hot-topic-pill-BLUEBELL');
      expect(pill.style.cursor).toBe('pointer');
    });
  });

  describe('onTopicTap guard conditions', () => {
    it('calls onTopicTap exactly once per click', () => {
      const onTap = vi.fn();
      render(<HotTopicStrip hotTopics={[buildTopic()]} onTopicTap={onTap} />);
      fireEvent.click(screen.getByTestId('hot-topic-pill-BLUEBELL'));
      expect(onTap).toHaveBeenCalledTimes(1);
    });

    it('does not throw when onTopicTap is null and pill is clicked', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} onTopicTap={null} />);
      expect(() => {
        fireEvent.click(screen.getByTestId('hot-topic-pill-BLUEBELL'));
      }).not.toThrow();
    });

    it('does not throw when onTopicTap is omitted and pill is clicked', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      expect(() => {
        fireEvent.click(screen.getByTestId('hot-topic-pill-BLUEBELL'));
      }).not.toThrow();
    });

    it('passes the exact topic object to onTopicTap', () => {
      const onTap = vi.fn();
      const topic = buildTopic({ type: 'MIST', label: 'MIST', date: '2026-04-18' });
      render(<HotTopicStrip hotTopics={[topic]} onTopicTap={onTap} />);
      fireEvent.click(screen.getByTestId('hot-topic-pill-MIST'));
      expect(onTap).toHaveBeenCalledTimes(1);
      expect(onTap.mock.calls[0][0]).toBe(topic);
    });
  });

  describe('infotip content', () => {
    it('passes the description text to InfoTip', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      fireEvent.click(screen.getByTestId('infotip-trigger'));
      expect(
        screen.getByText('Bluebells peak in sheltered woodland glades'),
      ).toBeInTheDocument();
    });

    it('renders infotip for empty string description', () => {
      render(
        <HotTopicStrip hotTopics={[buildTopic({ description: '' })]} />,
      );
      // Empty string is falsy — no infotip rendered
      expect(screen.queryByTestId('infotip-trigger')).not.toBeInTheDocument();
    });
  });

  describe('region text styling', () => {
    it('right-aligns region text', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      const regions = screen.getByText('Northumberland, The Lake District');
      expect(regions.style.textAlign).toBe('right');
    });

    it('allows region text to wrap onto multiple lines', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      const regions = screen.getByText('Northumberland, The Lake District');
      expect(regions.style.whiteSpace).toBe('normal');
    });
  });

  describe('DOM order', () => {
    it('detail text comes after title row in DOM order', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      const pill = screen.getByTestId('hot-topic-pill-BLUEBELL');
      const children = Array.from(pill.children);
      // First child is the title-row div, second is the detail span
      expect(children).toHaveLength(2);
      expect(children[0].tagName).toBe('DIV'); // title row
      expect(children[1].tagName).toBe('SPAN'); // detail
      expect(children[1].textContent).toBe(
        'Misty and still — perfect morning conditions',
      );
    });

    it('title row contains title-group and regions', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      const pill = screen.getByTestId('hot-topic-pill-BLUEBELL');
      const titleRow = pill.children[0];
      expect(titleRow.children).toHaveLength(2); // title-group + regions
      expect(titleRow.children[0].textContent).toContain('BLUEBELL CONDITIONS');
      expect(titleRow.children[1].textContent).toBe(
        'Northumberland, The Lake District',
      );
    });

    it('title row contains only title-group when no regions', () => {
      render(
        <HotTopicStrip hotTopics={[buildTopic({ regions: [] })]} />,
      );
      const pill = screen.getByTestId('hot-topic-pill-BLUEBELL');
      const titleRow = pill.children[0];
      expect(titleRow.children).toHaveLength(1); // title-group only
    });
  });

  describe('pill key uniqueness', () => {
    it('renders two pills for same type but different dates', () => {
      const topics = [
        buildTopic({ date: '2026-04-16' }),
        buildTopic({ date: '2026-04-17' }),
      ];
      // Both have type BLUEBELL — key is type-date so both render
      render(<HotTopicStrip hotTopics={topics} />);
      const strip = screen.getByTestId('hot-topic-strip');
      const pills = strip.querySelectorAll('button[data-testid^="hot-topic-pill-"]');
      expect(pills).toHaveLength(2);
    });
  });
});
