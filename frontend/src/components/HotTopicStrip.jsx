import PropTypes from 'prop-types';

/** Accent colours keyed by topic type. */
const TOPIC_ACCENT = {
  BLUEBELL: {
    border: 'rgba(99, 102, 241, 0.3)',
    labelColor: '#818cf8',
    background: 'rgba(99, 102, 241, 0.06)',
  },
};

const DEFAULT_ACCENT = {
  border: 'rgba(255, 255, 255, 0.1)',
  labelColor: 'rgba(255, 255, 255, 0.7)',
  background: 'rgba(255, 255, 255, 0.04)',
};

/**
 * Horizontally scrollable strip of Hot Topic pills shown between the Best Bet
 * cards and the quality slider in the briefing planner.
 *
 * @param {Object}   props
 * @param {Array}    props.hotTopics      array of hot topic objects from the API
 * @param {boolean}  props.isLiteUser     true when role === 'LITE_USER'
 * @param {Function} props.onTopicTap     callback(topic) invoked when a pill is tapped
 */
export default function HotTopicStrip({ hotTopics, isLiteUser, onTopicTap }) {
  if (!hotTopics || hotTopics.length === 0) return null;

  return (
    <div
      data-testid="hot-topic-strip"
      style={{
        display: 'flex',
        gap: '8px',
        overflowX: 'auto',
        padding: '8px 0',
        scrollbarWidth: 'none',
        msOverflowStyle: 'none',
      }}
    >
      {hotTopics.map((topic) => {
        const accent = TOPIC_ACCENT[topic.type] ?? DEFAULT_ACCENT;
        return (
          <button
            key={`${topic.type}-${topic.date}`}
            data-testid={`hot-topic-pill-${topic.type}`}
            onClick={() => !isLiteUser && onTopicTap && onTopicTap(topic)}
            disabled={isLiteUser}
            style={{
              display: 'flex',
              flexDirection: 'column',
              gap: '2px',
              padding: '8px 14px',
              borderRadius: '8px',
              border: `1px solid ${accent.border}`,
              background: accent.background,
              cursor: isLiteUser ? 'default' : 'pointer',
              whiteSpace: 'nowrap',
              flexShrink: 0,
              opacity: isLiteUser ? 0.45 : 1,
              pointerEvents: isLiteUser ? 'none' : 'auto',
              textAlign: 'left',
              transition: 'background 0.15s',
            }}
          >
            <span
              style={{
                fontSize: '11px',
                fontWeight: 600,
                textTransform: 'uppercase',
                letterSpacing: '0.5px',
                color: accent.labelColor,
              }}
            >
              {topic.label}
            </span>
            <span
              style={{
                fontSize: '12px',
                color: 'rgba(255, 255, 255, 0.55)',
              }}
            >
              {topic.detail}
            </span>
          </button>
        );
      })}
      {isLiteUser && (
        <span
          data-testid="hot-topic-upsell"
          style={{
            display: 'flex',
            alignItems: 'center',
            fontSize: '11px',
            fontWeight: 600,
            color: '#d4a843',
            whiteSpace: 'nowrap',
            padding: '0 8px',
            flexShrink: 0,
          }}
        >
          Upgrade to Pro
        </span>
      )}
    </div>
  );
}

HotTopicStrip.propTypes = {
  hotTopics: PropTypes.arrayOf(
    PropTypes.shape({
      type: PropTypes.string.isRequired,
      label: PropTypes.string.isRequired,
      detail: PropTypes.string,
      date: PropTypes.string.isRequired,
      priority: PropTypes.number,
      filterAction: PropTypes.string,
      regions: PropTypes.arrayOf(PropTypes.string),
    }),
  ).isRequired,
  isLiteUser: PropTypes.bool,
  onTopicTap: PropTypes.func,
};

HotTopicStrip.defaultProps = {
  isLiteUser: false,
  onTopicTap: null,
};
