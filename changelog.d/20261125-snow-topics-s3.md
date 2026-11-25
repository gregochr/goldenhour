### Fixed — snow topics read each place's own snowpack and white summits in view (snow S3)

"Fresh snow", "Fresh snow with mist" and "Snow on the fells" no longer read the 9 km grid snow depth or the
freezing level. They read the point snowpack each place and summit is modelled with (`snow_state`, from S2): a
place has snow underfoot when its own modelled snow water reaches 3 mm, at any height, and a place is ringed for
"Snow on the fells" when its nearest white summit (5 mm) is within 12 km and its sunrise low cloud is at most
60 %. An all-white place carries both. The fells topic words itself per day as green below, white throughout, or
unknown below, from the majority of its ringed places. Facts become `fresh ~N cm` or `lying` and `<summit> white`;
the `snow line` and `N m below the tops` facts are gone. A place with a missing or stale snow row gets no snow topic.
The daily topic log's SNOW intensity switches from grid depth in metres to modelled snow water in millimetres from
today (a unit change under the same topic type), and the Ready question "Is there snow on the tops?" is now offered
only when "Snow on the fells" is live.
