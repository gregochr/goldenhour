package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link RepositoryAskLocationNames}: the enabled roster as an id-to-place map. */
class RepositoryAskLocationNamesTest {

    private final LocationRepository locations = mock(LocationRepository.class);
    private final RepositoryAskLocationNames names = new RepositoryAskLocationNames(locations);

    private static LocationEntity location(Long id, String name, double lat, double lon) {
        return LocationEntity.builder().id(id).name(name).lat(lat).lon(lon).build();
    }

    @Test
    @DisplayName("maps every enabled location's id to its name and coordinates with one repository read, "
            + "skipping rows that have no id or no name")
    void mapsEnabledLocationsByIdWithOneQuery() {
        when(locations.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(
                location(1L, "Bamburgh", 55.6, -1.7), location(2L, "Whitby", 54.5, -0.6),
                location(null, "Unsaved", 1, 1), location(3L, null, 2, 2)));

        Map<Long, AskLocationNames.Place> byId = names.byId();

        assertThat(byId).containsExactlyInAnyOrderEntriesOf(Map.of(
                1L, new AskLocationNames.Place("Bamburgh", 55.6, -1.7),
                2L, new AskLocationNames.Place("Whitby", 54.5, -0.6)));
        verify(locations).findAllByEnabledTrueOrderByNameAsc();
    }

    @Test
    @DisplayName("no enabled locations is an empty map")
    void emptyRoster() {
        when(locations.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of());

        assertThat(names.byId()).isEmpty();
    }
}
