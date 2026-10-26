package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.OutcomeRatingDto;
import com.gregochr.goldenhour.model.OutcomeRatingRequest;
import com.gregochr.goldenhour.model.SlotRatingsDto;
import com.gregochr.goldenhour.service.OutcomeRatingService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * REST controller for the per-user "how did it look?" rating of a past sunrise or sunset
 * ({@code docs/engineering/outcome-rating-plan.md} §2 #1).
 *
 * <p>None of these paths is ETag-revalidated: they are per-user data, and {@code HttpCachingConfig}'s
 * revalidatable set is an exact-path allow-list (pinned by {@code
 * HttpCachingConfigTest.personalDataPathsAreNeverFiltered}).
 */
@RestController
@RequestMapping("/api/outcome/ratings")
public class OutcomeRatingController {

    /**
     * Maximum span, in days and inclusive of both endpoints, of one {@link #getMyRatings} request. The
     * Map reads from its oldest known past day to today, which is at most five days.
     */
    static final int MAX_RANGE_DAYS = 31;

    private final OutcomeRatingService service;

    /**
     * Constructs the controller.
     *
     * @param service the rating service
     */
    public OutcomeRatingController(OutcomeRatingService service) {
        this.service = service;
    }

    /**
     * The caller's own ratings over a date range. No role gate: a LITE account has no rows, so it
     * answers an empty list, and an account since downgraded sees its old rows, harmlessly.
     *
     * @param from first event date (inclusive), ISO {@code yyyy-MM-dd}
     * @param to   last event date (inclusive)
     * @param auth the current authentication
     * @return the caller's ratings, oldest event first
     * @throws IllegalArgumentException if {@code from} is after {@code to}, or the range spans more than
     *         {@value #MAX_RANGE_DAYS} days
     */
    @GetMapping
    public List<OutcomeRatingDto> getMyRatings(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Authentication auth) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("'from' must not be after 'to'");
        }
        long spanDays = ChronoUnit.DAYS.between(from, to) + 1;
        if (spanDays > MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("Requested range spans " + spanDays
                    + " days; the maximum is " + MAX_RANGE_DAYS + " days");
        }
        return service.myRatings(auth.getName(), from, to);
    }

    /**
     * Records the caller's rating of a window that has already happened, replacing an earlier one.
     * PRO_USER and ADMIN only: a LITE account is refused server-side. PUT because the call is
     * idempotent and replaces.
     *
     * @param request the slot and rating; an absent or null {@code rating} means "didn't see it". The
     *                optional {@code seen} tag: absent keeps the earlier tag, an explicit null clears
     *                it, a tag sets it (400 for {@code NOT_SEEN} beside a star, or a sighting beside
     *                "didn't see it")
     * @param auth    the current authentication
     * @return the stored rating
     */
    @PutMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'PRO_USER')")
    public OutcomeRatingDto putRating(@RequestBody OutcomeRatingRequest request, Authentication auth) {
        return service.rate(auth.getName(), request.locationId(), request.eventDate(),
                request.eventType(), request.rating(), request.seen());
    }

    /**
     * Every user's rating of one window, newest first, with the forecast they were rated against.
     * ADMIN only.
     *
     * @param locationId the location
     * @param date       the event date
     * @param eventType  SUNRISE or SUNSET
     * @param auth       the current authentication (their own row is marked {@code mine})
     * @return the window's ratings
     */
    @GetMapping("/all")
    @PreAuthorize("hasRole('ADMIN')")
    public SlotRatingsDto getAllForSlot(
            @RequestParam Long locationId,
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam String eventType,
            Authentication auth) {
        return service.slotRatings(auth.getName(), locationId, date, parseEventType(eventType));
    }

    /**
     * Reads the {@code eventType} parameter as text so an unknown value is this endpoint's 400, in the
     * same words as the write's, rather than a type-mismatch the global handler would call a 500.
     */
    private static TargetType parseEventType(String eventType) {
        try {
            return TargetType.valueOf(eventType);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("eventType must be SUNRISE or SUNSET");
        }
    }
}
