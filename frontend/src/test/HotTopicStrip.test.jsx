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
      // Title-group and regions wrapper are siblings inside the title row div
      const titleRow = title.closest('div').parentElement;
      expect(titleRow.contains(regions)).toBe(true);
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
      // Title row has title-group + regions/chevron wrapper; wrapper should be empty
      const regionsWrapper = titleRow.children[1];
      expect(regionsWrapper.children).toHaveLength(0);
    });

    it('does not render region span when regions is null', () => {
      render(<HotTopicStrip hotTopics={[buildTopic({ regions: null })]} />);
      const pill = screen.getByTestId('hot-topic-pill-BLUEBELL');
      const titleRow = pill.children[0];
      const regionsWrapper = titleRow.children[1];
      expect(regionsWrapper.children).toHaveLength(0);
    });

    it('does not render region span when regions is undefined', () => {
      render(
        <HotTopicStrip
          hotTopics={[buildTopic({ regions: undefined })]}
        />,
      );
      const pill = screen.getByTestId('hot-topic-pill-BLUEBELL');
      const titleRow = pill.children[0];
      const regionsWrapper = titleRow.children[1];
      expect(regionsWrapper.children).toHaveLength(0);
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

    it('title row contains title-group and regions wrapper', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      const pill = screen.getByTestId('hot-topic-pill-BLUEBELL');
      const titleRow = pill.children[0];
      expect(titleRow.children).toHaveLength(2); // title-group + regions/chevron wrapper
      expect(titleRow.children[0].textContent).toContain('BLUEBELL CONDITIONS');
      expect(titleRow.children[1].textContent).toContain(
        'Northumberland, The Lake District',
      );
    });

    it('title row regions wrapper is empty when no regions', () => {
      render(
        <HotTopicStrip hotTopics={[buildTopic({ regions: [] })]} />,
      );
      const pill = screen.getByTestId('hot-topic-pill-BLUEBELL');
      const titleRow = pill.children[0];
      expect(titleRow.children).toHaveLength(2); // title-group + empty wrapper
      expect(titleRow.children[1].children).toHaveLength(0);
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

  // ────── AURORA expanded card ──────

  describe('HotTopicStrip — AURORA expanded card', () => {
    const auroraTopic = {
      type: 'AURORA',
      label: 'AURORA TONIGHT',
      detail: 'Moderate activity tonight',
      date: '2026-04-17',
      priority: 1,
      regions: ['Northumberland'],
      description: null,
    };

    const auroraData = {
      alertLevel: 'MODERATE',
      kp: 5.3,
      clearLocationCount: 2,
      moonPhase: 'WAXING_GIBBOUS',
      moonIlluminationPct: 72,
      windowQuality: 'DARK_THEN_MOONLIT',
      moonRiseTime: '2026-04-17T23:15:00',
      moonSetTime: null,
      regions: [{
        regionName: 'Northumberland',
        verdict: 'GO',
        clearLocationCount: 1,
        totalDarkSkyLocations: 2,
        bestBortleClass: 2,
        glossHeadline: 'Clear skies expected',
        glossDetail: 'High pressure building from the west',
        locations: [
          {
            locationName: 'Kielder',
            bortleClass: 2,
            clear: true,
            cloudPercent: 15,
            temperatureCelsius: 4.2,
            windSpeedMs: 2.5,
            weatherCode: 0,
          },
          {
            locationName: 'Bamburgh',
            bortleClass: 4,
            clear: false,
            cloudPercent: 65,
            temperatureCelsius: 7.1,
            windSpeedMs: 5.8,
            weatherCode: 3,
          },
        ],
      }],
    };

    it('renders expand chevron on AURORA pill when aurora data is present', () => {
      render(
        <HotTopicStrip
          hotTopics={[auroraTopic]}
          auroraTonight={auroraData}
        />,
      );
      expect(screen.getByTestId('expand-chevron-AURORA')).toBeInTheDocument();
    });

    it('does not render expand chevron on non-AURORA pill', () => {
      render(<HotTopicStrip hotTopics={[buildTopic()]} />);
      expect(screen.queryByTestId('expand-chevron-AURORA')).not.toBeInTheDocument();
    });

    it('does not render expand chevron when aurora data is null', () => {
      render(
        <HotTopicStrip
          hotTopics={[auroraTopic]}
          auroraTonight={null}
        />,
      );
      expect(screen.queryByTestId('expand-chevron-AURORA')).not.toBeInTheDocument();
    });

    it('clicking AURORA pill toggles expanded card', () => {
      render(
        <HotTopicStrip
          hotTopics={[auroraTopic]}
          auroraTonight={auroraData}
        />,
      );
      expect(screen.queryByTestId('aurora-expanded-card')).not.toBeInTheDocument();

      fireEvent.click(screen.getByTestId('hot-topic-pill-AURORA'));
      expect(screen.getByTestId('aurora-expanded-card')).toBeInTheDocument();

      // Second click collapses
      fireEvent.click(screen.getByTestId('hot-topic-pill-AURORA'));
      expect(screen.queryByTestId('aurora-expanded-card')).not.toBeInTheDocument();
    });

    it('expanded card shows alert level', () => {
      render(
        <HotTopicStrip
          hotTopics={[auroraTopic]}
          auroraTonight={auroraData}
        />,
      );
      fireEvent.click(screen.getByTestId('hot-topic-pill-AURORA'));
      const alert = screen.getByTestId('aurora-expanded-alert');
      expect(alert.textContent).toBe('Moderate aurora');
    });

    it('expanded card shows Kp value', () => {
      render(
        <HotTopicStrip
          hotTopics={[auroraTopic]}
          auroraTonight={auroraData}
        />,
      );
      fireEvent.click(screen.getByTestId('hot-topic-pill-AURORA'));
      expect(screen.getByText('Kp 5.3')).toBeInTheDocument();
    });

    it('expanded card shows moon indicator with window quality transition', () => {
      render(
        <HotTopicStrip
          hotTopics={[auroraTopic]}
          auroraTonight={auroraData}
        />,
      );
      fireEvent.click(screen.getByTestId('hot-topic-pill-AURORA'));
      const moon = screen.getByTestId('aurora-expanded-moon');
      expect(moon).toBeInTheDocument();
      expect(moon.textContent).toContain('72%');
      expect(moon.textContent).toContain('dark until');
    });

    it('expanded card shows region name and gloss headline', () => {
      render(
        <HotTopicStrip
          hotTopics={[auroraTopic]}
          auroraTonight={auroraData}
        />,
      );
      fireEvent.click(screen.getByTestId('hot-topic-pill-AURORA'));
      const card = screen.getByTestId('aurora-expanded-card');
      expect(card.textContent).toContain('Northumberland');
      expect(card.textContent).toContain('Clear skies expected');
    });

    it('expanded card shows location rows with Bortle badges and clear/cloudy dots', () => {
      render(
        <HotTopicStrip
          hotTopics={[auroraTopic]}
          auroraTonight={auroraData}
        />,
      );
      fireEvent.click(screen.getByTestId('hot-topic-pill-AURORA'));
      const locationRows = screen.getAllByTestId('aurora-expanded-location');
      expect(locationRows).toHaveLength(2);
      // Locations are sorted by bortleClass ascending
      expect(locationRows[0].textContent).toContain('Kielder');
      expect(locationRows[0].textContent).toContain('Bortle 2');
      expect(locationRows[1].textContent).toContain('Bamburgh');
      expect(locationRows[1].textContent).toContain('Bortle 4');
    });

    it('expanded card shows weather details per location', () => {
      render(
        <HotTopicStrip
          hotTopics={[auroraTopic]}
          auroraTonight={auroraData}
        />,
      );
      fireEvent.click(screen.getByTestId('hot-topic-pill-AURORA'));
      const locationRows = screen.getAllByTestId('aurora-expanded-location');
      // Kielder: 15% cloud, 4°C, 6mph (2.5 * 2.237 ≈ 6)
      expect(locationRows[0].textContent).toContain('15% cloud');
      expect(locationRows[0].textContent).toContain('4°C');
      expect(locationRows[0].textContent).toContain('6mph');
      // Bamburgh: 65% cloud, 7°C, 13mph (5.8 * 2.237 ≈ 13)
      expect(locationRows[1].textContent).toContain('65% cloud');
      expect(locationRows[1].textContent).toContain('7°C');
      expect(locationRows[1].textContent).toContain('13mph');
    });

    it('LITE user cannot expand AURORA pill', () => {
      render(
        <HotTopicStrip
          hotTopics={[auroraTopic]}
          auroraTonight={auroraData}
          isLiteUser
        />,
      );
      fireEvent.click(screen.getByTestId('hot-topic-pill-AURORA'));
      expect(screen.queryByTestId('aurora-expanded-card')).not.toBeInTheDocument();
    });

    it('LITE user does not see expand chevron', () => {
      render(
        <HotTopicStrip
          hotTopics={[auroraTopic]}
          auroraTonight={auroraData}
          isLiteUser
        />,
      );
      expect(screen.queryByTestId('expand-chevron-AURORA')).not.toBeInTheDocument();
    });

    it('only one aurora pill can be expanded at a time', () => {
      const tonightTopic = { ...auroraTopic, detail: 'Moderate activity tonight' };
      const tomorrowTopic = {
        ...auroraTopic,
        label: 'AURORA TOMORROW',
        detail: 'Minor activity tomorrow night',
        date: '2026-04-18',
      };
      const auroraTomorrow = { ...auroraData, alertLevel: 'MINOR', kp: 4.0 };

      render(
        <HotTopicStrip
          hotTopics={[tonightTopic, tomorrowTopic]}
          auroraTonight={auroraData}
          auroraTomorrow={auroraTomorrow}
        />,
      );

      const pills = screen.getAllByTestId('hot-topic-pill-AURORA');
      // Expand first
      fireEvent.click(pills[0]);
      expect(screen.getAllByTestId('aurora-expanded-card')).toHaveLength(1);

      // Expand second — first should collapse
      fireEvent.click(pills[1]);
      expect(screen.getAllByTestId('aurora-expanded-card')).toHaveLength(1);
    });

    it('renders AURORA pill as normal (no expand) when aurora data is null', () => {
      const onTap = vi.fn();
      render(
        <HotTopicStrip
          hotTopics={[auroraTopic]}
          auroraTonight={null}
          onTopicTap={onTap}
        />,
      );
      expect(screen.getByTestId('hot-topic-pill-AURORA')).toBeInTheDocument();
      expect(screen.queryByTestId('expand-chevron-AURORA')).not.toBeInTheDocument();
      // Clicking should not expand and should not call onTopicTap (AURORA pills never call onTopicTap)
      fireEvent.click(screen.getByTestId('hot-topic-pill-AURORA'));
      expect(screen.queryByTestId('aurora-expanded-card')).not.toBeInTheDocument();
      expect(onTap).not.toHaveBeenCalled();
    });

    it('AURORA pill click does not call onTopicTap', () => {
      const onTap = vi.fn();
      render(
        <HotTopicStrip
          hotTopics={[auroraTopic]}
          auroraTonight={auroraData}
          onTopicTap={onTap}
        />,
      );
      fireEvent.click(screen.getByTestId('hot-topic-pill-AURORA'));
      expect(onTap).not.toHaveBeenCalled();
    });

    it('expanded card shows clear dot for clear location and cloudy dot for cloudy location', () => {
      render(
        <HotTopicStrip
          hotTopics={[auroraTopic]}
          auroraTonight={auroraData}
        />,
      );
      fireEvent.click(screen.getByTestId('hot-topic-pill-AURORA'));
      const locationRows = screen.getAllByTestId('aurora-expanded-location');
      // Kielder is clear
      const kielderDot = locationRows[0].querySelector('span[title="Clear skies"]');
      expect(kielderDot).toBeTruthy();
      expect(kielderDot.style.background).toBe('rgb(74, 222, 128)');
      // Bamburgh is cloudy
      const bamburghDot = locationRows[1].querySelector('span[title="Cloudy"]');
      expect(bamburghDot).toBeTruthy();
      expect(bamburghDot.style.background).toBe('rgba(248, 113, 113, 0.6)');
    });

    it('expanded card shows clear percentage per region', () => {
      render(
        <HotTopicStrip
          hotTopics={[auroraTopic]}
          auroraTonight={auroraData}
        />,
      );
      fireEvent.click(screen.getByTestId('hot-topic-pill-AURORA'));
      // 1 clear out of 2 total = 50%
      expect(screen.getByText('50% clear')).toBeInTheDocument();
    });

    it('expanded card shows best Bortle badge per region', () => {
      render(
        <HotTopicStrip
          hotTopics={[auroraTopic]}
          auroraTonight={auroraData}
        />,
      );
      fireEvent.click(screen.getByTestId('hot-topic-pill-AURORA'));
      // bestBortleClass=2 → "Truly dark · Bortle 2"
      expect(screen.getByText(/Truly dark · Bortle 2/)).toBeInTheDocument();
    });

    // ── resolveAuroraData logic ──

    it('resolves auroraTonight when topic detail contains "tonight"', () => {
      const tonightData = { ...auroraData, alertLevel: 'STRONG', kp: 7.0 };
      const tomorrowData = { ...auroraData, alertLevel: 'MINOR', kp: 3.0 };
      render(
        <HotTopicStrip
          hotTopics={[{ ...auroraTopic, detail: 'Strong activity tonight' }]}
          auroraTonight={tonightData}
          auroraTomorrow={tomorrowData}
        />,
      );
      fireEvent.click(screen.getByTestId('hot-topic-pill-AURORA'));
      const alert = screen.getByTestId('aurora-expanded-alert');
      expect(alert.textContent).toBe('Strong aurora');
      expect(screen.getByText('Kp 7.0')).toBeInTheDocument();
    });

    it('resolves auroraTomorrow when topic detail contains "tomorrow"', () => {
      const tonightData = { ...auroraData, alertLevel: 'STRONG', kp: 7.0 };
      const tomorrowData = { ...auroraData, alertLevel: 'MINOR', kp: 3.0 };
      render(
        <HotTopicStrip
          hotTopics={[{ ...auroraTopic, detail: 'Minor activity tomorrow night' }]}
          auroraTonight={tonightData}
          auroraTomorrow={tomorrowData}
        />,
      );
      fireEvent.click(screen.getByTestId('hot-topic-pill-AURORA'));
      const alert = screen.getByTestId('aurora-expanded-alert');
      expect(alert.textContent).toBe('Minor aurora');
      expect(screen.getByText('Kp 3.0')).toBeInTheDocument();
    });

    it('falls back to auroraTonight when detail contains neither keyword', () => {
      const tonightData = { ...auroraData, alertLevel: 'MODERATE', kp: 5.0 };
      const tomorrowData = { ...auroraData, alertLevel: 'STRONG', kp: 8.0 };
      render(
        <HotTopicStrip
          hotTopics={[{ ...auroraTopic, detail: 'Aurora activity expected' }]}
          auroraTonight={tonightData}
          auroraTomorrow={tomorrowData}
        />,
      );
      fireEvent.click(screen.getByTestId('hot-topic-pill-AURORA'));
      // Fallback prefers auroraTonight
      expect(screen.getByText('Kp 5.0')).toBeInTheDocument();
    });

    it('falls back to auroraTomorrow when auroraTonight is null and detail has no keyword', () => {
      const tomorrowData = { ...auroraData, alertLevel: 'MINOR', kp: 4.0 };
      render(
        <HotTopicStrip
          hotTopics={[{ ...auroraTopic, detail: 'Possible aurora' }]}
          auroraTonight={null}
          auroraTomorrow={tomorrowData}
        />,
      );
      fireEvent.click(screen.getByTestId('hot-topic-pill-AURORA'));
      expect(screen.getByText('Kp 4.0')).toBeInTheDocument();
    });

    it('handles null topic.detail without crashing', () => {
      render(
        <HotTopicStrip
          hotTopics={[{ ...auroraTopic, detail: null }]}
          auroraTonight={auroraData}
        />,
      );
      fireEvent.click(screen.getByTestId('hot-topic-pill-AURORA'));
      expect(screen.getByTestId('aurora-expanded-card')).toBeInTheDocument();
    });

    // ── AuroraExpandedCard edge cases ──

    it('renders expanded card header with no location rows when regions is empty', () => {
      const emptyRegions = { ...auroraData, regions: [] };
      render(
        <HotTopicStrip
          hotTopics={[auroraTopic]}
          auroraTonight={emptyRegions}
        />,
      );
      fireEvent.click(screen.getByTestId('hot-topic-pill-AURORA'));
      expect(screen.getByTestId('aurora-expanded-card')).toBeInTheDocument();
      expect(screen.getByTestId('aurora-expanded-alert')).toBeInTheDocument();
      expect(screen.queryAllByTestId('aurora-expanded-location')).toHaveLength(0);
    });

    it('excludes locations with null bortleClass from the location list', () => {
      const mixedBortle = {
        ...auroraData,
        regions: [{
          ...auroraData.regions[0],
          locations: [
            { locationName: 'Kielder', bortleClass: 2, clear: true, cloudPercent: 10 },
            { locationName: 'Unknown Site', bortleClass: null, clear: true, cloudPercent: 5 },
          ],
        }],
      };
      render(
        <HotTopicStrip
          hotTopics={[auroraTopic]}
          auroraTonight={mixedBortle}
        />,
      );
      fireEvent.click(screen.getByTestId('hot-topic-pill-AURORA'));
      const locationRows = screen.getAllByTestId('aurora-expanded-location');
      expect(locationRows).toHaveLength(1);
      expect(locationRows[0].textContent).toContain('Kielder');
      expect(locationRows[0].textContent).not.toContain('Unknown Site');
    });

    it('does not render moon section when moonPhase is null', () => {
      const noMoon = { ...auroraData, moonPhase: null, moonIlluminationPct: null };
      render(
        <HotTopicStrip
          hotTopics={[auroraTopic]}
          auroraTonight={noMoon}
        />,
      );
      fireEvent.click(screen.getByTestId('hot-topic-pill-AURORA'));
      expect(screen.getByTestId('aurora-expanded-card')).toBeInTheDocument();
      expect(screen.queryByTestId('aurora-expanded-moon')).not.toBeInTheDocument();
    });

    it('uses peakKp as fallback when kp is absent', () => {
      const peakKpOnly = { ...auroraData, kp: undefined, peakKp: 6.7 };
      // Remove the kp property entirely
      delete peakKpOnly.kp;
      render(
        <HotTopicStrip
          hotTopics={[auroraTopic]}
          auroraTonight={peakKpOnly}
        />,
      );
      fireEvent.click(screen.getByTestId('hot-topic-pill-AURORA'));
      expect(screen.getByText('Kp 6.7')).toBeInTheDocument();
    });

    it('does not render Kp line when both kp and peakKp are null', () => {
      const noKp = { ...auroraData, kp: null, peakKp: undefined };
      delete noKp.peakKp;
      render(
        <HotTopicStrip
          hotTopics={[auroraTopic]}
          auroraTonight={noKp}
        />,
      );
      fireEvent.click(screen.getByTestId('hot-topic-pill-AURORA'));
      expect(screen.getByTestId('aurora-expanded-card')).toBeInTheDocument();
      // No "Kp" text should appear
      const card = screen.getByTestId('aurora-expanded-card');
      expect(card.textContent).not.toMatch(/Kp \d/);
    });

    it('omits temperature when temperatureCelsius is null', () => {
      const noTemp = {
        ...auroraData,
        regions: [{
          ...auroraData.regions[0],
          locations: [{
            locationName: 'Kielder', bortleClass: 2, clear: true,
            cloudPercent: 10, temperatureCelsius: null, windSpeedMs: 3.0, weatherCode: 0,
          }],
        }],
      };
      render(
        <HotTopicStrip hotTopics={[auroraTopic]} auroraTonight={noTemp} />,
      );
      fireEvent.click(screen.getByTestId('hot-topic-pill-AURORA'));
      const row = screen.getByTestId('aurora-expanded-location');
      expect(row.textContent).toContain('10% cloud');
      expect(row.textContent).not.toContain('°C');
    });

    it('omits wind speed when windSpeedMs is null', () => {
      const noWind = {
        ...auroraData,
        regions: [{
          ...auroraData.regions[0],
          locations: [{
            locationName: 'Kielder', bortleClass: 2, clear: true,
            cloudPercent: 10, temperatureCelsius: 5.0, windSpeedMs: null, weatherCode: 0,
          }],
        }],
      };
      render(
        <HotTopicStrip hotTopics={[auroraTopic]} auroraTonight={noWind} />,
      );
      fireEvent.click(screen.getByTestId('hot-topic-pill-AURORA'));
      const row = screen.getByTestId('aurora-expanded-location');
      expect(row.textContent).toContain('5°C');
      expect(row.textContent).not.toContain('mph');
    });

    it('omits weather icon when weatherCode is null', () => {
      const noWeather = {
        ...auroraData,
        regions: [{
          ...auroraData.regions[0],
          locations: [{
            locationName: 'Kielder', bortleClass: 2, clear: true,
            cloudPercent: 10, temperatureCelsius: 5.0, windSpeedMs: 2.0, weatherCode: null,
          }],
        }],
      };
      render(
        <HotTopicStrip hotTopics={[auroraTopic]} auroraTonight={noWeather} />,
      );
      fireEvent.click(screen.getByTestId('hot-topic-pill-AURORA'));
      const row = screen.getByTestId('aurora-expanded-location');
      // Should show cloud, temp, wind but no weather emoji
      expect(row.textContent).toContain('10% cloud');
      expect(row.textContent).toContain('5°C');
      expect(row.textContent).not.toContain('\u2600\uFE0F'); // no sun emoji
      expect(row.textContent).not.toContain('\u2601\uFE0F'); // no cloud emoji
    });

    // ── Alert level label correctness ──

    it('shows "Strong aurora" for STRONG alert level', () => {
      const strong = { ...auroraData, alertLevel: 'STRONG' };
      render(
        <HotTopicStrip hotTopics={[auroraTopic]} auroraTonight={strong} />,
      );
      fireEvent.click(screen.getByTestId('hot-topic-pill-AURORA'));
      expect(screen.getByTestId('aurora-expanded-alert').textContent).toBe('Strong aurora');
    });

    it('shows raw level for QUIET (no "aurora" suffix)', () => {
      const quiet = { ...auroraData, alertLevel: 'QUIET' };
      render(
        <HotTopicStrip hotTopics={[auroraTopic]} auroraTonight={quiet} />,
      );
      fireEvent.click(screen.getByTestId('hot-topic-pill-AURORA'));
      const alert = screen.getByTestId('aurora-expanded-alert');
      expect(alert.textContent).toBe('QUIET');
      expect(alert.textContent).not.toContain('aurora');
    });

    // ── State isolation ──

    it('expanding AURORA pill does not prevent non-AURORA pill from firing onTopicTap', () => {
      const onTap = vi.fn();
      const bluebellTopic = buildTopic();
      render(
        <HotTopicStrip
          hotTopics={[auroraTopic, bluebellTopic]}
          auroraTonight={auroraData}
          onTopicTap={onTap}
        />,
      );
      // Expand aurora
      fireEvent.click(screen.getByTestId('hot-topic-pill-AURORA'));
      expect(screen.getByTestId('aurora-expanded-card')).toBeInTheDocument();
      // Click bluebell — onTopicTap should still fire
      fireEvent.click(screen.getByTestId('hot-topic-pill-BLUEBELL'));
      expect(onTap).toHaveBeenCalledTimes(1);
      expect(onTap).toHaveBeenCalledWith(bluebellTopic);
    });

    it('chevron rotates to 90deg when expanded and 0deg when collapsed', () => {
      render(
        <HotTopicStrip hotTopics={[auroraTopic]} auroraTonight={auroraData} />,
      );
      const chevron = screen.getByTestId('expand-chevron-AURORA');
      expect(chevron.style.transform).toBe('rotate(0deg)');

      fireEvent.click(screen.getByTestId('hot-topic-pill-AURORA'));
      expect(chevron.style.transform).toBe('rotate(90deg)');

      fireEvent.click(screen.getByTestId('hot-topic-pill-AURORA'));
      expect(chevron.style.transform).toBe('rotate(0deg)');
    });

    it('does not render region Bortle badge when bestBortleClass is null', () => {
      const noBortle = {
        ...auroraData,
        regions: [{
          ...auroraData.regions[0],
          bestBortleClass: null,
          locations: [
            { locationName: 'Kielder', bortleClass: 2, clear: true, cloudPercent: 10 },
          ],
        }],
      };
      render(
        <HotTopicStrip hotTopics={[auroraTopic]} auroraTonight={noBortle} />,
      );
      fireEvent.click(screen.getByTestId('hot-topic-pill-AURORA'));
      const card = screen.getByTestId('aurora-expanded-card');
      // Region-level "Truly dark · Bortle 2" badge should not appear
      // (individual location Bortle badge for Kielder still shows)
      const regionBadges = card.querySelectorAll('span');
      const regionBortleText = Array.from(regionBadges)
        .filter((s) => s.textContent.includes('Truly dark · Bortle'));
      expect(regionBortleText).toHaveLength(0);
    });

    it('does not render clear percentage when totalDarkSkyLocations is 0', () => {
      const noTotal = {
        ...auroraData,
        regions: [{
          ...auroraData.regions[0],
          totalDarkSkyLocations: 0,
          locations: [
            { locationName: 'Kielder', bortleClass: 2, clear: true, cloudPercent: 10 },
          ],
        }],
      };
      render(
        <HotTopicStrip hotTopics={[auroraTopic]} auroraTonight={noTotal} />,
      );
      fireEvent.click(screen.getByTestId('hot-topic-pill-AURORA'));
      const card = screen.getByTestId('aurora-expanded-card');
      expect(card.textContent).not.toContain('% clear');
    });

    it('renders glossDetail infotip inside expanded card when present', () => {
      render(
        <HotTopicStrip hotTopics={[auroraTopic]} auroraTonight={auroraData} />,
      );
      fireEvent.click(screen.getByTestId('hot-topic-pill-AURORA'));
      // glossDetail is present in fixture — infotip should render inside the card
      const card = screen.getByTestId('aurora-expanded-card');
      const infotips = card.querySelectorAll('[data-testid="infotip-trigger"]');
      expect(infotips.length).toBeGreaterThanOrEqual(1);
    });

    it('does not render glossDetail infotip when glossDetail is absent', () => {
      const noGloss = {
        ...auroraData,
        regions: [{
          ...auroraData.regions[0],
          glossHeadline: 'Clear skies',
          glossDetail: null,
        }],
      };
      render(
        <HotTopicStrip hotTopics={[auroraTopic]} auroraTonight={noGloss} />,
      );
      fireEvent.click(screen.getByTestId('hot-topic-pill-AURORA'));
      const card = screen.getByTestId('aurora-expanded-card');
      const infotips = card.querySelectorAll('[data-testid="infotip-trigger"]');
      expect(infotips).toHaveLength(0);
    });
  });
});

describe('HotTopicStrip — expandable pill click behaviour', () => {
  it('expandable BLUEBELL pill does not call onTopicTap — toggles expand instead', () => {
    const onTap = vi.fn();
    const topic = {
      type: 'BLUEBELL',
      label: 'Bluebell conditions',
      detail: 'Detail',
      date: '2026-04-20',
      priority: 1,
      regions: ['Northumberland'],
      expandedDetail: {
        regionGroups: [{ regionName: 'Northumberland', locations: [] }],
        bluebellMetrics: { bestScore: 8, qualityLabel: 'Good', scoringLocationCount: 3 },
      },
    };
    render(<HotTopicStrip hotTopics={[topic]} onTopicTap={onTap} />);

    fireEvent.click(screen.getByTestId('hot-topic-pill-BLUEBELL'));
    expect(onTap).not.toHaveBeenCalled();
    expect(screen.getByTestId('bluebell-expanded-card')).toBeInTheDocument();
  });

  it('expandable KING_TIDE pill does not call onTopicTap — toggles expand instead', () => {
    const onTap = vi.fn();
    const topic = {
      type: 'KING_TIDE',
      label: 'King tide',
      detail: 'Detail',
      date: '2026-04-20',
      priority: 1,
      regions: ['Northumberland'],
      expandedDetail: {
        regionGroups: [{ regionName: 'Northumberland', locations: [] }],
        tideMetrics: { tidalClassification: 'King tide', lunarPhase: 'New Moon', coastalLocationCount: 2 },
      },
    };
    render(<HotTopicStrip hotTopics={[topic]} onTopicTap={onTap} />);

    fireEvent.click(screen.getByTestId('hot-topic-pill-KING_TIDE'));
    expect(onTap).not.toHaveBeenCalled();
    expect(screen.getByTestId('tide-expanded-card')).toBeInTheDocument();
  });

  it('BLUEBELL pill without expandedDetail still calls onTopicTap', () => {
    const onTap = vi.fn();
    const topic = buildTopic({ expandedDetail: undefined });
    render(<HotTopicStrip hotTopics={[topic]} onTopicTap={onTap} />);

    fireEvent.click(screen.getByTestId('hot-topic-pill-BLUEBELL'));
    expect(onTap).toHaveBeenCalledWith(topic);
  });
});

describe('HotTopicStrip — regions hidden when expandable', () => {
  it('expandable BLUEBELL pill hides regions from collapsed view', () => {
    const topic = {
      type: 'BLUEBELL',
      label: 'Bluebell conditions',
      detail: 'Detail',
      date: '2026-04-20',
      priority: 1,
      regions: ['Northumberland', 'The Lake District'],
      expandedDetail: {
        regionGroups: [{ regionName: 'Northumberland', locations: [] }],
        bluebellMetrics: { bestScore: 8, qualityLabel: 'Good', scoringLocationCount: 3 },
      },
    };
    render(<HotTopicStrip hotTopics={[topic]} />);

    // Regions should NOT appear in the collapsed pill
    expect(screen.queryByText('Northumberland, The Lake District')).not.toBeInTheDocument();
  });

  it('non-expandable BLUEBELL pill shows regions in collapsed view', () => {
    const topic = buildTopic({ regions: ['Northumberland', 'The Lake District'] });
    render(<HotTopicStrip hotTopics={[topic]} />);

    expect(screen.getByText('Northumberland, The Lake District')).toBeInTheDocument();
  });
});

describe('HotTopicStrip — detail text italic on expandable pills', () => {
  it('expandable BLUEBELL pill renders detail text in italic', () => {
    const topic = {
      type: 'BLUEBELL',
      label: 'Bluebell conditions',
      detail: 'Misty and still today',
      date: '2026-04-20',
      priority: 1,
      regions: [],
      expandedDetail: {
        regionGroups: [],
        bluebellMetrics: { bestScore: 8, qualityLabel: 'Good', scoringLocationCount: 3 },
      },
    };
    render(<HotTopicStrip hotTopics={[topic]} />);

    const detail = screen.getByText('Misty and still today');
    expect(detail.style.fontStyle).toBe('italic');
  });

  it('non-expandable pill renders detail text without italic', () => {
    render(<HotTopicStrip hotTopics={[buildTopic()]} />);

    const detail = screen.getByText('Misty and still — perfect morning conditions');
    expect(detail.style.fontStyle).toBe('');
  });
});

describe('HotTopicStrip — bluebell expanded card', () => {
  const bluebellDetail = {
    regionGroups: [
      {
        regionName: 'Northumberland',
        glossHeadline: 'Misty dawn — ideal woodland light',
        locations: [
          {
            locationName: 'Allen Banks',
            locationType: 'Woodland',
            badge: 'Best',
            bluebellLocationMetrics: { score: 9, exposure: 'WOODLAND', summary: 'Misty and still' },
          },
          {
            locationName: 'Briarwood Banks',
            locationType: 'Woodland',
            badge: null,
            bluebellLocationMetrics: { score: 7, exposure: 'WOODLAND', summary: 'Calm morning' },
          },
        ],
      },
      {
        regionName: 'The Lake District',
        glossHeadline: null,
        locations: [
          {
            locationName: 'Rannerdale',
            locationType: 'Open fell',
            badge: 'Best',
            bluebellLocationMetrics: { score: 8, exposure: 'OPEN_FELL', summary: 'Golden hour light' },
          },
        ],
      },
    ],
    bluebellMetrics: { bestScore: 9, qualityLabel: 'Excellent', scoringLocationCount: 3 },
  };

  const bluebellTopic = {
    type: 'BLUEBELL',
    label: 'Bluebell conditions',
    detail: 'Misty and still today',
    date: '2026-04-20',
    priority: 1,
    regions: ['Northumberland', 'The Lake District'],
    description: 'Bluebell season description',
    expandedDetail: bluebellDetail,
  };

  it('shows chevron when expandedDetail is present', () => {
    render(<HotTopicStrip hotTopics={[bluebellTopic]} />);
    expect(screen.getByTestId('expand-chevron-BLUEBELL')).toBeInTheDocument();
  });

  it('click toggles expanded card', () => {
    render(<HotTopicStrip hotTopics={[bluebellTopic]} />);
    expect(screen.queryByTestId('bluebell-expanded-card')).not.toBeInTheDocument();

    fireEvent.click(screen.getByTestId('hot-topic-pill-BLUEBELL'));
    expect(screen.getByTestId('bluebell-expanded-card')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('hot-topic-pill-BLUEBELL'));
    expect(screen.queryByTestId('bluebell-expanded-card')).not.toBeInTheDocument();
  });

  it('shows region groups with locations', () => {
    render(<HotTopicStrip hotTopics={[bluebellTopic]} />);
    fireEvent.click(screen.getByTestId('hot-topic-pill-BLUEBELL'));

    expect(screen.getByText('Northumberland')).toBeInTheDocument();
    expect(screen.getByText('The Lake District')).toBeInTheDocument();
    expect(screen.getByText('Allen Banks')).toBeInTheDocument();
    expect(screen.getByText('Rannerdale')).toBeInTheDocument();
  });

  it('shows exposure chip per location', () => {
    render(<HotTopicStrip hotTopics={[bluebellTopic]} />);
    fireEvent.click(screen.getByTestId('hot-topic-pill-BLUEBELL'));

    const chips = screen.getAllByTestId('bluebell-exposure-chip');
    const chipTexts = chips.map((c) => c.textContent);
    expect(chipTexts).toContain('Woodland');
    expect(chipTexts).toContain('Open fell');
  });

  it('shows score with colour coding', () => {
    render(<HotTopicStrip hotTopics={[bluebellTopic]} />);
    fireEvent.click(screen.getByTestId('hot-topic-pill-BLUEBELL'));

    const scores = screen.getAllByTestId('bluebell-score');
    expect(scores.some((s) => s.textContent === '9/10')).toBe(true);
    // Score 9 should be green
    const score9 = scores.find((s) => s.textContent === '9/10');
    expect(score9.style.color).toBe('rgb(74, 222, 128)');
  });

  it('shows Best badge on top location', () => {
    render(<HotTopicStrip hotTopics={[bluebellTopic]} />);
    fireEvent.click(screen.getByTestId('hot-topic-pill-BLUEBELL'));

    const badges = screen.getAllByTestId('bluebell-badge');
    expect(badges).toHaveLength(2); // One per region's top location
    expect(badges[0].textContent).toBe('Best');
  });

  it('shows gloss headline per region', () => {
    render(<HotTopicStrip hotTopics={[bluebellTopic]} />);
    fireEvent.click(screen.getByTestId('hot-topic-pill-BLUEBELL'));

    expect(screen.getByTestId('bluebell-gloss-headline')).toBeInTheDocument();
    expect(screen.getByText(/Misty dawn/)).toBeInTheDocument();
  });

  it('LITE user cannot expand', () => {
    render(<HotTopicStrip hotTopics={[bluebellTopic]} isLiteUser={true} />);
    expect(screen.queryByTestId('expand-chevron-BLUEBELL')).not.toBeInTheDocument();
  });
});

describe('HotTopicStrip — tide expanded card', () => {
  const tideDetail = {
    regionGroups: [
      {
        regionName: 'Northumberland',
        locations: [
          {
            locationName: 'Bamburgh',
            locationType: 'Coastal',
            tideLocationMetrics: { tidePreference: 'HIGH' },
          },
          {
            locationName: 'Dunstanburgh Castle',
            locationType: 'Coastal',
            tideLocationMetrics: { tidePreference: 'HIGH' },
          },
        ],
      },
      {
        regionName: 'The North Yorkshire Coast',
        locations: [
          {
            locationName: 'Saltwick Bay',
            locationType: 'Coastal',
            tideLocationMetrics: { tidePreference: 'MID' },
          },
        ],
      },
    ],
    tideMetrics: { tidalClassification: 'King tide', lunarPhase: 'New Moon', coastalLocationCount: 3 },
  };

  const kingTideTopic = {
    type: 'KING_TIDE',
    label: 'King tide',
    detail: 'Rare extreme tidal range today',
    date: '2026-04-20',
    priority: 1,
    regions: ['Northumberland', 'The North Yorkshire Coast'],
    description: 'King tide description',
    expandedDetail: tideDetail,
  };

  it('shows chevron when expandedDetail is present', () => {
    render(<HotTopicStrip hotTopics={[kingTideTopic]} />);
    expect(screen.getByTestId('expand-chevron-KING_TIDE')).toBeInTheDocument();
  });

  it('click toggles expanded card', () => {
    render(<HotTopicStrip hotTopics={[kingTideTopic]} />);
    expect(screen.queryByTestId('tide-expanded-card')).not.toBeInTheDocument();

    fireEvent.click(screen.getByTestId('hot-topic-pill-KING_TIDE'));
    expect(screen.getByTestId('tide-expanded-card')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('hot-topic-pill-KING_TIDE'));
    expect(screen.queryByTestId('tide-expanded-card')).not.toBeInTheDocument();
  });

  it('shows region groups as section headers', () => {
    render(<HotTopicStrip hotTopics={[kingTideTopic]} />);
    fireEvent.click(screen.getByTestId('hot-topic-pill-KING_TIDE'));

    expect(screen.getByText('Northumberland')).toBeInTheDocument();
    expect(screen.getByText('The North Yorkshire Coast')).toBeInTheDocument();
  });

  it('shows locations with tide preference', () => {
    render(<HotTopicStrip hotTopics={[kingTideTopic]} />);
    fireEvent.click(screen.getByTestId('hot-topic-pill-KING_TIDE'));

    const locations = screen.getAllByTestId('tide-expanded-location');
    expect(locations).toHaveLength(3);
    const prefs = screen.getAllByTestId('tide-preference-label');
    const prefTexts = prefs.map((p) => p.textContent);
    expect(prefTexts).toContain('HIGH');
    expect(prefTexts).toContain('MID');
  });

  it('spring tide works identically', () => {
    const springTopic = {
      ...kingTideTopic,
      type: 'SPRING_TIDE',
      label: 'Spring tide',
      expandedDetail: {
        ...tideDetail,
        tideMetrics: { ...tideDetail.tideMetrics, tidalClassification: 'Spring tide' },
      },
    };
    render(<HotTopicStrip hotTopics={[springTopic]} />);
    fireEvent.click(screen.getByTestId('hot-topic-pill-SPRING_TIDE'));
    expect(screen.getByTestId('tide-expanded-card')).toBeInTheDocument();
  });
});

describe('HotTopicStrip — subtitle line', () => {
  it('bluebell subtitle shows scoring count and best score', () => {
    const topic = {
      type: 'BLUEBELL',
      label: 'Bluebell conditions',
      detail: 'Detail text',
      date: '2026-04-20',
      priority: 1,
      regions: [],
      expandedDetail: {
        regionGroups: [],
        bluebellMetrics: { bestScore: 8, qualityLabel: 'Good', scoringLocationCount: 5 },
      },
    };
    render(<HotTopicStrip hotTopics={[topic]} />);
    const subtitle = screen.getByTestId('subtitle-BLUEBELL');
    expect(subtitle.textContent).toContain('5 locations scoring');
    expect(subtitle.textContent).toContain('best 8/10');
  });

  it('tide subtitle shows classification, lunar phase and location count', () => {
    const topic = {
      type: 'KING_TIDE',
      label: 'King tide',
      detail: 'Detail text',
      date: '2026-04-20',
      priority: 1,
      regions: [],
      expandedDetail: {
        regionGroups: [],
        tideMetrics: { tidalClassification: 'King tide', lunarPhase: 'New Moon', coastalLocationCount: 4 },
      },
    };
    render(<HotTopicStrip hotTopics={[topic]} />);
    const subtitle = screen.getByTestId('subtitle-KING_TIDE');
    expect(subtitle.textContent).toContain('King tide');
    expect(subtitle.textContent).toContain('New Moon');
    expect(subtitle.textContent).toContain('4 coastal locations');
  });

  it('aurora subtitle shows Kp and clear location count', () => {
    const topic = {
      type: 'AURORA',
      label: 'AURORA TONIGHT',
      detail: 'Moderate activity tonight',
      date: '2026-04-17',
      priority: 1,
      regions: ['Northumberland'],
      description: null,
    };
    const auroraData = {
      alertLevel: 'MODERATE',
      kp: 5.3,
      regions: [
        { regionName: 'Northumberland', clearLocationCount: 2, totalDarkSkyLocations: 3 },
      ],
    };
    render(
      <HotTopicStrip
        hotTopics={[topic]}
        auroraTonight={auroraData}
      />,
    );
    const subtitle = screen.getByTestId('subtitle-AURORA');
    expect(subtitle.textContent).toContain('Kp 5.3 forecast tonight');
    expect(subtitle.textContent).toContain('2 locations clear');
  });

  it('non-expandable types show no subtitle', () => {
    const topic = {
      type: 'DUST',
      label: 'Elevated dust',
      detail: 'Detail',
      date: '2026-04-20',
      priority: 3,
      regions: [],
    };
    render(<HotTopicStrip hotTopics={[topic]} />);
    expect(screen.queryByTestId('subtitle-DUST')).not.toBeInTheDocument();
  });
});

describe('HotTopicStrip — bluebell score colour coding', () => {
  const makeBluebellTopic = (score) => ({
    type: 'BLUEBELL',
    label: 'Bluebell conditions',
    detail: 'Detail',
    date: '2026-04-20',
    priority: 1,
    regions: [],
    expandedDetail: {
      regionGroups: [{
        regionName: 'Test Region',
        locations: [{
          locationName: 'Test Location',
          locationType: 'Woodland',
          badge: null,
          bluebellLocationMetrics: { score, exposure: 'WOODLAND', summary: 'Summary' },
        }],
      }],
      bluebellMetrics: { bestScore: score, qualityLabel: 'Good', scoringLocationCount: 1 },
    },
  });

  it('score 9 renders green', () => {
    render(<HotTopicStrip hotTopics={[makeBluebellTopic(9)]} />);
    fireEvent.click(screen.getByTestId('hot-topic-pill-BLUEBELL'));
    const scoreEl = screen.getByTestId('bluebell-score');
    expect(scoreEl.textContent).toBe('9/10');
    expect(scoreEl.style.color).toBe('rgb(74, 222, 128)');
  });

  it('score 10 renders green', () => {
    render(<HotTopicStrip hotTopics={[makeBluebellTopic(10)]} />);
    fireEvent.click(screen.getByTestId('hot-topic-pill-BLUEBELL'));
    const scoreEl = screen.getByTestId('bluebell-score');
    expect(scoreEl.style.color).toBe('rgb(74, 222, 128)');
  });

  it('score 7 renders amber', () => {
    render(<HotTopicStrip hotTopics={[makeBluebellTopic(7)]} />);
    fireEvent.click(screen.getByTestId('hot-topic-pill-BLUEBELL'));
    const scoreEl = screen.getByTestId('bluebell-score');
    expect(scoreEl.textContent).toBe('7/10');
    expect(scoreEl.style.color).toBe('rgb(251, 191, 36)');
  });

  it('score 8 renders amber', () => {
    render(<HotTopicStrip hotTopics={[makeBluebellTopic(8)]} />);
    fireEvent.click(screen.getByTestId('hot-topic-pill-BLUEBELL'));
    const scoreEl = screen.getByTestId('bluebell-score');
    expect(scoreEl.style.color).toBe('rgb(251, 191, 36)');
  });

  it('score 5 renders muted', () => {
    render(<HotTopicStrip hotTopics={[makeBluebellTopic(5)]} />);
    fireEvent.click(screen.getByTestId('hot-topic-pill-BLUEBELL'));
    const scoreEl = screen.getByTestId('bluebell-score');
    expect(scoreEl.textContent).toBe('5/10');
    expect(scoreEl.style.color).toBe('rgba(255, 255, 255, 0.45)');
  });

  it('score 6 renders muted', () => {
    render(<HotTopicStrip hotTopics={[makeBluebellTopic(6)]} />);
    fireEvent.click(screen.getByTestId('hot-topic-pill-BLUEBELL'));
    const scoreEl = screen.getByTestId('bluebell-score');
    expect(scoreEl.style.color).toBe('rgba(255, 255, 255, 0.45)');
  });
});

describe('HotTopicStrip — chevron rotation on non-AURORA expandable pills', () => {
  it('BLUEBELL chevron rotates to 90deg when expanded and 0deg when collapsed', () => {
    const topic = {
      type: 'BLUEBELL',
      label: 'Bluebell conditions',
      detail: 'Detail',
      date: '2026-04-20',
      priority: 1,
      regions: [],
      expandedDetail: {
        regionGroups: [{ regionName: 'R1', locations: [] }],
        bluebellMetrics: { bestScore: 8, qualityLabel: 'Good', scoringLocationCount: 3 },
      },
    };
    render(<HotTopicStrip hotTopics={[topic]} />);
    const chevron = screen.getByTestId('expand-chevron-BLUEBELL');
    expect(chevron.style.transform).toBe('rotate(0deg)');

    fireEvent.click(screen.getByTestId('hot-topic-pill-BLUEBELL'));
    expect(chevron.style.transform).toBe('rotate(90deg)');

    fireEvent.click(screen.getByTestId('hot-topic-pill-BLUEBELL'));
    expect(chevron.style.transform).toBe('rotate(0deg)');
  });

  it('KING_TIDE chevron rotates to 90deg when expanded', () => {
    const topic = {
      type: 'KING_TIDE',
      label: 'King tide',
      detail: 'Detail',
      date: '2026-04-20',
      priority: 1,
      regions: [],
      expandedDetail: {
        regionGroups: [{ regionName: 'R1', locations: [] }],
        tideMetrics: { tidalClassification: 'King tide', lunarPhase: 'New Moon', coastalLocationCount: 2 },
      },
    };
    render(<HotTopicStrip hotTopics={[topic]} />);
    const chevron = screen.getByTestId('expand-chevron-KING_TIDE');
    expect(chevron.style.transform).toBe('rotate(0deg)');

    fireEvent.click(screen.getByTestId('hot-topic-pill-KING_TIDE'));
    expect(chevron.style.transform).toBe('rotate(90deg)');
  });
});

describe('HotTopicStrip — null metrics guards', () => {
  it('bluebell location with null bluebellLocationMetrics does not render score', () => {
    const topic = {
      type: 'BLUEBELL',
      label: 'Bluebell conditions',
      detail: 'Detail',
      date: '2026-04-20',
      priority: 1,
      regions: [],
      expandedDetail: {
        regionGroups: [{
          regionName: 'Northumberland',
          locations: [{
            locationName: 'Allen Banks',
            locationType: 'Woodland',
            badge: null,
            bluebellLocationMetrics: null,
          }],
        }],
        bluebellMetrics: { bestScore: 7, qualityLabel: 'Good', scoringLocationCount: 1 },
      },
    };
    render(<HotTopicStrip hotTopics={[topic]} />);
    fireEvent.click(screen.getByTestId('hot-topic-pill-BLUEBELL'));

    expect(screen.getByText('Allen Banks')).toBeInTheDocument();
    expect(screen.queryByTestId('bluebell-score')).not.toBeInTheDocument();
  });

  it('tide location with null tideLocationMetrics does not render preference label', () => {
    const topic = {
      type: 'KING_TIDE',
      label: 'King tide',
      detail: 'Detail',
      date: '2026-04-20',
      priority: 1,
      regions: [],
      expandedDetail: {
        regionGroups: [{
          regionName: 'Northumberland',
          locations: [{
            locationName: 'Bamburgh',
            locationType: 'Coastal',
            tideLocationMetrics: null,
          }],
        }],
        tideMetrics: { tidalClassification: 'King tide', lunarPhase: 'New Moon', coastalLocationCount: 1 },
      },
    };
    render(<HotTopicStrip hotTopics={[topic]} />);
    fireEvent.click(screen.getByTestId('hot-topic-pill-KING_TIDE'));

    expect(screen.getByText('Bamburgh')).toBeInTheDocument();
    expect(screen.queryByTestId('tide-preference-label')).not.toBeInTheDocument();
  });

  it('region with null glossHeadline does not render gloss element', () => {
    const topic = {
      type: 'BLUEBELL',
      label: 'Bluebell conditions',
      detail: 'Detail',
      date: '2026-04-20',
      priority: 1,
      regions: [],
      expandedDetail: {
        regionGroups: [{
          regionName: 'Northumberland',
          glossHeadline: null,
          locations: [{
            locationName: 'Allen Banks',
            locationType: 'Woodland',
            badge: null,
            bluebellLocationMetrics: { score: 7, exposure: 'WOODLAND', summary: 'Calm' },
          }],
        }],
        bluebellMetrics: { bestScore: 7, qualityLabel: 'Good', scoringLocationCount: 1 },
      },
    };
    render(<HotTopicStrip hotTopics={[topic]} />);
    fireEvent.click(screen.getByTestId('hot-topic-pill-BLUEBELL'));

    expect(screen.getByText('Northumberland')).toBeInTheDocument();
    expect(screen.queryByTestId('bluebell-gloss-headline')).not.toBeInTheDocument();
  });
});

describe('HotTopicStrip — generic expand behaviour', () => {
  it('expanding aurora collapses bluebell', () => {
    const auroraTopic = {
      type: 'AURORA',
      label: 'AURORA TONIGHT',
      detail: 'Moderate activity tonight',
      date: '2026-04-17',
      priority: 1,
      regions: ['Northumberland'],
      description: null,
    };
    const auroraData = {
      alertLevel: 'MODERATE',
      kp: 5.0,
      regions: [{ regionName: 'Northumberland', clearLocationCount: 1, totalDarkSkyLocations: 2 }],
    };
    const bluebellTopic = {
      type: 'BLUEBELL',
      label: 'Bluebell conditions',
      detail: 'Detail',
      date: '2026-04-20',
      priority: 1,
      regions: [],
      expandedDetail: {
        regionGroups: [{ regionName: 'R1', locations: [] }],
        bluebellMetrics: { bestScore: 8, qualityLabel: 'Good', scoringLocationCount: 3 },
      },
    };

    render(
      <HotTopicStrip
        hotTopics={[bluebellTopic, auroraTopic]}
        auroraTonight={auroraData}
      />,
    );

    // Expand bluebell
    fireEvent.click(screen.getByTestId('hot-topic-pill-BLUEBELL'));
    expect(screen.getByTestId('bluebell-expanded-card')).toBeInTheDocument();

    // Expand aurora — bluebell should collapse
    fireEvent.click(screen.getByTestId('hot-topic-pill-AURORA'));
    expect(screen.queryByTestId('bluebell-expanded-card')).not.toBeInTheDocument();
    expect(screen.getByTestId('aurora-expanded-card')).toBeInTheDocument();
  });

  it('only one pill expanded at a time across different types', () => {
    const bluebellTopic = {
      type: 'BLUEBELL',
      label: 'Bluebell conditions',
      detail: 'Detail',
      date: '2026-04-20',
      priority: 1,
      regions: [],
      expandedDetail: {
        regionGroups: [{ regionName: 'R1', locations: [] }],
        bluebellMetrics: { bestScore: 8, qualityLabel: 'Good', scoringLocationCount: 3 },
      },
    };
    const tideTopic = {
      type: 'KING_TIDE',
      label: 'King tide',
      detail: 'Detail',
      date: '2026-04-20',
      priority: 1,
      regions: [],
      expandedDetail: {
        regionGroups: [{ regionName: 'R1', locations: [] }],
        tideMetrics: { tidalClassification: 'King tide', lunarPhase: 'Full Moon', coastalLocationCount: 2 },
      },
    };

    render(<HotTopicStrip hotTopics={[bluebellTopic, tideTopic]} />);

    // Expand bluebell
    fireEvent.click(screen.getByTestId('hot-topic-pill-BLUEBELL'));
    expect(screen.getByTestId('bluebell-expanded-card')).toBeInTheDocument();
    expect(screen.queryByTestId('tide-expanded-card')).not.toBeInTheDocument();

    // Expand tide — bluebell should collapse
    fireEvent.click(screen.getByTestId('hot-topic-pill-KING_TIDE'));
    expect(screen.queryByTestId('bluebell-expanded-card')).not.toBeInTheDocument();
    expect(screen.getByTestId('tide-expanded-card')).toBeInTheDocument();
  });
});

describe('HotTopicStrip — aurora pill moon line', () => {
  const auroraTopic = {
    type: 'AURORA',
    label: 'AURORA TONIGHT',
    detail: 'Moderate activity tonight',
    date: '2026-04-17',
    priority: 1,
    regions: ['Northumberland'],
    description: null,
  };

  const auroraTomorrowTopic = {
    type: 'AURORA',
    label: 'AURORA TOMORROW',
    detail: 'Minor activity tomorrow night',
    date: '2026-04-18',
    priority: 2,
    regions: ['Northumberland'],
    description: null,
  };

  const tonightData = {
    alertLevel: 'MODERATE',
    kp: 5.3,
    moonPhase: 'NEW_MOON',
    moonIlluminationPct: 2.802,
    windowQuality: 'DARK_ALL_WINDOW',
    moonRiseTime: null,
    moonSetTime: null,
    regions: [{ regionName: 'Northumberland', clearLocationCount: 2, totalDarkSkyLocations: 3 }],
  };

  const tomorrowData = {
    peakKp: 4.0,
    label: 'Worth watching',
    alertLevel: 'MINOR',
    moonPhase: 'WAXING_CRESCENT',
    moonIlluminationPct: 8.5,
    regions: [{ regionName: 'Northumberland', clearLocationCount: 1, totalDarkSkyLocations: 3 }],
  };

  it('tonight pill shows moon line with phase name and rounded illumination', () => {
    render(
      <HotTopicStrip hotTopics={[auroraTopic]} auroraTonight={tonightData} />,
    );
    const moonLine = screen.getByTestId('aurora-pill-moon-line');
    expect(moonLine).toBeInTheDocument();
    expect(moonLine.textContent).toContain('New moon');
    expect(moonLine.textContent).toContain('3%');
    expect(moonLine.textContent).not.toContain('2.8');
  });

  it('tonight pill DARK_ALL_WINDOW shows green text and "dark all night"', () => {
    render(
      <HotTopicStrip hotTopics={[auroraTopic]} auroraTonight={tonightData} />,
    );
    const moonLine = screen.getByTestId('aurora-pill-moon-line');
    expect(moonLine.textContent).toContain('dark all night');
    const colourSpan = moonLine.querySelector('.text-green-400\\/70');
    expect(colourSpan).not.toBeNull();
  });

  it('DARK_THEN_MOONLIT shows amber text and "dark until" with moonrise time', () => {
    const data = { ...tonightData, windowQuality: 'DARK_THEN_MOONLIT', moonRiseTime: '2026-04-17T22:15:00', moonPhase: 'WAXING_GIBBOUS', moonIlluminationPct: 82 };
    render(
      <HotTopicStrip hotTopics={[auroraTopic]} auroraTonight={data} />,
    );
    const moonLine = screen.getByTestId('aurora-pill-moon-line');
    expect(moonLine.textContent).toContain('Waxing gibbous');
    expect(moonLine.textContent).toContain('82%');
    expect(moonLine.textContent).toContain('dark until');
    expect(moonLine.textContent).toContain('23:15'); // UTC 22:15 → BST 23:15
    const colourSpan = moonLine.querySelector('.text-amber-400');
    expect(colourSpan).not.toBeNull();
  });

  it('MOONLIT_THEN_DARK shows green text and "clears after" with moonset time', () => {
    const data = { ...tonightData, windowQuality: 'MOONLIT_THEN_DARK', moonSetTime: '2026-04-18T01:00:00', moonPhase: 'WANING_GIBBOUS', moonIlluminationPct: 65 };
    render(
      <HotTopicStrip hotTopics={[auroraTopic]} auroraTonight={data} />,
    );
    const moonLine = screen.getByTestId('aurora-pill-moon-line');
    expect(moonLine.textContent).toContain('Waning gibbous');
    expect(moonLine.textContent).toContain('clears after');
    expect(moonLine.textContent).toContain('02:00'); // UTC 01:00 → BST 02:00
  });

  it('MOONLIT_ALL_WINDOW shows red text and "moon above horizon all night"', () => {
    const data = { ...tonightData, windowQuality: 'MOONLIT_ALL_WINDOW', moonPhase: 'FULL_MOON', moonIlluminationPct: 96 };
    render(
      <HotTopicStrip hotTopics={[auroraTopic]} auroraTonight={data} />,
    );
    const moonLine = screen.getByTestId('aurora-pill-moon-line');
    expect(moonLine.textContent).toContain('Full moon');
    expect(moonLine.textContent).toContain('96%');
    expect(moonLine.textContent).toContain('moon above horizon all night');
    const colourSpan = moonLine.querySelector('.text-red-400');
    expect(colourSpan).not.toBeNull();
  });

  it('auroraTonight null renders pill without moon line', () => {
    render(
      <HotTopicStrip hotTopics={[auroraTopic]} auroraTonight={null} />,
    );
    expect(screen.queryByTestId('aurora-pill-moon-line')).not.toBeInTheDocument();
  });

  it('auroraTomorrow null renders tomorrow pill without moon line', () => {
    render(
      <HotTopicStrip hotTopics={[auroraTomorrowTopic]} auroraTomorrow={null} />,
    );
    expect(screen.queryByTestId('aurora-pill-moon-line')).not.toBeInTheDocument();
  });

  it('aurora data with moonPhase null renders pill without moon line', () => {
    const noMoon = { ...tonightData, moonPhase: null, moonIlluminationPct: null };
    render(
      <HotTopicStrip hotTopics={[auroraTopic]} auroraTonight={noMoon} />,
    );
    expect(screen.queryByTestId('aurora-pill-moon-line')).not.toBeInTheDocument();
  });

  it('tomorrow pill shows moon line from auroraTomorrow data', () => {
    render(
      <HotTopicStrip
        hotTopics={[auroraTomorrowTopic]}
        auroraTomorrow={tomorrowData}
      />,
    );
    const moonLine = screen.getByTestId('aurora-pill-moon-line');
    expect(moonLine.textContent).toContain('Waxing crescent');
    expect(moonLine.textContent).toContain('9%');
  });

  it('non-aurora pill types do not render moon line', () => {
    render(
      <HotTopicStrip
        hotTopics={[buildTopic({ type: 'DUST', label: 'Dust', date: '2026-04-17' })]}
        auroraTonight={tonightData}
      />,
    );
    expect(screen.queryByTestId('aurora-pill-moon-line')).not.toBeInTheDocument();
  });

  it('phase name renders in muted colour', () => {
    render(
      <HotTopicStrip hotTopics={[auroraTopic]} auroraTonight={tonightData} />,
    );
    const moonLine = screen.getByTestId('aurora-pill-moon-line');
    const phaseSpan = Array.from(moonLine.querySelectorAll('span'))
      .find((s) => s.textContent === 'New moon');
    expect(phaseSpan).toBeDefined();
    expect(phaseSpan.style.color).toBe('rgba(255, 255, 255, 0.5)');
  });
});
