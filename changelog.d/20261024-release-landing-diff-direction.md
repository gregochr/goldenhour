### Fixed — release-landing.sh's diff no longer reads backwards

The dry run's per-file stat compared the commit to the host's working tree, so files the deploy would add showed as
`Bin 101098 -> 0 bytes` and added Dockerfile lines showed as deletions. It now diffs in reverse and is labelled
"what the deploy changes on the host (host now → sha)", so the stat reads host-now to after-deploy.
