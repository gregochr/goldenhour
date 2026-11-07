# "The engineering behind PhotoCast" landing page: plan and copy (2026-11-05)

> **Revised 2026-11-05 after two adversarial reviews** (a fact-check against the tree, and a voice and
> disclosure review). Corrections applied: four required checks, not three; JaCoCo's exclusion list named;
> CodeQL blocks new alerts, not "any"; OWASP Top 10 2025 category names used exactly; ZAP is an API scan, not
> a full scan; admin access control worded to match `SecurityConfig`; declarative clients are Open-Meteo only;
> version reporting, plan links, the model swap, call logging and recorded contracts corrected; the "gates
> only go up" rule narrowed to what is documented; ownership moved to the top. Owner decisions are recorded below.

## Goal

A second public page, for engineers and hiring managers rather than photographers, that sets out the stack,
the CI/CD pipeline, the quality gates enforced on every change, the security controls mapped to the OWASP
Top 10, and the AI engineering inside the product, in my (the owner's) voice. It sits beside
`landing/how-its-built.html`, which stays the plain-English page for photographers and is not changed apart
from one link.

Owner ask, 2026-11-05: "Make it sound like me but talk about CI/CD, Java, specific test coverage gates (that are
being enforced as we speak), OWASP, etc." The page is meant to double as a portfolio piece.

**Voice.** Match `how-its-built.html`'s hero: first person ("I build PhotoCast…"), more than 25 years of
software engineering, measured and specific, British English. Engineering terms are used and named, not
explained at length. No superlatives, no "enterprise-grade", no "bulletproof". Say what a check does and, where
it matters, what it doesn't. Lead with what I own. Keep the "where it falls short" section: a candid gaps list
is the most credible part of a page like this.

## Owner decisions (2026-11-05)

1. **Contact:** `chris@photocast.online`, as a `mailto:` link in the closing.
2. **Container registry:** not named on the page. (Both `ghcr.io/gregochr/goldenhour-*` images were found to be
   anonymously pullable on 2026-11-05; whether to make them private is a separate decision, outside this page.)
3. **Repository link:** none for now. The repository is public, but the owner is considering making it private.
   Adding a link later is a one-line change.

## Build notes

- New file `landing/engineering.html`, built from `landing/how-its-built.html`'s skeleton: same `<head>`
  pattern (fonts, favicon, `photocast.css`), same masthead, `phead` hero, contents list, section and footer
  markup. Reuse existing classes. The gates table uses `photocast.css`'s existing table pattern (the `.tw`
  scroll wrapper with `role="region"`, `tabindex="0"` and an `aria-label`); no new CSS should be needed. If any
  is, add it to `photocast.css` following its conventions. No inline styles or scripts (the landing CSP).
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

## Accuracy rules (checked against the tree and GitHub on 2026-11-05; keep these exact)

Every figure is read from config, not memory. If the build or a reviewer finds a mismatch, the code wins and the
copy is corrected.

| Claim | Source |
|---|---|
| Required checks on `main`: Backend, Frontend, CodeQL, OWASP Dependency Check (four) | `gh api repos/gregochr/goldenhour/branches/main/protection/required_status_checks`. Do not publish that `strict` is off. |
| Backend: 80% line coverage **per class**, with an exclusion list of about 30 classes (data, config, some clients and orchestration services) | `backend/pom.xml` jacoco `<element>CLASS</element>`, `LINE`, `COVEREDRATIO`, `0.80`, `<excludes>` |
| Frontend: lines 99%, statements 98%, functions 98%, branches 95%; per file 95% lines / 90% branches with two documented lower-branch exceptions (TurnstileWidget 81, StartRunScope 87); diff-cover 90% of changed lines (line coverage only, PRs only, in the Frontend job); ratchets up only, lowered only with my say-so | `frontend/vite.config.js` thresholds and comment; `.github/workflows/ci.yml` "Changed-lines coverage (diff-cover, 90%)"; `dev-and-testing.md` |
| SpotBugs at the High threshold, with FindSecBugs, failing `verify` | `backend/pom.xml` |
| Checkstyle: Javadoc on public types and methods, unused imports, indentation, 120-char lines | checkstyle config |
| ESLint with `jsx-a11y`, `--max-warnings 0` | `frontend/eslint.config.js`, `frontend/package.json` |
| `npm audit` at `high` on every non-docs PR | `ci.yml` |
| ShellCheck job in CI | `ci.yml` "Shell — ShellCheck" |
| OWASP Dependency-Check fails at CVSS 7 or above, with reasoned suppressions; scans on PRs that change server dependencies (and reports a pass otherwise) and daily | `backend/pom.xml` `<failBuildOnCVSS>7`, `security-scan.yml` |
| CodeQL for Java and JavaScript: on PRs it reports alerts the change introduces; the failing severity is a repository setting | `codeql.yml` header, `release.sh` |
| OWASP ZAP weekly: authenticated API scan driven by the OpenAPI definition plus a frontend baseline, against a temporary copy; a gate script fails on any Low-or-above alert without a written exception, on scanner failure and on under-coverage; failure opens an issue | `zap.yml`, `.github/scripts/zap_gate.py` |
| PIT weekly over `service.*` and `util.*`, mutation threshold 60%, failure opens an issue; does not gate PRs | `backend/pom.xml`, `pitest.yml`, `ci-failure-issue.yml` |
| Admin access: `/api/admin/**` refused at the filter chain; every admin-only endpoint has a method-level role check | `SecurityConfig.java`, `@PreAuthorize` on admin controllers |
| Declarative `@HttpExchange` clients for Open-Meteo only | `client/OpenMeteo*Api.java` |
| Backend image built with its version; the app shows the server's version | `deploy.yml`, `backend/Dockerfile`, `HealthIndicator.jsx` |
| Recorded contract responses: weather, space weather, postcode, routing, light pollution (no tide) | `backend/src/test/resources/contract/` |
| Haiku 5.5 ran Batch Far-Term for a day and was moved off after the eval check | `reference/pipeline.md` |
| Production model calls (forecast runs and Ask) logged with input, output and cache token counts; the sky eval records its own tokens separately | `api_call_log`, `api-endpoints.md` |
| Ask: at most 4 turns, structured `submit_answer`, validator, keyword-matched Ready answers (no embeddings), cached static prompt and tools | `reference/ask-photocast.md` |
| "More than 33,000 automated tests", "more than 200 database migrations", "more than 120 written plans" | about 33,400 tests (14,913 backend + 18,524 frontend, CI run 42349072217, 2026-11-07); migrations and plans unchanged in kind (217 migration files, 134 plans on 2026-11-07, both still above the claimed 200 and 120) |

**OWASP names.** Use the 2025 edition's exact titles: Broken Access Control, Security Misconfiguration, Software
Supply Chain Failures, Injection. No numbering. Anything that isn't a category goes under "Beyond the
checklist".

**Platform versions.** The majors only: Java 25, Spring Boot 4, React 19, PostgreSQL. No library, plugin, image
or patch versions.

**Still never published** (from `how-its-built-plan.md`): scan results or current status; the contents of
suppression or exception lists; the ZAP coverage floor; which role the ZAP account has; security-header
configuration beyond "a Content Security Policy that disallows inline styles"; host names; costs; how or where
the CI runners are hosted; branch-protection settings beyond which checks are required. Naming which check
blocks a merge is allowed, because the owner asked for the gates by name. Say what each gate is, never what it
has found.

**Keep current.** When a gate's value changes (`vite.config.js` thresholds, the JaCoCo minimum, the SpotBugs
threshold, `failBuildOnCVSS`, `mutationThreshold`, the ZAP gate's risk level, required checks), update
`engineering.html` in the same PR, with its "Last checked" date. Add this to `dev-and-testing.md`'s build-ladder
section as one line in the same PR.

## Copy (use as written; light edits for flow are fine, facts are not)

### Hero
Eyebrow: Reference
H1: The engineering behind PhotoCast.
Lede: PhotoCast is a full-stack forecasting service that I design, build and run on my own, drawing on more
than 25 years of software engineering. This page is for engineers: the stack, the pipeline every change goes
through, the gates it has to pass, and where it still falls short. The figures are the ones enforced in the
build today.
Small line under the lede: Last checked: 5 November 2026. · For the plain-English version, see
[How PhotoCast is built](how-its-built.html).

### At a glance (short list straight after the hero)
- Java 25 and Spring Boot 4 on the server, React 19 in the browser, PostgreSQL underneath.
- Four required checks on every pull request, including per-class coverage, static analysis and dependency
  scanning.
- A tagged release builds, backs up, deploys and health-checks itself.
- Claude rates the sky and answers questions, and the model is tested every week against real outcomes.
- I own the design, the plans, every review decision and every release.

### Contents
The stack · Continuous integration · The gates · Security and the OWASP Top 10 · Continuous delivery ·
Testing beyond coverage · AI inside the product · How the code gets written · Where it falls short

### The stack
- **Backend:** Java 25 and Spring Boot 4: a REST API with Spring Security and JWT authentication, Spring Data
  JPA, Flyway migrations (more than 200 so far) and PostgreSQL in production. Virtual threads for the
  I/O-heavy forecast runs, and declarative HTTP clients for the weather service.
- **Frontend:** React 19, Vite and Tailwind, with Leaflet for the map. The frontend is deliberately a render
  layer: verdicts and picks are decided on the server, and the few things the client works out for itself are
  a named, reviewed list.
- **AI:** Claude, through the Anthropic Java SDK, rates the sky and powers Ask PhotoCast.
- **Data sources:** Open-Meteo weather and air quality, WorldTides, space-weather feeds, and postcode, routing
  and light-pollution services.
- **Delivery:** GitHub Actions, and Docker images built from each tagged release.

### Continuous integration
Every pull request goes through the same checks, whoever or whatever wrote it. Four are required by branch
protection on `main`, so a change can't be merged while one is failing:

- **Backend: build, test and coverage.** `mvn clean verify`, which compiles, runs Checkstyle, the unit tests
  and the integration tests, then enforces the coverage gate and SpotBugs. The integration tests use
  Testcontainers to start a real PostgreSQL, so every database migration is proven on the same engine as
  production before it's merged.
- **Frontend: lint, test and build.** ESLint (including accessibility rules), Vitest with overall, per-file and changed-lines coverage floors,
  `npm audit` at high severity, the production build, a check that the map library stays out of the first page
  load, and `nginx -t` on the web server's config, because nothing else would catch a broken config before
  production.
- **CodeQL** static analysis, for Java and JavaScript.
- **OWASP Dependency-Check** on the server's libraries. It scans when a change touches them, and otherwise
  reports a pass so the requirement can always be met.

Shell scripts are checked with ShellCheck as well. A pull request that only changes documentation skips the
expensive steps, but every required check still reports, so nothing merges unchecked.

### The gates
These are the values in the build as of the date above. The frontend floor only ever ratchets up, and I don't
lower any of them without a stated reason.

| Gate | Where it runs | Threshold | If it fails |
|---|---|---|---|
| Backend line coverage (JaCoCo) | Every PR | 80% **per class**, with a short exclusion list for plain data and config classes | The build fails |
| Frontend coverage (Vitest) | Every PR | Lines 99%, statements 98%, functions 98%, branches 95% overall, and **per file** at least 95% of lines and 90% of branches, with two files held at measured, documented lower branch figures | The build fails |
| Changed-lines coverage (diff-cover) | Every PR | 90% of the app lines a change adds or alters | The build fails |
| SpotBugs with FindSecBugs | Every PR | Any High-priority finding | The build fails |
| Checkstyle | Every PR | Javadoc on every public type and method, indentation, line length, unused imports | The build fails |
| ESLint, including `jsx-a11y` | Every PR | Any error or warning | The build fails |
| `npm audit` | Every PR | Any high or critical advisory | The build fails |
| CodeQL | Every PR (new alerts) and weekly (whole codebase) | New alerts a change introduces | The check fails |
| OWASP Dependency-Check | PRs that change server dependencies, and daily | CVSS 7.0 or above, unless investigated and recorded as not applicable | The check fails |
| OWASP ZAP | Weekly | Any alert of Low risk or above without a written exception, or a scan that didn't run properly | An issue is opened for me |
| PIT mutation testing | Weekly | 60% of mutants killed in the service and utility layers | An issue is opened for me |
| Stryker mutation testing | Weekly | The app's client logic | An issue is opened for me if the run fails |

Per-class coverage is the one I'd defend hardest. An 80% average lets a well-tested utility package hide an
untested service; per class, a new class can't lean on the rest of the codebase for its number. The app goes further: every file has its own floor, and the lines a change touches must be
covered too.

### Security and the OWASP Top 10
I use the OWASP Top 10 (2025) as a checklist rather than a badge. Where the checks above fit it:

- **Software Supply Chain Failures.** OWASP Dependency-Check on the server's libraries and `npm audit` on the
  app's, both blocking on serious findings. Dependabot proposes updates weekly; minor and patch updates merge
  once the required checks pass, larger ones are reviewed by hand, and either way they reach the live service
  only with the next release, which I make by hand.
- **Injection.** CodeQL and FindSecBugs on every change, and CodeQL again weekly so new rules also look at code
  that hasn't changed. Database access goes through JPA and parameterised queries; there's no SQL built from
  strings.
- **Broken Access Control.** Every admin-only endpoint carries a server-side role check, and everything under
  the admin API is also refused by Spring Security's filter chain before a controller sees it. Pro features are
  enforced by a 403 on the server; hiding a button is never the control.
- **Security Misconfiguration.** The app sets a Content Security Policy that disallows inline styles, and the
  web server's config is syntax-checked on every change.

**Beyond the checklist.**
- **Dynamic testing.** Every week OWASP ZAP signs in as a test account and runs an active scan of the API from
  its OpenAPI definition, against a temporary copy of the service, plus a baseline scan of the app. A gate
  script, not ZAP's own exit code, decides the result: any alert of Low risk or above without a written
  exception fails it, and so does a scan that covered less than it should.
- **Personal data stays out of shared caches.** Nothing personal rides a response that's shared through HTTP
  caching, and a test lists the personal paths that caching must never touch.
- **Secrets stay out of logs.** Links carrying one-time codes are kept out of the web server's logs, and a check
  confirms it.

None of this is a substitute for a professional penetration test, and I don't claim one.

### Continuous delivery
- **A tag is a release.** I run the release script, which folds the pending changelog entries into the
  changelog and tags the version. The tag starts the deploy workflow: it re-runs the full CI suite, builds the
  backend and frontend Docker images, publishes them to a container registry, backs up the database, deploys
  and checks the service came up healthy. Any failure stops the deployment and tells me.
- **Traceable.** Both images are tagged with the release version, the running server reports it and the app
  displays it. Every change arrives as a pull request, and larger ones link to a written plan. The changelog is
  built from one file per change, so open pull requests don't fight over the same line.
- **Watched.** A daily health check runs from inside the server and from outside it, a daily test restores the
  latest backup into a scratch database and counts the rows, and a daily smoke test sends a real request through
  the AI rating path.
- **Reversible.** There's a scripted rollback to the previous release, including the database. Fixing forward
  is usually better; rollback is the last resort.

### Testing beyond coverage
Coverage says what the tests ran, not whether they'd notice a bug. So:

- **Mutation testing.** PIT runs weekly over the service and utility layers, planting small faults and checking
  the tests fail. The threshold is 60% of mutants killed. Stryker does the same each week for the app's client logic; the app's screens aren't mutation-tested yet.
- **Testing the model, not just the code.** A weekly evaluation re-rates a fixed set of past sunrises and
  sunsets whose outcome is known, eight times each, and charts the spread over time. That catches drift when a
  model changes, and it decides which model runs which job: a newer model was taken off a production job after
  a day, when the evaluation showed it under-rating a clear-horizon morning.
- **Prompt regression tests.** A larger suite checks real model output against ground truth from what was
  actually observed. Only I change those expectations; the AI tools that write the code are forbidden to.
- **Recorded contracts.** Saved real responses from the weather, space-weather, postcode, routing and
  light-pollution services pin how PhotoCast parses them, and a contract test pins the JSON date format the API
  puts on the wire.
- **Time is injected.** "Today" in the UK is derived from an injected clock everywhere, so the tests cover
  midnight, daylight-saving changes and a server running on UTC.

In all, more than 33,000 automated tests across the server and the app.

### AI inside the product
- **Ask PhotoCast uses tool calling rather than document retrieval.** It's retrieval-augmented generation
  (RAG), but instead of searching a pile of documents, the model queries PhotoCast's own served forecasts
  through tools, in a loop of at most four turns, and replies through a structured tool call rather than free
  text. A validator checks every pick against the live forecast before it's shown. Common questions are matched
  to precomputed answers by keyword rules rather than embeddings, deliberately, so behaviour is predictable and
  testable.
- **Prompt caching.** The static part of the prompt and the tool definitions are cached across questions;
  only the per-question tail changes.
- **Batch processing.** Scheduled forecast runs go through the Message Batches API, and which model runs which
  job is a database setting, so a model can be swapped or rolled back without a deploy.
- **Spend is visible.** Every production model call is logged with its input, output and cache token counts,
  so a change in cost is something I can see, not guess.

### How the code gets written
I own the design, the plans, every review decision and every release. AI models do most of the typing, under a
fixed process: one model plans and reviews, and a different one writes the code in an isolated copy of the
repository and has to get the full local build green before I see the change. Every pull request is then
reviewed again by an AI reviewer from a different company. The plans are written down (more than 120 of them)
and challenged before any code exists. [How PhotoCast is built](how-its-built.html) covers this in plain
English.

### Where it falls short
- The app's tests run in a simulated browser. A few real-browser tests exist, but they run by hand.
- Mutation testing covers the server and the app's client logic, but not the app's screens, and runs weekly rather than on every change.
- The prompt regression suite runs by hand, because every run spends real money; the weekly evaluation fills the
  gap in between.
- PhotoCast runs as Docker containers on a single Linux host, with no separate staging copy of the live
  service. Backups, health checks and rollback reduce the risk; they don't remove it.
- The security checks are automated, not a professional penetration test.

### Closing
Line: If you'd like to talk through any of this, the code or the process, I'm happy to: [chris@photocast.online](mailto:chris@photocast.online).
Links: [How PhotoCast is built](how-its-built.html) · [FAQ](faq.html) · [Explore PhotoCast for free](https://app.photocast.online/register)
(use the start URL the other pages already use).

## Review checklist for the build

- Every figure in "The gates" matches the source named in the accuracy table, re-read on the day.
- Nothing from "Still never published" appears, GHCR is not named, and the repository is not linked.
- The page renders at 375 px with no horizontal page scroll (the gates table scrolls inside `.tw`).
- No inline styles or scripts (the landing CSP).
- Every footer link resolves, including from `guide/`.
