### Added — Map tab: how many places you rated on each past window, and a badge on the ones you did

A past window in the Map's window control now says `{n} rated` in star gold, or `Not rated`, where a
verdict would be: n is the number of places the signed-in reader has rated for it, "didn't see it"
included. The phone's Windows section shows the same words in place of `Past`. A window whose
ratings have not been read yet (an older day that has only just landed) or whose read failed prints
nothing in the dropdown, and keeps `Past` on the phone, rather than a false `Not rated`; under an
admin rewind there are no counts at all.

On a past window, every pin and every heat-view chip for a place you rated wears a small badge at its
top-right corner: `4★`, or `–` where you marked it "didn't see it". The badge is hidden from
assistive technology and its meaning is added to the pin's or chip's name (`, you rated 4 out of 5`
or `, you marked this not seen`). The label placer now reserves the badge's overhang (7px above, 9px
beside the chip), so a neighbouring name is moved clear of it rather than drawn under it. Nothing
changes on a window the forecast served, and an Ask pick never wears a badge, since a pick belongs to
its own window.
