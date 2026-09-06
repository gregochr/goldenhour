### Fixed — one answer per region, across the Plan tab and the Map tab

The region rail read a region's verdict straight off `displayVerdict`, while the map's window pill
read it through the shared resolver that also maps a legacy cached payload's triage verdict. On such
a payload the pill said `Worth it` above a rail cell saying `Not scored`. Both now read the same
helper. The rail's ranking comparator is shared too, rather than copied a third time, so the rail,
the map's leading-region pick and the new panel cannot drift apart about what "best region" means.
