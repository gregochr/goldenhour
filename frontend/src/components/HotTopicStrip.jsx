import PropTypes from 'prop-types';
import InfoTip from './InfoTip.jsx';

/** Accent colours keyed by topic type. */
const HOT_TOPIC_STYLES = {
  BLUEBELL:    { color: '#8b5cf6', emoji: '\uD83D\uDC9C' },
  KING_TIDE:   { color: '#3b82f6', emoji: '\uD83D\uDC51' },
  SPRING_TIDE: { color: '#60a5fa', emoji: '\uD83C\uDF0A' },
  STORM_SURGE: { color: '#f59e0b', emoji: '\u26A1' },
  AURORA:      { color: '#4ade80', emoji: '\uD83C\uDF0C' },
  DUST:        { color: '#f97316', emoji: '\uD83C\uDF05' },
  INVERSION:   { color: '#94a3b8', emoji: '\u2601\uFE0F' },
  SUPERMOON:   { color: '#fbbf24', emoji: '\uD83C\uDF15' },
  SNOW_FRESH:  { color: '#e0f2fe', emoji: '\u2744\uFE0F' },
  SNOW_MIST:   { color: '#cbd5e1', emoji: '\uD83C\uDF2B\uFE0F' },
  SNOW_TOPS:   { color: '#bfdbfe', emoji: '\uD83C\uDFD4\uFE0F' },
  NLC:         { color: '#818cf8', emoji: '\u2728' },
  METEOR:      { color: '#a78bfa', emoji: '\u2604\uFE0F' },
  EQUINOX:     { color: '#fcd34d', emoji: '\u2600\uFE0F' },
  CLEARANCE:   { color: '#fb923c', emoji: '\u26C5' },
};

const DEFAULT_STYLE = { color: '#ffffff33', emoji: '' };

/**
 * Two-column responsive grid of Hot Topic pills shown between the Best Bet
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
      className="hot-topic-grid"
    >
      {hotTopics.map((topic) => {
        const style = HOT_TOPIC_STYLES[topic.type] ?? DEFAULT_STYLE;
        const regionLine = topic.regions?.length > 0 ? topic.regions.join(', ') : null;
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
              borderRadius: '0 8px 8px 0',
              border: `1px solid ${style.color}33`,
              borderLeft: `3px solid ${style.color}`,
              background: `${style.color}0F`,
              cursor: isLiteUser ? 'default' : 'pointer',
              opacity: isLiteUser ? 0.45 : 1,
              pointerEvents: isLiteUser ? 'none' : 'auto',
              textAlign: 'left',
              transition: 'background 0.15s',
            }}
          >
            <div
              style={{
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'flex-start',
                gap: '12px',
                marginBottom: '4px',
              }}
            >
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: '6px',
                  flexShrink: 0,
                }}
              >
                {style.emoji && (
                  <span
                    style={{
                      fontSize: '14px',
                      lineHeight: 1,
                      ...(topic.type === 'INVERSION' ? { display: 'inline-block', transform: 'rotate(180deg)' } : {}),
                    }}
                  >
                    {style.emoji}
                  </span>
                )}
                <span
                  style={{
                    fontSize: '11px',
                    fontWeight: 600,
                    textTransform: 'uppercase',
                    letterSpacing: '0.5px',
                    color: style.color,
                    filter: 'brightness(1.4)',
                  }}
                >
                  {topic.label}
                </span>
                {topic.description && (
                  <span style={{ color: `${style.color}99` }}>
                    <InfoTip text={topic.description} position="above" />
                  </span>
                )}
              </div>
              {regionLine && (
                <span
                  style={{
                    fontSize: '11px',
                    color: 'rgba(255, 255, 255, 0.45)',
                    textAlign: 'right',
                    lineHeight: 1.3,
                    whiteSpace: 'normal',
                    wordBreak: 'normal',
                  }}
                >
                  {regionLine}
                </span>
              )}
            </div>
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
      description: PropTypes.string,
    }),
  ).isRequired,
  isLiteUser: PropTypes.bool,
  onTopicTap: PropTypes.func,
};

HotTopicStrip.defaultProps = {
  isLiteUser: false,
  onTopicTap: null,
};
