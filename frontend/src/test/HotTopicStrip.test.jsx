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

  it('renders the strip container with the grid class', () => {
    render(<HotTopicStrip hotTopics={[buildTopic()]} />);
    const strip = screen.getByTestId('hot-topic-strip');
    expect(strip).toBeInTheDocument();
    expect(strip.className).toContain('hot-topic-grid');
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

    it('renders a single region without separator', () => {
      render(
        <HotTopicStrip
          hotTopics={[buildTopic({ regions: ['Snowdonia'] })]}
        />,
      );
      expect(screen.getByText('Snowdonia')).toBeInTheDocument();
    });

    it('does not render region span when regions array is empty', () => {
      render(<HotTopicStrip hotTopics={[buildTopic({ regions: [] })]} />);
      const pill = screen.getByTestId('hot-topic-pill-BLUEBELL');
      const titleRow = pill.children[0];
      // Only the title-group div, no region span
      expect(titleRow.children).toHaveLength(1);
    });

    it('does not render region span when regions is null', () => {
      render(<HotTopicStrip hotTopics={[buildTopic({ regions: null })]} />);
      const pill = screen.getByTestId('hot-topic-pill-BLUEBELL');
      const titleRow = pill.children[0];
      expect(titleRow.children).toHaveLength(1);
    });

    it('does not render region span when regions is undefined', () => {
      render(
        <HotTopicStrip
          hotTopics={[buildTopic({ regions: undefined })]}
        />,
      );
      const pill = screen.getByTestId('hot-topic-pill-BLUEBELL');
      const titleRow = pill.children[0];
      expect(titleRow.children).toHaveLength(1);
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
    it('applies BLUEBELL accent left border and label colour', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      const pill = screen.getByTestId('hot-topic-pill-BLUEBELL');
      expect(pill.style.borderLeft).toBe('3px solid rgb(139, 92, 246)');
      const label = screen.getByText('BLUEBELL CONDITIONS');
      expect(label.style.color).toBe('rgb(139, 92, 246)');
    });

    it('applies default accent left border and label colour for unknown types', () => {
      render(
        <HotTopicStrip
          hotTopics={[buildTopic({ type: 'UNKNOWN', label: 'UNKNOWN TOPIC' })]}
        />,
      );
      const pill = screen.getByTestId('hot-topic-pill-UNKNOWN');
      expect(pill.style.borderLeft).toContain('3px solid');
      const label = screen.getByText('UNKNOWN TOPIC');
      expect(label.style.color).toBe('rgba(255, 255, 255, 0.2)');
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

    it('uses muted colour for region text', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      const regions = screen.getByText('Northumberland, The Lake District');
      expect(regions.style.color).toBe('rgba(255, 255, 255, 0.45)');
    });

    it('uses 11px font size for region text', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      const regions = screen.getByText('Northumberland, The Lake District');
      expect(regions.style.fontSize).toBe('11px');
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

  describe('pill layout styles', () => {
    it('uses column flex direction for pill content', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      const pill = screen.getByTestId('hot-topic-pill-BLUEBELL');
      expect(pill.style.flexDirection).toBe('column');
    });

    it('left-aligns pill text', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      const pill = screen.getByTestId('hot-topic-pill-BLUEBELL');
      expect(pill.style.textAlign).toBe('left');
    });

    it('title row uses space-between to separate title and regions', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      const pill = screen.getByTestId('hot-topic-pill-BLUEBELL');
      const titleRow = pill.children[0];
      expect(titleRow.style.justifyContent).toBe('space-between');
    });

    it('title-group does not shrink', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      const pill = screen.getByTestId('hot-topic-pill-BLUEBELL');
      const titleGroup = pill.children[0].children[0];
      expect(titleGroup.style.flexShrink).toBe('0');
    });
  });

  describe('title label styling', () => {
    it('renders title in uppercase', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      const label = screen.getByText('BLUEBELL CONDITIONS');
      expect(label.style.textTransform).toBe('uppercase');
    });

    it('renders title with bold weight', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      const label = screen.getByText('BLUEBELL CONDITIONS');
      expect(label.style.fontWeight).toBe('600');
    });

    it('renders title at 11px', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      const label = screen.getByText('BLUEBELL CONDITIONS');
      expect(label.style.fontSize).toBe('11px');
    });
  });

  describe('detail text styling', () => {
    it('uses muted colour for detail text', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      const detail = screen.getByText(
        'Misty and still — perfect morning conditions',
      );
      expect(detail.style.color).toBe('rgba(255, 255, 255, 0.55)');
    });

    it('uses 12px font size for detail text', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      const detail = screen.getByText(
        'Misty and still — perfect morning conditions',
      );
      expect(detail.style.fontSize).toBe('12px');
    });
  });

  describe('upsell styling', () => {
    it('renders upsell text in gold colour', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} isLiteUser />);
      const upsell = screen.getByTestId('hot-topic-upsell');
      expect(upsell.style.color).toBe('rgb(212, 168, 67)');
    });

    it('renders upsell text with bold weight', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} isLiteUser />);
      const upsell = screen.getByTestId('hot-topic-upsell');
      expect(upsell.style.fontWeight).toBe('600');
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

  describe('multi-topic click correctness', () => {
    it('clicking the second pill passes the second topic object', () => {
      const onTap = vi.fn();
      const topicA = buildTopic({ type: 'BLUEBELL', date: '2026-04-16' });
      const topicB = buildTopic({ type: 'FOG', label: 'FOG', date: '2026-04-17' });
      render(
        <HotTopicStrip hotTopics={[topicA, topicB]} onTopicTap={onTap} />,
      );
      fireEvent.click(screen.getByTestId('hot-topic-pill-FOG'));
      expect(onTap).toHaveBeenCalledTimes(1);
      expect(onTap.mock.calls[0][0]).toBe(topicB);
    });

    it('clicking the first pill does not pass the second topic', () => {
      const onTap = vi.fn();
      const topicA = buildTopic({ type: 'BLUEBELL', date: '2026-04-16' });
      const topicB = buildTopic({ type: 'FOG', label: 'FOG', date: '2026-04-17' });
      render(
        <HotTopicStrip hotTopics={[topicA, topicB]} onTopicTap={onTap} />,
      );
      fireEvent.click(screen.getByTestId('hot-topic-pill-BLUEBELL'));
      expect(onTap).toHaveBeenCalledTimes(1);
      expect(onTap.mock.calls[0][0]).toBe(topicA);
    });
  });

  describe('detail text edge cases', () => {
    it('renders empty detail span when detail is null', () => {
      render(
        <HotTopicStrip hotTopics={[buildTopic({ detail: null })]} />,
      );
      const pill = screen.getByTestId('hot-topic-pill-BLUEBELL');
      const detailSpan = pill.children[1];
      expect(detailSpan.tagName).toBe('SPAN');
      expect(detailSpan.textContent).toBe('');
    });

    it('renders empty detail span when detail is undefined', () => {
      render(
        <HotTopicStrip hotTopics={[buildTopic({ detail: undefined })]} />,
      );
      const pill = screen.getByTestId('hot-topic-pill-BLUEBELL');
      const detailSpan = pill.children[1];
      expect(detailSpan.tagName).toBe('SPAN');
      expect(detailSpan.textContent).toBe('');
    });
  });

  describe('infotip description null guard', () => {
    it('does not render infotip when description is null', () => {
      render(
        <HotTopicStrip
          hotTopics={[buildTopic({ description: null })]}
        />,
      );
      expect(screen.queryByTestId('infotip-trigger')).not.toBeInTheDocument();
    });
  });

  describe('pill container styles', () => {
    it('uses display flex on the pill', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      const pill = screen.getByTestId('hot-topic-pill-BLUEBELL');
      expect(pill.style.display).toBe('flex');
    });

    it('uses 2px gap between pill children', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      const pill = screen.getByTestId('hot-topic-pill-BLUEBELL');
      expect(pill.style.gap).toBe('2px');
    });

    it('uses 8px 14px padding on the pill', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      const pill = screen.getByTestId('hot-topic-pill-BLUEBELL');
      expect(pill.style.padding).toBe('8px 14px');
    });

    it('uses right-only border radius on the pill', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      const pill = screen.getByTestId('hot-topic-pill-BLUEBELL');
      expect(pill.style.borderRadius).toBe('0 8px 8px 0');
    });

    it('uses background transition on the pill', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      const pill = screen.getByTestId('hot-topic-pill-BLUEBELL');
      expect(pill.style.transition).toBe('background 0.15s');
    });
  });

  describe('title row styles', () => {
    it('uses display flex on the title row', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      const pill = screen.getByTestId('hot-topic-pill-BLUEBELL');
      const titleRow = pill.children[0];
      expect(titleRow.style.display).toBe('flex');
    });

    it('aligns title row items to flex-start', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      const pill = screen.getByTestId('hot-topic-pill-BLUEBELL');
      const titleRow = pill.children[0];
      expect(titleRow.style.alignItems).toBe('flex-start');
    });

    it('uses 12px gap between title group and regions', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      const pill = screen.getByTestId('hot-topic-pill-BLUEBELL');
      const titleRow = pill.children[0];
      expect(titleRow.style.gap).toBe('12px');
    });

    it('uses 4px bottom margin on the title row', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      const pill = screen.getByTestId('hot-topic-pill-BLUEBELL');
      const titleRow = pill.children[0];
      expect(titleRow.style.marginBottom).toBe('4px');
    });
  });

  describe('title group styles', () => {
    it('uses display flex on the title group', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      const pill = screen.getByTestId('hot-topic-pill-BLUEBELL');
      const titleGroup = pill.children[0].children[0];
      expect(titleGroup.style.display).toBe('flex');
    });

    it('vertically centres items in the title group', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      const pill = screen.getByTestId('hot-topic-pill-BLUEBELL');
      const titleGroup = pill.children[0].children[0];
      expect(titleGroup.style.alignItems).toBe('center');
    });

    it('uses 6px gap between label and infotip', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      const pill = screen.getByTestId('hot-topic-pill-BLUEBELL');
      const titleGroup = pill.children[0].children[0];
      expect(titleGroup.style.gap).toBe('6px');
    });
  });

  describe('label letter spacing', () => {
    it('uses 0.5px letter spacing on the label', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      const label = screen.getByText('BLUEBELL CONDITIONS');
      expect(label.style.letterSpacing).toBe('0.5px');
    });
  });

  describe('region line height', () => {
    it('uses 1.3 line height on region text', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      const regions = screen.getByText('Northumberland, The Lake District');
      expect(regions.style.lineHeight).toBe('1.3');
    });
  });

  describe('upsell layout styles', () => {
    it('uses display flex on the upsell badge', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} isLiteUser />);
      const upsell = screen.getByTestId('hot-topic-upsell');
      expect(upsell.style.display).toBe('flex');
    });

    it('vertically centres the upsell text', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} isLiteUser />);
      const upsell = screen.getByTestId('hot-topic-upsell');
      expect(upsell.style.alignItems).toBe('center');
    });

    it('uses 11px font size on the upsell badge', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} isLiteUser />);
      const upsell = screen.getByTestId('hot-topic-upsell');
      expect(upsell.style.fontSize).toBe('11px');
    });

    it('prevents upsell text from wrapping', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} isLiteUser />);
      const upsell = screen.getByTestId('hot-topic-upsell');
      expect(upsell.style.whiteSpace).toBe('nowrap');
    });

    it('uses 0 8px padding on the upsell badge', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} isLiteUser />);
      const upsell = screen.getByTestId('hot-topic-upsell');
      expect(upsell.style.padding).toBe('0px 8px');
    });
  });

  describe('pill border format', () => {
    it('uses 3px solid left border with accent colour for BLUEBELL', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      const pill = screen.getByTestId('hot-topic-pill-BLUEBELL');
      expect(pill.style.borderLeft).toBe('3px solid rgb(139, 92, 246)');
    });

    it('uses 3px solid left border with default accent for unknown type', () => {
      render(
        <HotTopicStrip
          hotTopics={[buildTopic({ type: 'UNKNOWN', label: 'UNKNOWN' })]}
        />,
      );
      const pill = screen.getByTestId('hot-topic-pill-UNKNOWN');
      expect(pill.style.borderLeft).toBe('3px solid rgba(255, 255, 255, 0.2)');
    });

    it('uses 1px solid outer border derived from accent colour for BLUEBELL', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      const pill = screen.getByTestId('hot-topic-pill-BLUEBELL');
      // borderLeft overrides the shorthand left side, so check top/right/bottom
      expect(pill.style.borderTop).toBe('1px solid rgba(139, 92, 246, 0.2)');
      expect(pill.style.borderRight).toBe('1px solid rgba(139, 92, 246, 0.2)');
      expect(pill.style.borderBottom).toBe('1px solid rgba(139, 92, 246, 0.2)');
    });

    it('uses accent-derived background for BLUEBELL', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      const pill = screen.getByTestId('hot-topic-pill-BLUEBELL');
      // #8b5cf60F → rgba(139, 92, 246, 0.06)
      expect(pill.style.background).toBe('rgba(139, 92, 246, 0.06)');
    });
  });

  describe('emoji rendering', () => {
    it('renders emoji span for BLUEBELL pill', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      const pill = screen.getByTestId('hot-topic-pill-BLUEBELL');
      const titleGroup = pill.children[0].children[0];
      // First child is emoji span, second is label span
      expect(titleGroup.children[0].textContent).toBe('\uD83D\uDC9C');
    });

    it('renders emoji span for AURORA pill with correct content', () => {
      render(
        <HotTopicStrip
          hotTopics={[buildTopic({ type: 'AURORA', label: 'AURORA ALERT' })]}
        />,
      );
      const pill = screen.getByTestId('hot-topic-pill-AURORA');
      const titleGroup = pill.children[0].children[0];
      expect(titleGroup.children[0].textContent).toBe('\uD83C\uDF0C');
    });

    it('renders emoji span for KING_TIDE pill with correct content', () => {
      render(
        <HotTopicStrip
          hotTopics={[buildTopic({ type: 'KING_TIDE', label: 'KING TIDE' })]}
        />,
      );
      const pill = screen.getByTestId('hot-topic-pill-KING_TIDE');
      const titleGroup = pill.children[0].children[0];
      expect(titleGroup.children[0].textContent).toBe('\uD83D\uDC51');
    });

    it('renders emoji span for STORM_SURGE pill with correct content', () => {
      render(
        <HotTopicStrip
          hotTopics={[buildTopic({ type: 'STORM_SURGE', label: 'STORM SURGE' })]}
        />,
      );
      const pill = screen.getByTestId('hot-topic-pill-STORM_SURGE');
      const titleGroup = pill.children[0].children[0];
      expect(titleGroup.children[0].textContent).toBe('\u26A1');
    });

    it('renders emoji span for DUST pill with correct content', () => {
      render(
        <HotTopicStrip
          hotTopics={[buildTopic({ type: 'DUST', label: 'SAHARA DUST' })]}
        />,
      );
      const pill = screen.getByTestId('hot-topic-pill-DUST');
      const titleGroup = pill.children[0].children[0];
      expect(titleGroup.children[0].textContent).toBe('\uD83C\uDF05');
    });

    it('renders emoji span for SUPERMOON pill with correct content', () => {
      render(
        <HotTopicStrip
          hotTopics={[buildTopic({ type: 'SUPERMOON', label: 'SUPERMOON' })]}
        />,
      );
      const pill = screen.getByTestId('hot-topic-pill-SUPERMOON');
      const titleGroup = pill.children[0].children[0];
      expect(titleGroup.children[0].textContent).toBe('\uD83C\uDF15');
    });

    it('renders emoji span for SPRING_TIDE pill with correct content', () => {
      render(
        <HotTopicStrip
          hotTopics={[buildTopic({ type: 'SPRING_TIDE', label: 'SPRING TIDE' })]}
        />,
      );
      const pill = screen.getByTestId('hot-topic-pill-SPRING_TIDE');
      const titleGroup = pill.children[0].children[0];
      expect(titleGroup.children[0].textContent).toBe('\uD83C\uDF0A');
    });

    it('renders emoji span for SNOW_FRESH pill with correct content', () => {
      render(
        <HotTopicStrip
          hotTopics={[buildTopic({ type: 'SNOW_FRESH', label: 'FRESH SNOW' })]}
        />,
      );
      const pill = screen.getByTestId('hot-topic-pill-SNOW_FRESH');
      const titleGroup = pill.children[0].children[0];
      expect(titleGroup.children[0].textContent).toBe('\u2744\uFE0F');
    });

    it('renders emoji span for NLC pill with correct content', () => {
      render(
        <HotTopicStrip
          hotTopics={[buildTopic({ type: 'NLC', label: 'NLC SEASON' })]}
        />,
      );
      const pill = screen.getByTestId('hot-topic-pill-NLC');
      const titleGroup = pill.children[0].children[0];
      expect(titleGroup.children[0].textContent).toBe('\u2728');
    });

    it('renders emoji span for METEOR pill with correct content', () => {
      render(
        <HotTopicStrip
          hotTopics={[buildTopic({ type: 'METEOR', label: 'METEOR SHOWER' })]}
        />,
      );
      const pill = screen.getByTestId('hot-topic-pill-METEOR');
      const titleGroup = pill.children[0].children[0];
      expect(titleGroup.children[0].textContent).toBe('\u2604\uFE0F');
    });

    it('renders emoji span for EQUINOX pill with correct content', () => {
      render(
        <HotTopicStrip
          hotTopics={[buildTopic({ type: 'EQUINOX', label: 'EQUINOX' })]}
        />,
      );
      const pill = screen.getByTestId('hot-topic-pill-EQUINOX');
      const titleGroup = pill.children[0].children[0];
      expect(titleGroup.children[0].textContent).toBe('\u2600\uFE0F');
    });

    it('renders emoji span for CLEARANCE pill with correct content', () => {
      render(
        <HotTopicStrip
          hotTopics={[buildTopic({ type: 'CLEARANCE', label: 'CLEARANCE' })]}
        />,
      );
      const pill = screen.getByTestId('hot-topic-pill-CLEARANCE');
      const titleGroup = pill.children[0].children[0];
      expect(titleGroup.children[0].textContent).toBe('\u26C5');
    });

    it('does not render emoji span for unknown topic types', () => {
      render(
        <HotTopicStrip
          hotTopics={[buildTopic({ type: 'UNKNOWN', label: 'UNKNOWN', description: undefined })]}
        />,
      );
      const pill = screen.getByTestId('hot-topic-pill-UNKNOWN');
      const titleGroup = pill.children[0].children[0];
      // First child should be the label span, not an emoji
      expect(titleGroup.children[0].style.fontSize).toBe('11px');
      expect(titleGroup.children).toHaveLength(1);
    });

    it('uses 14px font size on the emoji span', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      const pill = screen.getByTestId('hot-topic-pill-BLUEBELL');
      const emojiSpan = pill.children[0].children[0].children[0];
      expect(emojiSpan.style.fontSize).toBe('14px');
    });

    it('uses lineHeight 1 on the emoji span', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      const pill = screen.getByTestId('hot-topic-pill-BLUEBELL');
      const emojiSpan = pill.children[0].children[0].children[0];
      expect(emojiSpan.style.lineHeight).toBe('1');
    });
  });

  describe('INVERSION emoji rotation', () => {
    it('applies rotate(180deg) transform to INVERSION emoji', () => {
      render(
        <HotTopicStrip
          hotTopics={[buildTopic({ type: 'INVERSION', label: 'CLOUD INVERSION' })]}
        />,
      );
      const pill = screen.getByTestId('hot-topic-pill-INVERSION');
      const emojiSpan = pill.children[0].children[0].children[0];
      expect(emojiSpan.textContent).toBe('\u2601\uFE0F');
      expect(emojiSpan.style.transform).toBe('rotate(180deg)');
    });

    it('applies display inline-block to INVERSION emoji for transform to work', () => {
      render(
        <HotTopicStrip
          hotTopics={[buildTopic({ type: 'INVERSION', label: 'CLOUD INVERSION' })]}
        />,
      );
      const pill = screen.getByTestId('hot-topic-pill-INVERSION');
      const emojiSpan = pill.children[0].children[0].children[0];
      expect(emojiSpan.style.display).toBe('inline-block');
    });

    it('does not apply rotation to BLUEBELL emoji', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      const pill = screen.getByTestId('hot-topic-pill-BLUEBELL');
      const emojiSpan = pill.children[0].children[0].children[0];
      expect(emojiSpan.style.transform).toBe('');
    });

    it('does not apply rotation to AURORA emoji', () => {
      render(
        <HotTopicStrip
          hotTopics={[buildTopic({ type: 'AURORA', label: 'AURORA ALERT' })]}
        />,
      );
      const pill = screen.getByTestId('hot-topic-pill-AURORA');
      const emojiSpan = pill.children[0].children[0].children[0];
      expect(emojiSpan.style.transform).toBe('');
    });
  });

  describe('title brightness filter', () => {
    it('applies brightness(1.4) filter to BLUEBELL label', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      const label = screen.getByText('BLUEBELL CONDITIONS');
      expect(label.style.filter).toBe('brightness(1.4)');
    });

    it('applies brightness(1.4) filter to unknown type label', () => {
      render(
        <HotTopicStrip
          hotTopics={[buildTopic({ type: 'UNKNOWN', label: 'UNKNOWN' })]}
        />,
      );
      const label = screen.getByText('UNKNOWN');
      expect(label.style.filter).toBe('brightness(1.4)');
    });
  });

  describe('infotip accent wrapper', () => {
    it('wraps infotip in a span with accent colour at 99 hex opacity', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      const infotip = screen.getByTestId('infotip-trigger');
      // InfoTip trigger is inside <span className="relative ..."> inside our colour wrapper <span>
      const colourWrapper = infotip.closest('span.relative').parentElement;
      expect(colourWrapper.tagName).toBe('SPAN');
      expect(colourWrapper.style.color).toBe('rgba(139, 92, 246, 0.6)');
    });

    it('uses AURORA accent colour on infotip wrapper for AURORA pill', () => {
      render(
        <HotTopicStrip
          hotTopics={[buildTopic({ type: 'AURORA', label: 'AURORA ALERT' })]}
        />,
      );
      const infotip = screen.getByTestId('infotip-trigger');
      const colourWrapper = infotip.closest('span.relative').parentElement;
      expect(colourWrapper.style.color).toBe('rgba(74, 222, 128, 0.6)');
    });
  });

  describe('per-type colour differentiation', () => {
    it('AURORA pill uses green accent (#4ade80)', () => {
      render(
        <HotTopicStrip
          hotTopics={[buildTopic({ type: 'AURORA', label: 'AURORA ALERT' })]}
        />,
      );
      const pill = screen.getByTestId('hot-topic-pill-AURORA');
      expect(pill.style.borderLeft).toBe('3px solid rgb(74, 222, 128)');
      const label = screen.getByText('AURORA ALERT');
      expect(label.style.color).toBe('rgb(74, 222, 128)');
    });

    it('KING_TIDE pill uses royal blue accent (#3b82f6)', () => {
      render(
        <HotTopicStrip
          hotTopics={[buildTopic({ type: 'KING_TIDE', label: 'KING TIDE' })]}
        />,
      );
      const pill = screen.getByTestId('hot-topic-pill-KING_TIDE');
      expect(pill.style.borderLeft).toBe('3px solid rgb(59, 130, 246)');
      const label = screen.getByText('KING TIDE');
      expect(label.style.color).toBe('rgb(59, 130, 246)');
    });

    it('STORM_SURGE pill uses amber accent (#f59e0b)', () => {
      render(
        <HotTopicStrip
          hotTopics={[buildTopic({ type: 'STORM_SURGE', label: 'STORM SURGE' })]}
        />,
      );
      const pill = screen.getByTestId('hot-topic-pill-STORM_SURGE');
      expect(pill.style.borderLeft).toBe('3px solid rgb(245, 158, 11)');
      const label = screen.getByText('STORM SURGE');
      expect(label.style.color).toBe('rgb(245, 158, 11)');
    });

    it('INVERSION pill uses slate accent (#94a3b8)', () => {
      render(
        <HotTopicStrip
          hotTopics={[buildTopic({ type: 'INVERSION', label: 'CLOUD INVERSION' })]}
        />,
      );
      const pill = screen.getByTestId('hot-topic-pill-INVERSION');
      expect(pill.style.borderLeft).toBe('3px solid rgb(148, 163, 184)');
      const label = screen.getByText('CLOUD INVERSION');
      expect(label.style.color).toBe('rgb(148, 163, 184)');
    });

    it('DUST pill uses warm orange accent (#f97316)', () => {
      render(
        <HotTopicStrip
          hotTopics={[buildTopic({ type: 'DUST', label: 'SAHARA DUST' })]}
        />,
      );
      const pill = screen.getByTestId('hot-topic-pill-DUST');
      expect(pill.style.borderLeft).toBe('3px solid rgb(249, 115, 22)');
      const label = screen.getByText('SAHARA DUST');
      expect(label.style.color).toBe('rgb(249, 115, 22)');
    });

    it('SUPERMOON pill uses golden accent (#fbbf24)', () => {
      render(
        <HotTopicStrip
          hotTopics={[buildTopic({ type: 'SUPERMOON', label: 'SUPERMOON' })]}
        />,
      );
      const pill = screen.getByTestId('hot-topic-pill-SUPERMOON');
      expect(pill.style.borderLeft).toBe('3px solid rgb(251, 191, 36)');
      const label = screen.getByText('SUPERMOON');
      expect(label.style.color).toBe('rgb(251, 191, 36)');
    });

    it('SPRING_TIDE pill uses lighter blue accent (#60a5fa)', () => {
      render(
        <HotTopicStrip
          hotTopics={[buildTopic({ type: 'SPRING_TIDE', label: 'SPRING TIDE' })]}
        />,
      );
      const pill = screen.getByTestId('hot-topic-pill-SPRING_TIDE');
      expect(pill.style.borderLeft).toBe('3px solid rgb(96, 165, 250)');
    });

    it('each topic type in a mixed set has a distinct left border colour', () => {
      const topics = [
        buildTopic({ type: 'BLUEBELL', date: '2026-04-16' }),
        buildTopic({ type: 'AURORA', label: 'AURORA', date: '2026-04-17' }),
        buildTopic({ type: 'KING_TIDE', label: 'KING TIDE', date: '2026-04-18' }),
        buildTopic({ type: 'DUST', label: 'DUST', date: '2026-04-19' }),
      ];
      render(<HotTopicStrip hotTopics={topics} />);
      const colours = topics.map((t) => {
        const pill = screen.getByTestId(`hot-topic-pill-${t.type}`);
        return pill.style.borderLeft;
      });
      const unique = new Set(colours);
      expect(unique.size).toBe(4);
    });
  });

  describe('SNOW_MIST and SNOW_TOPS colour differentiation', () => {
    it('SNOW_MIST uses cool grey (#cbd5e1) distinct from SNOW_TOPS pale blue (#bfdbfe)', () => {
      const topics = [
        buildTopic({ type: 'SNOW_MIST', label: 'SNOW MIST', date: '2026-04-16' }),
        buildTopic({ type: 'SNOW_TOPS', label: 'SNOW TOPS', date: '2026-04-17' }),
      ];
      render(<HotTopicStrip hotTopics={topics} />);
      const mistPill = screen.getByTestId('hot-topic-pill-SNOW_MIST');
      const topsPill = screen.getByTestId('hot-topic-pill-SNOW_TOPS');
      expect(mistPill.style.borderLeft).toBe('3px solid rgb(203, 213, 225)');
      expect(topsPill.style.borderLeft).toBe('3px solid rgb(191, 219, 254)');
      expect(mistPill.style.borderLeft).not.toBe(topsPill.style.borderLeft);
    });
  });
});
