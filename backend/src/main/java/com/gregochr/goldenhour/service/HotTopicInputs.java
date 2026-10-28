package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.SlotSignals;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The shared inputs of one hot-topic pass: the slot signals for the range and the location rosters
 * that the strategies, the event enricher and the travel-day filter would otherwise each load for
 * themselves.
 *
 * <p>Every accessor is lazy and memoised: the first call runs the supplier, later calls return the
 * same unmodifiable list. A strategy that needs nothing therefore costs nothing, and a pass that
 * asks for the same roster twelve times issues one query.
 *
 * <p>⚠️ Single-thread, one instance per {@code HotTopicAggregator.getHotTopics} call. It holds
 * plain unsynchronised fields and is never shared across requests, so a roster read at one serve
 * can never be served at the next.
 */
public final class HotTopicInputs {

    private static final String NOT_PROVIDED = "This HotTopicInputs was not built with a source for ";

    private final Supplier<List<SlotSignals>> slotSignalSource;
    private final Supplier<List<LocationEntity>> coastalSource;
    private final Supplier<List<LocationEntity>> enabledSource;
    private final Supplier<List<LocationEntity>> darkSkySource;
    private final Supplier<List<LocationEntity>> bluebellSource;
    private final Predicate<LocalDate> travelDaySource;

    private List<SlotSignals> slotSignals;
    private List<LocationEntity> coastal;
    private List<LocationEntity> enabled;
    private List<LocationEntity> darkSky;
    private List<LocationEntity> bluebell;
    private final Map<LocalDate, Boolean> travelDays = new HashMap<>();

    /**
     * Builds inputs from the sources a pass can draw on. A source the caller will never use may be
     * {@code null}; asking for it then fails loudly rather than returning an empty roster.
     *
     * @param slotSignalSource the slot signals for the pass's date range
     * @param coastalSource    enabled coastal locations
     * @param enabledSource    enabled locations, ordered by name
     * @param darkSkySource    enabled locations with a Bortle class
     * @param bluebellSource   enabled bluebell locations
     * @param travelDaySource  whether a date is a travel day
     */
    public HotTopicInputs(Supplier<List<SlotSignals>> slotSignalSource,
            Supplier<List<LocationEntity>> coastalSource,
            Supplier<List<LocationEntity>> enabledSource,
            Supplier<List<LocationEntity>> darkSkySource,
            Supplier<List<LocationEntity>> bluebellSource,
            Predicate<LocalDate> travelDaySource) {
        this.slotSignalSource = slotSignalSource;
        this.coastalSource = coastalSource;
        this.enabledSource = enabledSource;
        this.darkSkySource = darkSkySource;
        this.bluebellSource = bluebellSource;
        this.travelDaySource = travelDaySource;
    }

    /**
     * Inputs carrying only the slot signals, for a strategy's own two-argument {@code detect}.
     *
     * @param source the slot signals for the range
     * @return inputs whose other sources are absent
     */
    public static HotTopicInputs ofSlotSignals(Supplier<List<SlotSignals>> source) {
        return new HotTopicInputs(source, null, null, null, null, null);
    }

    /**
     * Inputs carrying only the coastal roster.
     *
     * @param source the coastal locations
     * @return inputs whose other sources are absent
     */
    public static HotTopicInputs ofCoastal(Supplier<List<LocationEntity>> source) {
        return new HotTopicInputs(null, source, null, null, null, null);
    }

    /**
     * Inputs carrying only the enabled roster.
     *
     * @param source the enabled locations, ordered by name
     * @return inputs whose other sources are absent
     */
    public static HotTopicInputs ofEnabled(Supplier<List<LocationEntity>> source) {
        return new HotTopicInputs(null, null, source, null, null, null);
    }

    /**
     * Inputs carrying only the dark-sky roster.
     *
     * @param source the dark-sky locations
     * @return inputs whose other sources are absent
     */
    public static HotTopicInputs ofDarkSky(Supplier<List<LocationEntity>> source) {
        return new HotTopicInputs(null, null, null, source, null, null);
    }

    /**
     * Inputs carrying the four rosters, for the enricher's own one-argument {@code enrich}.
     *
     * @param coastalSource  the coastal locations
     * @param enabledSource  the enabled locations
     * @param darkSkySource  the dark-sky locations
     * @param bluebellSource the bluebell locations
     * @return inputs with no slot-signal or travel-day source
     */
    public static HotTopicInputs ofRosters(Supplier<List<LocationEntity>> coastalSource,
            Supplier<List<LocationEntity>> enabledSource,
            Supplier<List<LocationEntity>> darkSkySource,
            Supplier<List<LocationEntity>> bluebellSource) {
        return new HotTopicInputs(null, coastalSource, enabledSource, darkSkySource, bluebellSource, null);
    }

    /**
     * The unified slot read model for the pass's range, read once.
     *
     * @return unmodifiable slot signals
     */
    public List<SlotSignals> slotSignals() {
        if (slotSignals == null) {
            slotSignals = List.copyOf(require(slotSignalSource, "slot signals").get());
        }
        return slotSignals;
    }

    /**
     * Enabled coastal locations, read once.
     *
     * @return unmodifiable list
     */
    public List<LocationEntity> coastalLocations() {
        if (coastal == null) {
            coastal = List.copyOf(require(coastalSource, "coastal locations").get());
        }
        return coastal;
    }

    /**
     * Enabled locations ordered by name, read once. Order matters to callers.
     *
     * @return unmodifiable list
     */
    public List<LocationEntity> enabledLocations() {
        if (enabled == null) {
            enabled = List.copyOf(require(enabledSource, "enabled locations").get());
        }
        return enabled;
    }

    /**
     * Enabled locations with a Bortle class, read once.
     *
     * @return unmodifiable list
     */
    public List<LocationEntity> darkSkyLocations() {
        if (darkSky == null) {
            darkSky = List.copyOf(require(darkSkySource, "dark-sky locations").get());
        }
        return darkSky;
    }

    /**
     * Enabled bluebell locations, read once.
     *
     * @return unmodifiable list
     */
    public List<LocationEntity> bluebellLocations() {
        if (bluebell == null) {
            bluebell = List.copyOf(require(bluebellSource, "bluebell locations").get());
        }
        return bluebell;
    }

    /**
     * Whether the date is a travel day, asked of the source once per date.
     *
     * @param date the date to test
     * @return true when the operator is away that day
     */
    public boolean isTravelDay(LocalDate date) {
        Boolean known = travelDays.get(date);
        if (known == null) {
            known = require(travelDaySource, "travel days").test(date);
            travelDays.put(date, known);
        }
        return known;
    }

    private static <T> T require(T source, String what) {
        if (source == null) {
            throw new IllegalStateException(NOT_PROVIDED + what);
        }
        return source;
    }
}
