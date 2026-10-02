### Changed — Checkstyle now fails a public type, method or constructor with no Javadoc

`checkstyle.xml` said "Javadoc on all public classes and methods", but `JavadocMethod` and
`JavadocType` only validate a Javadoc that exists, so nothing reported a missing one — which is how
`BriefingSlot.withEvaluationGate` went two weeks undocumented. `MissingJavadocType` and
`MissingJavadocMethod` (scope public; constructors and compact constructors included; bean
getters/setters and `@Override` methods exempt) now enforce it in `src/main`. Test sources are
exempt from those two checks only, through a new `backend/checkstyle-suppressions.xml` located by
the pom's `suppressionsLocation` (and copied by `backend/Dockerfile`, whose build runs Checkstyle).
The 18 existing gaps in 14 files are documented, and `GlobalExceptionHandler.handleUnexpected`'s
Javadoc, which had drifted above the wrong method, now sits on it.
