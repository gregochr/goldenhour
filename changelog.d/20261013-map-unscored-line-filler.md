### Fixed — the map no longer says "not scored yet" over a window that has ratings

On the Map tab, stepping to a date past the six windows the Plan tab renders (a Friday sunset seen
from Tuesday, say) drew "This event is not scored yet" in the top-right while the chips showed real
stars and the counts footer read "14 rated". The line took its answer from the Plan tab's served
window, which those later dates do not have, so it read the missing window as "nothing rated". On
such a date it now asks the same scores the chips and the footer read, and stays silent when any
place is rated for that date and event. A window the Plan tab does serve keeps its own rule, and
the colour key still stays down on the later dates, because no heat field is painted there (the
field is built per rendered window; painting it for the later dates is a follow-up). Frontend only.
