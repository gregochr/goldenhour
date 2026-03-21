package com.gregochr.goldenhour.entity;

/**
 * AuroraWatch UK alert levels, in ascending severity order.
 *
 * <p>Mirrors the four status levels defined by the AuroraWatch UK API.
 * Colours match the AuroraWatch UI specification exactly.
 */
public enum AlertLevel {

    /** No significant geomagnetic activity. */
    GREEN(0, "#33ff33", "No significant activity"),

    /** Minor geomagnetic activity — aurora unlikely but possible at high latitudes. */
    YELLOW(1, "#ffff00", "Minor geomagnetic activity"),

    /** Possible aurora visible from northern England and Scotland. */
    AMBER(2, "#ff9900", "Amber alert: possible aurora"),

    /** Aurora likely visible across much of the UK. */
    RED(3, "#ff0000", "Red alert: aurora likely");

    private final int severity;
    private final String hexColour;
    private final String description;

    AlertLevel(int severity, String hexColour, String description) {
        this.severity = severity;
        this.hexColour = hexColour;
        this.description = description;
    }

    /**
     * Numeric severity for ordering — higher value = more severe.
     *
     * @return severity index
     */
    public int severity() {
        return severity;
    }

    /**
     * AuroraWatch-defined hex colour for this alert level.
     *
     * @return hex colour string, e.g. {@code "#ff9900"}
     */
    public String hexColour() {
        return hexColour;
    }

    /**
     * Human-readable description of this alert level.
     *
     * @return description string
     */
    public String description() {
        return description;
    }

    /**
     * Returns {@code true} for levels that warrant aurora notifications (AMBER or RED).
     *
     * @return {@code true} if this level is actionable
     */
    public boolean isAlertWorthy() {
        return this == AMBER || this == RED;
    }

    /**
     * Parses an AuroraWatch {@code status_id} attribute value into an {@link AlertLevel}.
     *
     * @param statusId the raw value from the XML attribute (e.g. {@code "amber"})
     * @return the corresponding {@link AlertLevel}
     * @throws IllegalArgumentException if {@code statusId} is unrecognised — this indicates an
     *         API change that must be handled explicitly
     */
    public static AlertLevel fromStatusId(String statusId) {
        return switch (statusId.toLowerCase()) {
            case "green"  -> GREEN;
            case "yellow" -> YELLOW;
            case "amber"  -> AMBER;
            case "red"    -> RED;
            default -> throw new IllegalArgumentException(
                    "Unknown AuroraWatch status_id: '" + statusId + "' — API may have changed");
        };
    }
}
