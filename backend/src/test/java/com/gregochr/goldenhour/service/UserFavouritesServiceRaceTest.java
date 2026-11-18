package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.AreaKind;
import com.gregochr.goldenhour.model.AreaKindRow;
import com.gregochr.goldenhour.model.FavouritesRequest;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.AreaRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.UserFavouriteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * The window between "does this location or area exist" and the insert: a target deleted in that gap reaches
 * the foreign key, and the caller must see "not found" (404), not a server error. Proven with mocks because a
 * real race cannot be made to happen on demand.
 */
@ExtendWith(MockitoExtension.class)
class UserFavouritesServiceRaceTest {

    private static final Long USER = 5L;
    private static final LocalDateTime NOW = LocalDateTime.of(2019, 3, 4, 10, 15);

    @Mock
    private AppUserRepository users;
    @Mock
    private UserFavouriteRepository favourites;
    @Mock
    private LocationRepository locations;
    @Mock
    private AreaRepository areas;
    @Mock
    private SubRegionMembershipService membership;

    private UserFavouritesService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2019-03-04T10:15:00Z"), ZoneOffset.UTC);
        service = new UserFavouritesService(users, favourites, locations, areas, membership, clock);
        when(users.findIdByUsername("pat")).thenReturn(Optional.of(USER));
        when(users.findByIdForUpdate(USER)).thenReturn(Optional.of(new AppUserEntity()));
        when(favourites.findRowsByUserId(USER)).thenReturn(List.of());
    }

    @Test
    @DisplayName("an area deleted between the check and the insert is NoSuchElementException on POST and on PUT")
    void areaGoneBeforeInsert() {
        when(areas.findKindRows(Set.of(9L))).thenReturn(List.of(new AreaKindRow(9L, AreaKind.NAMED)));
        when(favourites.insertArea(USER, 9L, NOW)).thenThrow(new DataIntegrityViolationException("fk"));

        assertThatThrownBy(() -> service.addArea("pat", 9L)).isInstanceOf(NoSuchElementException.class)
                .hasMessage("No area with id 9");
        assertThatThrownBy(() -> service.replace("pat", new FavouritesRequest(List.of(), List.of(9L))))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("a location deleted between the check and the insert is NoSuchElementException on POST and on PUT")
    void locationGoneBeforeInsert() {
        when(locations.findExistingIds(Set.of(4L))).thenReturn(List.of(4L));
        when(favourites.insertPlace(USER, 4L, NOW)).thenThrow(new DataIntegrityViolationException("fk"));

        assertThatThrownBy(() -> service.addPlace("pat", 4L)).isInstanceOf(NoSuchElementException.class)
                .hasMessage("No location with id 4");
        assertThatThrownBy(() -> service.replace("pat", new FavouritesRequest(List.of(4L), List.of())))
                .isInstanceOf(NoSuchElementException.class);
    }
}
