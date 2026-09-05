# landing — the marketing site at photocast.online

Plain static HTML and one stylesheet. **No build step, no bundler, no tests.** Open
`index.html` in a browser and what you see is what ships.

```
index.html              the marketing page
faq.html                privacy.html   terms.html   acknowledgements.html
photocast.css           the shared skin for all five pages
favicon.png             logo.png       screenshot*.png
Dockerfile              docker-compose.yml   nginx.conf
```

`app.photocast.online` (the React app) is a **different** site, in `frontend/`, deployed a
completely different way. Nothing here is involved in that.

## Releasing it

**The landing site is not part of the tagged release.** `.github/workflows/deploy.yml`
fires on a `v*` tag and builds exactly two images, backend and frontend — it never
looks at this directory. So cutting a version tag does not publish a landing change,
and publishing a landing change does not need a version tag. The two are independent.

There is no CI for it either. The container is built **on the production host** from the
files in this directory, which means the release is a manual step.

⚠️ **Do not run a full `git pull` on the host, and be careful with `~/goldenhour/deploy-landing.sh`,
which does.** Verified on the host 2026-09-05: `~/goldenhour` sits on `main` roughly **1,235
commits behind** `origin/main`, and carries four *hand-edited, uncommitted* production config
files — `docker-compose.yml`, `nginx.conf`, `backend/src/main/resources/application-prod.yml`
and `scripts/backup-postgres.sh`. A `git pull origin main` would try to merge 1,235 commits
across exactly those paths, and `deploy-landing.sh` runs under `set -e`, so a conflict aborts
it part-way. Production config is the last thing that should be in a half-finished merge. This
is the same staleness `.github/workflows/deploy.yml` works around by reading files out of the
tag (`git show "$TAG":nginx.conf`) rather than trusting the checkout.

Check out **only this directory**, which needs no merge and cannot touch those files:

```bash
ssh gregochr@100.76.73.16
cd ~/goldenhour

# tag the running image first — `--no-cache` below orphans it, and it is the only rollback
docker tag "$(docker inspect -f '{{.Image}}' photocast-landing)" photocast-landing:rollback-$(date +%Y%m%d)

git fetch origin main                  # or a branch, to deploy before merge
git checkout FETCH_HEAD -- landing/    # ONLY landing/; HEAD does not move

cd landing
docker compose build --no-cache
docker compose up -d
```

Then confirm — the stylesheet is the thing worth checking, for the reason in the next section:

```bash
for p in / faq.html privacy.html terms.html acknowledgements.html photocast.css; do
  printf "%-24s %s\n" "$p" "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8085/$p)"
done
```

All 200. `photocast.css` returning 404 means the `COPY` line is missing again.

To roll back, retag that saved image as `landing-landing:latest` and `docker compose up -d`.

The container listens on **8085**; Cloudflare Tunnel maps `photocast.online` to it. The tunnel's
routing config lives on the host (`~/.cloudflared/`), not in this repo.

## Adding a file

⚠️ **`Dockerfile` copies every file by name, one `COPY` per line.** It does not copy the
directory. A new asset that is not added there is missing in production while looking
perfectly fine locally — the page renders from disk in your browser and from the image
in the container, and only the container is short a file. This has already bitten once:
`photocast.css` arrived without a `COPY` line and would have shipped every page unstyled.

Add the `COPY` line in the same commit as the file.

## A note on the skin

`photocast.css` is deliberately written as one-line rules; do not reformat or minify it.
Colours come from the app's own verdict vocabulary — lichen green for *Worth it*, amber
for *Maybe*, red for *Stand down* — defined in `oklch` at matched chroma.

The masthead nav rules are scoped `.mast nav a:not(.btn)` rather than `.mast nav a`.
That `:not(.btn)` is load-bearing: without it the descendant selector (specificity 0,1,2)
out-specifies `.btn` (0,1,0) and repaints the sticky "Start free" call to action in
`--ink-soft` beige on amber, which measures **1.24:1**. Keep the exclusion on any rule
under `.mast nav a` that sets colour.
