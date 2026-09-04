### Fixed — the frontend dependency audit no longer reds a build on registry weather

CI's audit step failed on a pull request that touched no dependency file, reporting a `400` from
npm's `/-/npm/v1/security/audits/quick` endpoint and a notice that the endpoint is being retired.
The same commit passed on re-run, so the tree was never at fault.

The message pointed at the wrong fix. npm does not choose that endpoint: the Arborist version
bundled with npm 10 — which is what Node 22 ships — asks the **bulk** advisory endpoint first and
quietly falls back to the quick one when that throws, logging the real reason at a verbosity CI
never prints. So the failure on screen came from a fallback nobody selected, about an endpoint
nobody asked for, while the actual cause — the bulk endpoint being briefly unavailable — stayed
invisible. Switching to the bulk endpoint was already the behaviour.

The audit now runs through `scripts/npm-audit.sh`, which does two things. It runs the audit under
npm 11, which removed the quick fallback outright, so a registry problem names the endpoint that
actually failed. And it retries **only** on transport errors — a real advisory still fails on the
first attempt, because a gate that gets quieter the more it is asked is not a gate. A registry that
stays unreachable across every attempt still fails the build rather than reporting a pass it never
obtained.

The rest of the job keeps using the runner's own npm, so nothing changes about how dependencies are
installed.
