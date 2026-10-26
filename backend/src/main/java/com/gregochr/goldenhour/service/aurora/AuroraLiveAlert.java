package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.entity.AlertLevel;

/**
 * One consistent read of the live aurora alert, and the single definition of "a real alert worth a
 * night row": active, not simulated, and at MODERATE or STRONG.
 *
 * <p>Shared by {@link AuroraNightsService} and {@link AuroraAlertService} so the two role-free reads
 * cannot drift. The simulation is read first, and once: a simulation that ends mid-read cannot then be
 * served as a real alert (the order {@code AuroraController.getStatus} uses). A null level is QUIET.
 *
 * @param simulated whether a simulation was standing; when true, {@code level} is QUIET and
 *                  {@code active} is false whatever the cache held
 * @param level     the alert level, never null
 * @param active    whether an alert is active
 */
record AuroraLiveAlert(boolean simulated, AlertLevel level, boolean active) {

    private static final AuroraLiveAlert SIMULATION = new AuroraLiveAlert(true, AlertLevel.QUIET, false);

    /**
     * Reads the live alert from the cache, simulation first.
     *
     * @param stateCache the live alert state
     * @return the alert read; a simulation reads as QUIET and inactive
     */
    static AuroraLiveAlert read(AuroraStateCache stateCache) {
        if (stateCache.getSimulatedData() != null) {
            return SIMULATION;
        }
        AlertLevel cached = stateCache.getCurrentLevel();
        AlertLevel level = cached == null ? AlertLevel.QUIET : cached;
        return new AuroraLiveAlert(false, level, stateCache.isActive());
    }

    /**
     * Whether this is a real, active alert at an alert-worthy level.
     *
     * @return true when active and MODERATE or STRONG (never for a simulation)
     */
    boolean alertWorthy() {
        return active && level.isAlertWorthy();
    }
}
