package com.gregochr.goldenhour.model;

/**
 * An enabled location that at least one measured origin reads as {@link DriveStatus#UNROUTABLE}, with how many
 * origins said so out of how many could judge it (reach-scoped-card-plan R1b).
 *
 * <p>The denominator counts only origins that are measured and whose stamp is late enough to judge this
 * location, that is, whose status for it is MEASURED or UNROUTABLE; an origin still PENDING for the location
 * says nothing either way and is left out of both counts.
 *
 * @param locationId      the location id
 * @param name            the location name
 * @param unroutableHomes measured Pro and Admin homes with no route to it
 * @param judgedHomes     measured Pro and Admin homes that can judge it
 * @param unroutableBases measured region bases with no route to it
 * @param judgedBases     measured region bases that can judge it
 */
public record UnroutableLocationDto(
        Long locationId,
        String name,
        int unroutableHomes,
        int judgedHomes,
        int unroutableBases,
        int judgedBases) { }
