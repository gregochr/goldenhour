### Fixed — the four separators an accessible name really depends on now say so

A rebuilt measurement harness found four spaces that an accessible name genuinely depends on, and
none of them carried a note — while the separators that do nothing were the ones with warnings on
them. Each now states what it holds up. Comments only: no rendered or runtime change.

| where | without the space, the name reads |
|---|---|
| `MapView` subject filter chips | "🏔️Landscape" |
| `SlotLocationName` (Plan grid) | "🏔️Angel of the North" |
| `WindowControl`'s "Back to" row | "Back toThis morning — sunrise or sunset?" |
| `PromptTestView` run progress | "⟳2/5" |

Three of the four — `MapView`, `SlotLocationName` and `PromptTestView` — are **literal spaces written
straight into the JSX**, not `{' '}` expressions, and JSX deletes any whitespace that contains a line
break, so reflowing one of those lines would remove the space with no other sign. Their notes say
so. (`WindowControl`'s is an explicit `{' '}`.) `SlotLocationName`'s also sits *inside* its element
(`<span>{icon} </span>`) — a position browsers keep, and only jsdom's polyfill trims.

**How it was found.** Every whitespace-only text node in the DOMs the test suite renders — 4,167
across 921 distinct DOMs, captured at the end of each test and after every `fireEvent` — was
removed one at a time against the production-built stylesheet, at widths drawn from that
stylesheet's own breakpoints. Names were read by Playwright's accessible-name algorithm over each
of Chromium's, WebKit's and Firefox's own layout, and by Chromium's native accessibility tree,
which agreed with Playwright on all 4,167. 119 change an accessible name; every one belongs to the
four sites above or to the four `#819` already annotated (both `RegisterPage` terms separators,
`WindowComingUpConditions`' name and cadence, the Coming up tide chart). 906 change only what is
drawn — ordinary visible spaces, left unannotated, since removing one would show on screen.

It also confirmed by measurement two claims `#819` had made from the rule: `MapBreadcrumb`'s window
label renders glued without its space ("Tonightsunset") yet changes no name — its `<nav>` is named
by `aria-label` — and the Coming up `peak`/date separator is inert.

**The canonical doc's evidence is now stated correctly.** `WindowFirstComingUpHandoff` said the rule
was "measured in Chromium, WebKit and Firefox". Those readings were Playwright's own algorithm run
over each engine's styles — not three native implementations. It now says what was measured and by
what, and that native readings exist for Chromium alone.
