package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.LunarEclipseSight;
import com.gregochr.goldenhour.service.LunarEclipseCatalog.LunarEclipse;
import com.gregochr.goldenhour.util.ForecastHorizon;
import com.gregochr.goldenhour.util.LunarEclipseCalculator;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Builds the per-location {@link BriefingSlot.EclipseSight} attached at
 * {@link BriefingSlotBuilder}'s own build-time seam — the dawn/dusk race data
 * ({@code docs/engineering/lunar-eclipse-plan.md} §2.5).
 *
 * <h2>Real eclipses</h2>
 *
 * <p>A slot earns a sight when the built date carries a catalogued {@link LunarEclipse}
 * ({@link LunarEclipseCatalog#on}) <b>and</b> that eclipse's own window
 * ({@link LunarEclipseWording#eventType}) matches the slot's {@code eventType} — an eclipse lands
 * on exactly one of a day's two windows, the same rule {@link LunarEclipseHotTopicStrategy} uses
 * for the topic pill, so the two can never disagree about which window an eclipse belongs to. The
 * per-location moon geometry is {@link LunarEclipseCalculator#sight}, memoised there per
 * (eclipse date, latitude, longitude) — this class adds no caching of its own. Nothing here gates
 * on {@link LunarEclipseSight#visible()}: a location the Moon never clears the horizon from still
 * gets a sight (a negative altitude is itself the honest answer), the same way an inland location
 * still carries a {@code false}-valued {@link BriefingSlot.TideInfo} rather than none at all.
 *
 * <h2>Simulation parity is a SERVE-time overlay, never a build-time write</h2>
 *
 * <p>{@link #forSlot} — the only method {@link BriefingSlotBuilder} calls — attaches REAL sights
 * only. It used to also attach a fabricated simulated sight on the build path, and that was a
 * genuine defect (Codex review of #914): {@code BriefingService.getCachedBriefing()} serves a
 * persisted/cached {@code DailyBriefingResponse} on every request and only overlays a few fields
 * live (hot topics, aurora) — it does not re-run {@code BriefingSlotBuilder}. So a simulated sight
 * written at build time would sit inert in {@code daily_briefing_cache} until the next scheduled
 * refresh: toggling the admin simulation ON would show nothing until a refresh minutes or hours
 * later, and toggling it back OFF — or restarting the app — would leave the fabricated sight
 * visible and reloadable from the persisted cache indefinitely, the same "volatile state baked
 * into a durable cache" mistake CLAUDE.md's "Hot topics are recomputed LIVE on every serve" bullet
 * already names for a different field.
 *
 * <p>{@link #simulatedSightFor} is the correct seam instead: called from
 * {@code BriefingService.getCachedBriefing()}'s live-overlay step, on every serve, exactly
 * alongside the existing hot-topics/aurora overlays it already does there. Nothing this method
 * returns is ever persisted — it exists only in the response built for that one request — so
 * enabling the simulation shows the race on the very next request, disabling it removes it on the
 * next request after that, and a restart with the simulation off shows nothing, because the
 * persisted cache never contained a simulated sight to reload.
 *
 * <p>When {@link HotTopicSimulationService} has {@code LUNAR_ECLIPSE} active, no future catalogued
 * eclipse falls inside a live forecast's 5-day window — the nearest is 2028-01-12 — so the dawn
 * race could never otherwise be seen in a browser before then. On "today" (the simulated topic's
 * own placement, {@code HotTopicSimulationService}'s {@code LUNAR_ECLIPSE_SIM_ENRICHMENT}), this
 * re-dates the real 2026-08-28 Dunstanburgh eclipse's own reduction onto that date: every clock
 * field keeps its time-of-day, only the calendar date changes, so a future change to that
 * catalogue entry cannot drift out of step with what the topic pill and the admin's simulation
 * panel both already describe. The whole sight — moon geometry AND light stops — is Dunstanburgh's
 * own, re-dated, rather than location-specific: {@link #simulatedSightFor} is called once per
 * (date, event type) at serve time and its single result reused across every slot on that window,
 * because a serve-time overlay has no per-location {@code LocationEntity} to hand — only the
 * already-built {@link BriefingSlot}s, keyed by name/id, not coordinates. This is a verification
 * affordance for {@code docs/engineering/lunar-eclipse-plan.md} §7's browser check, gated on
 * {@link HotTopicSimulationService#isEnabled()} exactly as the aurora admin simulation is gated on
 * {@code AuroraStateCache.isSimulated()} — never a product path a real reader reaches.
 */
@Component
public class EclipseSightAssembler {

    /**
     * The type string carried on every {@link BriefingSlot.EclipseSight} and read as the key
     * {@link HotTopicSimulationService#getActiveTypes()} is tested against for simulation parity —
     * {@link LunarEclipseHotTopicStrategy}'s own constant, not a second independent literal, so a
     * rename there fails this class's compile rather than silently breaking either use here.
     */
    static final String TYPE = LunarEclipseHotTopicStrategy.TYPE;

    private static final String SUNRISE = "SUNRISE";
    private static final String SUNSET = "SUNSET";
    private static final String DAWN = "DAWN";
    private static final String DUSK = "DUSK";

    /**
     * How far past nautical dawn/dusk the umbral phase may still reach and count as racing the
     * light — see {@link #race}.
     */
    private static final long RACE_WINDOW_MINUTES = 60;

    /** The eclipse the simulated sight is re-dated from — see the class javadoc. */
    private static final LocalDate SIMULATED_ECLIPSE_DATE = LocalDate.of(2026, 8, 28);

    /** Dunstanburgh Castle — the location every simulated sight's clock times were reduced at. */
    private static final double SIMULATED_LAT = 55.49;
    private static final double SIMULATED_LON = -1.59;

    private final LunarEclipseCalculator calculator;
    private final SolarService solarService;
    private final HotTopicSimulationService simulationService;
    private final Clock clock;

    /**
     * Constructs an {@code EclipseSightAssembler}.
     *
     * @param calculator        per-location moon geometry for a catalogued eclipse
     * @param solarService      the four light-boundary instants the race's gradient stops key off
     * @param simulationService the admin hot-topic simulation toggle — gates simulation parity
     * @param clock             the injected clock {@code ForecastHorizon.today} resolves "today"
     *                          against for simulation parity's placement
     */
    public EclipseSightAssembler(LunarEclipseCalculator calculator, SolarService solarService,
            HotTopicSimulationService simulationService, Clock clock) {
        this.calculator = calculator;
        this.solarService = solarService;
        this.simulationService = simulationService;
        this.clock = clock;
    }

    /**
     * The REAL eclipse sight for one location's slot, or null when no catalogued lunar eclipse's
     * own window matches this one — the {@link BriefingSlotBuilder} build-time seam.
     *
     * <p><b>Never attaches a simulated sight.</b> See this class's own javadoc for why: a
     * simulated sight belongs only in the serve-time overlay ({@link #simulatedSightFor}), never
     * in anything {@code BriefingSlotBuilder} writes into {@code daily_briefing_cache}.
     *
     * @param location  the location the slot is for
     * @param date      the slot's date
     * @param eventType the slot's own window (SUNRISE or SUNSET)
     * @return the real sight, or null
     */
    public BriefingSlot.EclipseSight forSlot(LocationEntity location, LocalDate date, TargetType eventType) {
        String eventTypeStr = eventType.name();
        Optional<LunarEclipse> real = LunarEclipseCatalog.on(date);
        if (real.isPresent() && LunarEclipseWording.eventType(real.get()).equals(eventTypeStr)) {
            return buildReal(real.get(), location, date, eventTypeStr);
        }
        return null;
    }

    /**
     * The cheap top-level gate {@code BriefingService} checks before walking the served response
     * tree at all — near-zero cost on every request while simulation is off, the overwhelming
     * common case, the same "IDLE costs nothing" shape the aurora FSM already uses.
     *
     * @return true when {@code LUNAR_ECLIPSE} simulation is currently active
     */
    public boolean isSimulationActiveForLunarEclipse() {
        return simulationService.isEnabled() && simulationService.getActiveTypes().contains(TYPE);
    }

    /**
     * The simulated sight for this date and event type, or null when simulation does not apply
     * here — the serve-time overlay seam. See this class's own javadoc for the full reasoning.
     *
     * <p>Callers should check {@link #isSimulationActiveForLunarEclipse} once before walking a
     * whole response tree; this method re-checks it anyway (cheap) so it is safe to call in
     * isolation too.
     *
     * @param date      the window's date
     * @param eventType the window's own event type
     * @return the re-dated Dunstanburgh sight, or null
     */
    public BriefingSlot.EclipseSight simulatedSightFor(LocalDate date, TargetType eventType) {
        if (!isSimulatedFor(date, eventType.name())) {
            return null;
        }
        return buildSimulated(date);
    }

    private boolean isSimulatedFor(LocalDate date, String eventTypeStr) {
        // LunarEclipseCatalog.on(date).isEmpty() is part of the gate, not an incidental extra:
        // without it, an admin simulating LUNAR_ECLIPSE on a date that happens to carry a REAL
        // eclipse whose own window is the OTHER event type (e.g. a real SUNSET eclipse's date)
        // would still overlay the fabricated SUNRISE sight on that date's SUNRISE slots, rather
        // than staying absent the way every other non-matching window does. Simulation must never
        // manufacture a sight on a date this catalogue already has a real answer for, in either of
        // that date's windows.
        return isSimulationActiveForLunarEclipse()
                && SUNRISE.equals(eventTypeStr)
                && date.equals(ForecastHorizon.today(clock))
                && LunarEclipseCatalog.on(date).isEmpty();
    }

    private BriefingSlot.EclipseSight buildReal(LunarEclipse eclipse, LocationEntity location,
            LocalDate date, String eventTypeStr) {
        LunarEclipseSight sight = calculator.sight(eclipse, location.getLat(), location.getLon());
        LocalDateTime maximum = LunarEclipseWording.toLondonLocal(eclipse.max());
        LocalDateTime umbraStart = LunarEclipseWording.toLondonLocal(eclipse.u1());
        LocalDateTime umbraEnd = LunarEclipseWording.toLondonLocal(eclipse.u4());

        List<BriefingSlot.LightStop> stops;
        String race;
        if (SUNRISE.equals(eventTypeStr)) {
            stops = dawnStops(location.getLat(), location.getLon(), date);
            race = race(DAWN, umbraEnd.isAfter(stops.get(0).time().minusMinutes(RACE_WINDOW_MINUTES)));
        } else {
            stops = duskStops(location.getLat(), location.getLon(), date);
            race = race(DUSK, umbraStart.isBefore(stops.get(3).time().plusMinutes(RACE_WINDOW_MINUTES)));
        }

        return new BriefingSlot.EclipseSight(TYPE, sight.moonAltAtMax(), sight.moonAzAtMax(),
                sight.moonAzCardinal(), maximum, umbraStart, umbraEnd, sight.moonset(),
                sight.moonrise(), sight.setsInShadow(), sight.risesInShadow(), race, stops);
    }

    /**
     * Re-dates the fixed 2026-08-28 template onto the simulated window's date.
     *
     * <p><b>{@link #retime} keeps only a time-of-day and reapplies it to one target date — it has
     * no next-day carry.</b> That is safe here only because the template's whole chain (u1, max,
     * u4 and this location's own moonset) is independently verified to sit inside a single London
     * calendar day: {@code LunarEclipseCatalog}'s own class javadoc records that none of its seven
     * seeded entries crosses London midnight at {@code max}, and 2026-08-28's own contacts — p1
     * 01:23 through p4 07:02 UTC, all on the 28th — confirm the same for u1/u4, while the real
     * Dunstanburgh moonset (~06:18 BST, inside {@code [u1, u4]}) is likewise same-day. Re-dating
     * five same-day instants onto one target date preserves their relative order by construction
     * (each keeps the gap between its own time-of-day and the others'). <b>If a future phase ever
     * changes {@link #SIMULATED_ECLIPSE_DATE} to a template whose span crosses midnight, this
     * method would silently misorder the re-dated result</b> — re-verify that invariant before
     * changing the constant, the same discipline the catalogue's own entries are held to.
     *
     * <p>Takes no {@code LocationEntity}: this is a serve-time overlay over already-built
     * {@link BriefingSlot}s, which carry a name/id, not coordinates — see this class's own
     * javadoc. The light stops below are therefore Dunstanburgh's own too, re-dated exactly like
     * the moon geometry, rather than computed per the location the sight ends up attached to.
     */
    private BriefingSlot.EclipseSight buildSimulated(LocalDate date) {
        LunarEclipse template = LunarEclipseCatalog.on(SIMULATED_ECLIPSE_DATE)
                .orElseThrow(() -> new IllegalStateException(
                        "the simulated lunar eclipse date is not catalogued: " + SIMULATED_ECLIPSE_DATE));
        LunarEclipseSight sight = calculator.sight(template, SIMULATED_LAT, SIMULATED_LON);

        LocalDateTime maximum = retime(LunarEclipseWording.toLondonLocal(template.max()), date);
        LocalDateTime umbraStart = retime(LunarEclipseWording.toLondonLocal(template.u1()), date);
        LocalDateTime umbraEnd = retime(LunarEclipseWording.toLondonLocal(template.u4()), date);
        LocalDateTime moonset = retimeNullable(sight.moonset(), date);
        LocalDateTime moonrise = retimeNullable(sight.moonrise(), date);

        // Defence in depth for the invariant this method's own javadoc documents: retime() has no
        // next-day carry, so a future template whose span crosses midnight would silently misorder
        // the re-dated result. Fail loudly here rather than serving a chronologically impossible
        // sight — the same "defend the invariant at construction, not merely in a comment" rule
        // LunarEclipseSight's own compact constructor already applies to moonset/moonrise.
        if (!umbraStart.isBefore(maximum) || !maximum.isBefore(umbraEnd)) {
            throw new IllegalStateException("simulated sight re-dated out of order: umbraStart="
                    + umbraStart + " maximum=" + maximum + " umbraEnd=" + umbraEnd);
        }

        List<BriefingSlot.LightStop> stops = dawnStops(SIMULATED_LAT, SIMULATED_LON, date);
        String race = race(DAWN, umbraEnd.isAfter(stops.get(0).time().minusMinutes(RACE_WINDOW_MINUTES)));

        return new BriefingSlot.EclipseSight(TYPE, sight.moonAltAtMax(), sight.moonAzAtMax(),
                sight.moonAzCardinal(), maximum, umbraStart, umbraEnd, moonset, moonrise,
                sight.setsInShadow(), sight.risesInShadow(), race, stops);
    }

    private static String race(String label, boolean racing) {
        return racing ? label : null;
    }

    /**
     * The four SUNRISE light stops — {@code NAUTICAL_DAWN}, {@code CIVIL_DAWN}, {@code SUNRISE},
     * {@code GOLDEN_MORNING_END} — keyed with {@code MastheadLight.RULE_COLOURS}' own strings, in
     * that order (index 0 is always {@code NAUTICAL_DAWN}, relied on by {@link #buildReal}/
     * {@link #buildSimulated}'s race test).
     */
    private List<BriefingSlot.LightStop> dawnStops(double lat, double lon, LocalDate date) {
        LocalDateTime goldenMorningEnd = LunarEclipseWording.toLondonLocal(
                solarService.goldenBlueWindow(lat, lon, date, true).goldenHourEnd());
        return List.of(
                new BriefingSlot.LightStop("NAUTICAL_DAWN",
                        LunarEclipseWording.toLondonLocal(solarService.nauticalDawnUtc(lat, lon, date))),
                new BriefingSlot.LightStop("CIVIL_DAWN",
                        LunarEclipseWording.toLondonLocal(solarService.civilDawnUtc(lat, lon, date))),
                new BriefingSlot.LightStop("SUNRISE",
                        LunarEclipseWording.toLondonLocal(solarService.sunriseUtc(lat, lon, date))),
                new BriefingSlot.LightStop("GOLDEN_MORNING_END", goldenMorningEnd));
    }

    /**
     * The four SUNSET light stops — {@code GOLDEN_EVENING_START}, {@code SUNSET},
     * {@code CIVIL_DUSK}, {@code NAUTICAL_DUSK} — in that order (index 3 is always
     * {@code NAUTICAL_DUSK}, relied on by {@link #buildReal}'s race test).
     */
    private List<BriefingSlot.LightStop> duskStops(double lat, double lon, LocalDate date) {
        LocalDateTime goldenEveningStart = LunarEclipseWording.toLondonLocal(
                solarService.goldenBlueWindow(lat, lon, date, false).goldenHourStart());
        return List.of(
                new BriefingSlot.LightStop("GOLDEN_EVENING_START", goldenEveningStart),
                new BriefingSlot.LightStop("SUNSET",
                        LunarEclipseWording.toLondonLocal(solarService.sunsetUtc(lat, lon, date))),
                new BriefingSlot.LightStop("CIVIL_DUSK",
                        LunarEclipseWording.toLondonLocal(solarService.civilDuskUtc(lat, lon, date))),
                new BriefingSlot.LightStop("NAUTICAL_DUSK",
                        LunarEclipseWording.toLondonLocal(solarService.nauticalDuskUtc(lat, lon, date))));
    }

    /** Re-dates a London-local instant onto a different calendar date, keeping its time-of-day. */
    private static LocalDateTime retime(LocalDateTime original, LocalDate targetDate) {
        return targetDate.atTime(original.toLocalTime());
    }

    private static LocalDateTime retimeNullable(LocalDateTime original, LocalDate targetDate) {
        return original == null ? null : retime(original, targetDate);
    }
}
