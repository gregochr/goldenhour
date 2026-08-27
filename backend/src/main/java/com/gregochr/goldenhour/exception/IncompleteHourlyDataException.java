package com.gregochr.goldenhour.exception;

/**
 * Thrown when a required Open-Meteo hourly series is missing or too short at the resolved
 * forecast slot index.
 *
 * <p>Every OPTIONAL hourly field in {@code OpenMeteoResponseParser} already falls back to
 * {@code null} via bounds-aware accessors. This exception is for the fields the domain records
 * declare non-nullable ({@code CloudData}, the primitive fields of {@code WeatherData},
 * {@code AerosolData.boundaryLayerHeightMetres}) — a null or out-of-range read there is genuinely
 * incomplete data from the API, not something a default value can stand in for. Naming the field
 * and index lets the caller log the real cause instead of reporting a parser defect as an
 * Open-Meteo API outage.
 */
public class IncompleteHourlyDataException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String fieldName;
    private final int index;

    /**
     * Constructs the exception.
     *
     * @param fieldName the name of the required hourly field that was missing or out of range
     * @param index     the resolved slot index at which the field was read
     */
    public IncompleteHourlyDataException(String fieldName, int index) {
        super("Missing required Open-Meteo hourly field '" + fieldName + "' at index " + index);
        this.fieldName = fieldName;
        this.index = index;
    }

    /**
     * Returns the name of the missing or out-of-range required hourly field.
     *
     * @return the field name
     */
    public String getFieldName() {
        return fieldName;
    }

    /**
     * Returns the resolved slot index at which the field was read.
     *
     * @return the index
     */
    public int getIndex() {
        return index;
    }
}
