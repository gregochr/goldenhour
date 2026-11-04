# "How PhotoCast is built" landing page — plan and copy (2026-11-04)

> **Revised 2026-11-04 (second pass).** The copy below is the first version. The page was rewritten to a calmer,
> less absolute voice and every claim was re-checked against the code and workflows: per-change, scheduled and
> manual checks are now kept apart, GitHub traceability and the tag-triggered release are explained, and the
> landing site's separate `release-landing.sh` path is named. `landing/how-its-built.html` is now the source of
> truth for the copy; when you change behaviour the page describes (CI required checks, Dependabot, the weekly
> Sky Eval, backups, rollback), update the page in the same PR.

## Goal
A public page that answers "isn't this just vibe-coded?" by showing, plainly and without overclaiming, the controls
around an AI-built app. Audience: photographers first, curious engineers second. Voice: `landing/faq.html` —
warm, straightforward, knowledgeable, measured. British English. Third person / "you", as the FAQ does.

## Build notes
- New file `landing/how-its-built.html`, built from `landing/faq.html`'s skeleton: same `<head>` pattern
  (meta description, favicon, `photocast.css`), same masthead nav and footer, same section/heading classes. No
  new CSS unless unavoidable; if it is, add it to `photocast.css` following its conventions.
- `<title>`: "How PhotoCast is built — PhotoCast". Meta description: "How PhotoCast is planned, tested,
  reviewed and run: built with AI, held to engineering standards."
- Add `COPY how-its-built.html /usr/share/nginx/html/` to `landing/Dockerfile` (see `landing/README.md`).
  Check `nginx.conf` for any per-page allowlist or clean-URL rule and the Dockerfile fingerprint step.
- Footer link "How it's built" on every landing page that carries the footer (index, faq, privacy, terms,
  acknowledgements, and the guide pages if they share it), next to FAQ. One line in the FAQ's "What started as
  a personal experiment" answer area is optional — only if it reads naturally.
- `changelog.d/20261104-how-its-built-page.md`.

## Accuracy rules (the evidence is in the 2026-11-04 inventory; keep these exact)
- Branch protection requires Backend, Frontend and CodeQL checks. Codex review is a working rule, not a GitHub
  setting — so say "every change is reviewed", never "GitHub blocks unreviewed changes".
- Mutation testing (PIT) runs weekly on the core forecasting code; it does not gate each change.
- The prompt-regression suite runs by hand (it calls Claude and costs money). The Sky Eval runs weekly in production.
- Do NOT publish: security scan results or status, which scans block what, tool versions, header configuration,
  host names, costs, or the self-hosted runner. Security stays high-level.
- Numbers are rounded and dated-proof: "more than 25,000 automated tests" (≈13.9k frontend + ≈12.5k backend),
  "more than 200 database migrations", "over 120 written plans". Don't add others.

## Copy (use as written; light edits for flow are fine, facts are not)

### Hero
Eyebrow: Reference
H1: How PhotoCast is built.
Lede: PhotoCast is written with the help of AI. That's no secret, and it isn't a shortcut. Every change is planned,
reviewed, tested and checked against real skies before it reaches you. This page explains how.

### The short version
- Every change starts as a written plan, and the plan is challenged before any code is written.
- The code is written by one AI model and reviewed by others. No model approves its own work.
- More than 25,000 automated tests run on every change, and nothing is released until they pass.
- The forecasts themselves are tested against real sunrises and sunsets, every week.
- A person decides what ships, and when.

### Checking the forecast against the sky
A forecasting app is only as good as its forecasts, so the most important tests aren't about code at all.

**A weekly sky check.** Every week PhotoCast rates a fixed set of real mornings and evenings whose outcome is
already known. Each one is rated eight times, and every rating has to land in the expected range. If the AI
model behind the ratings drifts, this is where it shows. It also guides which model is used: a newer model has
been kept out of the live forecasts after it under-rated a clear morning in this check.

**Forecast regression tests.** A second, larger set of real cases checks the full rating, and the mist and
night-sky forecasts, against what actually happened. The expected answers come from real observation,
and only the owner can change them. The AI that writes PhotoCast's code is not allowed to edit them.
Ask PhotoCast's answers have their own set of expected answers.

**Your ratings.** Pro readers can say how a past sunrise or sunset actually turned out, using "How was it?" on the
map. Those ratings are compared with what PhotoCast forecast, to find the mornings it under-rated, the trips it
should have warned you off, and where it is consistently too hopeful or too cautious.

### How a change is made
1. **A written plan.** Each feature starts as a plan that sets out what will change and why. There are now over
   120 of them. The plan is reviewed adversarially: reviewers are asked to find what's wrong with it, not to
   agree with it.
2. **The code is written separately.** A different AI model writes the code, in its own isolated copy of the
   project, from the plan. It has to get the full set of checks passing before anyone looks at the result.
3. **An independent review.** The planner reviews the change line by line, and every change is then reviewed again
   by a separate AI reviewer from a different company. Anything they find is fixed and checked again.
4. **Automatic checks.** Every change runs the full test suite and code checks. A change can't be merged
   while any of them is failing.
5. **A person releases it.** Releases are made by the owner, by hand, after the changes have been used for real.
   Each release is recorded in a changelog, and a backup is taken first.

### The automated checks
- **Tests.** More than 25,000 automated tests cover the server and the app: the forecasting rules, the
  maps and planning screens, the tide and aurora logic, and the small details such as which day "today" is
  in the UK when the server runs on a different clock.
- **Tests that are tested.** It's possible to write tests that pass whatever the code does. The server's code is
  held to a minimum of 80% test coverage, and once a week mutation testing deliberately
  plants small bugs in the core forecasting code and checks that the tests catch them.
- **Real data, recorded.** The weather, space-weather, postcode and light-pollution services PhotoCast relies on
  are tested against recorded real responses, so a change in their format is caught before it reaches you.
- **A real database.** Every change to how data is stored (more than 200 so far) is tested against a real copy of
  the production database software before it can be merged.
- **Code standards.** Automatic checks enforce documentation, formatting and common bug patterns, and look for
  security mistakes in the code. The app's code is checked for accessibility problems too.

### Security and your data
- Code is scanned for security problems on every change, and again every week.
- Third-party libraries are kept up to date automatically, and checked for known vulnerabilities.
- Personal information, such as your home postcode and settings, is never stored in the shared caches that
  speed up forecasts for everyone.
- Sign-in tokens are kept out of the server's logs, and a test checks that they stay out.
See the [Privacy notice](privacy.html) for what PhotoCast stores and why.

### Keeping it running
- **Built the same way every time.** The database, the server and the app each run in their own container, built and deployed automatically from the released code. Every release is repeatable, and the service isn't tied to one particular machine.
- **When a source fails.** If a weather or tide service is slow or unavailable, PhotoCast retries, then backs off
  rather than hammering it. A location that keeps failing is set aside automatically instead of holding up the rest.
- **A watchdog.** If a forecast run doesn't start on time, or is still running when sunrise is close, the owner is
  emailed straight away.
- **Daily health checks.** The live service is checked every day, from inside and outside, and after every release.
- **Backups that are tested.** The database is backed up every night, and every day the latest backup is
  actually restored and checked, because a backup is only useful if it can be restored.
- **A way back.** There is a rollback procedure to return to the previous version if a release goes wrong.

### Where it's still improving
Being honest about the gaps is part of the same standard.
- The app's tests run in a simulated browser. A few tests in real browsers exist, but they're run by hand rather than on every change.
- The server's tests are checked by mutation testing and a coverage minimum. The app's tests don't have either yet.
- The larger forecast regression suite is run by hand rather than on every change, because each run asks the AI
  model real questions and costs real money. The weekly sky check fills that gap in between.
- PhotoCast runs on a single server, without a separate test copy of the live service. Backups, health checks and
  rollback are what make that safe enough for now.
- "How was it?" is only as useful as the ratings people give it. If you're on Pro, rating a sunrise after you've
  been out makes the forecasts better for everyone.

### Closing
Line: PhotoCast is a forecast, not a promise, and the sky will always have the last word. The aim is that when
the forecast is wrong, it's the weather's fault, not the software's.
Links: Read the [FAQ](faq.html) · Browse the [guide](guide/index.html) · [Start free](https://app.photocast.online) (use
whatever start URL index.html/faq.html already use).
