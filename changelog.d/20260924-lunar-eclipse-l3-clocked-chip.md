### Added — Plan-tab type registries and the clocked topic chip (frontend, L3)

Phase L3 of `docs/engineering/lunar-eclipse-plan.md` §2.1 and §2.6: `LUNAR_ECLIPSE` joins every
client-side registry the solar `ECLIPSE` type already sits in, one channel shared by two types.

`utils/windowFirstCards.js`'s `badgeChannel` gains an exact-match arm for `LUNAR_ECLIPSE` beside the
existing `ECLIPSE` one, ahead of the substring arms, so a served `LUNAR_ECLIPSE` badge takes the
same `eclipse` channel colour, ring and swatch every existing channel-keyed rule already applies —
and a never-shipped compound type such as `LUNAR_ECLIPSE_TIDE` still falls through to the `tide`
substring arm exactly as before, unaffected by the new exact match. `utils/windowFirstTopics.js`'s
`WHOLE_SKY_TOPIC_TYPES` gains `LUNAR_ECLIPSE` (its `regions` names visibility coverage, not an
eligibility roster, for the same reason `ECLIPSE`'s does). `utils/comingUpHandoff.js`'s
`SWATCH_COLOR` and `utils/comingUpGlyphs.js`'s `TYPE_GLYPHS` (`'lunar-eclipse': '🌘'`, a waning-
crescent override distinct from the shared `◐` eclipse-family glyph, mirroring the existing
`supermoon` override) each gain their own entry, since both are keyed by type rather than by
channel. `components/map/WindowControl.jsx`'s `CHANNEL_ICON` needed no change — it is keyed by
channel, and `LUNAR_ECLIPSE` already resolves to the existing `eclipse` entry through the shared
`badgeChannel` — pinned by a new test proving a `LUNAR_ECLIPSE` badge draws the identical row icon
as `ECLIPSE`.

New `CLOCKED_TOPIC_TYPES` (`ECLIPSE`, `LUNAR_ECLIPSE`) and `chipClock(badge)` in
`windowFirstTopics.js`: a listed badge's own `eventTime` — a genuinely different instant from the
window's own solar event, since an eclipse's maximum can fall hours from sunrise or sunset —
prints after a divider in `WindowFirstHeatStrip`'s topics line, in the tide chip's existing
emphasised pill shape (`.wf-hc-tw.wf-hc-clocked[data-channel="eclipse"]`: `padding: 1px 8px;
border-radius: 999px; background: rgba(196,120,127,.14); box-shadow: inset 0 0 0 1px
rgba(196,120,127,.42); color: #E3AEB3`, with `.wf-hc-clocked-time` carrying the
`border-left: 1px solid rgba(196,120,127,.45); padding-left: 6px` divider) — literal colour values,
not Tailwind theme tokens, so there is no `@theme static` pruning risk. Every other badge is
unaffected: the chip renders its plain `wf-hc-tw` shape exactly as before whenever `chipClock`
returns null (an unlisted type, or a listed type with no served `eventTime`). The card's hidden
`sr-only` accessible-name sentence appends the same clause ("Lunar eclipse at 05:13") from the same
`chipClock` read that builds the visible chip, so the two can never drift; a `SUPERMOON` badge
carrying an `eventTime` prints no clock anywhere, visible or accessible.

Backend: amended the `Badge` javadoc on `BriefingWindow.java` — the existing "render one or the
other, never both side by side" rule for a badge's own clock vs. its window's is scoped to a badge
whose clock names the *same* solar event as the window's; a `CLOCKED_TOPIC_TYPES` member's clock is
a wholly different event (the eclipse's own maximum), so stating both is not the duplication the
original rule forbids.

Tests: `windowFirstCards.test.js` (`badgeChannel('LUNAR_ECLIPSE')` routing, the exact-vs-substring
distinction), `windowFirstTopics.test.js` (`WHOLE_SKY_TOPIC_TYPES` membership, the 17-type shipped
roster now matching `TopicRarity.RANK_BY_TYPE`, and a new `chipClock` describe block covering both
listed types, case-insensitivity, an unlisted type, a listed type with no `eventTime`, and a missing
badge), `WindowFirstHeatStrip.test.jsx` (a `LUNAR_ECLIPSE` chip's divider and text, the solar
`ECLIPSE` chip gaining the identical treatment, a `SUPERMOON` badge with an `eventTime` rendering no
clock anywhere, a listed type with no `eventTime` rendering no clock, and the accessible name's "at
HH:mm" clause), `comingUpGlyphs.test.js` and `comingUpHandoff.test.js` (the new glyph and swatch
entries), `WindowControl.test.jsx` (the shared channel icon). 6547/6547 frontend tests pass; `npm
audit` reports 0 vulnerabilities; `npm run build` succeeds.

Adversarial review (five lenses — runtime behaviour, CSS/tokens, test quality, accessibility,
conventions/scope — all read-only against the working tree) found no defects requiring a fix; one
non-blocking test-quality note (a redundant assertion re-covering an already-pinned case) was left
as harmless, additional coverage.

Browser verification: seen — the Plan tab, Coming up tab and general layout render without error at
desktop and 390px with this branch's own frontend and backend running locally; the served
`GET /api/briefing` payload carries the simulated `LUNAR_ECLIPSE` badge with the expected shape
(`eventTime: "05:12"`, `rarityRank: 2`, facts, note, rarityNote). Tested via a real browser
`getComputedStyle` probe (not jsdom) on an injected `.wf-hc-tw.wf-hc-clocked[data-channel="eclipse"]`
node against this branch's own compiled stylesheet: every literal value (padding, border-radius,
background, box-shadow, `#E3AEB3`, the divider) resolved exactly as specified, confirming no CSS
pruning. **Not seen**: the chip actually painted on a live card with real data. The
`HotTopicSimulationService` template anchors the badge to *today's* sunrise only, and this
verification ran in the afternoon after that morning's sunrise had already elapsed — the Plan
matrix's pre-existing, unrelated "this morning has gone" empty-cell behaviour (confirmed against the
served payload and the window-sheet popup's own 6-window carousel, which also excludes it) hid the
only window this simulation ever populates. Component-level Vitest coverage renders the real chip
with real fixture data and is the substitute evidence for that one gap.
