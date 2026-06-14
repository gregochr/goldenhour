package com.gregochr.goldenhour.config;

import com.gregochr.goldenhour.model.SeasonalWindow;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.MonthDay;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Produces the configured {@link SeasonalWindow} beans from {@link SeasonProperties}.
 *
 * <p>Centralising the parse here means the five call sites that gate on bluebell season —
 * {@code ForecastDtoMapper}, {@code BluebellHotTopicStrategy}, {@code ForecastDataAugmentor}
 * and {@code BriefingService} — all inject the identical, single-source window rather than
 * each re-reading a hardcoded constant.
 */
@Configuration
public class SeasonConfig {

    /** Parses the {@code MM-dd} config strings (e.g. {@code "04-18"}). */
    private static final DateTimeFormatter MONTH_DAY_FORMAT = DateTimeFormatter.ofPattern("MM-dd");

    /**
     * Builds the bluebell {@link SeasonalWindow} from the configured start/end {@code MM-dd}
     * boundaries.
     *
     * @param props the bound season properties
     * @return the configured bluebell window
     * @throws IllegalStateException if either boundary is not a valid {@code MM-dd} value
     */
    @Bean
    public SeasonalWindow bluebellSeasonWindow(SeasonProperties props) {
        MonthDay start = parse(props.getBluebell().getStart(), "photocast.season.bluebell.start");
        MonthDay end = parse(props.getBluebell().getEnd(), "photocast.season.bluebell.end");
        return new SeasonalWindow(start, end, "BLUEBELL");
    }

    private static MonthDay parse(String value, String property) {
        try {
            return MonthDay.parse(value, MONTH_DAY_FORMAT);
        } catch (DateTimeParseException e) {
            throw new IllegalStateException(
                    "Invalid " + property + " — expected MM-dd (e.g. 04-18), got: " + value, e);
        }
    }
}
