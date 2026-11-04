# User guides and the in-app guide panel — plan

Source: the design handoff `PhotoCast User Guide.zip` (2026-11-03), unpacked as
`design_handoff_user_guides/`. Its README is the brief; this plan is how it lands in this repo.

## What ships

1. **Nine static guide pages on the landing site**, served at `https://photocast.online/guide/`
   (hub, getting started, reading the forecast, At a Glance, Coming up, Map, aurora and night sky,
   Ask PhotoCast, Settings), with `guide.css`, `guide.js` (embed mode), `guide-search.js` (hub
   search) and eight WebP screenshots under `guide/shots/`.
2. **An in-app help panel** that shows those pages in an iframe (`?embed=1`): a 440px non-modal
   drawer on desktop and tablet, a `BottomSheet` at about 88% height on phone.
3. **Doors into the panel**: a masthead `?` per tab, a `?` in the Ask header and the Settings
   header, 13 in-context `GuideLink` buttons, and a "What does this do?" text link beside each
   Lite lock.

A change to the guide copy is then a landing deploy (`release-landing.sh`), not an app release.

## Findings that change the handoff's deploy steps

- **The bundle's five landing pages are stale.** Its `index.html`, `faq.html`, `privacy.html`,
  `terms.html` and `acknowledgements.html` predate several merged copy changes (favicon.svg and
  apple-touch-icon links, the FAQ section nav and rewritten answers, the privacy retention rules,
  the sub-regions Lite line in terms, the acknowledgements additions, the index carousels and image
  viewer). **Do not copy them over.** Apply only the handoff's actual delta to the repo's current
  files: a `Guide` link in the masthead nav and footer of all five, and the FAQ's Ask answer
  linking to `guide/ask-photocast.html`. `photocast.css` is likewise **not** replaced (the repo's
  copy is newer; `guide.css` uses only its tokens).
- **`landing/Dockerfile` copies flat files only**, and its fingerprint loop rewrites `"file"` in
  `*.html` at the root only. The guide needs a directory COPY and a fingerprint pass inside
  `guide/` (its own assets `"guide.css"`, `"guide.js"`, `"guide-search.js"`, `"shots/x.webp"`, and
  the parent's `"../photocast.css"`, `"../favicon.png"`).
- **`release-landing.sh` preflight and smoke test see root pages only.** `list_referenced` reads
  `landing/*.html` and strips only `./`, so a guide page's `../photocast.css` would be reported as
  missing and `guide/*.html` would never be smoke-tested. It needs to walk `guide/*.html` and
  `guide/guide.css`, resolve each reference against the page's directory, and accept a file
  covered by a directory `COPY guide/ …` line.
- **Framing is blocked today.** `landing/security-headers.conf` sends `X-Frame-Options:
  SAMEORIGIN` on every response, and the app's CSP (`nginx.conf` `$csp`) has `frame-src
  https://challenges.cloudflare.com` only. Both ends change: `/guide/` documents send
  `Content-Security-Policy: frame-ancestors https://app.photocast.online` and no
  `X-Frame-Options`; the app adds `https://photocast.online` to `frame-src`.
- **`postMessage` trust.** `guide.js` posts `{type:'photocast-guide', page, title, hash}` to
  `'*'`. That payload is harmless, but the app's listener must check `event.origin` against the
  guide origin and `event.source` against its own iframe, and use `title` only as text.

## Phases (one PR each, in order: G1 → owner deploys the landing site → G2 → G3)

### G1 — the guide on the landing site (landing-only, deployable on its own)

- Add `landing/guide/` from the bundle verbatim: the nine pages, `guide.css`, `guide.js`,
  `guide-search.js`, `shots/*.webp`. Do not edit the copy (the owner's open copy questions are
  listed at the end of this plan). **Do not copy** the bundle's `photocast.css` or `favicon.png`
  (the repo's are newer: the favicon is #1471's film-spine mark).
- Add the handoff's delta to the repo's **current** `index.html`, `faq.html`, `privacy.html`,
  `terms.html`, `acknowledgements.html`: `<a href="guide/index.html">Guide</a>` in the masthead nav
  (after Pricing, before FAQ) and in the footer links (after Acknowledgements), as the bundle places
  them. On `faq.html` **append** to the existing Ask answer's paragraph (`landing/faq.html` ~:119)
  the sentence `The <a href="guide/ask-photocast.html">Ask PhotoCast guide</a> shows how it works.`
  Do not paste the bundle's Ask sentence (the owner rewrote that answer since). The phone
  `nav.secbar` on `index.html` postdates the bundle; leave it alone, so on a phone the Guide link
  is footer-only for now (a known gap, not a design decision).
- `landing/Dockerfile`: `COPY guide/ /usr/share/nginx/html/guide/`. Extend the fingerprint step so
  that inside `guide/` each own asset (`*.css *.js` and `shots/*.webp`) and each parent asset
  referenced as `"../<file>"` is rewritten to `?v=<hash>` in `guide/*.html`. The root loop is
  unchanged.
- `landing/nginx.conf` (no new include file: CI's `nginx -t` step and `scripts/nginx-log-canary.sh`
  mount only `nginx.conf` and `security-headers.conf`):
  - `location = /guide { absolute_redirect off; return 301 /guide/; }` (without it `/guide` serves
    the hub at the wrong base, so every relative URL breaks; `absolute_redirect off` because nginx
    sees plain http behind the tunnel).
  - `location /guide/ { try_files $uri $uri/ =404; error_page 404 /guide/index.html; ... }` with,
    inline and every one `always`: `Cache-Control "no-cache"`, nosniff, X-XSS-Protection,
    Referrer-Policy (as `security-headers.conf`), and `Content-Security-Policy "frame-ancestors
    https://app.photocast.online"`. **No** `X-Frame-Options`. The location's own `error_page` is
    what keeps a mistyped guide URL inside `/guide/` (the server-level `error_page 404 /index.html`
    would otherwise send the marketing page with SAMEORIGIN, which the panel refuses to show).
  - The asset regex location stays as it is: regex beats the non-`^~` prefix, so guide css/js/webp
    keep the 30-day cache; X-Frame-Options on a subresource has no effect.
  - Production framing is the app origin only. Local dev does not frame production; G2 points the
    app at a locally served `landing/` instead.
- `release-landing.sh`:
  - `list_referenced` also walks `git ls-tree -r --name-only "$sha" landing/guide/` (recursive;
    the existing call is not) for `*.html` and `guide.css`, resolving each reference against the
    page's directory (`../photocast.css` → `photocast.css`, `shots/a.webp` → `guide/shots/a.webp`).
  - `preflight_assets` accepts a name under `guide/` when the Dockerfile has `COPY guide/ `.
  - `smoke_paths` adds `/guide/` and every `/guide/*.html`.
  - The remote smoke and the public check (which today test `/` and `/photocast.css` only) fetch
    `/guide/map.html` and assert the CSP header contains `frame-ancestors
    https://app.photocast.online` and no `X-Frame-Options` is present, and that `/guide` answers
    301. Status codes alone cannot tell a guide page from the index fallback. The public check is
    the only one that would catch Cloudflare adding or stripping a header.
  - Gate: `./release-landing.sh --preflight-only <sha>` on the branch commit (local, no ssh: it
    exits before `check_ssh`).
- Tests in `frontend/src/test/landingNginxConf.test.js`: `location /guide/` sends the
  frame-ancestors CSP with `always`, sends no X-Frame-Options, keeps nosniff and Referrer-Policy,
  has its own `error_page 404`; `location = /guide` returns 301 `/guide/`; the root location still
  sends `X-Frame-Options "SAMEORIGIN"` through `security-headers.conf`.
- `landing/README.md`: the file list gains `guide/`; rewrite the "Adding a file" rule (a file under
  `guide/` needs no `COPY` line, every other file still does); fix the stale "There is no CI for
  it" line (CI runs `nginx -t` and the canary on it); add a short "Guide pages" section with the
  section-id contract (below) and how to fill a placeholder.
- Gates: the frontend's four commands (the test file changes, so CI runs the suite and the landing
  nginx step). Changelog entry.
- After merge, the **owner** deploys with `./release-landing.sh` (a session never deploys).

### G2 — the panel and the three header doors (app)

**Release order: the owner deploys G1's landing site before any app release that contains G2.**
Until then `/guide/` refuses to be framed and every door opens a blank panel.

#### Three presentations, chosen at press time

The handoff's "440px drawer over the content" does not survive this app's layering (review of
2026-11-03): over a Modal it sits under the scrim, a screen reader cannot reach it past an
`aria-modal` layer, at 1280px it covers the masthead cluster holding its own toggle, at tablet
width it covers most of the page, and under the tablet Ask sheet it is inert. So `openGuide`
decides how to present from the state at the moment of the press:

1. **Docked column** — desktop and wide (`useAskSurface()` is `'desktop'` or `'wide'`, i.e.
   ≥1024px) **and no `[role=dialog][aria-modal=true]` is in the DOM**. The guide takes the Ask
   dock's slot in the shell's docked row (`wf-shell--docked`, 360/380px; the guide may use up to
   440px where the row has room), below the masthead, so the masthead stays reachable. It is an
   `<aside aria-label="PhotoCast guide" data-guide-surface>`, **not** `role="dialog"`
   (`shellForeignDialog` would refuse Ask while it is up, and `aria-modal` would stand down every
   Map Escape rule). Guide and Ask dock are **mutually exclusive both ways**: opening the guide
   hides the dock (remember `dockWasOpen`), opening the dock closes the guide. Closing the guide
   restores the dock if `dockWasOpen`, with the dock's mount autofocus suppressed (an `autoFocus`
   prop on AskDock, default true), and focus goes back to the guide's door. When the door was
   inside the dock (dock header `?`, empty-state `?`), its node is gone, so focus returns to the
   restored dock's field instead (use autofocus for that case). On Operations (no dock row), the
   docked guide closes on entering the tab.
2. **Stacked dialog** — desktop/wide while one aria-modal layer is up (Settings, the popup, the
   four-day sheet, the Operations modal): a right-hand panel portalled to `document.body`,
   `role="dialog" aria-modal="true" aria-label="PhotoCast guide"`, with a scrim, on a new z rung
   above Modal's 50 and the peek's 60 (add it to the ladder comment in `index.css` ~:800-825;
   below BottomSheet's 9999). The layer under it is told it is stacked (below).
3. **Sheet** — phone and tablet (`useAskSurface()` is `'phone'` or `'tablet'`, <1024px):
   `BottomSheet size="tall" closeOnEscape label="PhotoCast guide"`. It portals to `body`, so the
   tablet Ask sheet's `inert` on the app container does not reach it. BottomSheet's own ✕ is the
   close (no second ✕); the header carries the title only. The iframe fills the tall scroller:
   no padding, no overflow on the scroller (scrolling is the iframe's), `flex: 1`.

**Two-deep rule** for 2 and 3: at press time count `[role=dialog][aria-modal=true]`. Fewer than
two: open stacked over it. Two or more: call `closeShellDialogs` first and count again; if the
count is now below two, open stacked; if it is still two or more (Settings over the Operations
modal), do nothing and leave focus where it was. There is no "called from Settings"
special case: while Settings is open the shell has already closed its own dialogs.

**Stacked protocol.** `useGuide()` exposes `guideModal` (true for presentations 2 and 3). Every
layer that can sit under it reads it and, while true, passes `stacked` (Modal's existing prop:
inert, no `aria-modal`) and withholds its own `closeOnEscape`, exactly as
`UserSettingsModal` does for `subRegionsStacked`:
- `UserSettingsModal`: `stacked = subRegionsStacked || guideModal`, and the same for `closeOnEscape`.
- The shell's Modals (popup, four-day sheet, pick/sheet dialogs, Operations modal): fold
  `guideModal` into their existing `stacked` / Escape terms.
- The Ask sheet (`BottomSheet`): if BottomSheet has no `stacked` support, its last-dialog Escape
  rule already defers to the later-mounted guide sheet; verify and test, and add `inert` on the
  lower sheet only if the existing BottomSheet stacking supports it.
Escape in presentation 1 is a native keydown listener on the aside node that closes the guide
and calls `stopPropagation` (as `AskDock.jsx:156-166`), and works only while focus is in the
panel chrome: a keypress inside the iframe never reaches the app (say so in the doc comment).

#### State, focus and the frame

- `src/utils/guide.js`: `GUIDE_ORIGIN = new URL(import.meta.env.VITE_GUIDE_ORIGIN ||
  'https://photocast.online').origin` (normalised, so a trailing slash still matches),
  `GUIDE_PAGES` (page id → display name), `TAB_GUIDE = { plan: 'at-a-glance', 'coming-up':
  'coming-up', map: 'map' }` (no Operations entry), `guideUrl(page, hash, { embed })`, and
  `GUIDE_SECTIONS`, the frozen list of every `page#section` the app opens (G3 uses it).
- A Vitest pin reads `landing/guide/*.html` from disk (as `landingNginxConf.test.js` reads
  `../landing/nginx.conf`) and checks every `GUIDE_SECTIONS` id and every `GUIDE_PAGES` page
  exists. G1 makes CI run the frontend suite on a `landing/guide/` change, so renaming an id
  fails the PR that renames it.
- `src/context/GuideContext.jsx`: `GuideProvider` around `AppInner`'s root; it receives
  `closeShellDialogs` and the active tab as props. `useGuide()` → `{ open, presentation, page,
  hash, guideModal, openGuide(page, hash?, { returnFocus? }), closeGuide() }`. No provider →
  no-op functions. `returnFocus` is a function returning the node to focus on close (default: the
  element focused at press time). A door inside a popover that unmounts on focus-out (G3's
  `ProUpsellDoor`) passes its trigger: `returnFocus: () => triggerRef.current`. Presentation 3
  passes the same function as BottomSheet's `restoreFallback`, so there is one owner of the
  return target.
- Each `openGuide` bumps a nonce that keys the iframe, so reopening the same `page#hash` after
  navigating inside the guide reloads it at the right place.
- `message` listener: accept only `event.origin === GUIDE_ORIGIN`, `event.source ===
  iframe.contentWindow`, `data.type === 'photocast-guide'`; take `title` (string, trailing full
  stop trimmed, capped at 80 chars, rendered as text) and `page` (only if it is a `GUIDE_PAGES`
  id) and `hash`. The tracked current page drives the header title and "Open in new tab ↗".
- iframe: `title="PhotoCast guide"`, `sandbox="allow-scripts allow-same-origin allow-popups
  allow-popups-to-escape-sandbox"`, `referrerPolicy="strict-origin"`.
- Header (1 and 2): title, "Open in new tab ↗" (`target=_blank rel=noopener`, the non-embed URL
  of the tracked page), ✕. Opening moves focus to ✕.
- Tab changes: GuideProvider closes presentations 2 and 3 in an effect keyed on App's
  `activePlanTab` (fed by `onTabChange`), never from `selectTab`, which runs during render and
  may hold shell setters only. Presentation 1 follows the reader: if its tracked page is the old
  tab's `TAB_GUIDE` page, it loads the new tab's page; otherwise it stays; entering Operations
  closes it.
- `hooks/useOutsideDismiss.js`: a press inside `[data-guide-surface]` does not dismiss a Map
  panel, as `[data-ask-surface]` does (presses inside the iframe never reach the parent's
  `mousedown` anyway; say so).
- `nginx.conf` (app): `frame-src https://challenges.cloudflare.com https://photocast.online`,
  update the CSP comment block (note that production's `frame-src` is fixed, so a non-default
  `VITE_GUIDE_ORIGIN` only works in dev). `nginxConf.test.js` asserts `$csp`'s `frame-src`.

#### The three header doors

- Masthead `?`: desktop and tablet between ⚙ and Sign out, styled as ⚙, `aria-label="Guide for
  this tab"`, `aria-expanded`, `aria-controls`; hidden on Operations; a toggle (open → close).
  **Phone: a "Guide" row in the ⋯ `MastheadMenu`** (via its `press()` helper), not a fourth
  control in the 34px `.wf-mast-right`.
- Ask: a `?` before ✕ in the dock header (`AskDock.jsx` ~:181-196) and in the Ask sheet's
  `header` prop (`AskSheet.jsx`), opening `ask-photocast`.
- Settings: a `?` beside the × (`UserSettingsModal.jsx` ~:1183-1186), opening `settings`.
- Every role sees the guide (it is help, not a feature). Rewind: unchanged.

#### Tests and checks

GuidePanel in each presentation (surface and modal-count mocks), the two-deep rule (0, 1, 2
layers), the stacked protocol on Settings (inert/no aria-modal and Escape withheld while the guide
is a dialog over it; Escape closes only the guide), the dock exclusivity and restore without
autofocus, focus return including a door that unmounted, the message filter (wrong origin, wrong
source, unknown page), nonce reload, the tab-change close, `useOutsideDismiss`, the masthead door
per tab and its absence on Operations, the MastheadMenu row, the section pin, the CSP assertion.
Browser check: serve `landing/` with `python3 -m http.server 8090 --directory landing` (no
frame-ancestors, so local framing works), run the app with `VITE_GUIDE_ORIGIN=http://localhost:8090`,
and check desktop 1280, tablet 768 and phone 390, with and without Settings open.

#### G2 build notes

What the build did that the text above does not say, or did differently:

- **`GUIDE_SECTIONS` is an object**, keyed by a short name (`lens`, `mapFilters`, `subRegions` …) with
  `{ page, section }` values, rather than a flat list of `page#section` strings, so G3's `GuideLink` can
  take `page` and `section` from one entry. `GUIDE_PAGES` also holds the hub (`index`), because
  `guide.js` posts the last path segment and the hub's is `''` or `index.html`; `pageIdFromMessage`
  strips `.html` and maps empty to `index`.
- **The message listener drops the whole message for an unknown page** (not just the page field): a
  title that describes a page the header cannot name is no use. The hash is kept only if it is a
  `#` and a short id, else empty.
- **The docked column is drawn by the shell** (`components/guide/GuideDock.jsx`, mounted beside the
  Ask dock); `GuideProvider` draws the dialog and the sheet. `useStickyViewportFill` moved out of
  `AskDock.jsx` into `hooks/` so the two columns share it. The docked width is 380px at 1024-1179px and
  440px from 1180px (the dock's 360/380 plus the guide's prose).
- **The dock is hidden, not closed**, while the guide is docked (`askDockOpen` is the remembered
  `dockWasOpen`). `AskDock` gained `focusOnMount` (not `autoFocus`, which `jsx-a11y/no-autofocus`
  rejects as a prop name); the shell passes false only for the render pair in which
  the docked guide closed with `restoreFocus` and a door outside the dock (`lastClose` in the context).
- **Two-deep**: the shell's closer is called inside `flushSync` so the second count sees the DOM after
  the dialogs left. If that leaves no layer on a desktop, the guide docks. A layer stacked under another
  has already lost its `aria-modal`, so the popup under a pick dialog counts as one layer, not two:
  "two or more" arises only with a layer the shell does not own (Settings over an Operations modal).
- **Operations modals** take no `guideModal` term. No G2 door can open the guide over them alone: the
  masthead `?` is hidden on Operations, Ask has no surface there, and the Settings `?` makes the guide a
  second layer over Settings, which the two-deep rule already covers. `PlanSearch` did need the term
  (it is a shell `Modal`, reachable by Tab from under the guide) and takes a `stacked` prop.
- **The Ask tall sheet has no `stacked` support** in `BottomSheet`, so under a guide sheet it keeps its
  `aria-modal` and stays keyboard-reachable; the guide sheet is later in the DOM, so BottomSheet's
  last-dialog Escape rule gives it the key (tested). Adding stacked support to `BottomSheet` is its own
  change.
- **`BottomSheet` gained `flush`** (a `data-flush` attribute, CSS in `index.css`): the scroller keeps its
  box but loses padding and overflow and becomes a flex column, so the iframe fills it.
- **Crossing 1024px closes an open guide** (docked and dialog need the wide bands, the sheet the narrow
  ones); the plan did not say what a rotation does.
- **The Ask sheet's header** is a new row: an "Ask" label and the `?`, above the conversation.
- **Not checked in a real browser from this build**: the z-index of the stacked dialog over the peek and
  popup, the docked layout and its animation at 1024-1180 and 1280, the iframe loading and its
  `postMessage`, and the focus behaviour that depends on `inert` (jsdom has none). The browser check in
  "Tests and checks" above is still to be run.

### G3 — the in-context `?` links and the Lite-lock links (app, one PR)

G3 and G4 of the first draft touch the same files with one mechanism, so they ship together.
`GuideLink` is built here (not in G2, where it would ship unused).

`src/components/guide/GuideLink.jsx`: a 24px round `?` with a 44px hit area, props `page`,
`section`, `label` (required; `aria-label` and `title`), `stopPropagation` on click (it lives in
clickable rows and cards), `data-testid="guide-link"`. `GuideWhy` is the text twin, "What does this
do?", the same colour, underline on hover. Both take page and section from `GUIDE_SECTIONS`.

#### The `?` links

One `GuideLink` each, page and section from `GUIDE_SECTIONS`:

| Where | File (from the touch-point map) | Opens | aria-label |
|---|---|---|---|
| Drive and rating bar | `WindowFirstLensBar.jsx`, after the readout | `at-a-glance#lens` | How the drive and rating bar works |
| Days-ahead key | `WindowFirstHeatStrip.jsx` end of `.wf-hstrip-h` | `reading-the-forecast#fixed` | What fixed and could still move mean |
| Coming up legend | `WindowFirstComingUp.jsx` `.wf-cu-head`, after the legend | `reading-the-forecast#fixed` | same |
| Since-line | `WindowComingUpSinceLine.jsx` before Mark as seen | `coming-up#new` | What new arrivals are |
| Filter chips | `WindowFirstComingUp.jsx` end of `.wf-cu-chips` | `coming-up#topics` | Every topic explained |
| Tide entry | `WindowComingUpEntry.jsx` facts block, tide-run entries only | `coming-up#tides` | How tide entries work |
| Map window control | `map/WindowControl.jsx`, last item of the menu | `map#window` | How the window control works |
| Map Filters footer | `map/FiltersPopover.jsx` `.wf-filters-foot` | `map#filters` | How the map filters work |
| Hot topics header | `map/MapTopicStrip.jsx` (desktop head; a `<button>`, so the row's click guard skips it; phone: beside, never inside, the header button) | `map#hot-topics` | How the hot topics panel works |
| Tide header | `map/MapTideStrip.jsx` `TideStripHeader` (not inside the locked button) | `map#tide` | How the tide panel works |
| Legend footer | `map/MapLegendPanel.jsx` after `wf-legend-note` | `reading-the-forecast#colour` | What the map colours mean |
| Aurora banner | `AuroraBanner.jsx` `wf-aurora-actions` (use `hold(...)`, stopPropagation: the card is clickable), and `LiteAuroraBanner.jsx` | `night-sky#alerts` | About aurora alerts |
| Ask empty state | `ask/AskEmptyState.jsx` after `AllowanceLine` | `ask-photocast#typed` | How Ask questions work |
| Settings › Your sub-regions | `subregions/SubRegionSettingsSection.jsx` beside the h3 (both arms) | `settings#sub-regions` | About sub-regions |

A door inside a Map panel must not close that panel when pressed (it is inside the panel's own
root, so `useOutsideDismiss` already lets it be). Phone-only rows keep phone-only layout. Each site
gets a test that the link renders and calls `openGuide` with its page and section.

#### "What does this do?" beside each Lite lock

`GuideWhy`, shown only where the reader is locked out (the existing `locked`/`!isPro` term, never a new client role read):

| Lite lock | Where | Opens |
|---|---|---|
| Drive from home | `WindowFirstLensBar.jsx` beside the Pro pill | `at-a-glance#lens` |
| Map Filters drive time | `FiltersPopover.jsx` drive segment group | `map#filters` |
| Bluebell | `FiltersPopover.jsx` chip; also change its `title` from "Upgrade to Pro…" to "Bluebell filter is part of Pro" (the no-"upgrade" rule) | `map#filters` |
| Best bet | `ProUpsellDoor` popover body (heat-strip foot) | `at-a-glance#bestbet` |
| Fiery Sky / Golden Hour | `ProUpsellDoor` in `LocationFourDaySheet.jsx` | `reading-the-forecast#fiery` |
| Tide panel | `TideUpsellContent` (popover and phone peek) | `map#tide` |
| Coming up tide row withheld | `WindowComingUpEntry.jsx` `wf-cu-locked` (inside a clickable card: stopPropagation) | `coming-up#tides` |
| Sub-region doors | `subregions/SubRegionDoors.jsx` (each door) and `subregions/ComingUpOutsideDoor.jsx` | `settings#sub-regions` |
| Aurora door, banner, peek, viewline | `ProUpsellDoor` AURORA_* at `LiteAuroraBanner.jsx`, `map/WindowControl.jsx` (menu), `map/MapPeekSheet.jsx`, and the viewline upsell chip in `MapView.jsx` | `night-sky#aurora-map` |
| Ask upsell | `AskUpsellContent` | `ask-photocast` |
| Legend "outside your sub-regions" | `MapLegendPanel.jsx` (and its phone Layers and heat-strip twins) | `settings#sub-regions` |
| Settings Drive times / Local radius | `UserSettingsModal.jsx`, outside the greyed `pointer-events-none` wrapper | `settings#drive` / `settings#radius` |

`ProUpsellDoor` gains an optional `guide={{page, section}}` prop that puts a `GuideWhy` at the end
of its popover body and passes `returnFocus: () => triggerRef.current` (the popover closes on
focus-out, so the link itself is gone by the time the guide closes; do not route this through
`unmountFocusRef`, which fires only when the door unmounts). Six sites take the prop: the
heat-strip Best bet, `LocationFourDaySheet.jsx`, and the four aurora doors above. The "tide fit in callouts" lock in
the handoff has no visible element (`TideFitBlock` renders nothing for Lite), so it gets no link.
The upsell popovers are `role="dialog"` without `aria-modal`, so they do not count toward G2's
two-deep rule; they close themselves on focus-out when the guide takes focus. Tests per site, and a LITE vs PRO test that the link appears only when locked.

## The section-id contract

The app opens exactly these (G2's `GUIDE_SECTIONS`; a frontend test checks each id exists in
`landing/guide/`): `at-a-glance#lens`, `at-a-glance#bestbet`, `reading-the-forecast#fixed`,
`reading-the-forecast#colour`, `reading-the-forecast#fiery`, `coming-up#new`, `coming-up#topics`,
`coming-up#tides`, `map#window`, `map#filters`, `map#hot-topics`, `map#tide`, `night-sky#alerts`,
`night-sky#aurora-map`, `ask-photocast#typed`, `settings#sub-regions`, `settings#drive`,
`settings#radius`, plus the pages `at-a-glance`, `coming-up`, `map`, `ask-photocast`, `settings`.
Rename one in the guide and it must change here too.

## Open with the owner (copy and capture; not blocking any phase)

From the handoff: "next few days" vs the FAQ's and pricing's "three-day"; astro nights described
as Lite and Pro; new Bortle band descriptions; sub-regions described conditionally; Map colours
option names unchecked; five placeholder screenshots (`map#shot-tide-match`,
`at-a-glance#shot-days`, `at-a-glance#shot-open`, `night-sky#shot-banner`,
`night-sky#shot-aurora-map`); the masthead and Ask shots show the admin-only "UP v2.37.0" pill and
want recapturing. All are landing-only edits after G1.
