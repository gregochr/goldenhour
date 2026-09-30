### Security — Frontend dependency audit fixes (brace-expansion 5.0.12, fast-uri 3.1.8)

- **The v2.22.2 release failed at the frontend `npm audit` gate, on both the tag's Deploy run and
  the same commit's CI run on `main`** — the promotion PR (#945) had passed minutes earlier, so this
  was time-triggered by the advisory database, not by anything in the release. Three high
  advisories in `brace-expansion` 4.0.0–5.0.11 (GHSA-q2hr-2g5m-vwhr, GHSA-qhr7-859c-m2p7,
  GHSA-6j4f-fj2g-mc7p — quadratic-time expansion and two stack-exhaustion recursions, all DoS),
  and one moderate in `fast-uri` 3.0.0–3.1.7 (GHSA-hrr3-gc8f-f4qj). The moderate one does not
  trip the `high` gate on its own and is fixed here because it is the same two-line shape.
- **The `overrides` pin was itself the vulnerable version, again.** `package.json` pins
  `brace-expansion` to force every `minimatch` (3.x, 5.x and 10.x) onto one 5.x node; #421 set it
  to `5.0.9` for the previous advisory in this package, and that is exactly the version now inside
  the vulnerable range. Bumped to `5.0.12`, the first release outside it. Editing the lockfile
  alone is not enough here — `npm ci` refuses the tree with `Missing: brace-expansion@5.0.9` five
  times over, once per `minimatch`, because the override still names the old version.
- **Transitive and dev-only**: `minimatch` reaches the tree through `eslint`,
  `@eslint/config-array`, `glob` and `filelist`; `fast-uri` through `ajv`. Both lockfile nodes are
  `"dev": true`; nothing shipped to users was affected. `ajv`'s `^3.0.1` range already permits
  `3.1.8`, so that one is lockfile-only.
- **Seven lines, deliberately not `npm audit fix`** (the same reasoning as #421 — it rewrites
  lockfile metadata the CI runner's older npm does not, including the `libc` field on the optional
  rollup binaries). Proven the way CI will read it: `rm -rf node_modules && npm ci` exits 0 with
  0 vulnerabilities and leaves the lockfile byte-identical, `scripts/npm-audit.sh frontend high`
  reports 0 under npm 11, and lint, 6,849 Vitest tests and the production build are green.
- **Deploy fires only on a tag push**, so this PR alone does not re-run the release: once it is on
  `main` the tag needs re-cutting (owner-run, as ever).
