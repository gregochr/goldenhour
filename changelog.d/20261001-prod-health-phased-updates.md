### Fixed — the morning health check no longer fails on apt updates Ubuntu has deferred by phasing

The first live run of `scripts/prod-health.sh`'s daily apt-updates check (#960) failed on exactly
one package, `thermald`, and opened #962. The owner then ran `sudo apt upgrade` on dockermacmini
twice and got the same answer both times: `The following upgrades have been deferred due to
phasing: thermald` — `0 upgraded, 0 newly installed, 0 to remove and 1 not upgraded.` Ubuntu's
phased rollout can list a package as upgradable via `apt list --upgradable` for days before `apt
upgrade` will actually install it, and the owner cannot act on that — `sudo apt upgrade` already
refuses the package by design, and forcing it with `sudo apt install thermald` is a deliberate
owner call, not something a health check should demand on its behalf. `check_apt_updates` now
counts the FAIL, and names the packages, entirely from apt's own installable-now simulation
(`apt-get -s upgrade`, run without sudo) — parsing its summary line for what apt would actually
install, its "will be upgraded" block for which packages those are, and its "deferred due to
phasing" block for which packages it is holding back instead. `apt list --upgradable` is no longer
called by this check at all: it cannot tell a real upgrade apart from one phasing is holding back,
so using it for package names printed a phased package as installable even while the FAIL count
itself, correctly, excluded it. A host with nothing installable but packages held by phasing now
reports `ok` and names them, instead of failing a run the owner has no lever to clear; apt's "N not
upgraded" count also covers packages "kept back" for dependency reasons, which `apt upgrade` cannot
install but `sudo apt full-upgrade` can, so those are parsed into their own block and still FAIL the
run (naming them and pointing at `sudo apt full-upgrade --dry-run`), distinct from a phasing
deferral — and anything held back that names itself in neither block still FAILs as unrecognised
rather than being silently waved through.
