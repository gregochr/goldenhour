package com.gregochr.goldenhour.model;

import java.math.BigDecimal;

/**
 * Aerosol and boundary layer measurements from the CAMS air quality model.
 *
 * @param pm25                      fine particulate matter concentration in µg/m³
 * @param dustUgm3                  dust concentration in µg/m³
 * @param aerosolOpticalDepth       aerosol optical depth (dimensionless)
 * @param boundaryLayerHeightMetres atmospheric boundary layer height in metres
 */
public record AerosolData(
        BigDecimal pm25,
        BigDecimal dustUgm3,
        BigDecimal aerosolOpticalDepth,
        int boundaryLayerHeightMetres) {
}
