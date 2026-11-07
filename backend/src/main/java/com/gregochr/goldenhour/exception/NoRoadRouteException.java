package com.gregochr.goldenhour.exception;

/**
 * Thrown when an admin save would leave a location with a point OpenRouteService cannot route to: a new place, a
 * moved pin or a cleared drive point whose pin has no road route, or a drive point that itself has none
 * (reach-scoped-card-plan R1c).
 *
 * <p>Mapped to HTTP 422 {@code {error, code: NO_ROAD_ROUTE, field: drivePoint}} by
 * {@link com.gregochr.goldenhour.controller.GlobalExceptionHandler}. The message is a plain sentence the admin UI
 * shows as is, and {@code field} tells the form to show the drive-point field as required.
 */
public class NoRoadRouteException extends RuntimeException {

    /** The {@code code} the 422 body carries, which the admin UI keys on. */
    public static final String CODE = "NO_ROAD_ROUTE";

    /** The form field the refusal belongs to. */
    public static final String FIELD = "drivePoint";

    /**
     * Constructs the exception.
     *
     * @param message what is wrong and what to do about it
     */
    public NoRoadRouteException(String message) {
        super(message);
    }
}
