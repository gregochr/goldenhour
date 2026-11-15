### Fixed — A clear sky overhead no longer demotes a spot when there is a strong cloud canvas toward the sun

Triage demoted a GO slot to MARGINAL, and flagged it "Clear all layers", whenever the observer point's
low, mid and high cloud were all under 15%, even when the sun's own horizon, 113 km away, carried a high
or mid-level canvas that the full evaluation rates highly. A slot is now kept at GO when it has horizon
data, the cloud toward the sun is low-free (under 15%) and its mid or high layer is 40% or more. Anti-sun
cloud does not count, a weak (15-39%) sunward canvas stays demoted, and a slot with no horizon data behaves
as before (`clear-sky-demotion-plan.md`, Phase B). A GO region's summary now reads "Go at N of M locations"
rather than "Clear at N of M locations", since a GO slot is no longer always a cloudless one. The temporary
`[CLEAR-ALL-LAYERS]` diagnostic log is removed.
