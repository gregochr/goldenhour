package com.gregochr.goldenhour.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for postcode lookup.
 *
 * <p>Checked when the controller binds the body ({@code @Valid}): a missing, blank or control-character
 * postcode answers HTTP 400 rather than reaching the lookup client.
 *
 * @param postcode the UK postcode to geocode
 */
public record PostcodeLookupRequest(
        @NotBlank @Pattern(regexp = SaveHomeRequest.NO_CONTROL_CHARACTERS) String postcode) {
}
