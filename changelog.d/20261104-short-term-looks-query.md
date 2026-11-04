### Docs — a query to size an overnight run that does far-term only

`docs/engineering/cost-queries/short-term-looks.sql` measures how much each forecast look moves the rating it replaces (by event, cycle pair, days ahead and stability), checks the 21:00 rating against the last one before the event using readers' "How was it?" ratings, and splits the forecast cost by cycle, event and model from a chosen date.
