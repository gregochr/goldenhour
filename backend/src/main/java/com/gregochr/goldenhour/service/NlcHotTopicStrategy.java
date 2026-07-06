package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.NlcNightClarity;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

/**
 * Detects noctilucent cloud (NLC) hot topics — but only for nights with a real viewing chance.
 *
 * <p>Noctilucent clouds are extremely high ice clouds that catch sunlight after sunset, glowing
 * electric blue on the northern horizon. They are visible from northern England only from late
 * May to early August, and only when the northern sky is actually clear. Rather than firing a
 * calendar reminder every night of the season (which quickly becomes ignored wallpaper), this
 * detector reads the {@link NlcClarityService} cache — populated during the briefing run from
 * real cloud cover at dark-sky locations — and fires only when a clear night exists in the
 * window, naming the earliest one. When no dark-sky location is forecast clear, the topic is
 * suppressed entirely. Makes no external API calls.
 */
@Component
public class NlcHotTopicStrategy implements HotTopicStrategy {

    private static final String NLC_DESCRIPTION =
            "Noctilucent clouds are extremely high ice clouds that catch sunlight after"
                    + " sunset, glowing electric blue on the northern horizon."
                    + " Visible late May to early August, and only under a clear northern sky."
                    + " No forecast reaches the mesosphere (~80km), so this shows only when the"
                    + " twilight geometry is right and skies are clear — never that NLC will appear.";

    /** Topic priority — calendar heads-up, sorts below the act-on-it topics. */
    private static final int PRIORITY = 8;

    private final NlcClarityService clarityService;

    /**
     * Constructs an {@code NlcHotTopicStrategy}.
     *
     * @param clarityService cache of which upcoming nights have a clear dark-sky NLC chance
     */
    public NlcHotTopicStrategy(NlcClarityService clarityService) {
        this.clarityService = clarityService;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Emits a single topic for the earliest clear night within the window, or empty when the
     * clarity scan found no viable night (out of season, cloudy everywhere, or not yet computed).
     */
    @Override
    public List<HotTopic> detect(LocalDate fromDate, LocalDate toDate) {
        NlcNightClarity clarity = clarityService.getCached();
        if (clarity == null || !clarity.hasClearNight()) {
            return List.of();
        }

        // Emit gate (change #3): the earliest in-range clear night that also has real twilight
        // geometry — at least one window where the sun reaches 6–16° below the horizon. In season
        // at these latitudes there is always at least a partial window, but it is computed, never
        // assumed, so a genuine white night (sun never 6° down) correctly suppresses the topic.
        NlcNightClarity.ClearNight night = clarity.clearNights().stream()
                .filter(n -> !n.date().isBefore(fromDate) && !n.date().isAfter(toDate))
                .filter(n -> n.eveningWindow() != null || n.morningWindow() != null)
                .findFirst()
                .orElse(null);
        if (night == null) {
            return List.of();
        }

        return List.of(new HotTopic(
                "NLC",
                "Noctilucent cloud season",
                buildDetail(night, fromDate),
                night.date(),
                PRIORITY,
                null,
                night.regions(),
                NLC_DESCRIPTION,
                null).withNlcWindows(night.eveningWindow(), night.morningWindow()));
    }

    private String buildDetail(NlcNightClarity.ClearNight night, LocalDate today) {
        String locations = night.clearLocationCount() == 1
                ? "1 dark-sky location"
                : night.clearLocationCount() + " dark-sky locations";
        return String.format("Clear northern horizon %s — %s",
                formatNightLabel(night.date(), today), locations);
    }

    private String formatNightLabel(LocalDate date, LocalDate today) {
        if (date.isEqual(today)) {
            return "tonight";
        }
        if (date.isEqual(today.plusDays(1))) {
            return "tomorrow night";
        }
        DayOfWeek dow = date.getDayOfWeek();
        return dow.getDisplayName(TextStyle.FULL, Locale.UK) + " night";
    }
}
