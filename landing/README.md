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

There is no CI for it either. The container is built **on the production host** from
the files in this directory, which means the release is a manual step:

```bash
ssh gregochr@100.76.73.16
cd ~/goldenhour
git pull                       # deploy.yml never pulls, so this checkout can be stale
cd landing
docker compose up -d --build   # --build is required; without it Compose reuses the old image
```

`--build` is not optional. `docker-compose.yml` here uses `build: .` rather than a
registry image, so a plain `docker compose up -d` will happily restart the *previous*
build and report success.

Then confirm it actually changed:

```bash
docker compose ps                        # photocast-landing, port 8085->80
curl -sI http://localhost:8085/photocast.css | head -1   # expect 200
```

The container listens on **8085**; Cloudflare Tunnel maps `photocast.online` to it. The
tunnel's routing config lives on the host (`~/.cloudflared/`), not in this repo — if the
public URL does not update after a successful local `curl`, that is where to look.

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
