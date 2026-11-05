### CI — Check coverage of the backend lines each change touches

Pull requests now also fail when under 90% of the `backend/src/main` lines they add or change are covered by tests,
the same check the frontend has had since #1697. The per-class and whole-codebase floors cannot see a change that
adds untested code to an already well-covered class; this one can. The step runs `diff-cover` over the JaCoCo report
and writes its table to the job summary. No production code changed.
