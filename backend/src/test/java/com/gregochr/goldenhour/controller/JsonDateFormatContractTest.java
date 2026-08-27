package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.ForecastListDto;
import com.gregochr.goldenhour.model.UserSettingsResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the exact wire format {@code java.time} types render in on real HTTP responses.
 *
 * <p>{@link com.gregochr.goldenhour.config.AppConfig#objectMapper()} defines a hand-built
 * {@code com.fasterxml.jackson.databind.ObjectMapper} (Jackson 2) bean that registers only
 * {@code JavaTimeModule}, with {@code WRITE_DATES_AS_TIMESTAMPS} never explicitly disabled — so
 * that bean, in isolation, serialises {@code LocalDate}/{@code LocalDateTime}/{@code Instant} as
 * numeric arrays, not ISO-8601 strings (confirmed by hand against the bare Jackson 2 stack this
 * project pins). But this app runs on Spring Boot 4 / Spring Framework 7, which default the MVC
 * JSON message converter to the newer <b>Jackson 3</b> stack ({@code tools.jackson}) — a completely
 * separate object graph from the Jackson 2 {@code com.fasterxml.jackson.databind.ObjectMapper}
 * bean AppConfig defines. Confirmed directly against this application context: the
 * {@code RequestMappingHandlerAdapter}'s converter list carries a
 * {@code JacksonJsonHttpMessageConverter} backed by an auto-configured {@code jacksonJsonMapper}
 * (Jackson 3 {@code JsonMapper}) bean — there is no {@code MappingJackson2HttpMessageConverter} in
 * the chain at all, so AppConfig's Jackson 2 bean is <em>never in the HTTP response path</em>. Its
 * {@code WRITE_DATES_AS_TIMESTAMPS} gap is real but moot for the wire format: Boot's Jackson 3
 * auto-configuration supplies its own (ISO-8601) date serialisation independent of it.
 *
 * <p>None of this is visible from a hand-built {@code new ObjectMapper()} in a plain unit test —
 * that only proves what a Jackson 2 mapper does in isolation, which (a) is not what AppConfig's
 * bean alone would do without Boot's Jackson 3 layer masking the gap, and (b) is not the object
 * actually wired into Spring MVC's response pipeline regardless. Six other test classes in this
 * codebase (e.g. {@code model.DailyBriefingResponseJsonTest}, {@code model.HotTopicJsonTest})
 * build their own {@code new ObjectMapper()} for exactly this reason: they can only prove internal
 * round-tripping of a mapper nobody serves traffic through. This class extends
 * {@link AbstractControllerTest} ({@code @SpringBootTest} + {@code @AutoConfigureMockMvc}) instead
 * of {@code @WebMvcTest} or a hand-built mapper, so its assertions run against the exact converter
 * chain a real request hits.
 */
class JsonDateFormatContractTest extends AbstractControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser
    @DisplayName("GET /api/forecast renders LocalDate and LocalDateTime fields in the pinned wire format")
    void getForecasts_pinsLocalDateAndLocalDateTimeFormat() throws Exception {
        ForecastListDto dto = new ForecastListDto(
                1L, "Durham UK", null, null,
                LocalDate.of(2026, 4, 16), TargetType.SUNSET,
                LocalDateTime.of(2026, 4, 16, 12, 0, 0),
                LocalDateTime.of(2026, 4, 16, 19, 45, 30),
                null, 4, 72, 80, null, null, null,
                null, null, null, null, null, null);
        when(dtoMapper.toListDtoList(any(), anyBoolean())).thenReturn(List.of(dto));

        mockMvc.perform(get("/api/forecast"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].targetDate").value("2026-04-16"))
                .andExpect(jsonPath("$[0].forecastRunAt").value("2026-04-16T12:00:00"))
                .andExpect(jsonPath("$[0].solarEventTime").value("2026-04-16T19:45:30"));
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/user/settings renders an Instant field in the pinned wire format")
    void getSettings_pinsInstantFormat() throws Exception {
        when(settingsService.getSettings(any())).thenReturn(new UserSettingsResponse(
                "testuser", "test@example.com", "PRO_USER",
                "DH1 3LE", 54.7761, -1.5733, "Durham, County Durham",
                null, Instant.parse("2026-04-16T10:15:30Z"), null));

        mockMvc.perform(get("/api/user/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.driveTimesCalculatedAt").value("2026-04-16T10:15:30Z"));
    }
}
