### Added — Met Office warnings on the At a Glance matrix and in the window popup (W3)

The At a Glance matrix gains a warnings lane, first in the "days ahead" stack, one lane per run of days a Met Office
warning applies to, most severe on the top row (level, then impact, then likelihood). A window card wears a text marker
in the official level colour ("Amber warning +1", no icon or triangle, as the style guide asks) and its spoken sentence
names every warning. The window popup gains a WARNING row showing each warning in full: headline, every what-to-expect
line, what should I do, further details, the update text and "Updated" label for a re-issued warning, validity in UK
time, the places count for the area, the Met Office likelihood and impact matrix (drawn from the served numbers, tick on
the warning's cell, text alternative "Likelihood 3 of 4, impact 2 of 4"), the attribution and the Met Office links.
Warnings are matched against the polled list so a withdrawn warning leaves within the poll; a stale feed says so and
never reads as an all-clear. A warning never changes a rating, verdict or count, every role sees the same, and nothing
shows while `photocast.warnings.enabled` is off.
