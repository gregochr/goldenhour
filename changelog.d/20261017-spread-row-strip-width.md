### Changed — Plan card Spread row: one histogram width across the strip

The Spread row's count columns were sized per card, from that card's longest count, so two cards
side by side with longest counts of 91 and 125 drew histograms of different widths. The width and
the smaller count type are now decided once for the whole strip, from the longest count on any card
that draws bars, so neighbouring cards line up. A card that prints text instead of bars (below the
minimum-sample gate) or an away cell contributes nothing, so it cannot widen the others.
