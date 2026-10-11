### Added — the Ask thread carries the night version

A 200 that names a night carries an opaque `nightVersion`, which the client echoes on that exchange in
`thread[]` exactly as it echoes `generatedAt` (and only on an exchange that has one, so a solar thread is
unchanged on the wire). A thread the server ends because the night forecast moved
(`threadResetReason: "night forecast updated"`) tells the reader so: "The night forecast has updated since
your last question — this is a fresh answer."
