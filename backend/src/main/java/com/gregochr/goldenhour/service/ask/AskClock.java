package com.gregochr.goldenhour.service.ask;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * The one clock face Ask shows a reader: {@code HH:mm} in {@code Europe/London}. The briefing's build
 * time and a window's event time are both UTC {@link LocalDateTime}s on the wire; the run label, the
 * tool results ({@link AskTools}) and a Ready answer's own label all print them through here.
 */
final class AskClock {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private AskClock() {
    }

    /**
     * The {@code HH:mm} Europe/London label of a UTC time (BST in summer, so 05:02 UTC reads 06:02).
     *
     * @param utc the time, read as UTC, or null
     * @return the label, or null for a null time
     */
    static String londonHHmm(LocalDateTime utc) {
        if (utc == null) {
            return null;
        }
        return utc.atZone(ZoneOffset.UTC).withZoneSameInstant(LONDON).format(HH_MM);
    }
}
