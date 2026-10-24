### Added — release-landing.sh deploys the landing site from the laptop

The landing site (`landing/`, photocast.online) is not part of the tagged release, so it was
redeployed by typing commands on the production host. `./release-landing.sh` now does it from the
laptop: it refuses a page whose asset has no `COPY` line in the Dockerfile, shows what will change
on the host, asks one question, tags the running image for rollback, rebuilds, smoke-tests every
page and asset there, and checks the public URL. It only ever runs `git checkout FETCH_HEAD --
landing/` on the host, never a pull, because the host's checkout is stale and carries hand-edited
production config. `--rollback` lists and restores the saved images. `landing/README.md` is updated,
and the host's old `deploy-landing.sh` (which runs `git pull`) should be deleted.
