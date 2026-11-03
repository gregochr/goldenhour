### Added — Repeating jobs, watchdog windows, arrows and a close-up on the Scheduler's Day picture

The Day's jobs picture now draws the whole day. Under the scheduled rows, a "Repeating through the day"
section shows each polling job as ticks (or a band labelled "every 1 min" when there are too many to
draw), with failed and partial runs marked in coral and amber. A paused job's would-be fires appear
as dashed outlines, the forecast cycles show their watchdog window with a red deadline line, and thin
arrows join a job to the poller that collects its results (and to the job staggered after it); a chip
turns the arrows off, and selecting a job turns its arrows gold and dims the rest. Hovering a mark or
focusing a row shows a tooltip with its status, time, duration, outcome and plain name, and Escape
closes it without closing the panel. "Whole day" / "02:00-05:00 close-up" zooms into the crowded
overnight slot and is remembered. The day endpoint gains a `PAUSED` fire status and a `ticks` list
for repeating cron jobs, both backward compatible.
