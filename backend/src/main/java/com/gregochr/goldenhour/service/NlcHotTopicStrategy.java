package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.SeasonalWindow;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.List;
import java.util.Objects;

/**
 * Detects noctilucent cloud (NLC) season hot topics from a fixed seasonal window.
 *
 * <p>Noctilucent clouds are extremely high ice clouds that catch sunlight after sunset,
 * glowing electric blue on the northern horizon. They are visible from northern England
 * only from late May to early August. This is a pure calendar heads-up — it fires for the
 * earliest in-season day in the window and points photographers at dark-sky locations with
 * a clear northern horizon. Makes no external API calls.
 *
 * <p>The season is held as a private {@link SeasonalWindow} constant rather than a Spring
 * bean: the bluebell window is already the sole {@code SeasonalWindow} bean and is injected
 * by type at several sites, so registering a second bean would make those injections
 * ambiguous. A private constant keeps the same calendar-gate behaviour without that risk.
 */
@Component
public class NlcHotTopicStrategy implements HotTopicStrategy {

    private static final String NLC_DESCRIPTION =
            "Noctilucent clouds are extremely high ice clouds that catch sunlight after"
                    + " sunset, glowing electric blue on the northern horizon."
                    + " Visible late May to early August.";

    /** Topic priority — calendar heads-up, sorts below the act-on-it topics. */
    private static final int PRIORITY = 8;

    /** NLC visibility season for northern England: late May to early August. */
    private static final SeasonalWindow NLC_SEASON =
            new SeasonalWindow(MonthDay.of(5, 25), MonthDay.of(8, 10), "NLC");

    private final LocationRepository locationRepository;

    /**
     * Constructs an {@code NlcHotTopicStrategy}.
     *
     * @param locationRepository repository for dark-sky region lookups
     */
    public NlcHotTopicStrategy(LocationRepository locationRepository) {
        this.locationRepository = locationRepository;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Emits a single topic dated to the earliest in-season day within the window,
     * or empty when no day in the window falls in NLC season.
     */
    @Override
    public List<HotTopic> detect(LocalDate fromDate, LocalDate toDate) {
        for (LocalDate date = fromDate; !date.isAfter(toDate); date = date.plusDays(1)) {
            if (NLC_SEASON.isActive(date)) {
                return List.of(buildTopic(date));
            }
        }
        return List.of();
    }

    private HotTopic buildTopic(LocalDate date) {
        List<String> darkSkyRegions =
                locationRepository.findByBortleClassIsNotNullAndEnabledTrue().stream()
                        .map(LocationEntity::getRegion)
                        .filter(Objects::nonNull)
                        .map(RegionEntity::getName)
                        .distinct()
                        .toList();

        return new HotTopic(
                "NLC",
                "Noctilucent cloud season",
                "NLC season — check the northern horizon after dusk",
                date,
                PRIORITY,
                null,
                darkSkyRegions,
                NLC_DESCRIPTION,
                null);
    }
}
