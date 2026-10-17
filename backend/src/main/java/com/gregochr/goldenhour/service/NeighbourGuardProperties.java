package com.gregochr.goldenhour.service;

import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bound to {@code photocast.neighbour-guard} (docs/engineering/neighbour-guard-plan.md §4).
 *
 * <p>Every numeric bound is enforced by the setter, so an out-of-range value fails startup (the
 * binder wraps the {@link IllegalArgumentException}) exactly as {@code AskProperties} does. Every
 * key is optional. {@code enabled} defaults to <b>false</b>: the guard ships off until the Ask and
 * client phases land, so the Plan pick, the best-reachable line and Ask never disagree about a place
 * in between.
 *
 * <p>The margin's lower bound of 2 makes a {@code min-rating} below 3 unreachable (a slot rated 2
 * cannot be two stars above anything rated 1 or more and still be worth guarding), so the floor's
 * bound is 3 to 5; it exists to let an operator guard only 4-star-and-better claims.
 */
@Component
@ConfigurationProperties(prefix = "photocast.neighbour-guard")
@Getter
public class NeighbourGuardProperties implements InitializingBean {

    private static final Logger LOG = LoggerFactory.getLogger(NeighbourGuardProperties.class);

    /** Smallest neighbourhood radius accepted, in kilometres. */
    public static final double MIN_RADIUS_KM = 1.0;
    /** Largest neighbourhood radius accepted, in kilometres. */
    public static final double MAX_RADIUS_KM = 20.0;
    /** Fewest similar-height neighbours that may be required. */
    public static final int MIN_NEIGHBOURS = 1;
    /** Most similar-height neighbours that may be required. */
    public static final int MAX_NEIGHBOURS = 5;
    /** Smallest star gap that counts as disagreement. */
    public static final int MIN_MARGIN = 2;
    /** Largest star gap that may be required. */
    public static final int MAX_MARGIN = 4;
    /** Lowest sky rating floor accepted. */
    public static final int MIN_RATING_FLOOR = 3;
    /** Highest sky rating floor accepted. */
    public static final int MAX_RATING_FLOOR = 5;
    /** Smallest elevation gap accepted, in metres. */
    public static final int MIN_ELEVATION_GAP_M = 0;
    /** Largest elevation gap accepted, in metres. */
    public static final int MAX_ELEVATION_GAP_M = 1_000;

    /** Whether the guard flags anything at all. Off by default. */
    @Setter
    private boolean enabled;

    private double radiusKm = 5.0;
    private int minNeighbours = 2;
    private int margin = 2;
    private int minRating = 3;
    private int maxElevationGapM = 150;

    /**
     * Sets the neighbourhood radius.
     *
     * @param radiusKm 1 to 20 kilometres
     * @throws IllegalArgumentException if out of range or not a number
     */
    public void setRadiusKm(double radiusKm) {
        if (Double.isNaN(radiusKm) || radiusKm < MIN_RADIUS_KM || radiusKm > MAX_RADIUS_KM) {
            throw new IllegalArgumentException("photocast.neighbour-guard.radius-km must be "
                    + MIN_RADIUS_KM + ".." + MAX_RADIUS_KM + " but was " + radiusKm);
        }
        this.radiusKm = radiusKm;
    }

    /**
     * Sets how many similar-height neighbours must be rated below a slot before it is flagged.
     *
     * @param minNeighbours 1 to 5
     * @throws IllegalArgumentException if out of range
     */
    public void setMinNeighbours(int minNeighbours) {
        this.minNeighbours = within("min-neighbours", minNeighbours, MIN_NEIGHBOURS, MAX_NEIGHBOURS);
    }

    /**
     * Sets the star gap that counts as disagreement.
     *
     * @param margin 2 to 4
     * @throws IllegalArgumentException if out of range
     */
    public void setMargin(int margin) {
        this.margin = within("margin", margin, MIN_MARGIN, MAX_MARGIN);
    }

    /**
     * Sets the lowest sky rating that can be flagged.
     *
     * @param minRating 3 to 5
     * @throws IllegalArgumentException if out of range
     */
    public void setMinRating(int minRating) {
        this.minRating = within("min-rating", minRating, MIN_RATING_FLOOR, MAX_RATING_FLOOR);
    }

    /**
     * Sets the greatest elevation gap at which two places count as being at a similar height.
     *
     * @param maxElevationGapM 0 to 1000 metres
     * @throws IllegalArgumentException if out of range
     */
    public void setMaxElevationGapM(int maxElevationGapM) {
        this.maxElevationGapM = within("max-elevation-gap-m", maxElevationGapM,
                MIN_ELEVATION_GAP_M, MAX_ELEVATION_GAP_M);
    }

    /**
     * Logs the effective configuration once, as bound, and warns when the thresholds cannot work
     * together: a slot rated exactly {@code min-rating} needs a neighbour {@code margin} stars below
     * it, so with {@code min-rating - margin < 1} there is no such neighbour and it can never be
     * flagged.
     */
    @Override
    public void afterPropertiesSet() {
        LOG.info("[NEIGHBOUR GUARD] enabled={} radiusKm={} minNeighbours={} margin={} minRating={}"
                + " maxElevationGapM={}", enabled, radiusKm, minNeighbours, margin, minRating,
                maxElevationGapM);
        if (minRating - margin < 1) {
            LOG.warn("[NEIGHBOUR GUARD] min-rating ({}) minus margin ({}) is below 1: a slot rated exactly"
                    + " min-rating can never be flagged", minRating, margin);
        }
    }

    private static int within(String key, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException("photocast.neighbour-guard." + key + " must be " + min
                    + ".." + max + " but was " + value);
        }
        return value;
    }
}
