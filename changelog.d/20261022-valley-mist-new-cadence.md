### Fixed — Coming up rows show the observed recent rate, not a guess

The Coming up strip printed the configured first guess as if it were an observed rate ("about one a
week" beside "2 plumes"), and a Valley mist row called itself RECURRENT before it had recorded a day.
Dust, valley inversions and valley mist now state the rate the trailing window actually shows, on the
history line once it holds two occurrences ("2 plumes in the last 60 days · lately about one a month",
"18 strong mornings in the last 60 days · lately about twice a week"), and their threshold line states
no frequency. The history lines say "in the last 60 days" instead of "since <date>", because the first
occurrence's date is often just the window's own start, and the gap wording gained "every other day"
and "twice a week". Valley mist reads `new` until two likely days are recorded. Scores, badges, rarity
bits and the tide rows are unchanged.
