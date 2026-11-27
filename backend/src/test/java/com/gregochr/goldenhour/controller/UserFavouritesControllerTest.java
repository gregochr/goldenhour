package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.exception.FavouritesLimitException;
import com.gregochr.goldenhour.model.FavouritesRequest;
import com.gregochr.goldenhour.model.FavouritesResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller tests for {@code /api/user/settings/favourites}: Pro and Admin only (Lite is 403 on every
 * method and never reaches the service), the merged wire shape, the status codes and the message at the cap,
 * and that no response is cacheable. The rules themselves are proven in {@code UserFavouritesServiceJpaTest}.
 */
class UserFavouritesControllerTest extends AbstractControllerTest {

    private static final String BASE = "/api/user/settings/favourites";

    @Autowired
    private MockMvc mockMvc;

    private static FavouritesResponse list() {
        return new FavouritesResponse(
                List.of(new FavouritesResponse.Place(4L, LocalDateTime.of(2019, 3, 4, 10, 15))),
                List.of(new FavouritesResponse.Area(9L, LocalDateTime.of(2019, 3, 5, 11, 30))),
                List.of(4L, 5L, 6L),
                List.of(new FavouritesResponse.Date("king-tide:2026-12-03", LocalDateTime.of(2019, 3, 6, 9, 0))));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String role) throws Exception {
        return mockMvc.perform(request.with(user("pat").roles(role)));
    }

    private static MockHttpServletRequestBuilder withBody(MockHttpServletRequestBuilder request, String json) {
        return request.contentType(MediaType.APPLICATION_JSON).content(json);
    }

    @ParameterizedTest(name = "{0} reads the merged list")
    @ValueSource(strings = {"PRO_USER", "ADMIN"})
    @DisplayName("GET serves places, areas and the merged location ids, uncacheable")
    void get_proAndAdmin(String role) throws Exception {
        when(userFavouritesService.get("pat")).thenReturn(list());

        call(get(BASE), role)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.places[0].locationId").value(4))
                .andExpect(jsonPath("$.places[0].createdAt").value("2019-03-04T10:15:00"))
                .andExpect(jsonPath("$.areas[0].areaId").value(9))
                .andExpect(jsonPath("$.locationIds.length()").value(3))
                .andExpect(jsonPath("$.locationIds[2]").value(6))
                .andExpect(jsonPath("$.dates[0].key").value("king-tide:2026-12-03"))
                .andExpect(jsonPath("$.dates[0].createdAt").value("2019-03-06T09:00:00"))
                .andExpect(header().doesNotExist("ETag"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }

    @ParameterizedTest(name = "Lite is refused {0}")
    @ValueSource(strings = {"GET", "PUT", "POST-place", "POST-area", "POST-date", "DELETE-place", "DELETE-area",
            "DELETE-date"})
    @DisplayName("every method is 403 for a Lite reader and never reaches the service")
    void lite_isRefusedEverywhere(String which) throws Exception {
        MockHttpServletRequestBuilder request = switch (which) {
            case "GET" -> get(BASE);
            case "PUT" -> withBody(put(BASE), "{\"locationIds\":[1],\"areaIds\":[]}");
            case "POST-place" -> post(BASE + "/places/1");
            case "POST-area" -> post(BASE + "/areas/1");
            case "POST-date" -> post(BASE + "/dates/king-tide:2026-12-03");
            case "DELETE-date" -> delete(BASE + "/dates/king-tide:2026-12-03");
            case "DELETE-place" -> delete(BASE + "/places/1");
            default -> delete(BASE + "/areas/1");
        };

        call(request, "LITE_USER").andExpect(status().isForbidden());

        verifyNoInteractions(userFavouritesService);
    }

    @Test
    @DisplayName("an unauthenticated caller is 401 and never reaches the service")
    void unauthenticated() throws Exception {
        mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());

        verifyNoInteractions(userFavouritesService);
    }

    @Test
    @DisplayName("PUT passes the two id lists to the service and returns the new list")
    void put_replaces() throws Exception {
        when(userFavouritesService.replace("pat", new FavouritesRequest(List.of(4L), List.of(9L))))
                .thenReturn(list());

        call(withBody(put(BASE), "{\"locationIds\":[4],\"areaIds\":[9]}"), "PRO_USER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.places[0].locationId").value(4));
    }

    @Test
    @DisplayName("PUT over the cap is 409 with the reader-facing message")
    void put_overCap() throws Exception {
        when(userFavouritesService.replace("pat", new FavouritesRequest(List.of(4L), null)))
                .thenThrow(new FavouritesLimitException("You can keep up to 25 places."));

        call(withBody(put(BASE), "{\"locationIds\":[4]}"), "PRO_USER")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("You can keep up to 25 places."));
    }

    @Test
    @DisplayName("PUT with a null id is 400")
    void put_nullId() throws Exception {
        when(userFavouritesService.replace("pat", new FavouritesRequest(java.util.Arrays.asList((Long) null), null)))
                .thenThrow(new IllegalArgumentException("A favourite id must not be null"));

        call(withBody(put(BASE), "{\"locationIds\":[null]}"), "ADMIN").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST a place and POST an area star one and return the list")
    void post_stars() throws Exception {
        when(userFavouritesService.addPlace("pat", 4L)).thenReturn(list());
        when(userFavouritesService.addArea("pat", 9L)).thenReturn(list());

        call(post(BASE + "/places/4"), "PRO_USER").andExpect(status().isOk())
                .andExpect(jsonPath("$.places[0].locationId").value(4));
        call(post(BASE + "/areas/9"), "PRO_USER").andExpect(status().isOk())
                .andExpect(jsonPath("$.areas[0].areaId").value(9));
    }

    @Test
    @DisplayName("POST at the cap is 409; POST of an unknown id is 404")
    void post_capAndUnknown() throws Exception {
        when(userFavouritesService.addPlace("pat", 4L))
                .thenThrow(new FavouritesLimitException("You can keep up to 25 places."));
        when(userFavouritesService.addArea("pat", 99L)).thenThrow(new NoSuchElementException("No area with id 99"));

        call(post(BASE + "/places/4"), "PRO_USER").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("You can keep up to 25 places."));
        call(post(BASE + "/areas/99"), "PRO_USER").andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST and DELETE a date watch and un-watch it, returning the list with its dates")
    void dates_watchAndUnwatch() throws Exception {
        when(userFavouritesService.addDate("pat", "king-tide:2026-12-03")).thenReturn(list());
        when(userFavouritesService.removeDate("pat", "king-tide:2026-12-03")).thenReturn(list());

        call(post(BASE + "/dates/king-tide:2026-12-03"), "PRO_USER").andExpect(status().isOk())
                .andExpect(jsonPath("$.dates[0].key").value("king-tide:2026-12-03"))
                .andExpect(header().doesNotExist("ETag"));
        call(delete(BASE + "/dates/king-tide:2026-12-03"), "ADMIN").andExpect(status().isOk());
    }

    @Test
    @DisplayName("a bad date key is 400, and a new watch at the cap is 409 with the reader-facing message")
    void dates_badKeyAndCap() throws Exception {
        when(userFavouritesService.addDate("pat", "nonsense")).thenThrow(new IllegalArgumentException("bad key"));
        when(userFavouritesService.removeDate("pat", "nonsense")).thenThrow(new IllegalArgumentException("bad key"));
        when(userFavouritesService.addDate("pat", "king-tide:2026-12-03"))
                .thenThrow(new FavouritesLimitException("You can keep up to 25 places and dates."));

        call(post(BASE + "/dates/nonsense"), "PRO_USER").andExpect(status().isBadRequest());
        call(delete(BASE + "/dates/nonsense"), "PRO_USER").andExpect(status().isBadRequest());
        call(post(BASE + "/dates/king-tide:2026-12-03"), "PRO_USER").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("You can keep up to 25 places and dates."));
    }

    @Test
    @DisplayName("DELETE a place and DELETE an area un-star one and return the list; unknown is 404")
    void delete_unstars() throws Exception {
        when(userFavouritesService.removePlace("pat", 4L)).thenReturn(list());
        when(userFavouritesService.removeArea("pat", 9L)).thenReturn(list());
        when(userFavouritesService.removePlace("pat", 99L)).thenThrow(new NoSuchElementException("No location"));

        call(delete(BASE + "/places/4"), "ADMIN").andExpect(status().isOk());
        call(delete(BASE + "/areas/9"), "ADMIN").andExpect(status().isOk());
        call(delete(BASE + "/places/99"), "ADMIN").andExpect(status().isNotFound());
    }
}
