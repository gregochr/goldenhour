package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.OutcomeRatingEntity;
import com.gregochr.goldenhour.entity.SeenTag;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.OutcomeRatingDto;
import com.gregochr.goldenhour.model.SeenChoice;
import com.gregochr.goldenhour.model.SlotRatingRow;
import com.gregochr.goldenhour.model.SlotRatingsDto;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.OutcomeRatingRepository;
import com.gregochr.goldenhour.service.AsOfEventResolver.AsOfEvent;
import com.gregochr.goldenhour.service.OutcomeRatingStore.RatedRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link OutcomeRatingService} with the store, resolver, repositories and solar calculator mocked and
 * every instant exact: the order of the refusals, the afterglow and age boundaries, and that the
 * provenance the resolver answers is what reaches the store, column for column.
 */
@ExtendWith(MockitoExtension.class)
class OutcomeRatingServiceTest {

    /** 12:00 UTC on 15 Oct 2026 - BST, so the UK date and the UTC date agree. */
    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 15);
    /** Today's sunrise, 05:41 UTC (06:41 BST). */
    private static final LocalDateTime SUNRISE = LocalDateTime.of(2026, 10, 15, 5, 41);
    private static final Instant SUNRISE_INSTANT = SUNRISE.toInstant(ZoneOffset.UTC);
    private static final Instant RUN_AT = Instant.parse("2026-10-15T01:05:00Z");
    private static final long USER_ID = 5L;
    private static final long LOCATION_ID = 10L;

    @Mock
    private OutcomeRatingStore store;
    @Mock
    private OutcomeRatingRepository repository;
    @Mock
    private AppUserRepository userRepository;
    @Mock
    private LocationRepository locationRepository;
    @Mock
    private SolarEventFreshness solarEventFreshness;
    @Mock
    private AsOfEventResolver resolver;

    private final LocationEntity location = LocationEntity.builder().id(LOCATION_ID).name("Hartside")
            .lat(54.7).lon(-2.2).build();
    private OutcomeRatingService service;

    private static AppUserEntity user(long id, String name) {
        AppUserEntity user = new AppUserEntity();
        user.setId(id);
        user.setUsername(name);
        return user;
    }

    @BeforeEach
    void setUp() {
        service = serviceAt(NOW);
    }

    private OutcomeRatingService serviceAt(Instant now) {
        return new OutcomeRatingService(store, repository, userRepository, locationRepository,
                solarEventFreshness, resolver, Clock.fixed(now, ZoneOffset.UTC));
    }

    /** The user, the location and today's sunrise exist; the resolver answers {@code asOf}. */
    private void world(AsOfEvent asOf) {
        when(userRepository.findByUsername("pro")).thenReturn(Optional.of(user(USER_ID, "pro")));
        when(locationRepository.findById(LOCATION_ID)).thenReturn(Optional.of(location));
        when(solarEventFreshness.eventTime(location, DAY, TargetType.SUNRISE)).thenReturn(SUNRISE);
        when(resolver.resolve(location, DAY, TargetType.SUNRISE, SUNRISE_INSTANT)).thenReturn(asOf);
    }

    private static final AsOfEvent SERVED = new AsOfEvent(null, 99L, RUN_AT, 7L, 4, null);

    // ── the happy path ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("rate: every column reaches the store - the user's id, the slot, the star, THIS place's "
            + "event instant, the resolver's run, evaluation id and captured star, and the clock")
    void rate_writesEveryColumn() {
        world(SERVED);

        OutcomeRatingDto dto = service.rate("pro", LOCATION_ID, DAY, TargetType.SUNRISE, 4);

        verify(store).upsert(USER_ID, LOCATION_ID, DAY, TargetType.SUNRISE, 4, null, SUNRISE_INSTANT,
                new RatedRun(7L, RUN_AT, 99L, 4), NOW);
        assertThat(dto).isEqualTo(new OutcomeRatingDto(LOCATION_ID, "Hartside", DAY, TargetType.SUNRISE,
                4, NOW, RUN_AT, 7L, 4));
    }

    @Test
    @DisplayName("rate: 'didn't see it' (null) is stored as null with whatever the resolver found")
    void rate_notSeen() {
        world(AsOfEvent.NONE);

        OutcomeRatingDto dto = service.rate("pro", LOCATION_ID, DAY, TargetType.SUNRISE, null);

        verify(store).upsert(USER_ID, LOCATION_ID, DAY, TargetType.SUNRISE, null, null, SUNRISE_INSTANT,
                RatedRun.NONE, NOW);
        assertThat(dto.rating()).isNull();
        assertThat(dto.forecastRunAt()).isNull();
        assertThat(dto.pipelineRunId()).isNull();
        assertThat(dto.forecastRating()).isNull();
    }

    @Test
    @DisplayName("rate: changing one's mind keeps the provenance recorded the first time and does not "
            + "resolve again - a restart-degraded re-resolution must not overwrite a full record")
    void rate_reRateKeepsStoredProvenance() {
        when(userRepository.findByUsername("pro")).thenReturn(Optional.of(user(USER_ID, "pro")));
        when(locationRepository.findById(LOCATION_ID)).thenReturn(Optional.of(location));
        when(solarEventFreshness.eventTime(location, DAY, TargetType.SUNRISE)).thenReturn(SUNRISE);
        when(repository.findByUserIdAndLocationIdAndEventDateAndEventType(USER_ID, LOCATION_ID, DAY,
                TargetType.SUNRISE)).thenReturn(Optional.of(
                        OutcomeRatingEntity.builder().userId(USER_ID).locationId(LOCATION_ID).eventDate(DAY)
                                .eventType(TargetType.SUNRISE).rating(3).eventTime(SUNRISE_INSTANT)
                                .pipelineRunId(7L).forecastRunAt(RUN_AT).forecastEvaluationId(99L)
                                .forecastRating(4).createdAt(NOW).updatedAt(NOW).build()));

        OutcomeRatingDto dto = service.rate("pro", LOCATION_ID, DAY, TargetType.SUNRISE, 2);

        verify(store).upsert(USER_ID, LOCATION_ID, DAY, TargetType.SUNRISE, 2, null, SUNRISE_INSTANT,
                new RatedRun(7L, RUN_AT, 99L, 4), NOW);
        verifyNoInteractions(resolver);
        assertThat(dto.forecastRating()).isEqualTo(4);
        assertThat(dto.pipelineRunId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("rate: an earlier row recorded with no forecast at all is resolved afresh")
    void rate_reRateOfAProvenanceFreeRowResolvesAgain() {
        world(SERVED);
        when(repository.findByUserIdAndLocationIdAndEventDateAndEventType(USER_ID, LOCATION_ID, DAY,
                TargetType.SUNRISE)).thenReturn(Optional.of(
                        OutcomeRatingEntity.builder().userId(USER_ID).locationId(LOCATION_ID).eventDate(DAY)
                                .eventType(TargetType.SUNRISE).rating(3).eventTime(SUNRISE_INSTANT)
                                .createdAt(NOW).updatedAt(NOW).build()));

        service.rate("pro", LOCATION_ID, DAY, TargetType.SUNRISE, 2);

        verify(store).upsert(USER_ID, LOCATION_ID, DAY, TargetType.SUNRISE, 2, null, SUNRISE_INSTANT,
                new RatedRun(7L, RUN_AT, 99L, 4), NOW);
    }

    @Test
    @DisplayName("rate: 1 and 5 are accepted")
    void rate_boundaryRatingsAccepted() {
        world(SERVED);

        service.rate("pro", LOCATION_ID, DAY, TargetType.SUNRISE, 1);
        service.rate("pro", LOCATION_ID, DAY, TargetType.SUNRISE, 5);

        verify(store, times(2)).upsert(anyLong(), anyLong(), any(), any(), any(), any(), any(), any(),
                any());
    }

    // ── refusals, in order ──────────────────────────────────────────────────────

    @Test
    @DisplayName("rate: 0 and 6 are refused with the plan's sentence, before any lookup")
    void rate_ratingOutOfRange() {
        for (int bad : new int[] {0, 6, -1, 100}) {
            assertThatThrownBy(() -> service.rate("pro", LOCATION_ID, DAY, TargetType.SUNRISE, bad))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("rating must be between 1 and 5");
        }
        verifyNoInteractions(userRepository, locationRepository, store, resolver);
    }

    @Test
    @DisplayName("rate: HOURLY, a missing type, location or date are 400s before any lookup")
    void rate_badSlot() {
        assertThatThrownBy(() -> service.rate("pro", LOCATION_ID, DAY, TargetType.HOURLY, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("eventType must be SUNRISE or SUNSET");
        assertThatThrownBy(() -> service.rate("pro", LOCATION_ID, DAY, null, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("eventType must be SUNRISE or SUNSET");
        assertThatThrownBy(() -> service.rate("pro", null, DAY, TargetType.SUNRISE, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("locationId and eventDate are required");
        assertThatThrownBy(() -> service.rate("pro", LOCATION_ID, null, TargetType.SUNRISE, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("locationId and eventDate are required");
        verifyNoInteractions(userRepository, locationRepository, store, resolver);
    }

    @Test
    @DisplayName("rate: an unknown user is a 404 and nothing is written")
    void rate_unknownUser() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rate("ghost", LOCATION_ID, DAY, TargetType.SUNRISE, 3))
                .isInstanceOf(NoSuchElementException.class).hasMessage("User not found");
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("rate: an unknown location is a 404 and nothing is written")
    void rate_unknownLocation() {
        when(userRepository.findByUsername("pro")).thenReturn(Optional.of(user(USER_ID, "pro")));
        when(locationRepository.findById(LOCATION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rate("pro", LOCATION_ID, DAY, TargetType.SUNRISE, 3))
                .isInstanceOf(NoSuchElementException.class).hasMessage("Location not found: 10");
        verifyNoInteractions(store, resolver);
    }

    @Test
    @DisplayName("rate: the afterglow boundary - event + 30 min == now is NOT passed (hasPassed is strict), "
            + "one nanosecond later is")
    void rate_afterglowBoundary() {
        Instant boundary = SUNRISE_INSTANT.plusSeconds(30 * 60);
        OutcomeRatingService atBoundary = serviceAt(boundary);
        world(SERVED);

        assertThatThrownBy(() -> atBoundary.rate("pro", LOCATION_ID, DAY, TargetType.SUNRISE, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("That sunrise has not happened yet");
        verifyNoInteractions(store);

        serviceAt(boundary.plusNanos(1)).rate("pro", LOCATION_ID, DAY, TargetType.SUNRISE, 3);
        verify(store).upsert(USER_ID, LOCATION_ID, DAY, TargetType.SUNRISE, 3, null, SUNRISE_INSTANT,
                new RatedRun(7L, RUN_AT, 99L, 4), boundary.plusNanos(1));
    }

    @Test
    @DisplayName("rate: a sunset that has not happened says 'sunset', and a place whose event time cannot be "
            + "computed is not 'passed'")
    void rate_notPassedWording() {
        when(userRepository.findByUsername("pro")).thenReturn(Optional.of(user(USER_ID, "pro")));
        when(locationRepository.findById(LOCATION_ID)).thenReturn(Optional.of(location));
        when(solarEventFreshness.eventTime(location, DAY, TargetType.SUNSET))
                .thenReturn(LocalDateTime.of(2026, 10, 15, 16, 30));

        assertThatThrownBy(() -> service.rate("pro", LOCATION_ID, DAY, TargetType.SUNSET, 3))
                .hasMessage("That sunset has not happened yet");

        when(solarEventFreshness.eventTime(location, DAY, TargetType.SUNRISE)).thenReturn(null);
        assertThatThrownBy(() -> service.rate("pro", LOCATION_ID, DAY, TargetType.SUNRISE, 3))
                .hasMessage("That sunrise has not happened yet");
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("rate: MAX_RATING_AGE_DAYS is 30 - 5 - 1 = 24; the boundary day is accepted, one older is refused")
    void rate_ageBound() {
        assertThat(OutcomeRatingService.MAX_RATING_AGE_DAYS).isEqualTo(24);
        LocalDate oldest = DAY.minusDays(24);
        LocalDate tooOld = DAY.minusDays(25);
        when(userRepository.findByUsername("pro")).thenReturn(Optional.of(user(USER_ID, "pro")));
        when(locationRepository.findById(LOCATION_ID)).thenReturn(Optional.of(location));
        when(solarEventFreshness.eventTime(location, oldest, TargetType.SUNRISE))
                .thenReturn(oldest.atTime(5, 41));
        when(solarEventFreshness.eventTime(location, tooOld, TargetType.SUNRISE))
                .thenReturn(tooOld.atTime(5, 41));
        when(resolver.resolve(any(), any(), any(), any())).thenReturn(AsOfEvent.NONE);

        service.rate("pro", LOCATION_ID, oldest, TargetType.SUNRISE, 3);
        verify(store).upsert(USER_ID, LOCATION_ID, oldest, TargetType.SUNRISE, 3, null,
                oldest.atTime(5, 41).toInstant(ZoneOffset.UTC), RatedRun.NONE, NOW);

        assertThatThrownBy(() -> service.rate("pro", LOCATION_ID, tooOld, TargetType.SUNRISE, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("That sunrise is too old to rate");
        verify(store, times(1)).upsert(anyLong(), anyLong(), any(), any(), any(), any(), any(), any(),
                any());
    }

    @Test
    @DisplayName("rate: the age bound counts UK days - at 23:30 UTC in BST it is already tomorrow's date")
    void rate_ageBoundUsesTheUkDate() {
        Instant lateUtc = Instant.parse("2026-10-14T23:30:00Z");
        OutcomeRatingService late = serviceAt(lateUtc);
        LocalDate ukToday = LocalDate.of(2026, 10, 15);
        LocalDate ok = ukToday.minusDays(24);
        LocalDate tooOld = ukToday.minusDays(25);
        when(userRepository.findByUsername("pro")).thenReturn(Optional.of(user(USER_ID, "pro")));
        when(locationRepository.findById(LOCATION_ID)).thenReturn(Optional.of(location));
        when(solarEventFreshness.eventTime(location, ok, TargetType.SUNRISE)).thenReturn(ok.atTime(5, 41));
        when(solarEventFreshness.eventTime(location, tooOld, TargetType.SUNRISE))
                .thenReturn(tooOld.atTime(5, 41));
        when(resolver.resolve(any(), any(), any(), any())).thenReturn(AsOfEvent.NONE);

        late.rate("pro", LOCATION_ID, ok, TargetType.SUNRISE, 3);
        assertThatThrownBy(() -> late.rate("pro", LOCATION_ID, tooOld, TargetType.SUNRISE, 3))
                .hasMessage("That sunrise is too old to rate");
    }

    // ── the caller's own range ──────────────────────────────────────────────────

    private static OutcomeRatingEntity entity(long locationId, LocalDate date, TargetType type, Integer rating) {
        return OutcomeRatingEntity.builder().userId(USER_ID).locationId(locationId).eventDate(date)
                .eventType(type).rating(rating).eventTime(SUNRISE_INSTANT).forecastRunAt(RUN_AT)
                .pipelineRunId(7L).forecastRating(3).updatedAt(NOW).createdAt(NOW).build();
    }

    @Test
    @DisplayName("myRatings: location names are joined with ONE findAllById over the distinct ids")
    void myRatings_joinsNamesOnce() {
        LocalDate from = DAY.minusDays(2);
        when(userRepository.findByUsername("pro")).thenReturn(Optional.of(user(USER_ID, "pro")));
        when(repository.findByUserIdAndEventDateBetweenOrderByEventDateAscIdAsc(USER_ID, from, DAY))
                .thenReturn(List.of(entity(10L, from, TargetType.SUNRISE, 4),
                        entity(10L, DAY, TargetType.SUNSET, null), entity(11L, DAY, TargetType.SUNRISE, 2)));
        LocationEntity other = LocationEntity.builder().id(11L).name("Gibbet Hill").lat(1).lon(1).build();
        when(locationRepository.findAllById(Set.of(10L, 11L))).thenReturn(List.of(location, other));

        List<OutcomeRatingDto> out = service.myRatings("pro", from, DAY);

        assertThat(out).extracting(OutcomeRatingDto::locationName, OutcomeRatingDto::eventType,
                OutcomeRatingDto::rating).containsExactly(
                org.assertj.core.api.Assertions.tuple("Hartside", TargetType.SUNRISE, 4),
                org.assertj.core.api.Assertions.tuple("Hartside", TargetType.SUNSET, null),
                org.assertj.core.api.Assertions.tuple("Gibbet Hill", TargetType.SUNRISE, 2));
        assertThat(out.getFirst().forecastRunAt()).isEqualTo(RUN_AT);
        assertThat(out.getFirst().pipelineRunId()).isEqualTo(7L);
        assertThat(out.getFirst().forecastRating()).isEqualTo(3);
        assertThat(out.getFirst().updatedAt()).isEqualTo(NOW);
        verify(locationRepository, times(1)).findAllById(any());
    }

    @Test
    @DisplayName("myRatings: an account that has rated nothing answers [] and never looks up a location")
    void myRatings_empty() {
        when(userRepository.findByUsername("lite")).thenReturn(Optional.of(user(6L, "lite")));
        when(repository.findByUserIdAndEventDateBetweenOrderByEventDateAscIdAsc(6L, DAY, DAY))
                .thenReturn(List.of());

        assertThat(service.myRatings("lite", DAY, DAY)).isEmpty();
        verifyNoInteractions(locationRepository);
    }

    @Test
    @DisplayName("myRatings: an unknown user is a 404")
    void myRatings_unknownUser() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.myRatings("ghost", DAY, DAY))
                .isInstanceOf(NoSuchElementException.class);
    }

    // ── the admin's all-ratings read ────────────────────────────────────────────

    @Test
    @DisplayName("slotRatings: the caller's row is marked mine, order is as served, and the head is the "
            + "newest row's stored event time and run")
    void slotRatings_withRows() {
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user(1L, "admin")));
        when(locationRepository.findById(LOCATION_ID)).thenReturn(Optional.of(location));
        Instant later = NOW.minusSeconds(60);
        when(repository.findAllForSlot(LOCATION_ID, DAY, TargetType.SUNRISE)).thenReturn(List.of(
                new SlotRatingRow(USER_ID, "pro", null, NOW, SUNRISE_INSTANT, RUN_AT, 7L, null),
                new SlotRatingRow(1L, "admin", 4, later, SUNRISE_INSTANT.minusSeconds(5), RUN_AT.minusSeconds(5),
                        6L, null)));

        SlotRatingsDto out = service.slotRatings("admin", LOCATION_ID, DAY, TargetType.SUNRISE);

        assertThat(out.ratings()).containsExactly(
                new SlotRatingsDto.Row("pro", null, NOW, false),
                new SlotRatingsDto.Row("admin", 4, later, true));
        assertThat(out.eventTime()).isEqualTo(SUNRISE_INSTANT);
        assertThat(out.forecastRunAt()).isEqualTo(RUN_AT);
        assertThat(out.pipelineRunId()).isEqualTo(7L);
        verifyNoInteractions(resolver, solarEventFreshness);
    }

    @Test
    @DisplayName("slotRatings: a window nobody has rated still carries its run, resolved afresh")
    void slotRatings_noRowsStillCarriesTheRun() {
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user(1L, "admin")));
        when(locationRepository.findById(LOCATION_ID)).thenReturn(Optional.of(location));
        when(repository.findAllForSlot(LOCATION_ID, DAY, TargetType.SUNRISE)).thenReturn(List.of());
        when(solarEventFreshness.eventTime(location, DAY, TargetType.SUNRISE)).thenReturn(SUNRISE);
        when(resolver.resolve(location, DAY, TargetType.SUNRISE, SUNRISE_INSTANT)).thenReturn(SERVED);

        SlotRatingsDto out = service.slotRatings("admin", LOCATION_ID, DAY, TargetType.SUNRISE);

        assertThat(out.ratings()).isEmpty();
        assertThat(out.eventTime()).isEqualTo(SUNRISE_INSTANT);
        assertThat(out.forecastRunAt()).isEqualTo(RUN_AT);
        assertThat(out.pipelineRunId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("slotRatings: a place whose event time cannot be computed has a null head, not an error")
    void slotRatings_noSolarTime() {
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user(1L, "admin")));
        when(locationRepository.findById(LOCATION_ID)).thenReturn(Optional.of(location));
        when(repository.findAllForSlot(LOCATION_ID, DAY, TargetType.SUNSET)).thenReturn(List.of());
        when(solarEventFreshness.eventTime(location, DAY, TargetType.SUNSET)).thenReturn(null);
        when(resolver.resolve(location, DAY, TargetType.SUNSET, null)).thenReturn(AsOfEvent.NONE);

        SlotRatingsDto out = service.slotRatings("admin", LOCATION_ID, DAY, TargetType.SUNSET);

        assertThat(out.eventTime()).isNull();
        assertThat(out.forecastRunAt()).isNull();
    }

    @Test
    @DisplayName("slotRatings: bad slot is 400, unknown caller or location is 404")
    void slotRatings_refusals() {
        assertThatThrownBy(() -> service.slotRatings("admin", LOCATION_ID, DAY, TargetType.HOURLY))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(userRepository);

        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.slotRatings("ghost", LOCATION_ID, DAY, TargetType.SUNRISE))
                .isInstanceOf(NoSuchElementException.class);

        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user(1L, "admin")));
        when(locationRepository.findById(LOCATION_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.slotRatings("admin", LOCATION_ID, DAY, TargetType.SUNRISE))
                .isInstanceOf(NoSuchElementException.class);
        verify(repository, never()).findAllForSlot(any(), any(), any());
    }

    // ── the seen tag (valley-mist-plan M3) ──────────────────────────────────────

    /** The user, location and sunrise exist and the reader already has {@code earlier} for the slot. */
    private void worldWithEarlier(Integer rating, SeenTag seen) {
        when(userRepository.findByUsername("pro")).thenReturn(Optional.of(user(USER_ID, "pro")));
        when(locationRepository.findById(LOCATION_ID)).thenReturn(Optional.of(location));
        when(solarEventFreshness.eventTime(location, DAY, TargetType.SUNRISE)).thenReturn(SUNRISE);
        when(repository.findByUserIdAndLocationIdAndEventDateAndEventType(USER_ID, LOCATION_ID, DAY,
                TargetType.SUNRISE)).thenReturn(Optional.of(
                        OutcomeRatingEntity.builder().userId(USER_ID).locationId(LOCATION_ID).eventDate(DAY)
                                .eventType(TargetType.SUNRISE).rating(rating).seen(seen)
                                .eventTime(SUNRISE_INSTANT).pipelineRunId(7L).forecastRunAt(RUN_AT)
                                .forecastEvaluationId(99L).forecastRating(4).createdAt(NOW).updatedAt(NOW)
                                .build()));
    }

    private static final RatedRun KEPT_RUN = new RatedRun(7L, RUN_AT, 99L, 4);

    @Test
    @DisplayName("rate: a tag reaches the store and the response, independent of the star")
    void rate_seenTagIsStoredAndReturned() {
        world(SERVED);

        OutcomeRatingDto dto = service.rate("pro", LOCATION_ID, DAY, TargetType.SUNRISE, 4,
                new SeenChoice(SeenTag.MIST));

        verify(store).upsert(USER_ID, LOCATION_ID, DAY, TargetType.SUNRISE, 4, SeenTag.MIST,
                SUNRISE_INSTANT, KEPT_RUN, NOW);
        assertThat(dto.seen()).isEqualTo(SeenTag.MIST);
        assertThat(dto.rating()).isEqualTo(4);
    }

    @Test
    @DisplayName("rate: a re-rate that does not mention seen KEEPS the earlier tag")
    void rate_absentSeenKeepsTheEarlierTag() {
        worldWithEarlier(3, SeenTag.MIST);

        OutcomeRatingDto dto = service.rate("pro", LOCATION_ID, DAY, TargetType.SUNRISE, 5);

        verify(store).upsert(USER_ID, LOCATION_ID, DAY, TargetType.SUNRISE, 5, SeenTag.MIST,
                SUNRISE_INSTANT, KEPT_RUN, NOW);
        assertThat(dto.seen()).isEqualTo(SeenTag.MIST);
    }

    @Test
    @DisplayName("rate: an explicit null seen CLEARS the earlier tag")
    void rate_explicitNullSeenClears() {
        worldWithEarlier(3, SeenTag.MIST);

        OutcomeRatingDto dto = service.rate("pro", LOCATION_ID, DAY, TargetType.SUNRISE, 3,
                SeenChoice.CLEAR);

        verify(store).upsert(USER_ID, LOCATION_ID, DAY, TargetType.SUNRISE, 3, null, SUNRISE_INSTANT,
                KEPT_RUN, NOW);
        assertThat(dto.seen()).isNull();
    }

    @Test
    @DisplayName("rate: a new tag replaces the earlier one")
    void rate_newTagReplaces() {
        worldWithEarlier(3, SeenTag.MIST);

        service.rate("pro", LOCATION_ID, DAY, TargetType.SUNRISE, 3, new SeenChoice(SeenTag.FLAT));

        verify(store).upsert(USER_ID, LOCATION_ID, DAY, TargetType.SUNRISE, 3, SeenTag.FLAT,
                SUNRISE_INSTANT, KEPT_RUN, NOW);
    }

    @Test
    @DisplayName("rate: NOT_SEEN beside a star is a 400, before any lookup")
    void rate_notSeenTagWithAStarIsRefused() {
        assertThatThrownBy(() -> service.rate("pro", LOCATION_ID, DAY, TargetType.SUNRISE, 3,
                new SeenChoice(SeenTag.NOT_SEEN)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("seen NOT_SEEN goes with \"didn't see it\", not a star rating");

        verifyNoInteractions(userRepository, locationRepository, store);
    }

    @Test
    @DisplayName("rate: a sighting tag beside 'didn't see it' (no star) is a 400, before any lookup")
    void rate_sightingTagWithNoStarIsRefused() {
        for (SeenTag tag : List.of(SeenTag.COLOUR, SeenTag.MIST, SeenTag.INVERSION, SeenTag.FLAT)) {
            assertThatThrownBy(() -> service.rate("pro", LOCATION_ID, DAY, TargetType.SUNRISE, null,
                    new SeenChoice(tag)))
                    .as("tag %s", tag)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("a \"didn't see it\" rating can only carry seen NOT_SEEN");
        }

        verifyNoInteractions(userRepository, locationRepository, store);
    }

    @Test
    @DisplayName("rate: 'didn't see it' may carry NOT_SEEN, a clear, or nothing")
    void rate_notSeenRecordMayCarryNotSeenOrNothing() {
        world(AsOfEvent.NONE);

        OutcomeRatingDto tagged = service.rate("pro", LOCATION_ID, DAY, TargetType.SUNRISE, null,
                new SeenChoice(SeenTag.NOT_SEEN));
        service.rate("pro", LOCATION_ID, DAY, TargetType.SUNRISE, null, SeenChoice.CLEAR);
        service.rate("pro", LOCATION_ID, DAY, TargetType.SUNRISE, null);

        assertThat(tagged.seen()).isEqualTo(SeenTag.NOT_SEEN);
        verify(store).upsert(USER_ID, LOCATION_ID, DAY, TargetType.SUNRISE, null, SeenTag.NOT_SEEN,
                SUNRISE_INSTANT, RatedRun.NONE, NOW);
        verify(store, times(2)).upsert(USER_ID, LOCATION_ID, DAY, TargetType.SUNRISE, null, null,
                SUNRISE_INSTANT, RatedRun.NONE, NOW);
    }

    @Test
    @DisplayName("rate: an absent seen keeps an earlier tag only while it can stand beside the new rating")
    void rate_absentSeenDropsATagTheNewRatingContradicts() {
        // 'didn't see it' (NOT_SEEN) replaced by a star: NOT_SEEN cannot stand beside a star.
        worldWithEarlier(null, SeenTag.NOT_SEEN);
        service.rate("pro", LOCATION_ID, DAY, TargetType.SUNRISE, 4);
        verify(store).upsert(USER_ID, LOCATION_ID, DAY, TargetType.SUNRISE, 4, null, SUNRISE_INSTANT,
                KEPT_RUN, NOW);
    }

    @Test
    @DisplayName("rate: an earlier NOT_SEEN stays beside a new 'didn't see it' when none is sent")
    void rate_absentSeenKeepsNotSeenBesideNoStar() {
        worldWithEarlier(null, SeenTag.NOT_SEEN);
        service.rate("pro", LOCATION_ID, DAY, TargetType.SUNRISE, null);
        verify(store).upsert(USER_ID, LOCATION_ID, DAY, TargetType.SUNRISE, null, SeenTag.NOT_SEEN,
                SUNRISE_INSTANT, KEPT_RUN, NOW);
    }

    @Test
    @DisplayName("rate: a star replaced by 'didn't see it' drops the sighting tag it carried")
    void rate_absentSeenDropsASightingWhenTheStarGoes() {
        worldWithEarlier(4, SeenTag.COLOUR);
        service.rate("pro", LOCATION_ID, DAY, TargetType.SUNRISE, null);
        verify(store).upsert(USER_ID, LOCATION_ID, DAY, TargetType.SUNRISE, null, null, SUNRISE_INSTANT,
                KEPT_RUN, NOW);
    }

    @Test
    @DisplayName("myRatings and slotRatings serve the tag")
    void readsServeTheTag() {
        when(userRepository.findByUsername("pro")).thenReturn(Optional.of(user(USER_ID, "pro")));
        when(repository.findByUserIdAndEventDateBetweenOrderByEventDateAscIdAsc(USER_ID, DAY, DAY))
                .thenReturn(List.of(
                        OutcomeRatingEntity.builder().userId(USER_ID).locationId(LOCATION_ID).eventDate(DAY)
                                .eventType(TargetType.SUNRISE).rating(4).seen(SeenTag.INVERSION)
                                .eventTime(SUNRISE_INSTANT).createdAt(NOW).updatedAt(NOW).build()));
        when(locationRepository.findAllById(Set.of(LOCATION_ID))).thenReturn(List.of(location));

        assertThat(service.myRatings("pro", DAY, DAY)).extracting(OutcomeRatingDto::seen)
                .containsExactly(SeenTag.INVERSION);

        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user(1L, "admin")));
        when(locationRepository.findById(LOCATION_ID)).thenReturn(Optional.of(location));
        when(repository.findAllForSlot(LOCATION_ID, DAY, TargetType.SUNRISE)).thenReturn(List.of(
                new SlotRatingRow(USER_ID, "pro", 4, NOW, SUNRISE_INSTANT, RUN_AT, 7L, SeenTag.MIST),
                new SlotRatingRow(1L, "admin", 3, NOW, SUNRISE_INSTANT, RUN_AT, 7L, null)));

        SlotRatingsDto all = service.slotRatings("admin", LOCATION_ID, DAY, TargetType.SUNRISE);

        assertThat(all.ratings()).extracting(SlotRatingsDto.Row::seen)
                .containsExactly(SeenTag.MIST, null);
    }
}
