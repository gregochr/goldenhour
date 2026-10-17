### Changed — the Plan card no longer names a lone high sky rating as the best place within reach

The neighbour guard's server flag (`neighbourOutlier` on a served slot) now reaches the Plan tab.
A card's "Best" line, the popup header's "best N★ within reach", the search box's figure and the
lunar-eclipse dawn-race caption skip a flagged place and name the next rated one; if that place is
rated below 3 stars, or nothing else is rated, the line reads a muted "no clear pick" (its tooltip
says no place is named because the highest rating is well above every place around it, and says
"within reach" only when a drive time was measured). The flagged place keeps its star everywhere a
star is shown: the spread histogram, the pool counts, the ranked spot strip and the map. With the
guard off on the server (the default until its final phase) nothing changes.
