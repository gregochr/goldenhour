# Landing page and user guide: typography, structure, length

Owner handoff, 2026-11-28 (`design_handoff_landing_and_guide.zip`, reviewed against `1d41144`, v2.42.5).
The renders (before and an approximate after, 1280 and 390 px) stayed with the owner's handoff; they are
14 MB and not committed. No feature flags. Replaced text, CSS and images are deleted in the same PR.

Three PRs, in order. Each is built by a Sonnet subagent in its own worktree; the planner reviews the diff.

## T1, typography (PR 1)

Running text is IBM Plex Sans (`--body`), but five selectors set it in Newsreader:

| Selector | File | Change |
|---|---|---|
| `.story-grid p` | `landing/home.css` | `--body`, 17 px, line-height 1.65 |
| `.feat p.q` | `landing/home.css` | `--body` italic 16 px, `--ink-soft` (the lines are deleted in L1 anyway) |
| `.step.result .reflect` | `landing/home.css` | `--body` italic 16 px |
| `.shead .dateline` | `landing/home.css` | `--body` italic 16 px |
| `.g-lede` | `landing/guide/guide.css` | `--body` 17 px |

**The rule** (a comment at the top of `landing/photocast.css`, mirrored in `screen-spec.md` §3): Newsreader is
for headings (h1–h3), the PhotoCast wordmark and the hero's one italic tagline. Every sentence you read is IBM
Plex Sans.

After the change `--serif` is used only by `.mark .wm`, `.foot .tag` and (via `--disp`) the hero `.sub`, so it
is folded into `--disp` and the token deleted.

Guard: a Vitest test (`frontend/src/test/landingTypography.test.js`) reads every CSS file under `landing/`
and every `<style>`/`style=` in `landing/**/*.html`, and fails if any rule whose selector targets `p`, `li`,
`figcaption`, `td` or a class used on one sets `font-family` to Newsreader, `--serif` or `--disp`, except
`.hero .sub`.

## L, landing (PR 2)

L1 features cut to five as "What only PhotoCast does." (two scores, tide at the light, Ask PhotoCast,
northern lights, Met Office warnings); "200+ locations across northern England" moves to the FAQ's "Which
areas…". L2 the hero keeps the h1, the italic tagline, one sentence and the CTA. L3 a new hero pair: This week
on a phone with the map view opened from a card, from a local build, webp at 2×, "development build" caption,
zoom viewer kept; `shot-map.webp` deleted if unused. L4 "What do the stars mean?" becomes the FAQ's first
question; `.stars` and its CSS go. L5 order: hero → #plan → #field → features → pricing → FAQ → story (about
four paragraphs) → final CTA. L6 one menu on every landing and guide page: Plan a shoot · Pricing · Guide ·
FAQ · Start free; the phone `secbar` stays on the home page only, in the new order. L7 the #platforms band
becomes one line under the hero CTA: "In your browser now · iPad and iPhone apps in development".

**As built (L, 2026-11-28).** The hero shots are the guide's own phone shots (`guide/shots/this-week-phone.webp`,
`map-phone.webp`, 2×, taken after the screen-spec changes), referenced in place so a G3 retake updates both. With
only the handoff's cuts the page measured 10,875 / 9,274 px, so these were added to meet the targets: a two-column
hero from 1024 px; #plan keeps only the Further ahead shot (`shot-week.webp` duplicated the hero and is deleted);
the place-sheet figure under #features is deleted (`shot-detail.webp`); G2's pricing change (five bullets a card,
"Compare Lite and Pro in full →", `landingPricing.test.js`) moved here from phase G; the phone section gap is 72 px.
Result: 8,795 px at 1280 and 7,859 px at 390.

Kept as is: honest-claims wording, pricing ("to be announced"), the final CTA, the footer.
Acceptance: home page under 9,000 px at 1280 and under 8,000 px at 390, measured and reported in the PR.

## G, guide (PR 3)

G1 `planning.html` splits into the overview (`#screen`, `#top`, `#lens`, `#away`, `#tips` plus a short
walk-through), `this-week.html` (`#this-week`, `#your-places`, `#days`, `#windows`, `#open`, `#bestbet`) and
`further-ahead.html` (`#further-ahead`, `#topics`, `#new`, `#filters`, `#entries`, `#tides`, `#actions`,
`#watch-a-date`). `#find` and `#warnings` become two sentences and a link each. `guide/moved.js` redirects
every moved `planning.html#id` to its new page, keeping `?embed=1`. Updated with it: `frontend/src/utils/guide.js`,
`guideSections.test.js`, `guide-search.js` (PAGES), the hub cards and every cross-link; a test asserts every id
the app links to exists on its page. `planning.html` under 6,000 px.
G2 (built in phase L) the landing pricing cards keep five bullets each and link to the hub's Lite/Pro table ("Compare Lite and
Pro in full →"); a test compares the Pro feature names on the two.
G3 the new pages take the screen-spec shots as each screen-spec PR lands (`screen-spec.md` §8).

All pages pass the existing a11y job; images have alt text; the new hero pair is described.
