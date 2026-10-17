package com.gregochr.goldenhour.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LocationTideFactTest {

    private static final Set<String> IDENTITY = Set.of("locationId", "locationName");

    @Test
    @DisplayName("drift guard: every component but the identity pair has a same-named TideInfo accessor")
    void everyTideComponentMapsToATideInfoAccessor() {
        for (RecordComponent c : LocationTideFact.class.getRecordComponents()) {
            if (IDENTITY.contains(c.getName())) {
                continue;
            }
            Method onTideInfo = null;
            for (RecordComponent t : BriefingSlot.TideInfo.class.getRecordComponents()) {
                if (t.getName().equals(c.getName())) {
                    onTideInfo = t.getAccessor();
                }
            }
            assertThat(onTideInfo).as("TideInfo accessor for " + c.getName()).isNotNull();
            assertThat(boxed(onTideInfo.getReturnType())).as("type of " + c.getName())
                    .isEqualTo(boxed(c.getType()));
        }
    }

    @Test
    @DisplayName("the identity pair mirrors the slot's own components")
    void identityMirrorsTheSlot() {
        for (String name : IDENTITY) {
            boolean found = false;
            for (RecordComponent s : BriefingSlot.class.getRecordComponents()) {
                found |= s.getName().equals(name);
            }
            assertThat(found).as("BriefingSlot component " + name).isTrue();
        }
    }

    @Test
    @DisplayName("from() returns null for null, a null tide, and a null tide state")
    void fromReturnsNullWithoutTideState() {
        assertThat(LocationTideFact.from(null)).isNull();
        assertThat(LocationTideFact.from(
                new BriefingSlot(1L, "Durham", null, Verdict.GO, null, null, java.util.List.of(),
                        null))).isNull();
        assertThat(LocationTideFact.from(
                new BriefingSlot(1L, "Durham", null, Verdict.GO, null, BriefingSlot.TideInfo.NONE,
                        java.util.List.of(), null))).isNull();
    }

    private static Class<?> boxed(Class<?> type) {
        if (type == boolean.class) {
            return Boolean.class;
        }
        return type;
    }
}
