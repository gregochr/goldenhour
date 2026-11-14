### Fixed — flaky waitlist step test in RegisterPageBranches

The waitlist test answered the Turnstile widget before its `useEffect` had rendered it, so under CI
load it answered the unmounted registration widget and the submit button stayed disabled. The test
helper now waits for the waitlist widget to be rendered before the test touches it. Test-only change.
