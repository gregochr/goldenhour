package com.gregochr.goldenhour.service;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.time.MonthDay;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the peak night, time of maximum and rate of each known shower out of the text of an IMO Meteor Shower
 * Calendar PDF (meteor-showers-plan Phase 4). Pure: text and calendar year in, a map out; no Spring, no clock.
 *
 * <p>The date and ZHR come from <b>Table 5, "Working List of Visual Meteor Showers"</b>: the first line naming
 * {@code (NNN CODE)} followed by an activity range ({@code Nov 06–Nov 30}) and a {@code Mon dd} maximum, the ZHR
 * being the last token ({@code 80+} becomes 80, a trailing {@code (?)} is dropped, {@code Var} keeps the
 * catalogue rate). The time of maximum comes from, in order: the shower's own section ({@code Maximum: November 17,
 * 23h 45m UT}), then the one sentence of prose that names the shower. A time whose month and day are more than a
 * day from the Table 5 date is ignored, so one shower's time can never be taken from another's sentence. With no
 * time the night is {@link MeteorPeakNight#ofDateOnly} from the shower's best hours.
 *
 * <p>Showers the code does not know are ignored: which showers PhotoCast shows stays a reviewed code decision.
 * {@link #problems} says whether a parse is fit to store.
 */
final class ImoCalendarParser {

    /** One shower's parsed peak. */
    record ParsedPeak(LocalDate imoDate, Instant maxUtc, LocalDate night, int zhr) { }

    /** A parsed night must lie within this many days of the code's fallback {@code MonthDay}. */
    static final int MAX_DAYS_FROM_FALLBACK = 3;

    /** The highest ZHR accepted. */
    static final int MAX_ZHR = 500;

    private static final String TIME = "(\\d{1,2})h\\s*(?:(\\d{2})m)?\\s*(?:to\\s+(\\d{1,2})h\\s*)?UT";

    private static final Pattern SECTION_MAX = Pattern.compile("Maximum:\\s*([A-Z][a-z]+)\\s+(\\d{1,2}),\\s*" + TIME);

    private static final Pattern PROSE_TIME = Pattern.compile(
            "([A-Z][a-z]+)\\s+(\\d{1,2}),?\\s*\\(?(?:(?:close to|shortly after|centred around|around|expected on"
                    + "|near|at)\\s+)?" + TIME);

    private static final Pattern SENTENCE_END = Pattern.compile("\\.\\s+[A-Z]");

    private static final Pattern TRAILING_UNCERTAIN = Pattern.compile("\\s*\\(\\?\\)\\s*$");

    private static final int SECTION_LOOKAHEAD_LINES = 3;

    private static final int PROSE_WINDOW_LINES = 3;

    private static final int MINUTES_PER_HOUR = 60;

    private static final int MINUTES_PER_DAY = 1440;

    private static final int MAX_HOUR = 23;

    private static final int MAX_MINUTE = 59;

    private static final Map<String, Month> MONTHS = new LinkedHashMap<>();

    static {
        for (Month m : Month.values()) {
            String name = m.name().charAt(0) + m.name().substring(1).toLowerCase(Locale.ROOT);
            MONTHS.put(name, m);
            MONTHS.put(name.substring(0, 3), m);
        }
    }

    private ImoCalendarParser() {
    }

    /**
     * Parses every known shower found in the calendar text.
     *
     * @param text         the extracted PDF text
     * @param calendarYear the calendar's year
     * @return the parsed peaks by shower code, in catalogue order; a shower with no readable Table 5 row is absent
     */
    static Map<String, ParsedPeak> parse(String text, int calendarYear) {
        List<String> lines = List.of(text.split("\n", -1));
        Map<String, ParsedPeak> out = new LinkedHashMap<>();
        for (MeteorHotTopicStrategy.Shower shower : MeteorHotTopicStrategy.SHOWERS) {
            ParsedPeak peak = parseShower(lines, shower, calendarYear);
            if (peak != null) {
                out.put(shower.code(), peak);
            }
        }
        return out;
    }

    private static ParsedPeak parseShower(List<String> lines, MeteorHotTopicStrategy.Shower shower, int year) {
        String code = shower.code();
        Pattern row = Pattern.compile("\\(\\d{3} " + code + "\\)\\s*([A-Z][a-z]{2}) (\\d{2})\\s*[–-]\\s*"
                + "([A-Z][a-z]{2}) (\\d{2})\\s+([A-Z][a-z]{2}) (\\d{2})\\b(.*)$");
        int rowLine = -1;
        Matcher rowMatch = null;
        for (int i = 0; i < lines.size() && rowMatch == null; i++) {
            Matcher m = row.matcher(lines.get(i));
            if (m.find()) {
                rowLine = i;
                rowMatch = m;
            }
        }
        if (rowMatch == null) {
            return null;
        }
        LocalDate imoDate = date(year, rowMatch.group(5), rowMatch.group(6));
        Integer zhr = zhr(rowMatch.group(7), shower);
        if (imoDate == null || zhr == null) {
            return null;
        }
        Instant maxUtc = sectionTime(lines, code, year, imoDate);
        if (maxUtc == null) {
            maxUtc = proseTime(lines, code, year, imoDate, rowLine);
        }
        LocalDate night = maxUtc != null
                ? MeteorPeakNight.of(maxUtc)
                : MeteorPeakNight.ofDateOnly(imoDate, shower.bestHours());
        return new ParsedPeak(imoDate, maxUtc, night, zhr);
    }

    private static Integer zhr(String tail, MeteorHotTopicStrategy.Shower shower) {
        String cleaned = TRAILING_UNCERTAIN.matcher(tail.trim()).replaceFirst("").trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        String[] tokens = cleaned.split("\\s+");
        String last = tokens[tokens.length - 1];
        if ("Var".equalsIgnoreCase(last)) {
            return shower.zhrPeak();
        }
        last = last.replaceFirst("^[+≈]+", "").replaceFirst("\\+$", "");
        if (!last.matches("\\d{1,4}")) {
            return null;
        }
        return Integer.parseInt(last);
    }

    private static Instant sectionTime(List<String> lines, String code, int year, LocalDate imoDate) {
        Pattern head = Pattern.compile("\\(\\d{3} " + code + "\\)\\s*$");
        for (int i = 0; i < lines.size(); i++) {
            if (head.matcher(lines.get(i)).find()) {
                int end = Math.min(lines.size(), i + 1 + SECTION_LOOKAHEAD_LINES);
                String window = String.join(" ", lines.subList(i + 1, end));
                Instant t = firstTime(SECTION_MAX.matcher(window), year, imoDate);
                if (t != null) {
                    return t;
                }
            }
        }
        return null;
    }

    private static Instant proseTime(List<String> lines, String code, int year, LocalDate imoDate, int rowLine) {
        Pattern named = Pattern.compile("\\(\\d{3} " + code + "\\)");
        for (int i = 0; i < lines.size(); i++) {
            if (i == rowLine || !named.matcher(lines.get(i)).find()) {
                continue;
            }
            int end = Math.min(lines.size(), i + PROSE_WINDOW_LINES);
            String window = String.join(" ", lines.subList(i, end));
            Matcher at = named.matcher(window);
            String forward = at.find() ? window.substring(at.start()) : window;
            Matcher stop = SENTENCE_END.matcher(forward);
            if (stop.find()) {
                forward = forward.substring(0, stop.start() + 1);
            }
            Instant t = firstTime(PROSE_TIME.matcher(forward), year, imoDate);
            if (t != null) {
                return t;
            }
        }
        return null;
    }

    /** The first match whose date is within a day of {@code imoDate} and whose time is a real clock time. */
    private static Instant firstTime(Matcher m, int year, LocalDate imoDate) {
        while (m.find()) {
            LocalDate day = date(year, m.group(1), m.group(2));
            if (day == null || Math.abs(ChronoUnit.DAYS.between(imoDate, day)) > 1) {
                continue;
            }
            int hour = Integer.parseInt(m.group(3));
            int minute = m.group(4) == null ? 0 : Integer.parseInt(m.group(4));
            if (hour > MAX_HOUR || minute > MAX_MINUTE) {
                continue;
            }
            int start = hour * MINUTES_PER_HOUR + minute;
            int minutes = start;
            if (m.group(5) != null) {
                int endHour = Integer.parseInt(m.group(5));
                if (endHour > MAX_HOUR) {
                    continue;
                }
                int end = endHour * MINUTES_PER_HOUR;
                if (end < start) {
                    end += MINUTES_PER_DAY;
                }
                minutes = (start + end) / 2;
            }
            return day.atStartOfDay(ZoneOffset.UTC).plusMinutes(minutes).toInstant();
        }
        return null;
    }

    private static LocalDate date(int year, String monthName, String day) {
        Month month = MONTHS.get(monthName);
        if (month == null) {
            return null;
        }
        try {
            return LocalDate.of(year, month, Integer.parseInt(day));
        } catch (DateTimeException e) {
            return null;
        }
    }

    /**
     * Why a parse is not fit to store, or an empty list when it is: every major shower must be present, every
     * night within {@value #MAX_DAYS_FROM_FALLBACK} days of the code's fallback date (the Quadrantids across the
     * year boundary), every ZHR 1 to {@value #MAX_ZHR}, and the nights distinct.
     *
     * @param parsed the parse
     * @param year   the calendar year
     * @return one sentence per problem; empty when the parse is valid
     */
    static List<String> problems(Map<String, ParsedPeak> parsed, int year) {
        List<String> problems = new ArrayList<>();
        Set<LocalDate> nights = new HashSet<>();
        for (MeteorHotTopicStrategy.Shower shower : MeteorHotTopicStrategy.SHOWERS) {
            ParsedPeak peak = parsed.get(shower.code());
            if (peak == null) {
                if (shower.major()) {
                    problems.add("no Table 5 row for " + shower.code());
                }
                continue;
            }
            if (daysFromFallback(shower.peak(), peak.night(), year) > MAX_DAYS_FROM_FALLBACK) {
                problems.add(shower.code() + " night " + peak.night() + " is more than " + MAX_DAYS_FROM_FALLBACK
                        + " days from " + shower.peak());
            }
            if (peak.zhr() < 1 || peak.zhr() > MAX_ZHR) {
                problems.add(shower.code() + " ZHR " + peak.zhr() + " is outside 1-" + MAX_ZHR);
            }
            if (!nights.add(peak.night())) {
                problems.add(shower.code() + " shares its night " + peak.night() + " with another shower");
            }
        }
        return problems;
    }

    private static long daysFromFallback(MonthDay fallback, LocalDate night, int year) {
        long best = Long.MAX_VALUE;
        for (int y = year - 1; y <= year + 1; y++) {
            best = Math.min(best, Math.abs(ChronoUnit.DAYS.between(fallback.atYear(y), night)));
        }
        return best;
    }
}
