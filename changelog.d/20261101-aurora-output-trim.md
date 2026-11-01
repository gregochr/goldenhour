### Changed — Aurora ratings no longer generate unused per-location detail

The aurora scoring prompt now asks Claude only for each location's name, stars and a summary of up to 90 characters. The per-location `detail` bullets, which nothing displayed, are no longer generated, and the moon-impact note moves into the summary. The output token budget per location drops from 220 to 110, cutting aurora output by roughly 55-65% and speeding up alerts.
