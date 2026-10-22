### Added — Ask explains a named place's rating

Asking "Why is Alnmouth a 4 on Saturday morning?" used to be answered "Not in the forecast", because Ask's only
tool for a place's rating, `rank_spots`, returns places PhotoCast would recommend, and a 4★ place in a
thin-sample region (hatched on the Map) or one the neighbour guard holds back is not one. A new typed-question
tool, `look_up_place`, returns one named place's served rating, verdict, headline and tide fit at each window,
with a status saying whether PhotoCast recommends it and why not. The answer explains the rating and never
offers the place as a pick: a lookup adds nothing the validator accepts as evidence, so recommendations,
`rank_spots`, the Ready answers and the Plan and Map picks are unchanged. A neighbour outlier the reader names
is now given its star, with a note that it is rated well above the places beside it. Explanation answers are
not cached.
