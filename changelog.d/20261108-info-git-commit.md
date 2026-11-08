### Added — `/actuator/info` now reports the running build's git commit

`GitCommitInfoContributor` publishes `git.commit.id` (the full sha) and `git.commit.time` from `GitInfoService`, which
reads the `GIT_COMMIT_*` environment variables in the production image (no `git.properties`). It stays silent when
Spring Boot already has `GitProperties`, and when no commit is known. The commit message and branch are deliberately
not published. This switches on the post-deploy smoke's "new build is the one running" probe for images built after
this change; the first deploy after it merges still warns, because the outgoing image lacks the field.
