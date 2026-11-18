### Fixed — CI no longer fails on a transient Maven wrapper download error

Backend, CodeQL, E2E, accessibility, PIT, OWASP, prompt-regression and real-API-smoke jobs now cache the Maven distribution the wrapper downloads and warm it with up to three attempts (a shared composite action, `.github/actions/maven-wrapper`) before the real Maven command, so an intermittent `wget: Failed to fetch ...apache-maven-3.10.1-bin.zip` no longer fails a required check. Real build failures are not retried. No job, check or build step changed.
