package com.gregochr.goldenhour.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the briefing records' {@code with*} copy methods against silently dropping a component.
 *
 * <p>These records grew new components one at a time ({@code renderedEvents},
 * {@code previousGeneratedAt}, {@code bestBetsWithdrawn}, {@code solarEventTime}...), and every
 * positional rebuild that predated a component dropped it without a compile error. This test
 * fills <em>every</em> component of a record with a distinct sentinel, calls each wither by
 * reflection, and asserts that every component the wither does not name survives and every one it
 * does name takes the new value.
 *
 * <p><b>The wither table is explicit and checked in both directions.</b> Which components a wither
 * names is declared in {@link #WITHERS} rather than inferred from parameter names (which would need
 * {@code -parameters} and would still be ambiguous for {@code withLightlyEvaluated()} or
 * {@code withKind(awarded)}). A wither found by reflection but missing from the table fails the
 * test, so a new wither cannot be added without declaring what it touches; a table row with no
 * matching method fails too, so the table cannot rot.
 *
 * <p>Sentinels are built by {@link Sentinels}, a per-type factory map; the records' canonical
 * constructors normalise some components (for example {@code List.copyOf}), which is why the
 * expectation is read back from the constructed instance rather than assumed.
 */
class RecordWitherPreservationTest {

    /** One wither: the components it replaces, in parameter order, or a fixed-value setter. */
    private record Wither(Class<?> owner, String method, List<String> components,
            Object fixedValue, boolean hasFixedValue) {

        static Wither of(Class<?> owner, String method, String... components) {
            return new Wither(owner, method, List.of(components), null, false);
        }

        /** A no-argument wither that sets {@code component} to {@code value}. */
        static Wither sets(Class<?> owner, String method, String component, Object value) {
            return new Wither(owner, method, List.of(component), value, true);
        }

        String key() {
            return owner.getSimpleName() + "." + method;
        }
    }

    /** Every wither on the four guarded records (and {@code BriefingWindow.Pick}), by declaration. */
    private static final List<Wither> WITHERS = List.of(
            Wither.of(DailyBriefingResponse.class, "withDays", "days"),
            Wither.of(DailyBriefingResponse.class, "withMovement", "days", "previousGeneratedAt"),
            Wither.of(DailyBriefingResponse.class, "withPlan", "days", "renderedEvents"),
            Wither.of(DailyBriefingResponse.class, "withBestBets", "bestBets", "bestBetsWithdrawn"),
            Wither.of(DailyBriefingResponse.class, "withLiveOverlays",
                    "auroraTonight", "auroraTomorrow", "hotTopics"),
            Wither.of(BriefingRegion.class, "withMeanRating", "meanRating"),
            Wither.of(BriefingRegion.class, "withBestRating", "bestRating"),
            Wither.of(BriefingRegion.class, "withMeanRatingDelta", "meanRatingDelta"),
            Wither.of(BriefingRegion.class, "withSampleSufficient", "sampleSufficient"),
            Wither.of(BriefingRegion.class, "withForcedSample", "forcedSample"),
            Wither.sets(BriefingRegion.class, "withLightlyEvaluated", "lightlyEvaluated", true),
            Wither.of(BriefingRegion.class, "withConfidence", "confidence"),
            Wither.of(BriefingRegion.class, "withGloss", "glossHeadline", "glossDetail"),
            Wither.of(BriefingRegion.class, "withSlots", "slots"),
            Wither.of(BriefingDay.class, "withEventSummaries", "eventSummaries"),
            Wither.of(BriefingDay.class, "withPeak", "peak"),
            Wither.of(BriefingEventSummary.class, "withRegions", "regions"),
            Wither.of(BriefingEventSummary.class, "withUnregioned", "unregioned"),
            Wither.of(BriefingEventSummary.class, "withWindow", "window"),
            Wither.of(BriefingWindow.Pick.class, "withKind", "kind"),
            Wither.of(BriefingSlot.class, "withTide", "tide"),
            Wither.of(BriefingSlot.class, "withEvaluationGate", "evaluationGate"),
            Wither.of(BriefingSlot.class, "withEclipse", "eclipse"));

    /**
     * Withers deliberately outside the table: {@code BriefingSlot.withClaudeScores} is overloaded
     * (three arities share a name, so reflection by name cannot pick one) and recomputes
     * {@code displayVerdict} from the new rating, so "replaces only what it names" is false by
     * design. It is guarded by its own test below instead.
     */
    private static final Set<String> EXEMPT = Set.of("BriefingSlot.withClaudeScores");

    private static final List<Class<?>> GUARDED = List.of(
            DailyBriefingResponse.class, BriefingDay.class, BriefingRegion.class,
            BriefingEventSummary.class,
            BriefingWindow.Pick.class, BriefingSlot.class);

    @Test
    @DisplayName("every with* method on a guarded record is declared in the table, and vice versa")
    void witherTable_matchesTheWithersFoundByReflection() {
        Set<String> found = new TreeSet<>();
        for (Class<?> type : GUARDED) {
            for (Method m : type.getDeclaredMethods()) {
                if (isWither(type, m) && !EXEMPT.contains(type.getSimpleName() + "." + m.getName())) {
                    found.add(type.getSimpleName() + "." + m.getName());
                }
            }
        }
        Set<String> declared = new TreeSet<>();
        WITHERS.forEach(w -> declared.add(w.key()));

        assertThat(found).as("withers found by reflection vs the table").isEqualTo(declared);
    }

    @Test
    @DisplayName("every table row names real components, one per wither parameter")
    void witherTable_namesRealComponentsAndMatchesParameterCounts() {
        for (Wither w : WITHERS) {
            Set<String> names = new HashSet<>();
            for (RecordComponent c : w.owner().getRecordComponents()) {
                names.add(c.getName());
            }
            assertThat(names).as(w.key()).containsAll(w.components());
            Method m = witherMethod(w);
            assertThat(m.getParameterCount()).as(w.key() + " parameter count")
                    .isEqualTo(w.hasFixedValue() ? 0 : w.components().size());
        }
    }

    @Test
    @DisplayName("a wither replaces exactly the components it names and preserves all the others")
    void eachWither_replacesOnlyWhatItNamesAndPreservesTheRest() throws Exception {
        for (Wither w : WITHERS) {
            Class<?> type = w.owner();
            Map<String, Object> overrides = new HashMap<>();
            if (w.hasFixedValue()) {
                // The original must hold the OPPOSITE of what the setter writes, or "changed" is vacuous.
                overrides.put(w.components().get(0), Sentinels.opposite(w.fixedValue()));
            }
            Object original = Sentinels.record(type, 1, overrides);

            Object[] args = new Object[w.hasFixedValue() ? 0 : w.components().size()];
            Map<String, Object> expectedNamed = new HashMap<>();
            for (int i = 0; i < args.length; i++) {
                RecordComponent c = component(type, w.components().get(i));
                Object fresh = Sentinels.freshDifferentFrom(c, accessor(original, c));
                args[i] = fresh;
                expectedNamed.put(c.getName(), fresh);
            }
            if (w.hasFixedValue()) {
                expectedNamed.put(w.components().get(0), w.fixedValue());
            }

            Object copy;
            try {
                copy = witherMethod(w).invoke(original, args);
            } catch (InvocationTargetException e) {
                throw new AssertionError(w.key() + " threw", e.getCause());
            }

            assertThat(copy).as(w.key() + " returns the same type").isInstanceOf(type);
            for (RecordComponent c : type.getRecordComponents()) {
                Object actual = accessor(copy, c);
                if (expectedNamed.containsKey(c.getName())) {
                    assertThat(actual).as(w.key() + " must set " + c.getName())
                            .isEqualTo(expectedNamed.get(c.getName()));
                    assertThat(actual).as(w.key() + " must change " + c.getName())
                            .isNotEqualTo(accessor(original, c));
                } else {
                    assertThat(actual).as(w.key() + " must preserve " + c.getName())
                            .isEqualTo(accessor(original, c));
                }
            }
        }
    }

    @Test
    @DisplayName("BriefingSlot.withClaudeScores (exempt from the table) preserves every non-score component")
    void briefingSlot_withClaudeScores_preservesEveryNonScoreComponent() {
        Set<String> scoreComponents = Set.of("claudeRating", "skyRating", "fierySkyPotential",
                "goldenHourPotential", "claudeSummary", "displayVerdict", "claudeHeadline");
        BriefingSlot original = (BriefingSlot) Sentinels.record(BriefingSlot.class, 1, Map.of());

        BriefingSlot copy = original.withClaudeScores(4, 3, 55, 66, "new summary", "new headline");

        for (RecordComponent c : BriefingSlot.class.getRecordComponents()) {
            if (!scoreComponents.contains(c.getName())) {
                assertThat(accessor(copy, c)).as("withClaudeScores must preserve " + c.getName())
                        .isEqualTo(accessor(original, c));
            }
        }
        assertThat(copy.claudeRating()).isEqualTo(4);
        assertThat(copy.skyRating()).isEqualTo(3);
    }

    /**
     * Tripwire, not a preservation check: {@code BriefingWindow} has no withers, so there is
     * nothing to preserve through. It fails if a wither appears without joining the table, and
     * round-trips the canonical constructor so every component, {@code tideFacts} included (added in P1),
     * is built and compared here.
     */
    @Test
    @DisplayName("tripwire: BriefingWindow has no withers; the canonical constructor carries every component")
    void briefingWindow_canonicalConstructorCarriesEveryComponent() throws Exception {
        assertThat(Arrays.stream(BriefingWindow.class.getDeclaredMethods())
                .filter(m -> isWither(BriefingWindow.class, m)))
                .as("BriefingWindow gained a wither: add it to WITHERS and GUARDED")
                .isEmpty();

        Object built = Sentinels.record(BriefingWindow.class, 1, Map.of());
        Object[] values = new Object[BriefingWindow.class.getRecordComponents().length];
        for (int i = 0; i < values.length; i++) {
            values[i] = accessor(built, BriefingWindow.class.getRecordComponents()[i]);
            assertThat(values[i]).as("sentinel for component " + i + " is non-default")
                    .isNotNull();
        }
        Object rebuilt = canonical(BriefingWindow.class).newInstance(values);
        assertThat(rebuilt).isEqualTo(built);
    }

    /**
     * Tripwire, not a preservation check: a convenience constructor of {@code BriefingWindow}
     * (P1 adds one) fails here until the components it defaults are registered in
     * {@code documentedDefaults}, and then asserts it defaults nothing else.
     */
    @Test
    @DisplayName("tripwire: a BriefingWindow convenience constructor must register what it defaults")
    void briefingWindow_convenienceConstructorsDefaultOnlyRegisteredComponents() throws Exception {
        // Component names a convenience constructor of the given arity is documented to default.
        // The 8-arg form (every component but tideFacts) is the one P1 added so the existing
        // `new BriefingWindow(` sites compile unchanged; it defaults tideFacts to null. A
        // constructor added later must be registered, which is the moment to state what it defaults.
        Map<Integer, Set<String>> documentedDefaults = Map.of(8, Set.of("tideFacts"));

        RecordComponent[] components = BriefingWindow.class.getRecordComponents();
        Object full = Sentinels.record(BriefingWindow.class, 1, Map.of());
        Constructor<?> canonical = canonical(BriefingWindow.class);
        for (Constructor<?> ctor : BriefingWindow.class.getConstructors()) {
            if (ctor.equals(canonical)) {
                continue;
            }
            int arity = ctor.getParameterCount();
            Set<String> allowed = documentedDefaults.get(arity);
            assertThat(allowed).as("unregistered " + arity + "-arg BriefingWindow constructor")
                    .isNotNull();
            Object[] prefix = new Object[arity];
            for (int i = 0; i < arity; i++) {
                prefix[i] = accessor(full, components[i]);
            }
            Object built = ctor.newInstance(prefix);
            for (int i = 0; i < components.length; i++) {
                Object actual = accessor(built, components[i]);
                if (i < arity) {
                    assertThat(actual).as("carried " + components[i].getName())
                            .isEqualTo(accessor(full, components[i]));
                } else {
                    assertThat(allowed).as("defaulted " + components[i].getName()).contains(
                            components[i].getName());
                }
            }
        }
    }

    // ── reflection helpers ─────────────────────────────────────────────────────────

    private static boolean isWither(Class<?> type, Method m) {
        return m.getName().startsWith("with")
                && Modifier.isPublic(m.getModifiers())
                && !Modifier.isStatic(m.getModifiers())
                && m.getReturnType().equals(type);
    }

    private static Method witherMethod(Wither w) {
        for (Method m : w.owner().getDeclaredMethods()) {
            if (m.getName().equals(w.method()) && isWither(w.owner(), m)) {
                return m;
            }
        }
        throw new AssertionError("Table row without a method: " + w.key());
    }

    private static RecordComponent component(Class<?> type, String name) {
        for (RecordComponent c : type.getRecordComponents()) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        throw new AssertionError(type.getSimpleName() + " has no component " + name);
    }

    private static Object accessor(Object record, RecordComponent c) {
        try {
            Method m = c.getAccessor();
            m.setAccessible(true);
            return m.invoke(record);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Cannot read " + c.getName(), e);
        }
    }

    private static Constructor<?> canonical(Class<?> type) throws NoSuchMethodException {
        Class<?>[] types = Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getType).toArray(Class<?>[]::new);
        return type.getDeclaredConstructor(types);
    }

    /**
     * Per-type sentinel factory. Every value is non-null, non-empty and non-zero, and varies with
     * both the seed and the component name so two same-typed components never share a value.
     */
    private static final class Sentinels {

        private static final int MAX_DEPTH = 2;

        private Sentinels() {
        }

        static Object record(Class<?> type, int seed, Map<String, Object> overrides) {
            return record(type, seed, overrides, 0);
        }

        private static Object record(Class<?> type, int seed, Map<String, Object> overrides,
                int depth) {
            RecordComponent[] components = type.getRecordComponents();
            Object[] values = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                RecordComponent c = components[i];
                values[i] = overrides.containsKey(c.getName())
                        ? overrides.get(c.getName())
                        : make(c.getGenericType(), seed, c.getName(), depth);
            }
            try {
                return canonical(type).newInstance(values);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("Cannot build a sentinel " + type.getSimpleName(), e);
            }
        }

        /** A value for {@code c} that differs from {@code current}, built with a later seed. */
        static Object freshDifferentFrom(RecordComponent c, Object current) {
            for (int seed = 2; seed < 40; seed++) {
                Object candidate = make(c.getGenericType(), seed, c.getName(), 0);
                if (!candidate.equals(current)) {
                    return candidate;
                }
            }
            throw new AssertionError("No distinct sentinel for " + c.getName());
        }

        static Object opposite(Object fixed) {
            if (fixed instanceof Boolean b) {
                return !b;
            }
            throw new AssertionError("No opposite for " + fixed);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static Object make(Type type, int seed, String name, int depth) {
            int salt = seed * 31 + Math.floorMod(name.hashCode(), 997);
            if (type instanceof ParameterizedType p) {
                Class<?> raw = (Class<?>) p.getRawType();
                if (List.class.isAssignableFrom(raw)) {
                    // Nested lists stay empty past the depth cap, to keep the object graph small.
                    if (depth >= MAX_DEPTH) {
                        return new ArrayList<>();
                    }
                    List<Object> list = new ArrayList<>();
                    list.add(make(p.getActualTypeArguments()[0], seed, name, depth + 1));
                    return list;
                }
                if (Map.class.isAssignableFrom(raw)) {
                    return new HashMap<>();
                }
                throw new AssertionError("No sentinel factory for " + type);
            }
            Class<?> c = (Class<?>) type;
            if (c == String.class) {
                return name + "-" + seed;
            } else if (c == int.class || c == Integer.class) {
                return salt + 1;
            } else if (c == long.class || c == Long.class) {
                return (long) salt + 1;
            } else if (c == double.class || c == Double.class) {
                return salt + 0.5;
            } else if (c == boolean.class || c == Boolean.class) {
                return seed % 2 == 1;
            } else if (c == BigDecimal.class) {
                return BigDecimal.valueOf(salt + 1);
            } else if (c == LocalDateTime.class) {
                return LocalDateTime.of(2026, 1, 1, 0, 0).plusMinutes(salt);
            } else if (c == LocalDate.class) {
                return LocalDate.of(2026, 1, 1).plusDays(salt);
            } else if (c == LocalTime.class) {
                return LocalTime.of(0, 0).plusMinutes(Math.floorMod(salt, 1440));
            } else if (c == Instant.class) {
                return Instant.EPOCH.plusSeconds(salt);
            } else if (c.isEnum()) {
                Object[] constants = c.getEnumConstants();
                return constants[Math.floorMod(seed + name.length(), constants.length)];
            } else if (c.isRecord()) {
                return record(c, seed, Map.of(), depth + 1);
            }
            throw new AssertionError("No sentinel factory for " + c.getName()
                    + " (component " + name + "): add it to Sentinels.make");
        }
    }
}
