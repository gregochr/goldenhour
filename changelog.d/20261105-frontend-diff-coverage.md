### CI — Fail a PR whose changed frontend lines are under 90% covered

The frontend CI job now runs `diff-cover` (pinned 10.0.0) over Vitest's new `lcov` report on pull requests and fails when less than 90% of the lines a PR adds or changes under `frontend/src` are covered. Test files, `src/test/**` and `src/main.jsx` are not counted, and a PR with no covered source changes passes. Line coverage only; the global coverage floor is unchanged.
