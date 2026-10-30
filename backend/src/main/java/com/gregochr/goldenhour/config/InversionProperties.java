package com.gregochr.goldenhour.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bound to the {@code photocast.inversion} section of {@code application.yml} (inversion-v2-plan Phase 1b).
 *
 * <p>Both switches are read once at startup, so flipping either is a configuration change plus a backend
 * restart (an environment variable on the production host), which also clears the briefing and hot-topic
 * memos. They are the revert path for the v2 inversion score: with {@code v2.enabled} false the slot is
 * scored exactly as before Phase 1b and no valley profile is fetched.
 */
@Component
@ConfigurationProperties(prefix = "photocast.inversion")
@Getter
@Setter
public class InversionProperties {

    /** The v2 (warm layer measured from the valley floor) settings. */
    private V2 v2 = new V2();

    /**
     * Settings for the v2 inversion score.
     */
    @Getter
    @Setter
    public static class V2 {

        /**
         * Whether the v2 score drives the stored inversion score for a site with a confirmed valley floor.
         * Defaults to {@code true}; false puts every site back on the old calculator.
         */
        private boolean enabled = true;

        /**
         * Whether v2 may raise a low site's score by at most one point over the old score. Defaults to
         * {@code true}. Applies only below the viewpoint height {@code RAISE_CAP_MAX_ELEVATION_M}; summits
         * are never capped. Lift it (false) after a winter of evidence.
         */
        private boolean raiseCapEnabled = true;
    }
}
