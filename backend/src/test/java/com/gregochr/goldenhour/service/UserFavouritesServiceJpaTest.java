package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.AreaEntity;
import com.gregochr.goldenhour.entity.AreaKind;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.exception.FavouritesLimitException;
import com.gregochr.goldenhour.model.FavouriteLocations;
import com.gregochr.goldenhour.model.FavouritesRequest;
import com.gregochr.goldenhour.model.FavouritesResponse;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.AreaRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.RegionRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The favourites service on a real JPA stack (H2) with REAL transaction boundaries (each call commits on its
 * own, as in production), so the per-reader lock and the cap can be raced. The V226 SQL itself (CHECK,
 * partial unique indexes, cascades) is proven on Postgres by {@code UserFavouriteMigrationTest} in CI.
 */
@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Import({UserFavouritesService.class, SubRegionMembershipService.class, UserFavouritesServiceJpaTest.ClockConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserFavouritesServiceJpaTest {

    /** A date unrelated to when the suite runs. */
    private static final Instant STARRED_AT = Instant.parse("2019-03-04T10:15:00Z");

    private static final String PRO = "pat";

    @TestConfiguration
    static class ClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(STARRED_AT, ZoneOffset.UTC);
        }
    }

    @Autowired
    private UserFavouritesService service;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private LocationRepository locations;

    @Autowired
    private AreaRepository areas;

    @Autowired
    private RegionRepository regions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private final List<Long> userIds = new ArrayList<>();

    @BeforeEach
    void plantReader() {
        user(PRO);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM user_favourite");
        jdbc.update("DELETE FROM location_sub_region");
        users.deleteAllById(userIds);
        areas.deleteAll();
        locations.deleteAll();
        regions.deleteAll();
    }

    private void user(String name) {
        userIds.add(users.save(AppUserEntity.builder().username(name).password("hash").role(UserRole.PRO_USER)
                .enabled(true).createdAt(LocalDateTime.of(2019, 1, 1, 12, 0)).email(name + "@example.com")
                .build()).getId());
    }

    private Long location(String name, boolean enabled) {
        return locations.save(LocationEntity.builder().name(name).lat(55.0).lon(-2.0).enabled(enabled)
                .createdAt(LocalDateTime.of(2019, 1, 1, 12, 0)).build()).getId();
    }

    private Long named(String name, Long... members) {
        AreaEntity area = new AreaEntity();
        area.setName(name);
        area.setLocationIds(new LinkedHashSet<>(List.of(members)));
        return areas.save(area).getId();
    }

    private Long subRegion(String name, Long... members) {
        RegionEntity region = regions.save(RegionEntity.builder().name("Region of " + name)
                .createdAt(LocalDateTime.of(2019, 1, 1, 12, 0)).build());
        AreaEntity area = new AreaEntity();
        area.setName(name);
        area.setKind(AreaKind.SUB_REGION);
        area.setRegion(region);
        Long id = areas.save(area).getId();
        for (Long member : members) {
            jdbc.update("INSERT INTO location_sub_region (location_id, area_id) VALUES (?, ?)", member, id);
        }
        return id;
    }

    private static FavouritesRequest request(List<Long> places, List<Long> areaIds) {
        return new FavouritesRequest(places, areaIds);
    }

    @Test
    @DisplayName("a new reader has nothing starred")
    void get_empty() {
        FavouritesResponse out = service.get(PRO);

        assertThat(out.places()).isEmpty();
        assertThat(out.areas()).isEmpty();
        assertThat(out.locationIds()).isEmpty();
    }

    @Test
    @DisplayName("an unknown username is a 404-shaped NoSuchElementException")
    void get_unknownUser() {
        assertThatThrownBy(() -> service.get("nobody")).isInstanceOf(NoSuchElementException.class)
                .hasMessage("User not found: nobody");
    }

    @Test
    @DisplayName("locationIds is the stars plus the enabled members of starred named areas and sub-regions, "
            + "de-duplicated and in id order; a disabled location is kept in places but hidden")
    void get_mergesAndExpands() {
        Long bamburgh = location("Bamburgh", true);
        Long cawfields = location("Cawfields", true);
        Long walltown = location("Walltown", true);
        Long hidden = location("Hidden Cove", false);
        Long unrelated = location("Unrelated", true);
        Long wall = named("Hadrian's Wall", cawfields, walltown, hidden);
        Long coast = subRegion("Northumberland Coast", bamburgh, cawfields);

        service.addPlace(PRO, hidden);
        service.addPlace(PRO, walltown);
        service.addArea(PRO, wall);
        FavouritesResponse out = service.addArea(PRO, coast);

        assertThat(out.places()).extracting(FavouritesResponse.Place::locationId).containsExactly(hidden, walltown);
        assertThat(out.areas()).extracting(FavouritesResponse.Area::areaId).containsExactly(wall, coast);
        assertThat(out.locationIds()).containsExactly(bamburgh, cawfields, walltown).doesNotContain(hidden, unrelated);
    }

    @Test
    @DisplayName("forAsk: a reader with nothing starred has no favourites")
    void forAsk_empty() {
        Long reader = userIds.getFirst();

        assertThat(service.forAsk(reader)).isSameAs(FavouriteLocations.NONE);
    }

    @Test
    @DisplayName("forAsk: stars plus enabled area members, each member naming its alphabetically first starred "
            + "area; a place starred alone names none, and a disabled place is hidden")
    void forAsk_mergesAndNamesAreas() {
        Long bamburgh = location("Bamburgh", true);
        Long cawfields = location("Cawfields", true);
        Long walltown = location("Walltown", true);
        Long hidden = location("Hidden Cove", false);
        Long alone = location("Alone", true);
        Long wall = named("Hadrian's Wall", cawfields, walltown, hidden);
        Long coast = subRegion("Northumberland Coast", bamburgh, cawfields);
        service.addPlace(PRO, alone);
        service.addPlace(PRO, hidden);
        service.addArea(PRO, coast);
        service.addArea(PRO, wall);

        FavouriteLocations out = service.forAsk(userIds.getFirst());

        assertThat(out.locationIds()).containsExactlyInAnyOrder(alone, bamburgh, cawfields, walltown);
        assertThat(out.areaByLocation()).containsOnly(
                java.util.Map.entry(bamburgh, "Northumberland Coast"),
                java.util.Map.entry(cawfields, "Hadrian's Wall"),
                java.util.Map.entry(walltown, "Hadrian's Wall"));
        assertThat(out.fingerprint()).isNotNull();
    }

    @Test
    @DisplayName("the read's statement count is the same for two starred areas as for twenty-two (no N+1)")
    void get_statementCountIsConstant() {
        Long member = location("Member", true);
        service.addArea(PRO, named("Named 0", member));
        service.addArea(PRO, subRegion("Sub 0", member));
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        service.get(PRO);
        stats.clear();
        service.get(PRO);
        long two = stats.getPrepareStatementCount();

        for (int i = 1; i <= 10; i++) {
            Long spot = location("Spot " + i, true);
            service.addArea(PRO, named("Named " + i, member, spot));
            service.addArea(PRO, subRegion("Sub " + i, spot));
        }
        stats.clear();
        FavouritesResponse out = service.get(PRO);
        long many = stats.getPrepareStatementCount();

        assertThat(out.areas()).hasSize(22);
        assertThat(many).isEqualTo(two);
    }

    @Test
    @DisplayName("a disabled starred location appears in locationIds once it is enabled")
    void get_disabledPlaceReturnsWhenEnabled() {
        Long place = location("Slow Cove", false);
        service.addPlace(PRO, place);
        assertThat(service.get(PRO).locationIds()).isEmpty();

        jdbc.update("UPDATE locations SET enabled = TRUE WHERE id = ?", place);

        assertThat(service.get(PRO).locationIds()).containsExactly(place);
    }

    @Test
    @DisplayName("a favourite row whose area is gone (the local H2 schema has no keys) is dropped, not served")
    void get_dropsOrphanedArea() {
        Long place = location("Alone", true);
        Long area = named("Short Lived", place);
        service.addArea(PRO, area);
        jdbc.update("DELETE FROM area_location WHERE area_id = ?", area);
        jdbc.update("DELETE FROM area WHERE id = ?", area);

        FavouritesResponse out = service.get(PRO);

        assertThat(out.areas()).isEmpty();
        assertThat(out.locationIds()).isEmpty();
    }

    @Test
    @DisplayName("POST is idempotent: a second star changes nothing and keeps the first star's time")
    void addPlace_idempotent() {
        Long place = location("Bamburgh", true);
        service.addPlace(PRO, place);
        jdbc.update("UPDATE user_favourite SET created_at = ?", LocalDateTime.of(2018, 5, 6, 7, 8));

        FavouritesResponse out = service.addPlace(PRO, place);

        assertThat(out.places()).hasSize(1);
        assertThat(out.places().get(0).createdAt()).isEqualTo(LocalDateTime.of(2018, 5, 6, 7, 8));
    }

    @Test
    @DisplayName("a new star is stamped from the injected clock")
    void addPlace_stampsFromTheClock() {
        Long place = location("Bamburgh", true);

        FavouritesResponse out = service.addPlace(PRO, place);

        assertThat(out.places().get(0).createdAt()).isEqualTo(LocalDateTime.of(2019, 3, 4, 10, 15));
    }

    @Test
    @DisplayName("starring an area twice is idempotent")
    void addArea_idempotent() {
        Long area = named("Hadrian's Wall");
        service.addArea(PRO, area);

        assertThat(service.addArea(PRO, area).areas()).hasSize(1);
    }

    @Test
    @DisplayName("DELETE removes a star and is idempotent for one that is not starred")
    void remove_isIdempotent() {
        Long place = location("Bamburgh", true);
        Long area = named("Hadrian's Wall", place);
        service.addPlace(PRO, place);
        service.addArea(PRO, area);

        assertThat(service.removePlace(PRO, place).places()).isEmpty();
        assertThat(service.removePlace(PRO, place).places()).isEmpty();
        assertThat(service.removeArea(PRO, area).areas()).isEmpty();
        assertThat(service.removeArea(PRO, area).areas()).isEmpty();
    }

    @Test
    @DisplayName("an unknown location or area id is NoSuchElementException on every write, and writes nothing")
    void unknownIdsAreRefused() {
        Long place = location("Bamburgh", true);
        long missing = place + 1_000L;

        assertThatThrownBy(() -> service.addPlace(PRO, missing)).isInstanceOf(NoSuchElementException.class)
                .hasMessage("No location with id " + missing);
        assertThatThrownBy(() -> service.removePlace(PRO, missing)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> service.addArea(PRO, missing)).isInstanceOf(NoSuchElementException.class)
                .hasMessage("No area with id " + missing);
        assertThatThrownBy(() -> service.removeArea(PRO, missing)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> service.replace(PRO, request(List.of(place, missing), List.of())))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> service.replace(PRO, request(List.of(place), List.of(missing))))
                .isInstanceOf(NoSuchElementException.class);
        assertThat(service.get(PRO).places()).isEmpty();
    }

    @Test
    @DisplayName("PUT replaces the whole list: kept stars keep their time, dropped go, new arrive")
    void replace_diffs() {
        Long keep = location("Keep", true);
        Long drop = location("Drop", true);
        Long add = location("Add", true);
        Long keptArea = named("Kept Area", keep);
        Long droppedArea = named("Dropped Area", drop);
        Long addedArea = named("Added Area", add);
        service.replace(PRO, request(List.of(keep, drop), List.of(keptArea, droppedArea)));
        jdbc.update("UPDATE user_favourite SET created_at = ?", LocalDateTime.of(2018, 5, 6, 7, 8));

        FavouritesResponse out = service.replace(PRO, request(List.of(add, keep, keep), List.of(addedArea, keptArea)));

        assertThat(out.places()).extracting(FavouritesResponse.Place::locationId).containsExactlyInAnyOrder(keep, add);
        assertThat(out.areas()).extracting(FavouritesResponse.Area::areaId)
                .containsExactlyInAnyOrder(keptArea, addedArea);
        assertThat(out.places()).filteredOn(p -> p.locationId().equals(keep)).singleElement()
                .extracting(FavouritesResponse.Place::createdAt).isEqualTo(LocalDateTime.of(2018, 5, 6, 7, 8));
        assertThat(out.places()).filteredOn(p -> p.locationId().equals(add)).singleElement()
                .extracting(FavouritesResponse.Place::createdAt).isEqualTo(LocalDateTime.of(2019, 3, 4, 10, 15));

        assertThat(service.replace(PRO, request(null, null)).places()).isEmpty();
        assertThat(service.get(PRO).areas()).isEmpty();
    }

    @Test
    @DisplayName("PUT refuses a null id with IllegalArgumentException")
    void replace_nullId() {
        List<Long> withNull = new ArrayList<>();
        withNull.add(null);

        assertThatThrownBy(() -> service.replace(PRO, request(withNull, List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the cap is 25 places plus areas: 25 is allowed, a 26th new star is refused with the message")
    void cap_isTwentyFive() {
        List<Long> places = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            places.add(location("Spot " + i, true));
        }
        Long area = named("One Area");
        Long extra = location("Spot extra", true);
        service.replace(PRO, request(places, List.of()));

        service.addArea(PRO, area);
        assertThat(service.get(PRO).places().size() + service.get(PRO).areas().size()).isEqualTo(25);
        assertThatThrownBy(() -> service.addPlace(PRO, extra)).isInstanceOf(FavouritesLimitException.class)
                .hasMessage("You can keep up to 25 places.");
        assertThatThrownBy(() -> service.addArea(PRO, named("Another"))).isInstanceOf(FavouritesLimitException.class);
        // An existing star at the cap is still idempotent, and an un-star frees a place.
        assertThat(service.addArea(PRO, area).areas()).hasSize(1);
        service.removeArea(PRO, area);
        assertThat(service.addPlace(PRO, extra).places()).hasSize(25);
    }

    @Test
    @DisplayName("PUT of more than 25 distinct ids is refused and writes nothing; duplicates do not count twice")
    void replace_overCap() {
        List<Long> places = new ArrayList<>();
        for (int i = 0; i < 26; i++) {
            places.add(location("Spot " + i, true));
        }
        List<Long> exactly = new ArrayList<>(places.subList(0, 25));
        exactly.addAll(places.subList(0, 5));

        assertThatThrownBy(() -> service.replace(PRO, request(places, List.of())))
                .isInstanceOf(FavouritesLimitException.class).hasMessage("You can keep up to 25 places.");
        assertThat(service.get(PRO).places()).isEmpty();
        assertThat(service.replace(PRO, request(exactly, List.of())).places()).hasSize(25);
    }

    @Test
    @DisplayName("favourites are per reader: another reader's stars are invisible and do not count to the cap")
    void perReader() {
        user("other");
        Long place = location("Bamburgh", true);
        service.addPlace("other", place);

        assertThat(service.get(PRO).places()).isEmpty();
        assertThat(service.get("other").places()).hasSize(1);
    }

    @Test
    @DisplayName("a favourite on an area coexists with the same reader's sub-region pick row and leaves it alone")
    void independentOfSubRegionPicks() {
        Long place = location("Bamburgh", true);
        Long coast = subRegion("Northumberland Coast", place);
        Long userId = userIds.get(0);
        jdbc.update("INSERT INTO app_user_sub_region (user_id, area_id, created_at) VALUES (?, ?, ?)", userId,
                coast, LocalDateTime.of(2019, 1, 2, 3, 4));

        service.addArea(PRO, coast);
        service.removeArea(PRO, coast);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user_sub_region WHERE user_id = ?",
                Integer.class, userId)).isEqualTo(1);
        jdbc.update("DELETE FROM app_user_sub_region");
    }

    @Test
    @DisplayName("two simultaneous stars at 24 of 25 admit exactly one: the per-reader lock serialises them")
    void cap_holdsUnderConcurrency() throws Exception {
        List<Long> places = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            places.add(location("Spot " + i, true));
        }
        service.replace(PRO, request(places, List.of()));
        Long a = location("Racer A", true);
        Long b = location("Racer B", true);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (Long target : List.of(a, b)) {
                Callable<Boolean> star = () -> {
                    go.await();
                    try {
                        service.addPlace(PRO, target);
                        return true;
                    } catch (FavouritesLimitException e) {
                        return false;
                    }
                };
                results.add(pool.submit(star));
            }
            go.countDown();
            Set<Boolean> outcomes = new LinkedHashSet<>();
            int admitted = 0;
            for (Future<Boolean> result : results) {
                boolean ok = result.get();
                outcomes.add(ok);
                admitted += ok ? 1 : 0;
            }
            assertThat(admitted).isEqualTo(1);
            assertThat(outcomes).containsExactlyInAnyOrder(true, false);
        } finally {
            pool.shutdownNow();
        }
        assertThat(service.get(PRO).places()).hasSize(25);
    }
}
