### Fixed — acknowledgements credited a deleted dependency and misdescribed solar-utils

`react-leaflet-cluster` was removed from the product on 2026-09-04, the day before this
revamp, and is not in `frontend/package.json`. The acknowledgements page still credited
it — carried through the rewrite verbatim, so it became wrong between the two versions.

`solar-utils` was listed as *v1.2.0 … Published on GitHub Packages*. Both halves were
wrong: `backend/pom.xml` pins 2.1.0 and resolves it from JitPack. The repository element
that declares it is `<id>github</id>` with a `jitpack.io` URL, which is the trap that
makes this an easy thing to get wrong twice.
