package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.AlmanacEvent;
import com.gregochr.goldenhour.model.AlmanacKind;
import com.gregochr.goldenhour.model.EclipseCircumstances;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.evaluation.PromptUtils;
import com.gregochr.goldenhour.util.EclipseCalculator;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Every catalogued solar eclipse in the feed's ninety-day range.
 *
 * <p>Exists separately from {@link EclipseHotTopicStrategy} for the reason
 * {@link SupermoonAlmanacSource} and {@link MeteorAlmanacSource} do: the hot-topic path is bounded
 * by a four-day forecast window, and this feed answers for ninety days. The two share the
 * catalogue and the reduction, so they cannot disagree about what an eclipse looks like from here —
 * only about how far ahead they can see it.
 *
 * <p><b>The rarity line is composed here, not on the client.</b> It rides {@code meta} as a
 * finished string. {@code AlmanacEvent.meta} is a {@code Map<String, String>} and the feed's stated
 * rule is that no number is parsed, compared or re-formatted on the client, so a bare
 * {@code returnYears} integer could not be rendered without breaking it. Composing the sentence
 * here also keeps the design's intent — that the line is <em>generated</em> from the return period
 * rather than authored per event — while the threshold that decides whether it is worth saying at
 * all lives with the catalogue, in {@link EclipseCatalog#RARITY_THRESHOLD_YEARS}.
 */
@Component
public class EclipseAlmanacSource implements AlmanacSource {

    /** Machine-readable discriminator. */
    static final String TYPE = "eclipse";

    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final LocationRepository locationRepository;

    /**
     * Constructs an {@code EclipseAlmanacSource}.
     *
     * @param locationRepository the enabled-location roster the eclipse is reduced against
     */
    public EclipseAlmanacSource(LocationRepository locationRepository) {
        this.locationRepository = locationRepository;
    }

    @Override
    public List<AlmanacEvent> events(LocalDate from, LocalDate to) {
        List<EclipseCatalog.Eclipse> eclipses = EclipseCatalog.between(from, to);
        if (eclipses.isEmpty()) {
            return List.of();
        }
        List<LocationEntity> enabled = locationRepository.findAllByEnabledTrueOrderByNameAsc();

        List<AlmanacEvent> events = new ArrayList<>();
        for (EclipseCatalog.Eclipse eclipse : eclipses) {
            toEvent(eclipse, enabled).ifPresent(events::add);
        }
        return events;
    }

    /**
     * One feed entry, or empty when no configured location sees this eclipse at all.
     *
     * <p>Degrades the way the interface's contract requires: an eclipse nobody can see is absent
     * rather than present with hedged figures, and an eclipse with no roster to reduce against
     * keeps its dates and carries no numbers rather than borrowing a city's.
     */
    private Optional<AlmanacEvent> toEvent(EclipseCatalog.Eclipse eclipse, List<LocationEntity> enabled) {
        Optional<Seen> best = enabled.stream()
                .map(location -> EclipseCalculator
                        .circumstances(eclipse.elements(), location.getLat(), location.getLon())
                        .map(c -> new Seen(location, c))
                        .orElse(null))
                .filter(java.util.Objects::nonNull)
                .max(Comparator.comparingDouble(s -> s.circumstances().magnitude()));

        if (enabled.isEmpty()) {
            // No roster to reduce against. The eclipse still happens, so the entry exists with its
            // date and its rarity — both facts of the catalogue — and no per-location figure.
            return Optional.of(new AlmanacEvent(
                    eclipse.date(), eclipse.date(), AlmanacKind.ALMANAC, TYPE,
                    title(null), datesOnlyDetail(),
                    AlmanacEvent.metaOf("rarity", rarity(eclipse)),
                    List.of()));
        }
        if (best.isEmpty()) {
            return Optional.empty();
        }

        EclipseCircumstances c = best.get().circumstances();
        return Optional.of(new AlmanacEvent(
                eclipse.date(), eclipse.date(), AlmanacKind.ALMANAC, TYPE,
                title(c), detail(c, best.get().location().getName()),
                AlmanacEvent.metaOf(
                        "coverage", c.obscurationPct() + "% covered",
                        "maximum", londonTime(c) + " · sun " + c.sunAltitudeRounded() + "° up "
                                + PromptUtils.toCardinal((int) Math.round(c.sunAzimuthDeg())),
                        "rarity", rarity(eclipse),
                        "location", best.get().location().getName()),
                List.of()));
    }

    /** A location and what it sees, so the roster can be ranked without recomputing. */
    private record Seen(LocationEntity location, EclipseCircumstances circumstances) { }

    private static String title(EclipseCircumstances circumstances) {
        if (circumstances == null) {
            return "Solar eclipse";
        }
        if (circumstances.isTotal()) {
            return "Total solar eclipse";
        }
        return circumstances.magnitude() >= 0.80 ? "Deep partial eclipse" : "Partial solar eclipse";
    }

    private static String datesOnlyDetail() {
        return "A solar eclipse falls on this date. How much of the sun goes, and how high it sits"
                + " when it does, depends on where you stand — add a location to see the figures.";
    }

    private static String detail(EclipseCircumstances circumstances, String locationName) {
        return "The moon covers " + circumstances.obscurationPct() + "% of the sun at maximum, with"
                + " the sun only " + circumstances.sunAltitudeRounded() + "° above the horizon — low"
                + " enough to frame against a foreground rather than shooting straight up. Figures"
                + " are for " + locationName + ", the deepest view on your list. A certified solar"
                + " filter belongs on the lens, not only over your eye.";
    }

    /**
     * The recurrence sentence, or null when this eclipse returns often enough not to be worth one.
     *
     * <p>Null rather than blank: {@code metaOf} drops both, so an unresearched return period is an
     * absent key and the row simply says less, which is the feed's stated degrade rule.
     */
    private static String rarity(EclipseCatalog.Eclipse eclipse) {
        return eclipse.isRare() ? "nothing comparable from the UK until " + eclipse.nextComparable() : null;
    }

    private static String londonTime(EclipseCircumstances circumstances) {
        return circumstances.maximumUtc().atZone(ZoneOffset.UTC)
                .withZoneSameInstant(LONDON).toLocalTime().format(HH_MM);
    }
}
