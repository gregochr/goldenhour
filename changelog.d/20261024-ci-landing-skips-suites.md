### Changed — landing-only PRs skip the backend and frontend suites

CI's docs-only detector now also treats anything under `landing/` (the static marketing site,
deployed by `release-landing.sh` and never built by CI) as skippable, so a PR that touches only
`landing/` and documentation skips the backend and frontend suites and the Java CodeQL scan, as a
docs-only PR already does. The required checks still report green; the JavaScript CodeQL scan still
runs. Any other path in the diff, an empty diff, or a non-PR event still runs everything.
