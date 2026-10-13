### Changed — the afternoon forecast job's description no longer says "mid-afternoon"

V174 rewords the `intraday_forecast_refresh` row on Operations › Scheduler: it now runs "ahead of
tonight's sunset (a set time before it on the sun-relative schedule, else at its cron time)", naming
no time of day. On the sun-relative schedule the cycle runs as early as about 11:40 UK in December,
so "mid-afternoon" (or any "afternoon") read wrong. The update applies only while the row still holds the wording V141 wrote,
so a description edited by hand is left alone, and it changes nothing but the description.
