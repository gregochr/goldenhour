# landing — the marketing site at photocast.online

Plain static HTML, one stylesheet and two small scripts. **No build step, no bundler, no tests.** Open
`index.html` in a browser and what you see is what ships.

```
index.html              the marketing page
faq.html                privacy.html   terms.html   acknowledgements.html
guide/                  the user guide: nine pages, guide.css, guide.js, guide-search.js, shots/*.webp
photocast.css           the shared skin for all five pages
viewer.js               the screenshot viewer (index.html only; plain JS, no dependencies)
home.js                 phone-only enhancements for index.html (plain JS, no dependencies)
favicon.svg  favicon.png  apple-touch-icon.png   screenshot*.png
Dockerfile              docker-compose.yml   nginx.conf
```

`app.photocast.online` (the React app) is a **different** site, in `frontend/`, deployed a
completely different way. Nothing here is involved in that.

## Releasing it

**The landing site is not part of the tagged release.** `.github/workflows/deploy.yml`
fires on a `v*` tag and builds exactly two images, backend and frontend — it never
looks at this directory. So cutting a version tag does not publish a landing change,
and publishing a landing change does not need a version tag. The two are independent.

CI does not build or deploy it, but a change under `landing/guide/`, `nginx.conf`, `Dockerfile` or
`security-headers.conf` runs the frontend suite (which pins the nginx headers) and the landing
`nginx -t` step plus the access-log canary. The container is built **on the production host** from the
files in this directory, and the release is a command you run from your laptop:

```bash
./release-landing.sh                  # deploy origin/main
./release-landing.sh --ref my-branch  # deploy an unmerged branch (it warns you)
./release-landing.sh --dry-run        # preflight + show the diff, change nothing
./release-landing.sh --rollback       # list the host's rollback images; --rollback TAG restores one
```

It checks that every asset the pages reference has a `COPY` line, shows what will change on the
host, asks one question, tags the running image for rollback, rebuilds, smoke-tests every page and
asset (and the served stylesheet's hash) on the host, then checks `https://photocast.online`. A
failed smoke test prints the rollback command and does not roll back by itself. Rollback restores
the image, not the host's `landing/` files.

⚠️ **Do not run a full `git pull` on the host, and delete `~/goldenhour/deploy-landing.sh`,
which does.** Verified on the host 2026-09-05: `~/goldenhour` sits on `main` roughly **1,235
commits behind** `origin/main`, and carries four *hand-edited, uncommitted* production config
files — `docker-compose.yml`, `nginx.conf`, `backend/src/main/resources/application-prod.yml`
and `scripts/backup-postgres.sh`. A `git pull origin main` would try to merge 1,235 commits
across exactly those paths, and `deploy-landing.sh` runs under `set -e`, so a conflict aborts
it part-way. Production config is the last thing that should be in a half-finished merge. This
is the same staleness `.github/workflows/deploy.yml` works around by reading files out of the
tag (`git show "$TAG":nginx.conf`) rather than trusting the checkout. (The script is named
`release-landing.sh` so it cannot collide with that host file.)

### Fallback: by hand

`release-landing.sh` does exactly this and never anything more. Check out **only this directory**,
which needs no merge and cannot touch those files:

```bash
ssh gregochr@100.76.73.16
cd ~/goldenhour

# tag the running image first — `--no-cache` below orphans it, and it is the only rollback
docker tag "$(docker inspect -f '{{.Image}}' photocast-landing)" photocast-landing:rollback-$(date +%Y%m%d)

git fetch origin main                  # or a branch, to deploy before merge
git checkout FETCH_HEAD -- landing/    # ONLY landing/; HEAD does not move

cd landing && docker compose build --no-cache && docker compose up -d --force-recreate

for p in / faq.html privacy.html terms.html acknowledgements.html photocast.css; do
  printf "%-24s %s\n" "$p" "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8085/$p)"
done
```

All 200; `photocast.css` returning 404 means the `COPY` line is missing again. To roll back, retag
the saved image as `landing-landing:latest` and `docker compose up -d --no-build --force-recreate`.

The container listens on **8085**; Cloudflare Tunnel maps `photocast.online` to it. The tunnel's
routing config lives on the host (`~/.cloudflared/`), not in this repo.

## Adding a file

⚠️ **`Dockerfile` copies every root file by name, one `COPY` per line.** The one exception is
`guide/`, which is copied as a directory (`COPY guide/ …`): a file added under `guide/` needs no `COPY`
line. Every other new asset that is not added there is missing in production while looking
perfectly fine locally — the page renders from disk in your browser and from the image
in the container, and only the container is short a file. This has already bitten once:
`photocast.css` arrived without a `COPY` line and would have shipped every page unstyled.

Add the `COPY` line in the same commit as the file (not needed under `guide/`).

**Asset URLs are fingerprinted at image build.** nginx and Cloudflare cache css/js/images for 30
days under fixed URLs, so a changed file would be masked. A `RUN` step in `Dockerfile` hashes each
`*.css *.js *.webp *.png` and rewrites its exact quoted reference in the pages to `"file?v=<hash>"`.
The source HTML keeps plain URLs (local previews are unchanged), and plain paths are still served.

**Caching split.** HTML is served `Cache-Control: no-cache` (revalidated on every visit, a cheap 304), so a
deploy is seen at once; fingerprinted assets are cached for 30 days. The security headers live in
`security-headers.conf` (included at server level and in each location) because a location with its own
`add_header` stops inheriting the server-level ones.

The live `photocast.css` still needs a one-off Cloudflare purge to clear the copy cached under the
old plain URL. That only matters for the plain URL: once fingerprinted URLs ship, nothing requests it.

## Guide pages

`guide/` holds the user guide, served at `https://photocast.online/guide/`. The app's help panel (a
later phase) frames these pages with `?embed=1`; `guide.js` is the embed mode and posts the page title
to the app. `nginx.conf` sends `/guide/` documents `Content-Security-Policy: frame-ancestors
https://app.photocast.online` and no `X-Frame-Options` (every other page keeps `SAMEORIGIN`).
`/guide` redirects to `/guide/`. The fingerprint step in the `Dockerfile` also runs inside `guide/`
(its own assets and `../photocast.css`, `../favicon.png`).

Deploy the landing site before any app release that opens the guide panel; until then /guide/ refuses to be framed.

**Section-id contract.** The app opens exactly these `page#section` ids, and a frontend test checks
each exists in `guide/`. Rename one here and it must change in the app too: `at-a-glance#lens`,
`at-a-glance#bestbet`, `reading-the-forecast#fixed`, `reading-the-forecast#colour`,
`reading-the-forecast#fiery`, `coming-up#new`, `coming-up#topics`, `coming-up#tides`, `map#window`,
`map#filters`, `map#hot-topics`, `map#tide`, `night-sky#alerts`, `night-sky#aurora-map`,
`ask-photocast#typed`, `settings#sub-regions`, `settings#drive`, `settings#radius`, plus the pages
`at-a-glance`, `coming-up`, `map`, `ask-photocast`, `settings`.

**Filling a placeholder screenshot.** Placeholders (`map#shot-tide-match`, `at-a-glance#shot-days`,
`at-a-glance#shot-open`, `night-sky#shot-banner`, `night-sky#shot-aurora-map`) are `<figure id="shot-…">`
blocks holding a `<div class="ph">`. Add the WebP to `guide/shots/`, replace the `ph` div with an
`<img src="shots/name.webp" width height alt>` like its neighbours, and deploy; no `COPY` line is needed.

## A note on the skin

`photocast.css` is deliberately written as one-line rules; do not reformat or minify it.
Its tokens are the app's own `@theme` (`frontend/src/index.css`), re-synced from there rather than
re-tuned here: Newsreader for headings and the wordmark, IBM Plex Sans for body text, IBM Plex
Mono for labels (all from Google Fonts; Bricolage Grotesque and DM Mono are gone). The
interactive accent is bone (`--brand` = `--color-plex-gold` = `#F2E7D3`, hover `#FBF4E6`);
coral appears only in the brand lockup's kicker. The only other colour is the verdict
vocabulary, which carries meaning: `--go` `#8AAE72` for *Worth it*, `--maybe` `#E0A542` for
*Maybe*, `--poor` `#C8452F` for *Stand down*. `--ink-dim` is decoration only (bullets, dots),
never text. No shadows, no gradients.

The masthead nav rules are scoped `.mast nav a:not(.btn)` rather than `.mast nav a`.
That `:not(.btn)` is load-bearing: without it the descendant selector (specificity 0,1,2)
out-specifies `.btn` (0,1,0) and repaints the sticky "Start free" call to action in
`--ink-soft` (bone at 66% alpha) on its bone fill, which is effectively bone on bone, about
1:1 and unreadable. Keep the exclusion on any rule under `.mast nav a` that sets colour.
