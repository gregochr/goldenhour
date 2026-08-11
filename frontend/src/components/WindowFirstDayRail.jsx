import React, { useCallback } from 'react';
import PropTypes from 'prop-types';
import PopoverHost from './PopoverHost.jsx';
import usePopoverHost from '../hooks/usePopoverHost.js';

/** Gloss panel geometry, matching the shipped `.summary-region-tip` the rail reuses. */
const TIP_WIDTH = 264;
const TIP_GAP = 9;
const TIP_MARGIN = 8;

/**
 * The pick flag's own metrics, and the date-row height derived from them.
 *
 * <p>A rail is a comparison surface: the eye runs <em>across</em> it, so the sun line and the
 * verdict line have to sit on the same y on every tile. A two-line chip is 9px taller than the
 * date row it sits in, so on the two flagged tiles it pushed everything below down and the rail
 * read ragged — measured at 12px of drift in the browser, which is plainly visible and was not
 * visible in any test.
 *
 * <p>So every tile reserves the chip's height whether it carries one or not, and the chip takes
 * {@code align-self: flex-start} so it stops participating in the row's baseline alignment (which
 * was adding a further 3px of descender space under its second line). The reserved height is
 * <em>computed</em> from the chip's own type rather than typed in, so changing the chip's size
 * cannot silently reintroduce the drift.
 */
const FLAG_FONT_PX = 9.5;
const FLAG_LINE_HEIGHT = 1.25;
const FLAG_PAD_Y_PX = 2;
const FLAG_LINES = 2;
export const DATE_ROW_MIN_HEIGHT_PX = FLAG_LINES * FLAG_FONT_PX * FLAG_LINE_HEIGHT + 2 * FLAG_PAD_Y_PX;

/**
 * The window-first Plan tab's day rail — one tile per upcoming day, the full Plan summary.
 *
 * <h2>A copy of the v1 summary tile, restyled; not a rewiring of it</h2>
 *
 * <p>The medallion, the sun times, the verdict line, the identity-ordered region chips with their
 * {@code ◎} mark, the show-on-map action and the away variant all exist in
 * {@code BriefingSummaryStrip} and are copied here. Plan §5: rewiring that component would break
 * the v1 arm of the flag comparison, which §4 rests on. Genuinely new: the pick flag chip, today's
 * border tint, and the spec's px geometry.
 *
 * <p><b>No {@code ProvisionalMark}.</b> Plan §2.7 makes the window card's verdict badge the single
 * render site for confidence; a second mark on the rail breaks "one uniform channel" as surely as
 * omitting it would. The tile still carries {@code confidence} — P5's badge reads it.
 *
 * <h2>The pick flag is two lines, and that is a change from the mock</h2>
 *
 * <p>The design draws a one-line {@code ◎ BEST} chip, because in the first handoff a pick was
 * per-window and the rail's day only ever held one. The second handoff (plan §2.3) made the picks
 * <em>forecast-wide</em> — exactly two across every rendered window, each bound to the window it
 * falls on, and either may be a sunrise or a sunset. A day-level {@code BEST} with no event names
 * a day when the thing being recommended is a window, and the reader has to open the card to find
 * out which half of the day it meant. So line two names the event.
 *
 * <p>When both picks land on the same day the tile flags the <b>Best</b> one only. Two chips would
 * double the densest element in the rail for a runner-up, and the Also good still carries its own
 * region-chip accent on the same tile.
 *
 * <h2>The tile is not itself a widget — the action inside it is</h2>
 *
 * <p>The v1 pill this is copied from puts {@code role="button"} on the whole tile and then renders
 * region chips, themselves {@code role="button"}, inside it. Every clickable tile has at least one
 * chip, so the nesting is unconditional, not an edge case: axe's {@code nested-interactive} fires
 * on all of them, and because a button takes its accessible name from its contents the tile
 * announces as one ~20-word blob — dates, times, verdict, every region, and the arrow glyphs —
 * which is then read again chip by chip on Tab.
 *
 * <p>So the action is a native {@code <button>} — the pattern {@code CloseToHome} already argues
 * for in its own comment ("a native {@code <button>} rather than the handoff's
 * {@code role="button"} + tabindex + key handlers: it is Enter- and Space-activatable and focusable
 * with no JavaScript at all") — and the tile goes back to being the plain {@code <div>} the design
 * of record draws. The chips become siblings of one button rather than descendants of another.
 *
 * <p><b>The whole-tile click goes with it, and that is the honest end of the trade.</b> Keeping it
 * needed either a lint suppression or reconstructed key handlers on an element with no role, and
 * making the tile itself the button is worse than the ARIA version — interactive content inside a
 * {@code <button>} is invalid HTML, and the chips have to stay interactive. The button is full
 * width so the target is a row rather than ten pixels of text, and the tile carries no hover lift:
 * lifting a container that cannot be pressed is the same lie in a quieter voice.
 * {@code BriefingSummaryStrip} is untouched — plan §5's copy-not-rewire rule protects the v1
 * component from edits, and constrains nothing about this file's own markup.
 *
 * <h2>An away day keeps its sun times</h2>
 *
 * <p>The mock replaces the whole {@code ↑ ↓} line with {@code ✈ Away · business} — but its own away
 * banner says, in the same document, that "sun times still shown in the rail", and the rail's code
 * does not do that. The prose is the intent and the code is the slip: sunrise and sunset are
 * almanac, true whether or not a forecast was run, and someone away on business can still shoot
 * from where they are. So the flight marker moves to the verdict line, which is the line that
 * actually has nothing to say. Rendering it on both — the first version of this — printed
 * {@code ✈ Away} twice on one tile.
 *
 * <h2>The pick chip is ungated, and that is the P4 decision §7 asked for</h2>
 *
 * <p>Plan §7 poses it as a choice between greying and clean omission, on the premise that "the
 * underlying prose is PRO-gated today". That premise is true of {@code BestBet.headline} — Claude
 * best-bet-advisor output on the {@code bestBets} path — and <b>false</b> of what this reads.
 * {@code BriefingWindow.Pick} is region gloss, which no role check touches anywhere on the
 * {@code /api/briefing} path, which LITE already reads in two places on the v1 Plan tab, and which
 * {@code freemium_ui_strategy.md:79-80} lists as LITE-included. §2.3 rejected the {@code bestBets}
 * vehicle partly <em>because</em> routing through it "would make the pilot's headline feature
 * PRO-only against §7", and §7's own role-gating bullet says Best Bet needs no new gating.
 *
 * <p>The one real counter — that v1 shows LITE a blurred {@code BestBetPlaceholder} one row away,
 * so an ungated chip would contradict it — does not reach this arm: the flag branches above
 * {@code <main>}, so {@code DailyBriefing} and its placeholder never render beside this rail. The
 * two are never on screen together. If the arms are ever merged, that is the point to revisit.
 *
 * <h2>It uses the shared popover host rather than its own panel</h2>
 *
 * <p>The handoff expected the copied tile to bring its own gloss tooltip and P5's pick chips to be
 * {@code usePopoverHost}'s first caller. Brought forward deliberately, for one reason the copy
 * cannot answer: on phone this rail is a horizontal scroller ({@code overflow-x: auto}), and a
 * panel placed once from a viewport rect describes a position its anchor leaves the instant the
 * rail is swiped. The host dismisses on scroll; the copied panel has no scroll handling and no
 * keyboard dismissal at all. Nothing in {@code BriefingSummaryStrip} changes either way.
 *
 * @param {object}   props
 * @param {Array}    props.tiles       tile descriptors from {@code buildRailTiles}
 * @param {Function} [props.onTileClick]   callback(date, targetType) for a rated tile
 * @param {Function} [props.onRegionClick] callback(regionName, date, targetType) for a chip
 */
export default function WindowFirstDayRail({ tiles, onTileClick, onRegionClick, onOpenPick, peeksSuppressed }) {
  const { popover, show, hide } = usePopoverHost();

  const showGloss = useCallback((event, region, headColour, dateStr) => {
    // Nothing while a dialog is over the page, and this is a fix rather than a nicety: the gloss
    // panel is `z-index: 60` and `Modal` is `z-50`, so without this a hover on the way to the pick
    // dialog paints a tooltip OVER it, with no focus trap to stop the pointer reaching either. The
    // rail is the last surface in the arm with no such guard — the spot strip has carried one since
    // P10′.
    if (peeksSuppressed) return;
    show(
      `${dateStr}:${region.regionName}`,
      event.currentTarget,
      (
        <>
          <span className="summary-region-tip-head" style={{ color: headColour }}>
            {region.verdictLabel}
            {region.wx ? ` · ${region.wx}` : ''}
          </span>
          <span className="summary-region-tip-body">
            {region.glossDetail || region.glossHeadline || region.summary || 'No detail available.'}
          </span>
        </>
      ),
      { width: TIP_WIDTH, gap: TIP_GAP, margin: TIP_MARGIN },
    );
  }, [show, peeksSuppressed]);

  if (!tiles || tiles.length === 0) return null;

  return (
    <>
      <div
        data-testid="window-first-day-rail"
        // Padding lives in `.rail-scroller` entirely, all four sides, because the phone gutter is a
        // media query and one cannot reach an inline style. That also retires the trap this comment
        // used to describe: the inline `padding` SHORTHAND set padding-bottom: 0 at inline priority
        // and beat the class's own `padding-bottom: 4px`, while its paired `margin-bottom: -4px`
        // still applied, so the ring room was silently zero. With one owner there is nothing left to
        // disagree — but the 4px bottom is now restated in the phone override for the same reason.
        className="rail-scroller flex gap-2"
      >
        {tiles.map((tile) => {
          const clickable = tile.ratedCount > 0 && !tile.isAway;
          // Verdict drives the text colour; "today" owns the border, so the two never compete for
          // the same channel — a gold-bordered tile can still read amber or green inside.
          const verdictColour = tile.peak === 'go'
            ? 'var(--color-badge-go)'
            : tile.peak === 'maybe'
              ? 'var(--color-verdict-marginal)'
              : 'var(--color-plex-text-muted)';
          const handleClick = () => { if (clickable) onTileClick?.(tile.date, tile.targetType); };
          return (
            <div
              key={tile.date}
              data-testid="rail-day"
              data-today={tile.isToday ? 'true' : undefined}
              data-away={tile.isAway ? 'true' : undefined}
              className="min-w-0 select-none"
              style={{
                flex: '1 0 150px',
                border: `1px solid ${tile.isToday ? 'rgba(201,162,75,0.55)' : 'var(--color-plex-border)'}`,
                borderRadius: '9px',
                background: tile.isToday ? 'rgba(201,162,75,0.08)' : 'var(--color-plex-panel)',
                padding: '9px 11px 10px',
                opacity: tile.isAway ? 0.45 : 1,
              }}
            >
              <div
                data-testid="rail-day-dateline"
                className="flex items-baseline"
                style={{ gap: '7px', minHeight: `${DATE_ROW_MIN_HEIGHT_PX}px` }}
              >
                <span
                  className="font-mono uppercase text-plex-text-muted"
                  style={{ fontSize: '9.5px', letterSpacing: '0.1em' }}
                >
                  {tile.dow}
                </span>
                <span className="font-bold text-plex-text" style={{ fontSize: '16px', lineHeight: 1 }}>
                  {tile.dayNum}
                </span>
                <span
                  className="font-semibold text-plex-text truncate"
                  style={{ fontSize: '12.5px' }}
                >
                  {tile.dayLabel}
                </span>
                {/* A BUTTON, not the read-out it began as. The plan's second handoff asks for this
                    in as many words — "region chips open a gloss, pick chips open the pick's prose"
                    — and it was the one clause of that sentence still undone. It opens the SAME
                    dialog the window card's pick badge opens, rather than a second surface: the two
                    say the same words in the same accent, so a reader who taps one and then the
                    other must not be shown two different things.

                    The tile around it stays inert, so nothing is nested inside anything
                    interactive; the region chips beside it are already buttons on the same
                    principle. Every inline style below is unchanged — they are what
                    `DATE_ROW_MIN_HEIGHT_PX` is derived from, and the rail's baselines go ragged if
                    the chip's box moves. */}
                {tile.pick && !tile.isAway && (
                  <button
                    type="button"
                    data-testid="rail-pick-flag"
                    data-pick={tile.pick.kind}
                    onClick={() => onOpenPick?.(tile.date, tile.pick.targetType)}
                    // The visible words come first and contiguously, so WCAG 2.5.3's label-in-name
                    // holds; the day is appended because an element list otherwise shows two
                    // identical "BEST sunset" controls, one here and one on the card below.
                    aria-label={`${tile.pick.kind === 'best' ? 'BEST' : 'ALSO'} ${tile.pick.event} — ${tile.dayLabel}`}
                    className="rail-pick-flag ml-auto flex-none self-start font-mono text-right"
                    style={{
                      fontSize: `${FLAG_FONT_PX}px`,
                      lineHeight: FLAG_LINE_HEIGHT,
                      fontWeight: 600,
                      padding: `${FLAG_PAD_Y_PX}px 6px`,
                      borderRadius: '5px',
                      background: tile.pick.kind === 'best'
                        ? 'rgba(138,174,114,0.16)'
                        : 'rgba(124,141,214,0.16)',
                      // Both are the LIFTED text variants, never the channel colours themselves:
                      // 9.5px on a 16% tint of its own hue is exactly the case those exist for.
                      color: tile.pick.kind === 'best'
                        ? 'var(--color-badge-go)'
                        : 'var(--color-badge-also)',
                    }}
                  >
                    <span className="block whitespace-nowrap">
                      <span aria-hidden="true">◎</span>
                      {tile.pick.kind === 'best' ? ' BEST' : ' ALSO'}
                    </span>
                    <span className="block whitespace-nowrap font-normal opacity-80">
                      {tile.pick.event}
                    </span>
                  </button>
                )}
              </div>

              <div
                data-testid="rail-day-sun"
                className="font-mono text-plex-text-muted whitespace-nowrap"
                style={{ fontSize: '10px', marginTop: '4px', fontVariantNumeric: 'tabular-nums' }}
              >
                {[
                  tile.sunriseTime && `↑ ${tile.sunriseTime}`,
                  tile.sunsetTime && `↓ ${tile.sunsetTime}`,
                ].filter(Boolean).join(' ')}
              </div>

              <div
                data-testid="rail-day-verdict"
                className="font-mono"
                style={{
                  fontSize: '10.5px',
                  marginTop: '4px',
                  fontStyle: tile.peak === 'poor' ? 'italic' : 'normal',
                  color: tile.isAway ? 'var(--color-verdict-marginal)' : verdictColour,
                }}
              >
                {tile.peakLabel}
              </div>

              <div
                data-testid="rail-day-regions"
                className="font-mono"
                style={{
                  fontSize: '10.5px',
                  color: tile.isAway ? 'var(--color-plex-text-muted)' : 'var(--color-plex-text-secondary)',
                  lineHeight: 1.35,
                  marginTop: '4px',
                }}
              >
                {tile.regions?.length
                  ? tile.regions.map((region, i) => (
                    <span key={region.regionName}>
                      {i > 0 && <span style={{ color: 'var(--color-plex-text-muted)' }}>, </span>}
                      <span
                        data-testid="rail-region-chip"
                        data-peak={tile.peak}
                        data-pick={region.pickKind || undefined}
                        className="summary-region-chip"
                        role="button"
                        tabIndex={0}
                        onClick={(e) => {
                          e.stopPropagation();
                          onRegionClick?.(region.regionName, tile.date, region.targetType);
                        }}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter' || e.key === ' ') {
                            e.preventDefault();
                            e.stopPropagation();
                            onRegionClick?.(region.regionName, tile.date, region.targetType);
                          }
                        }}
                        onMouseEnter={(e) => showGloss(e, region, verdictColour, tile.date)}
                        onMouseLeave={() => hide(`${tile.date}:${region.regionName}`)}
                        onFocus={(e) => showGloss(e, region, verdictColour, tile.date)}
                        onBlur={() => hide(`${tile.date}:${region.regionName}`)}
                      >
                        {region.pickKind && <span className="rn-mark" aria-hidden="true">◎</span>}
                        {region.shortName}
                      </span>
                    </span>
                  ))
                  : tile.countLabel}
              </div>

              {clickable && (
                <button
                  type="button"
                  data-testid="rail-day-show-on-map"
                  onClick={handleClick}
                  aria-label={`Show ${tile.dayLabel} on the map`}
                  className="rail-day-show-on-map font-mono block w-full text-left"
                  style={{ fontSize: '10px', marginTop: '8px' }}
                >
                  <span aria-hidden="true">◍ </span>
                  Show on map
                  <span aria-hidden="true"> →</span>
                </button>
              )}
            </div>
          );
        })}
      </div>
      <PopoverHost
        popover={popover}
        className={`summary-region-tip${popover?.alignRight ? ' summary-region-tip--right' : ''}`}
      />
    </>
  );
}

WindowFirstDayRail.propTypes = {
  tiles: PropTypes.arrayOf(
    PropTypes.shape({
      date: PropTypes.string.isRequired,
      isToday: PropTypes.bool,
      targetType: PropTypes.string,
      dow: PropTypes.string.isRequired,
      dayNum: PropTypes.string.isRequired,
      dayLabel: PropTypes.string.isRequired,
      sunriseTime: PropTypes.string,
      sunsetTime: PropTypes.string,
      peak: PropTypes.oneOf(['go', 'maybe', 'poor', 'away']).isRequired,
      peakLabel: PropTypes.string.isRequired,
      countLabel: PropTypes.string,
      pick: PropTypes.shape({
        kind: PropTypes.oneOf(['best', 'also']).isRequired,
        event: PropTypes.string.isRequired,
        // REQUIRED now the chip is a control: it is half the key the shell resolves the card by, so
        // a missing one yields a button that opens nothing — the species §6 bans outright.
        targetType: PropTypes.string.isRequired,
      }),
      regions: PropTypes.arrayOf(
        PropTypes.shape({
          regionName: PropTypes.string.isRequired,
          shortName: PropTypes.string.isRequired,
          targetType: PropTypes.string,
          verdictLabel: PropTypes.string,
          wx: PropTypes.string,
          summary: PropTypes.string,
          glossHeadline: PropTypes.string,
          glossDetail: PropTypes.string,
          pickKind: PropTypes.oneOf(['best', 'also']),
        }),
      ),
      ratedCount: PropTypes.number.isRequired,
      isAway: PropTypes.bool,
      confidence: PropTypes.oneOf(['high', 'medium', 'low']),
    }),
  ).isRequired,
  onTileClick: PropTypes.func,
  onRegionClick: PropTypes.func,
  /** callback(date, targetType) — opens that window's pick prose. */
  onOpenPick: PropTypes.func,
  /** True while a dialog is over the pane; suppresses the region gloss, which out-ranks it. */
  peeksSuppressed: PropTypes.bool,
};
