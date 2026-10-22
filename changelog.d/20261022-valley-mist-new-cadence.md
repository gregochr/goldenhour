### Fixed — Coming up conditions no longer state a frequency they have not measured

The Coming up strip printed the configured first guess as if it were an observed rate ("about one a
week" beside "2 plumes", and a Valley mist row calling itself RECURRENT before it had recorded a day).
Dust, valley inversions and valley mist now open their quant line with "no frequency measured yet"
unless the rarity was measured (dust at or over the evidentiary bar), and valley mist reads `new` until
the trailing window holds two likely days. The history lines also say "in the last 60 days" instead of
"since <date>", because the first occurrence's date is often just the window's own start. Scores,
badges, rarity bits and the tide rows are unchanged.
