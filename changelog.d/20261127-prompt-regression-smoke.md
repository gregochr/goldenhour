### Changed — paid prompt regression runs as a once-per-PR smoke, on a label

The paid prompt-regression suites ran in full on every push of a prompt-changing PR, which exhausted the
CI key's spend cap on 2026-11-27. They now run only when the `run-prompt-regression` label is added to the
PR, as the last step before merge: the sky quick check plus `AskPromptRegressionTest`, about $0.25 a run.
The full suites, ValleyMist included, run from a manual run with `scope=full`. Job and step names, the key
and the never-required rule are unchanged.
