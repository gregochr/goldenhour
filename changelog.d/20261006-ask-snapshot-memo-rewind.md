### Fixed — Ask PhotoCast's snapshot memo is bypassed while a rewind is active

The 30-second memo of the Ask read model treated a negative age (a memo built at a later real instant than the
rewound "now") as younger than 30 seconds, so an admin's rewound `GET /api/ask/ready` was freshness-checked against
the live snapshot and its cards could contradict the rewound Plan view, and kept reusing it. A request under a
rewind now builds a snapshot for the rewound clock and neither reads nor writes the memo, the same rule
`AlmanacService` follows for its day cache, and a memo whose age is negative is rebuilt rather than reused.
