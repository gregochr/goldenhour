package com.gregochr.goldenhour.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SubRegionProperties}: the defaults (enforcement off, 7 days of grace, 30 of lock) and the startup
 * validation.
 */
class SubRegionPropertiesTest {

    @Test
    @DisplayName("defaults: not enforced, grace 7, lock 30, and valid")
    void defaults() {
        SubRegionProperties properties = new SubRegionProperties();

        assertThat(properties.isEnforce()).isFalse();
        assertThat(properties.getGraceDays()).isEqualTo(7);
        assertThat(properties.getLockDays()).isEqualTo(30);
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("1 and 365 days are accepted for each")
    void bounds_accepted() {
        SubRegionProperties properties = new SubRegionProperties();
        properties.setGraceDays(1);
        properties.setLockDays(365);

        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a grace of 0 or 366 days fails startup, naming the key")
    void grace_outOfRange() {
        SubRegionProperties zero = new SubRegionProperties();
        zero.setGraceDays(0);
        SubRegionProperties high = new SubRegionProperties();
        high.setGraceDays(366);

        assertThatThrownBy(zero::validate).isInstanceOf(IllegalStateException.class)
                .hasMessage("photocast.sub-regions.grace-days=0 must be between 1 and 365");
        assertThatThrownBy(high::validate).isInstanceOf(IllegalStateException.class)
                .hasMessage("photocast.sub-regions.grace-days=366 must be between 1 and 365");
    }

    @Test
    @DisplayName("a lock of 0 or -1 days fails startup, naming the key")
    void lock_outOfRange() {
        SubRegionProperties zero = new SubRegionProperties();
        zero.setLockDays(0);
        SubRegionProperties negative = new SubRegionProperties();
        negative.setLockDays(-1);

        assertThatThrownBy(zero::validate).isInstanceOf(IllegalStateException.class)
                .hasMessage("photocast.sub-regions.lock-days=0 must be between 1 and 365");
        assertThatThrownBy(negative::validate).isInstanceOf(IllegalStateException.class)
                .hasMessage("photocast.sub-regions.lock-days=-1 must be between 1 and 365");
    }
}
