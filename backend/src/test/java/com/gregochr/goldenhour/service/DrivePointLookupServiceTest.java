package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.PostcodeLookupException;
import com.gregochr.goldenhour.client.PostcodeLookupUnavailableException;
import com.gregochr.goldenhour.client.PostcodesIoClient;
import com.gregochr.goldenhour.model.DrivePointCandidate;
import com.gregochr.goldenhour.model.PlaceResult;
import com.gregochr.goldenhour.model.PostcodeLookupResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DrivePointLookupService}: nearest first, cut at 25 km, a postcode offered as typed.
 */
@ExtendWith(MockitoExtension.class)
class DrivePointLookupServiceTest {

    /** Helvellyn's summit, the location the lookups are made for. */
    private static final double LAT = 54.5275;
    private static final double LON = -3.0162;

    @Mock
    private PostcodesIoClient postcodesIoClient;

    private DrivePointLookupService service;

    @BeforeEach
    void setUp() {
        service = new DrivePointLookupService(postcodesIoClient);
    }

    private static PlaceResult place(String name, String type, double lat, double lon, String county,
            String region) {
        return new PlaceResult(name, type, lat, lon, county, region);
    }

    @Test
    @DisplayName("a name is searched, filtered to 25 km, and sorted nearest first with a distance")
    void search_name_sortsNearestFirstAndFiltersAt25Km() {
        PlaceResult glenridding = place("Glenridding", "Village", 54.5442, -2.9505, "Cumberland", "North West");
        PlaceResult thirlspot = place("Thirlspot", "Hamlet", 54.6000, -3.1000, "Cumberland", "North West");
        PlaceResult farAway = place("Glenridding", "Village", 51.5, -0.1, null, "London");
        when(postcodesIoClient.searchPlaces("Glen")).thenReturn(List.of(farAway, thirlspot, glenridding));

        List<DrivePointCandidate> result = service.search("  Glen ", LAT, LON);

        assertThat(result).extracting(DrivePointCandidate::name).containsExactly("Glenridding", "Thirlspot");
        assertThat(result.get(0).detail()).isEqualTo("Village, Cumberland");
        assertThat(result.get(0).lat()).isEqualTo(54.5442);
        assertThat(result.get(0).lon()).isEqualTo(-2.9505);
        assertThat(result.get(0).distanceKm()).isBetween(4.0, 8.0);
        assertThat(result.get(0).distanceKm()).isLessThan(result.get(1).distanceKm());
    }

    @Test
    @DisplayName("the 25 km cut keeps a place just inside it and drops one just outside")
    void search_cutIsAt25Km() {
        // 0.22 degrees of latitude is about 24.5 km; 0.23 about 25.6 km.
        PlaceResult inside = place("Inside", "Village", LAT + 0.22, LON, "X", null);
        PlaceResult outside = place("Outside", "Village", LAT + 0.23, LON, "X", null);
        when(postcodesIoClient.searchPlaces("Edge")).thenReturn(List.of(outside, inside));

        assertThat(service.search("Edge", LAT, LON)).extracting(DrivePointCandidate::name)
                .containsExactly("Inside");
    }

    @Test
    @DisplayName("an empty answer from postcodes.io is an empty list, not an error")
    void search_noMatches_isEmpty() {
        when(postcodesIoClient.searchPlaces("Zzzz")).thenReturn(List.of());

        assertThat(service.search("Zzzz", LAT, LON)).isEmpty();
    }

    @Test
    @DisplayName("at most ten candidates are returned")
    void search_capsAtTen() {
        List<PlaceResult> many = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            many.add(place("Place" + i, "Hamlet", LAT + i * 0.005, LON, "Cumberland", null));
        }
        when(postcodesIoClient.searchPlaces("Place")).thenReturn(many);

        assertThat(service.search("Place", LAT, LON)).hasSize(10);
    }

    @Test
    @DisplayName("the detail falls back to the region, and is empty when the place says neither")
    void search_detailFallsBackToRegion() {
        when(postcodesIoClient.searchPlaces("Bare")).thenReturn(Arrays.asList(
                place("Bare", "Village", LAT, LON, null, "North West"),
                place("Barer", null, LAT + 0.01, LON, null, null),
                place("Barest", " ", LAT + 0.02, LON, "  ", null),
                null));

        List<DrivePointCandidate> result = service.search("Bare", LAT, LON);

        assertThat(result).extracting(DrivePointCandidate::detail)
                .containsExactly("Village, North West", "", "");
    }

    @Test
    @DisplayName("a postcode goes through the postcode lookup, not the place search, and is offered unfiltered")
    void search_postcode_usesTheExistingLookup() {
        when(postcodesIoClient.lookup("CA11 0PA")).thenReturn(new PostcodeLookupResult(
                "CA11 0PA", 54.5442, -2.9505, "Patterdale, Westmorland and Furness"));

        List<DrivePointCandidate> result = service.search("CA11 0PA", LAT, LON);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("Patterdale");
        assertThat(result.get(0).detail()).isEqualTo("CA11 0PA, Patterdale, Westmorland and Furness");
        assertThat(result.get(0).lat()).isEqualTo(54.5442);
        assertThat(result.get(0).lon()).isEqualTo(-2.9505);
        verify(postcodesIoClient).lookup("CA11 0PA");
    }

    @Test
    @DisplayName("a postcode far from the location is still offered, and a spaceless one is recognised")
    void search_farPostcode_isStillOffered() {
        when(postcodesIoClient.lookup("SW1A1AA")).thenReturn(new PostcodeLookupResult(
                "SW1A 1AA", 51.501, -0.1416, "Westminster"));

        List<DrivePointCandidate> result = service.search("SW1A1AA", LAT, LON);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("Westminster");
        assertThat(result.get(0).distanceKm()).isGreaterThan(MAX_KM);
    }

    @Test
    @DisplayName("a postcode with no place name uses the postcode itself")
    void search_postcodeWithoutPlaceName_usesThePostcode() {
        when(postcodesIoClient.lookup("CA11 0PA"))
                .thenReturn(new PostcodeLookupResult("CA11 0PA", 54.5442, -2.9505, null));

        assertThat(service.search("CA11 0PA", LAT, LON).get(0).name()).isEqualTo("CA11 0PA");
    }

    @Test
    @DisplayName("a blank or missing query is a 400-style refusal and nothing is looked up")
    void search_blank_isRefused() {
        assertThatThrownBy(() -> service.search("   ", LAT, LON)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search(null, LAT, LON)).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(postcodesIoClient);
    }

    @Test
    @DisplayName("an unknown postcode and an unavailable service propagate their own exceptions")
    void search_failures_propagate() {
        when(postcodesIoClient.lookup("ZZ9 9ZZ")).thenThrow(new PostcodeLookupException("Invalid postcode"));
        when(postcodesIoClient.searchPlaces("Glen")).thenThrow(new PostcodeLookupUnavailableException());

        assertThatThrownBy(() -> service.search("ZZ9 9ZZ", LAT, LON)).isInstanceOf(PostcodeLookupException.class);
        assertThatThrownBy(() -> service.search("Glen", LAT, LON))
                .isInstanceOf(PostcodeLookupUnavailableException.class);
    }

    private static final double MAX_KM = DrivePointLookupService.MAX_DISTANCE_KM;
}
