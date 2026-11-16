### Added — shadow log of how many places in the same weather cell could share one rating call

Each forecast batch submission now writes one `[BATCH SHARE]` line per bucket (inland or coastal, near or far term) counting how
many sky requests would have been saved if places whose requests are identical apart from the place name sent one request
between them, once with the event time exactly as printed and once truncated to the minute. It is measurement only: no request,
prompt, custom id or stored row changes, and a failure while counting is logged and ignored. The figures decide whether the
shared-request change in the triage cost-cut plan is worth building.
