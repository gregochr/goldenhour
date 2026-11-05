# "The engineering behind PhotoCast" landing page: plan and copy (2026-11-05)

## Goal

A second public page, for engineers and hiring managers rather than photographers, that sets out the stack,
the CI/CD pipeline, the quality gates enforced on every change, the OWASP-aligned security controls and the
AI engineering inside the product, in my (the owner's) voice. It sits beside `landing/how-its-built.html`,
which stays the plain-English page for photographers and is not changed apart from one link.

Owner ask, 2026-11-05: "Make it sound like me but talk about CI/CD, Java, specific test coverage gates (that are
being enforced as we speak), OWASP, etc." The page is meant to double as a portfolio piece.

**Voice.** Match `how-its-built.html`'s hero: first person ("I build PhotoCast…"), more than 25 years of
software engineering, measured and specific, British English. Engineering terms are used and named, not
explained at length. No superlatives, no "enterprise-grade", no "bulletproof". Say what a check does and, where
it matters, what it doesn't. Keep the "where it falls short" section; a candid gaps list is the most credible
part of a page like this.

## Build notes

- New file `landing/engineering.html`, built from `landing/how-its-built.html`'s skeleton: same `<head>`
  pattern (fonts, favicon, `photocast.css`), same masthead, `phead` hero, contents list, section and footer
  markup. Reuse existing classes. If a gates table needs styling `photocast.css` lacks, add the minimum there
  following its conventions (tokens, no inline styles; the landing CSP forbids inline styles).
- `<title>`: "The engineering behind PhotoCast — PhotoCast". Meta description: "The stack, CI/CD pipeline,
  enforced test and coverage gates, OWASP-aligned security checks and AI engineering behind PhotoCast."
- `landing/Dockerfile`: add `COPY engineering.html /usr/share/nginx/html/` beside the `how-its-built.html` line.
  Check `landing/nginx.conf` and `landing/README.md` for any per-page list and follow it.
- Footer: add `<a href="engineering.html">Engineering</a>` after "How it's built" in every landing footer that
  carries that link (index, faq, privacy, terms, acknowledgements, how-its-built, and the guide pages if their
  footer carries it; guide pages use `../engineering.html`).
- `how-its-built.html`: one sentence at the end of the hero, "If you're an engineer and want the detail, see
  [the engineering behind PhotoCast](engineering.html)." Nothing else on that page changes.
- `changelog.d/20261105-engineering-page.md`, `### Added — "The engineering behind PhotoCast" page`.

## Accuracy rules (checked against the tree on 2026-11-05; keep these exact)

Every number below is read from config, not memory. If a reviewer or builder finds a mismatch, the code wins and
the copy is corrected.

| Claim | Source |
|---|---|
| Backend: 80% line coverage **per class**, enforced in `verify` | `backend/pom.xml` jacoco `<element>CLASS</element>`, `LINE`, `COVEREDRATIO`, `0.80` |
| Frontend: lines 99%, statements 98%, functions 98%, branches 94% | `frontend/vite.config.js` `test.coverage.thresholds` |
| SpotBugs at the High threshold, with FindSecBugs, failing `verify` | `backend/pom.xml` spotbugs `<threshold>High</threshold>`, findsecbugs plugin |
| Checkstyle: Javadoc on public types and methods, 120-char lines | `CLAUDE.md` Code Standards, checkstyle config |
| OWASP Dependency-Check fails at CVSS 7 or above; runs on PRs that change dependencies and daily | `backend/pom.xml` `<failBuildOnCVSS>7`, `.github/workflows/security-scan.yml` |
| Frontend `npm audit` at `high` on every change | `ci.yml` "OWASP Dependency Audit (frontend)" |
| CodeQL for Java and JavaScript on every PR and weekly | `.github/workflows/codeql.yml` |
| OWASP ZAP weekly: authenticated API scan plus a frontend baseline scan against a temporary copy; the gate fails on any alert of Low risk or above not covered by a written exception, and on a scan that didn't run properly | `.github/workflows/zap.yml`, `.github/scripts/zap_gate.py` |
| PIT mutation testing weekly on the service and util packages, mutation threshold 60%, failure opens an issue; does not gate PRs | `backend/pom.xml` pitest `<mutationThreshold>60`, `targetClasses`; `pitest.yml`; `ci-failure-issue.yml` |
| Migrations tested against PostgreSQL with Testcontainers in CI | `IntegrationTestBase`, `ci.yml` backend job |
| ESLint with `jsx-a11y` | `frontend/eslint.config.js` |
| Required checks on `main`: Backend, Frontend, CodeQL | branch protection (see `how-its-built-plan.md`) |
| Release: tag triggers `deploy.yml`, which re-runs CI, builds the backend and frontend images, pushes them to GitHub Container Registry, backs up, deploys and health-checks | `.github/workflows/deploy.yml` |
| Dependabot weekly for Maven and npm; minor and patch updates auto-merge once required checks pass | `.github/dependabot.yml`, `dependabot-auto-merge.yml` |
| Daily real-API smoke test, daily production health check, daily backup restore test | `real-api-smoke.yml`, `prod-health.yml`, `backup-verify.yml` |
| Ask PhotoCast: Claude tool loop, at most 4 turns, structured `submit_answer`, validator, no embeddings (keyword-matched Ready answers) | `docs/engineering/reference/ask-photocast.md` |
| Prompt caching on Ask; Message Batches API for forecast runs; model per run type chosen by a database row | `ask-photocast.md` "prompt caching", `BatchCachePrimer`, `CLAUDE.md` invariants |
| "More than 25,000 automated tests", "more than 200 database migrations" | `how-its-built-plan.md` (do not add new counts) |

**Platform versions.** Name the platform majors only: Java 25, Spring Boot 4, React 19, PostgreSQL. No library,
plugin, image or patch versions.

**Still never published** (unchanged from `how-its-built-plan.md`): scan results or current status, the
contents of suppression or exception lists, security-header configuration beyond "a Content Security Policy
that disallows inline styles", host names, costs, and how or where the CI runners are hosted. Saying which
check blocks a merge is now allowed, because the owner asked for the gates by name; keep to what each gate is,
not what it has found.

**Keep current.** When a gate's value changes (`vite.config.js` thresholds, jacoco minimum, SpotBugs threshold,
`failBuildOnCVSS`, `mutationThreshold`, the ZAP gate's risk level, required checks), update `engineering.html`
in the same PR. The page's "Last checked" date is updated with it.

## Copy (use as written; light edits for flow are fine, facts are not)

### Hero
Eyebrow: Reference
H1: The engineering behind PhotoCast.
Lede: PhotoCast is a full-stack forecasting service I build and run on my own, drawing on more than 25 years of
software engineering. This page is for engineers: the stack, the pipeline every change goes through, the gates
it has to pass, and where it still falls short. The figures are the ones enforced in the build today.
Small line under the lede: Last checked: 5 November 2026. · For the plain-English version, see
[How PhotoCast is built](how-its-built.html).

### Contents
The stack · Continuous integration · The gates · Security, against the OWASP Top 10 · Continuous delivery ·
Testing beyond coverage · AI inside the product · How the code gets written · Where it falls short

### The stack
- **Backend:** Java 25 and Spring Boot 4: a REST API with Spring Security and JWT authentication, Spring Data
  JPA, Flyway migrations (more than 200 so far) and PostgreSQL in production. Virtual threads for the
  I/O-heavy forecast runs, and declarative HTTP clients for the weather and tide services.
- **Frontend:** React 19, Vite and Tailwind, with Leaflet for the map. The frontend is deliberately a render
  layer: verdicts and picks are decided on the server, and the few things the client works out for itself are
  a named, reviewed list.
- **AI:** Claude, through the Anthropic Java SDK, rates the sky and powers Ask PhotoCast.
- **Data sources:** Open-Meteo weather and air quality, WorldTides, space-weather feeds, and postcode, routing
  and light-pollution services.
- **Delivery:** GitHub Actions, Docker images published to GitHub Container Registry, and Docker Compose on a
  Linux server.

### Continuous integration
Every pull request runs the same checks, whoever or whatever wrote it. Three are required by branch protection
on `main`, so a change can't be merged while one is failing:

- **Backend: build, test and coverage.** `mvn clean verify`, which compiles, runs Checkstyle, the unit tests
  and the integration tests, then enforces the coverage gate and SpotBugs. The integration tests use
  Testcontainers to start a real PostgreSQL, so every database migration is proven on the same engine as
  production before it's merged.
- **Frontend: lint, test and build.** ESLint (including accessibility rules), Vitest with a coverage floor,
  `npm audit` at high severity, the production build, a check that the map library stays out of the first page
  load, and `nginx -t` on the web server's config, because nothing else would catch a broken config before
  production.
- **CodeQL** static analysis, for Java and JavaScript.

When a change touches the server's dependencies, OWASP Dependency-Check runs too. A pull request that only
changes documentation skips the suites rather than spending twenty minutes proving nothing.

### The gates
These are the values in the build as of the date above. They only ever go up: lowering one needs a reason in
the pull request.

| Gate | Where it runs | Threshold | What happens if it fails |
|---|---|---|---|
| Backend line coverage (JaCoCo) | Every PR | 80% **per class**, not averaged across the codebase | The build fails |
| Frontend coverage (Vitest) | Every PR | Lines 99%, statements 98%, functions 98%, branches 94% | The build fails |
| SpotBugs with FindSecBugs | Every PR | Any High-priority finding | The build fails |
| Checkstyle | Every PR | Javadoc on every public type and method, formatting, unused imports | The build fails |
| ESLint, including `jsx-a11y` | Every PR | Any error | The build fails |
| `npm audit` | Every PR | Any high or critical advisory | The build fails |
| CodeQL | Every PR and weekly | Any alert | The check fails |
| OWASP Dependency-Check | PRs that change server dependencies, and daily | CVSS 7.0 or above, unless investigated and recorded as not applicable | The check fails |
| OWASP ZAP | Weekly | Any alert of Low risk or above without a written exception, or a scan that didn't run properly | An issue is opened for me |
| PIT mutation testing | Weekly | 60% of mutants killed in the core forecasting code | An issue is opened for me |

Per-class coverage is the one I'd defend hardest. An 80% average lets a well-tested utility package hide an
untested service; per class, every new class has to bring its own tests.

### Security, against the OWASP Top 10
I use the OWASP Top 10 as a checklist rather than a badge. Some of how it maps:

- **Vulnerable and outdated components.** OWASP Dependency-Check on the server's libraries and `npm audit` on
  the app's, both failing the build on serious findings. Dependabot proposes updates weekly; minor and patch
  updates merge once the required checks pass, larger ones are reviewed by hand.
- **Injection and insecure code patterns.** CodeQL and FindSecBugs on every change, and CodeQL again weekly so
  new rules also look at code that hasn't changed. Database access goes through JPA and parameterised queries.
- **Broken access control.** Admin endpoints are refused by Spring Security's filter chain before a controller
  sees the request, and checked again in the code behind them. Pro features are enforced by a 403 on the
  server; hiding a button is never the control.
- **Dynamic testing.** Every week OWASP ZAP signs in as an ordinary user and runs a full API scan against a
  temporary copy of the service, working through every endpoint in the API's definition, plus a baseline scan
  of the app. A gate script, not ZAP's own exit code, decides the result: any alert of Low risk or above without
  a written exception fails it, and so does a scan that covered less than it should.
- **Security misconfiguration.** The app sets a Content Security Policy that disallows inline styles, and the
  web server's config is syntax-checked on every change.
- **Data exposure.** Personal data never rides a response that's shared through HTTP caching, and a test lists
  the personal paths that caching must never touch. Links carrying one-time codes are kept out of the web
  server's logs, and a check confirms it.

None of this is a substitute for a professional penetration test, and I don't claim one.

### Continuous delivery
- **A tag is a release.** I run the release script, which folds the pending changelog entries into the
  changelog and tags the version. The tag starts the deploy workflow: it re-runs the full CI suite, builds the
  backend and frontend Docker images, publishes them to GitHub Container Registry, backs up the database, deploys
  and checks the service came up healthy. Any failure stops the deployment and tells me.
- **Traceable.** The running server and app report the version they were built from, every change arrives as a
  pull request linked to its plan, and the changelog is built from one file per change, so open pull requests
  don't fight over the same line.
- **Watched.** A daily health check runs from inside the server and from outside it, a daily test restores the
  latest backup into a scratch database and counts the rows, and a daily smoke test sends one real request
  through the AI rating path.
- **Reversible.** There's a written rollback to the previous release, including the database. Fixing forward is
  usually better; rollback is the last resort.

### Testing beyond coverage
Coverage says what the tests ran, not whether they'd notice a bug. So:

- **Mutation testing.** PIT runs weekly over the core forecasting, evaluation, aurora and batch code, planting
  small faults and checking the tests fail. The threshold is 60% of mutants killed.
- **Testing the model, not just the code.** A weekly evaluation re-rates a fixed set of past sunrises and
  sunsets whose outcome is known, eight times each, and charts the spread over time. That catches drift when a
  model changes, and it's how models are chosen: a newer model was kept out of production after it under-rated
  a clear-horizon morning.
- **Prompt regression tests.** A larger suite checks real model output against ground truth from what was
  actually observed. Only I change those expectations; the AI tools that write the code are forbidden to.
- **Recorded contracts.** Saved real responses from each external service pin how PhotoCast parses them, and a
  contract test pins the JSON date format the API puts on the wire.
- **Time is injected.** "Today" in the UK is derived from an injected clock everywhere, so the tests cover
  midnight, daylight-saving changes and a server running on UTC.

In all, more than 25,000 automated tests across the server and the app.

### AI inside the product
- **Ask PhotoCast is retrieval-augmented generation (RAG), the agentic kind.** Instead of searching a pile of
  documents, the model calls tools that query PhotoCast's own served forecasts, in a loop of at most four turns,
  and replies through a structured tool call rather than free text. Every answer is validated against the live
  forecast before it's shown. Common questions are matched to precomputed answers by keyword rules rather than
  embeddings, deliberately, so behaviour is predictable and testable.
- **Prompt caching.** The static part of the prompt and the tool definitions are cached across questions;
  only the per-question tail changes.
- **Batch processing.** Forecast runs go through the Message Batches API, and which model runs which job is a
  database setting, so a model can be swapped or rolled back without a deploy.
- **Spend is measured.** Every model call is logged with its token counts, including cache reads and writes, so
  a cost change is a measured decision rather than a guess.

### How the code gets written
I use AI tools for most of the typing, under a fixed process. One model plans and reviews, and a different one
writes the code in an isolated copy of the repository. It has to get the full local build green before I see
the change, and every pull request is reviewed again by an AI reviewer from a different company. The plans are
written down (more than 120 of them) and challenged before any code exists. [How PhotoCast is
built](how-its-built.html) covers this in plain English.

### Where it falls short
- The app's tests run in a simulated browser. A few real-browser tests exist, but they run by hand.
- Mutation testing covers the server only, and runs weekly rather than on every change.
- The prompt regression suite runs by hand, because every run spends real money; the weekly evaluation fills the
  gap in between.
- There's one production server and no separate staging copy of the live service. Backups, health checks and
  rollback reduce the risk; they don't remove it.
- The security checks are automated, not a professional penetration test.

### Closing
Line: If you'd like to talk about any of this, the code or the process, I'm happy to.
Links: [How PhotoCast is built](how-its-built.html) · [FAQ](faq.html) · [Explore PhotoCast for free](https://app.photocast.online/register)
(use the start URL the other pages already use).

## Review checklist for the build

- Every figure in "The gates" matches the source named in the accuracy table, re-read on the day.
- Nothing from "Still never published" appears.
- The page renders at 375 px with no horizontal scroll (the gates table scrolls inside its own wrapper or
  stacks), and in both themes if `photocast.css` has a dark theme.
- No inline styles or scripts (the landing CSP).
- Every footer link resolves, including from `guide/`.
