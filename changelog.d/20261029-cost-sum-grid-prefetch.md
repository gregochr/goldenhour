### Changed — run cost summed in SQL, batch weather prefetched once per grid cell

Completing a job run now asks the database for the run's total cost instead of loading every
`api_call_log` row (request and response bodies included) and adding them up in Java, and the batch
summary on the admin metrics page reads only the five columns it uses. Totals and summaries are
unchanged.

The forecast batch's weather pre-fetch now fetches once per Open-Meteo grid cell and fans the
result out to every location in that cell; a location with no known grid cell keeps its own fetch.
Declared input change: Open-Meteo downscales temperature by a point's elevation, so two coordinates
in one cell could previously differ slightly. They now share one reading, the same assumption the
briefing's weather loader already makes.
