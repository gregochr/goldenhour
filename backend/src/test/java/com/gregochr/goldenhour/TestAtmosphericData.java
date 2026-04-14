package com.gregochr.goldenhour;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AerosolData;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.CloudApproachData;
import com.gregochr.goldenhour.model.CloudData;
import com.gregochr.goldenhour.model.ComfortData;
import com.gregochr.goldenhour.model.DirectionalCloudData;
import com.gregochr.goldenhour.model.MistTrend;
import com.gregochr.goldenhour.model.TideSnapshot;
import com.gregochr.goldenhour.model.WeatherData;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Test factory for {@link AtmosphericData} with sensible defaults.
 *
 * <p>Every test that needs an {@code AtmosphericData} instance should use this factory
 * instead of constructing the record directly. This centralises the construction and
 * makes future sub-record changes a single-point update.
 */
public final class TestAtmosphericData {

    private String locationName = "Durham UK";
    private LocalDateTime solarEventTime = LocalDateTime.of(2026, 6, 21, 20, 47);
    private TargetType targetType = TargetType.SUNSET;
    private int lowCloud = 10;
    private int midCloud = 50;
    private int highCloud = 30;
    private int visibility = 25000;
    private BigDecimal windSpeed = new BigDecimal("3.50");
    private int windDirection = 225;
    private BigDecimal precipitation = BigDecimal.ZERO;
    private int humidity = 62;
    private int weatherCode = 3;
    private BigDecimal shortwaveRadiation = new BigDecimal("180.00");
    private BigDecimal pm25 = new BigDecimal("8.50");
    private BigDecimal dust = new BigDecimal("2.10");
    private BigDecimal aod = new BigDecimal("0.120");
    private int boundaryLayerHeight = 1200;
    private Double temperature = null;
    private Double apparentTemperature = null;
    private Integer precipProbability = null;
    private Double dewPoint = null;
    private Double pressure = 1013.25;
    private DirectionalCloudData directionalCloud = null;
    private TideSnapshot tide = null;
    private CloudApproachData cloudApproach = null;
    private MistTrend mistTrend = null;
    private String locationOrientation = null;
    private Double inversionScore = null;

    private TestAtmosphericData() {
    }

    /**
     * Creates a new builder with sensible defaults.
     *
     * @return a new builder
     */
    public static TestAtmosphericData builder() {
        return new TestAtmosphericData();
    }

    /**
     * Shortcut: builds with all defaults immediately.
     *
     * @return a default AtmosphericData instance
     */
    public static AtmosphericData defaults() {
        return builder().build();
    }

    public TestAtmosphericData locationName(String val) {
        this.locationName = val;
        return this;
    }

    public TestAtmosphericData solarEventTime(LocalDateTime val) {
        this.solarEventTime = val;
        return this;
    }

    public TestAtmosphericData targetType(TargetType val) {
        this.targetType = val;
        return this;
    }

    public TestAtmosphericData lowCloud(int val) {
        this.lowCloud = val;
        return this;
    }

    public TestAtmosphericData midCloud(int val) {
        this.midCloud = val;
        return this;
    }

    public TestAtmosphericData highCloud(int val) {
        this.highCloud = val;
        return this;
    }

    public TestAtmosphericData visibility(int val) {
        this.visibility = val;
        return this;
    }

    public TestAtmosphericData windSpeed(BigDecimal val) {
        this.windSpeed = val;
        return this;
    }

    public TestAtmosphericData windDirection(int val) {
        this.windDirection = val;
        return this;
    }

    public TestAtmosphericData precipitation(BigDecimal val) {
        this.precipitation = val;
        return this;
    }

    public TestAtmosphericData humidity(int val) {
        this.humidity = val;
        return this;
    }

    public TestAtmosphericData weatherCode(int val) {
        this.weatherCode = val;
        return this;
    }

    public TestAtmosphericData shortwaveRadiation(BigDecimal val) {
        this.shortwaveRadiation = val;
        return this;
    }

    public TestAtmosphericData pm25(BigDecimal val) {
        this.pm25 = val;
        return this;
    }

    public TestAtmosphericData dust(BigDecimal val) {
        this.dust = val;
        return this;
    }

    public TestAtmosphericData aod(BigDecimal val) {
        this.aod = val;
        return this;
    }

    public TestAtmosphericData boundaryLayerHeight(int val) {
        this.boundaryLayerHeight = val;
        return this;
    }

    public TestAtmosphericData temperature(Double val) {
        this.temperature = val;
        return this;
    }

    public TestAtmosphericData apparentTemperature(Double val) {
        this.apparentTemperature = val;
        return this;
    }

    public TestAtmosphericData precipProbability(Integer val) {
        this.precipProbability = val;
        return this;
    }

    public TestAtmosphericData dewPoint(Double val) {
        this.dewPoint = val;
        return this;
    }

    public TestAtmosphericData pressure(Double val) {
        this.pressure = val;
        return this;
    }

    public TestAtmosphericData directionalCloud(DirectionalCloudData val) {
        this.directionalCloud = val;
        return this;
    }

    public TestAtmosphericData tide(TideSnapshot val) {
        this.tide = val;
        return this;
    }

    public TestAtmosphericData cloudApproach(CloudApproachData val) {
        this.cloudApproach = val;
        return this;
    }

    public TestAtmosphericData mistTrend(MistTrend val) {
        this.mistTrend = val;
        return this;
    }

    public TestAtmosphericData locationOrientation(String val) {
        this.locationOrientation = val;
        return this;
    }

    public TestAtmosphericData inversionScore(Double val) {
        this.inversionScore = val;
        return this;
    }

    /**
     * Builds the {@link AtmosphericData} from the configured values.
     *
     * @return a new AtmosphericData instance
     */
    public AtmosphericData build() {
        return new AtmosphericData(
                locationName, solarEventTime, targetType,
                new CloudData(lowCloud, midCloud, highCloud),
                new WeatherData(visibility, windSpeed, windDirection,
                        precipitation, humidity, weatherCode, shortwaveRadiation, dewPoint, pressure),
                new AerosolData(pm25, dust, aod, boundaryLayerHeight),
                new ComfortData(temperature, apparentTemperature, precipProbability),
                directionalCloud, tide, cloudApproach, mistTrend,
                locationOrientation, null, null, null, inversionScore, null);
    }
}
