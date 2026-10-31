package com.gregochr.goldenhour.entity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for {@link TideType#coversEveryState}. */
class TideTypeTest {

    @Test
    @DisplayName("null and empty sets do not cover every state")
    void coversEveryState_nullOrEmpty_false() {
        assertFalse(TideType.coversEveryState(null));
        assertFalse(TideType.coversEveryState(Set.of()));
    }

    @Test
    @DisplayName("one or two states do not cover every state")
    void coversEveryState_partial_false() {
        assertFalse(TideType.coversEveryState(Set.of(TideType.HIGH)));
        assertFalse(TideType.coversEveryState(Set.of(TideType.HIGH, TideType.MID)));
        assertFalse(TideType.coversEveryState(Set.of(TideType.MID, TideType.LOW)));
    }

    @Test
    @DisplayName("HIGH, MID and LOW together cover every state")
    void coversEveryState_allThree_true() {
        assertTrue(TideType.coversEveryState(Set.of(TideType.HIGH, TideType.MID, TideType.LOW)));
    }
}
