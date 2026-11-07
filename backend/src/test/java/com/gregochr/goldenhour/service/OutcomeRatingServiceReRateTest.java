package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.AppBuild;
import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.SeenTag;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.OutcomeRatingDto;
import com.gregochr.goldenhour.model.SeenChoice;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.OutcomeRatingRepository;
import com.gregochr.goldenhour.service.AsOfEventResolver.AsOfEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link OutcomeRatingService#rate} over the REAL repository and store on H2, inside one persistence
 * context (a {@code @DataJpaTest} transaction stands in for the request's open-in-view session). The
 * service reads the earlier row, the store runs a column-scoped bulk UPDATE that bypasses the
 * context, and the service re-reads: without the update clearing the context that re-read returns the
 * stale managed copy, so a re-rate answered with the PREVIOUS star.
 */
@DataJpaTest
class OutcomeRatingServiceReRateTest {

    /** 12:00 UTC on 15 Oct 2026 - well away from today's date. */
    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 15);
    private static final LocalDateTime SUNRISE = LocalDateTime.of(2026, 10, 15, 5, 41);
    private static final long LOCATION_ID = 10L;

    @Autowired
    private OutcomeRatingRepository repository;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private TestEntityManager entityManager;

    private OutcomeRatingService service;

    @BeforeEach
    void setUp() {
        users.save(AppUserEntity.builder().username("pro").email("pro@example.com")
                .password("{bcrypt}hash").role(UserRole.PRO_USER).enabled(true)
                .createdAt(LocalDateTime.of(2026, 1, 1, 12, 0)).build());
        LocationEntity location = LocationEntity.builder().id(LOCATION_ID).name("Hartside")
                .lat(54.7).lon(-2.2).build();
        LocationRepository locations = mock(LocationRepository.class);
        when(locations.findById(LOCATION_ID)).thenReturn(Optional.of(location));
        SolarEventFreshness solar = mock(SolarEventFreshness.class);
        when(solar.eventTime(location, DAY, TargetType.SUNRISE)).thenReturn(SUNRISE);
        AsOfEventResolver resolver = mock(AsOfEventResolver.class);
        when(resolver.resolve(location, DAY, TargetType.SUNRISE, SUNRISE.toInstant(ZoneOffset.UTC)))
                .thenReturn(AsOfEvent.NONE);
        service = new OutcomeRatingService(new OutcomeRatingStore(repository, new AppBuild("v2.29.0")),
                repository, users, locations, solar, resolver, Clock.fixed(NOW, ZoneOffset.UTC));
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("re-rating returns the NEW star, not the managed copy read before the bulk update")
    void reRate_returnsNewStar() {
        service.rate("pro", LOCATION_ID, DAY, TargetType.SUNRISE, 1);

        OutcomeRatingDto second = service.rate("pro", LOCATION_ID, DAY, TargetType.SUNRISE, 4);
        OutcomeRatingDto third = service.rate("pro", LOCATION_ID, DAY, TargetType.SUNRISE, 2);

        assertThat(second.rating()).isEqualTo(4);
        assertThat(third.rating()).isEqualTo(2);
    }

    @Test
    @DisplayName("re-tagging returns the NEW seen tag, and 'didn't see it' returns its own answer")
    void reTag_returnsNewTag() {
        service.rate("pro", LOCATION_ID, DAY, TargetType.SUNRISE, 3, new SeenChoice(SeenTag.COLOUR));

        OutcomeRatingDto retagged = service.rate("pro", LOCATION_ID, DAY, TargetType.SUNRISE, 3,
                new SeenChoice(SeenTag.MIST));
        OutcomeRatingDto notSeen = service.rate("pro", LOCATION_ID, DAY, TargetType.SUNRISE, null,
                new SeenChoice(SeenTag.NOT_SEEN));

        assertThat(retagged.seen()).isEqualTo(SeenTag.MIST);
        assertThat(retagged.rating()).isEqualTo(3);
        assertThat(notSeen.rating()).isNull();
        assertThat(notSeen.seen()).isEqualTo(SeenTag.NOT_SEEN);
    }
}
