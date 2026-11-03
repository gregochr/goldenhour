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

## Phases (one PR each, in order)

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

- `nginx.conf` (app): `frame-src https://challenges.cloudflare.com https://photocast.online`;
  update the CSP comment block. Add a `nginxConf.test.js` assertion that `$csp`'s `frame-src`
  lists both.
- `src/utils/guide.js`: `GUIDE_ORIGIN` (from `import.meta.env.VITE_GUIDE_ORIGIN`, default
  `https://photocast.online`), `TAB_GUIDE = { plan: 'at-a-glance', 'coming-up': 'coming-up', map:
  'map' }` (operations: none, the button is hidden), `guideUrl(page, hash, { embed })`, and
  `GUIDE_SECTIONS`, the frozen list of every `page#section` the app opens (single source for the
  doors in G3 and G4, and pinned by a test against the ids in `landing/guide/*.html`, read from
  disk as `nginxConf.test.js` reads `nginx.conf`, so renaming an id in the guide fails the
  frontend suite).
- `src/context/GuideContext.jsx`: `GuideProvider` wrapping `AppInner`'s root div; `useGuide()` →
  `{ open, page, hash, openGuide(page, hash?), closeGuide() }`. Absent provider → no-op functions
  (so isolated component tests need no provider).
- `src/components/guide/GuidePanel.jsx`, rendered as the last child of `AppInner`'s root:
  - Desktop/tablet (`!useIsMobile()`): `<aside aria-label="PhotoCast guide" data-guide-surface>`,
    fixed right, 440px (max 100vw), full height below safe area, slide-in transform. **Not
    `role="dialog"`**: `shellForeignDialog` would refuse Ask while it is up, BottomSheet would
    lose its Escape, and `aria-modal` would stand down every Map Escape rule. Header: the title
    (from the guide's postMessage, else the page name from `utils/guide.js`), "Open in new tab ↗"
    (`target=_blank rel=noopener`, the non-embed URL), ✕. Esc closes when focus is inside the
    panel chrome (a keypress inside the iframe never reaches the app; say so in the doc comment).
    No scrim on desktop (non-modal).
  - Phone: `BottomSheet size="tall" closeOnEscape label="PhotoCast guide"` with the same header
    (no "Open in new tab"). Scrim tap closes. It is modal, so the at-most-two rule applies:
    `openGuide` on phone first closes the shell's dialogs (`closeShellDialogs`) unless it was
    called from Settings (Settings + guide = two).
  - The iframe: `src = guideUrl(page, hash, {embed:true})`, `title="PhotoCast guide"`,
    `sandbox="allow-scripts allow-same-origin allow-popups allow-popups-to-escape-sandbox"`,
    `referrerPolicy="strict-origin"`, `loading="lazy"` off (it is opened on demand). Changing
    `page`/`hash` while open reloads the frame.
  - `message` listener: accept only `event.origin === GUIDE_ORIGIN`, `event.source ===
    iframe.contentWindow`, `data.type === 'photocast-guide'`, `typeof data.title === 'string'`;
    render the title as text, trimmed of a trailing full stop, capped at 80 chars.
  - Focus: opening moves focus to the panel's ✕ (desktop) or follows BottomSheet; closing returns
    it to the door that opened it (record `document.activeElement` in `openGuide`).
- Ask dock: while the desktop panel is open the dock closes, and it re-opens on close if it was
  open before. Do it in the shell (`askDockOpen` + a `guideSuspendedDock` flag read from
  `useGuide()`), not by adding the dock to `selectTab`.
- `hooks/useOutsideDismiss.js`: a press inside `[data-guide-surface]` does not dismiss a Map
  panel (as `[data-ask-surface]` does). Test it.
- `selectTab` does not close the desktop panel (it is non-modal and follows the reader); the
  masthead `?` then follows the tab (if open, switching tab loads the new tab's page). Phone: the
  sheet is a dialog, so `selectTab` closes it — wire it through the same route the shell uses for
  its other dialogs, keeping `selectTab`'s body to shell setters only (it runs during render).
- Doors:
  - Masthead `?` (desktop/tablet between ⚙ and Sign out, styled like ⚙; phone in
    `.wf-mast-right` beside ⋯), `aria-label="Guide for this tab"`, `aria-expanded`,
    `aria-controls`, hidden on operations. Toggles: open → close.
  - Ask dock header: a `?` before ✕ (`ask-photocast`). Ask sheet: the `header` prop gains the
    same `?`.
  - Settings header: a `?` beside the ×, opening `settings`.
- `src/components/guide/GuideLink.jsx`: 24px round `?` with a 44px hit area (padding or `::before`),
  props `page`, `section`, `label` (required, becomes `aria-label` and `title`),
  `stopPropagation` on click (it lives inside clickable rows), `data-testid="guide-link"`. Built and
  tested here, used in G3.
- Role gating: every role sees the guide (it is help, not a feature). Rewind: unchanged.
- Tests: GuidePanel (desktop aside vs phone sheet via the `useIsMobile` mock, title from a
  trusted message, ignored from a wrong origin or source, ✕/Esc close, focus return), the masthead
  button per tab and hidden on operations, the dock suspend/restore, useOutsideDismiss, the
  GUIDE_SECTIONS-vs-landing-ids pin, the CSP assertion.
- Browser check: run `landing/` with a plain static server (`python3 -m http.server 8090
  --directory landing`) and the app with `VITE_GUIDE_ORIGIN=http://localhost:8090`; a plain
  static server sends no frame-ancestors, so local framing works.

### G3 — the 13 in-context `?` links (app)

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

### G4 — "What does this do?" beside each Lite lock (app)

A plain text button (`wf-guide-why`, the `?` link's colour, underline on hover), shown only where
the reader is locked out (the existing `locked`/`!isPro` term, never a new client role read):

| Lite lock | Where | Opens |
|---|---|---|
| Drive from home | `WindowFirstLensBar.jsx` beside the Pro pill | `at-a-glance#lens` |
| Map Filters drive time | `FiltersPopover.jsx` drive segment group | `map#filters` |
| Bluebell | `FiltersPopover.jsx` chip; also change its `title` from "Upgrade to Pro…" to "Bluebell filter is part of Pro" (the no-"upgrade" rule) | `map#filters` |
| Best bet | `ProUpsellDoor` popover body (heat-strip foot) | `at-a-glance#bestbet` |
| Fiery Sky / Golden Hour | `ProUpsellDoor` in `LocationFourDaySheet.jsx` | `reading-the-forecast#fiery` |
| Tide panel | `TideUpsellContent` (popover and phone peek) | `map#tide` |
| Coming up tide row withheld | `WindowComingUpEntry.jsx` `wf-cu-locked` | `coming-up#tides` |
| Aurora door, banner, peek | `ProUpsellDoor` AURORA_* (Lite banner, window menu, peek) | `night-sky#aurora-map` |
| Ask upsell | `AskUpsellContent` | `ask-photocast` |
| Legend "outside your sub-regions" | `MapLegendPanel.jsx` (and its phone Layers and heat-strip twins) | `settings#sub-regions` |
| Settings Drive times / Local radius | `UserSettingsModal.jsx`, outside the greyed `pointer-events-none` wrapper | `settings#drive` / `settings#radius` |

`ProUpsellDoor` gains an optional `guide={{page, section}}` prop that puts the link at the end of
its popover body, so the four door sites change by one prop. The "tide fit in callouts" lock in
the handoff has no visible element (`TideFitBlock` renders nothing for Lite), so it gets no link.
Opening the guide from inside an upsell popover closes that popover first (the popover is a
transient layer; leaving it open over a desktop drawer is fine, but on phone it would be a third
layer). Tests per site, and a LITE vs PRO test that the link appears only when locked.

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
