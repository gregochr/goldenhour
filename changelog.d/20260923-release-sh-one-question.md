### Changed — `release.sh` asks one question: the version

The release script used to ask five questions: the commit to tag, the version, whether to promote
the changelog, the tag message, and a final "Proceed?". It now asks only for the version. It
offers the next major, patch and minor versions as `1`/`2`/`3` (Enter takes the patch) or takes
any `x.y.z` typed in, and that answer is the go-ahead. The commits since the last tag are printed
before the question, then the changelog promotion, tag and push run unattended. The old questions
are now flags: `./release.sh 2.21.3` skips the version question, `--target <ref>` tags an earlier
commit on main, and `-m "<message>"` sets the tag message. Every refusal guard is unchanged. It
stops to ask again only if main gained a commit other than the promotion while that PR was
merging, because the reader never saw that commit in the list they approved.

After pushing the tag it now watches the Deploy workflow. It prints each job as it starts, then
reports `✅ vX.Y.Z is deployed to production` or `❌` with the failed jobs, and rings the terminal
bell and posts a macOS notification either way. The watching stops on its own after an hour.
Ctrl-C stops the watching only, not the deploy.
