package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.BluebellExposure;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.SolarEventType;
import com.gregochr.goldenhour.entity.TideType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the exact JSON contract of {@code GET /api/locations} through the real Spring context.
 *
 * <p>Written and captured against the unmodified {@code List<LocationEntity>} controller response
 * <em>before</em> the DTO refactor lands, so it proves the refactor changed nothing about the wire
 * format — same field names, same order, same null handling, same values, including the
 * {@code woodlandOnly} property computed by {@code LocationEntity.isWoodlandOnly()}. Extends
 * {@link AbstractControllerTest} rather than building a hand-rolled {@code ObjectMapper}: this app's
 * {@code @RestController} responses are serialised by an auto-configured Jackson 3
 * {@code JsonMapper}, not the Jackson 2 bean in {@code AppConfig} (see
 * {@link JsonDateFormatContractTest}), so only the real converter chain proves the wire format.
 */
class LocationJsonContractTest extends AbstractControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser
    @DisplayName("GET /api/locations renders the exact pinned JSON contract")
    void getLocations_rendersExactJsonContract() throws Exception {
        RegionEntity region = RegionEntity.builder()
                .id(9L).name("Lake District").enabled(true)
                .baseName("Keswick").baseLat(54.6).baseLon(-3.13)
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .build();
        LocationEntity full = LocationEntity.builder()
                .id(1L).name("Bamburgh Castle").lat(55.609).lon(-1.7099)
                .solarEventType(Set.of(SolarEventType.SUNSET))
                .tideType(Set.of(TideType.HIGH))
                .locationType(Set.of(LocationType.SEASCAPE, LocationType.WOODLAND))
                .region(region)
                .enabled(true)
                .createdAt(LocalDateTime.of(2026, 2, 1, 12, 0))
                .consecutiveFailures(2)
                .lastFailureAt(LocalDateTime.of(2026, 2, 2, 8, 0))
                .disabledReason("test reason")
                .bortleClass(4)
                .skyBrightnessSqm(21.1)
                .shoreNormalBearingDegrees(90.0)
                .effectiveFetchMetres(5000.0)
                .avgShelfDepthMetres(12.0)
                .coastalTidal(true)
                .elevationMetres(30)
                .overlooksWater(true)
                .gridLat(55.61)
                .gridLng(-1.71)
                .bluebellExposure(BluebellExposure.WOODLAND)
                .build();
        LocationEntity minimal = LocationEntity.builder()
                .id(2L).name("Minimal Spot").lat(1.0).lon(2.0)
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                .build();

        when(locationService.findAll()).thenReturn(List.of(full, minimal));

        String expectedJson = "["
                + "{\"id\":1,\"name\":\"Bamburgh Castle\",\"lat\":55.609,\"lon\":-1.7099,"
                + "\"solarEventType\":[\"SUNSET\"],\"tideType\":[\"HIGH\"],"
                + "\"locationType\":[\"SEASCAPE\",\"WOODLAND\"],"
                + "\"region\":{\"id\":9,\"name\":\"Lake District\",\"enabled\":true,"
                + "\"baseName\":\"Keswick\",\"baseLat\":54.6,\"baseLon\":-3.13,"
                + "\"createdAt\":\"2026-01-01T00:00:00\"},"
                + "\"enabled\":true,\"createdAt\":\"2026-02-01T12:00:00\","
                + "\"consecutiveFailures\":2,\"lastFailureAt\":\"2026-02-02T08:00:00\","
                + "\"disabledReason\":\"test reason\",\"bortleClass\":4,"
                + "\"skyBrightnessSqm\":21.1,\"shoreNormalBearingDegrees\":90.0,"
                + "\"effectiveFetchMetres\":5000.0,\"avgShelfDepthMetres\":12.0,"
                + "\"coastalTidal\":true,\"elevationMetres\":30,\"overlooksWater\":true,"
                + "\"gridLat\":55.61,\"gridLng\":-1.71,\"bluebellExposure\":\"WOODLAND\","
                + "\"woodlandOnly\":false},"
                + "{\"id\":2,\"name\":\"Minimal Spot\",\"lat\":1.0,\"lon\":2.0,"
                + "\"solarEventType\":[],\"tideType\":[],\"locationType\":[],\"region\":null,"
                + "\"enabled\":true,\"createdAt\":\"2026-01-01T00:00:00\","
                + "\"consecutiveFailures\":0,\"lastFailureAt\":null,\"disabledReason\":null,"
                + "\"bortleClass\":null,\"skyBrightnessSqm\":null,"
                + "\"shoreNormalBearingDegrees\":null,\"effectiveFetchMetres\":null,"
                + "\"avgShelfDepthMetres\":null,\"coastalTidal\":false,\"elevationMetres\":null,"
                + "\"overlooksWater\":false,\"gridLat\":null,\"gridLng\":null,"
                + "\"bluebellExposure\":null,\"woodlandOnly\":false}"
                + "]";

        mockMvc.perform(get("/api/locations"))
                .andExpect(status().isOk())
                .andExpect(content().json(expectedJson, true));
    }
}
