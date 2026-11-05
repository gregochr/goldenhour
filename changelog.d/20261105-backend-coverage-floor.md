### CI — Hold every backend class to 95% of lines and 90% of branches

`jacoco:check` now fails the build when any backend class covers under 95% of its lines or 90% of its branches
(it was 80% of lines, with 32 classes excluded). The exclusions are gone. Seven classes with a branch that cannot
be reached have their own floor in `backend/pom.xml`, each with its reason, and a whole-codebase floor of 99.3% of
lines and 95.8% of branches sits behind them. No production code changed.
