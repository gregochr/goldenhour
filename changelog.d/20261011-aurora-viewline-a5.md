### Docs — the 10 Oct aurora viewline night reconstructed, and the fix decided

`docs/engineering/aurora-viewline-investigation.md` §5 reconstructs the night of 10 Oct 2026 from
the host log: a MODERATE alert at 16:14 UTC on a 5.67 forecast (line at 56°N), escalation to
STRONG at 18:47 on a live Kp 7.0 (the live line, capped by the table at 52°N), and — after the
v2.25.1 deploy restarted the backend at 21:21 — a fresh MODERATE alert on a 6.67 forecast,
truncated to the Kp 6 row, at 54°N: the dashed amber line in the 22:43 screenshot. §6 records the
owner's nine decisions (camera visibility; NOAA's geomagnetic rule converted per longitude plus a
4° horizon allowance; no truncation; the live OVATION line no longer capped; the forecast line
computed per request from the rest-of-tonight maximum; always live with forecast as fallback;
forecast dashed amber, live solid ink) and §7 is the A6 specification.
