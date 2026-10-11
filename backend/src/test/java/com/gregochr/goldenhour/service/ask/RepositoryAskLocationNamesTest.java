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

/** Unit tests for {@link RepositoryAskLocationNames}: the enabled roster as an id-to-name map. */
class RepositoryAskLocationNamesTest {

    private final LocationRepository locations = mock(LocationRepository.class);
    private final RepositoryAskLocationNames names = new RepositoryAskLocationNames(locations);

    private static LocationEntity location(Long id, String name) {
        return LocationEntity.builder().id(id).name(name).build();
    }

    @Test
    @DisplayName("maps every enabled location's id to its name with one repository read, skipping rows "
            + "that have no id or no name")
    void mapsEnabledLocationsByIdWithOneQuery() {
        when(locations.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(
                location(1L, "Bamburgh"), location(2L, "Whitby"), location(null, "Unsaved"),
                location(3L, null)));

        Map<Long, String> byId = names.byId();

        assertThat(byId).containsExactlyInAnyOrderEntriesOf(Map.of(1L, "Bamburgh", 2L, "Whitby"));
        verify(locations).findAllByEnabledTrueOrderByNameAsc();
    }

    @Test
    @DisplayName("no enabled locations is an empty map")
    void emptyRoster() {
        when(locations.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of());

        assertThat(names.byId()).isEmpty();
    }
}
