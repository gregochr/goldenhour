package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.FavouritesRequest;
import com.gregochr.goldenhour.model.FavouritesResponse;
import com.gregochr.goldenhour.service.UserFavouritesService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own favourites (find-and-favourites-plan F3): starred places and areas, served merged.
 *
 * <p>Pro and Admin only; a Lite account gets 403. Under {@code /api/user/settings}, which
 * {@code HttpCachingConfig} never filters: the body is the caller's own list, and ETag revalidation would
 * persist it to a browser HTTP cache JavaScript cannot evict on logout. Every rule lives in
 * {@link UserFavouritesService}; nothing here but routing.
 *
 * <p>Every endpoint answers with the new merged list. POST and DELETE are idempotent; an unknown location
 * or area id is 404; a new star over the cap of 25 is 409 {@code You can keep up to 25 places.}.
 */
@RestController
@RequestMapping("/api/user/settings/favourites")
@PreAuthorize("hasAnyRole('PRO_USER','ADMIN')")
public class UserFavouritesController {

    private final UserFavouritesService favouritesService;

    /**
     * Constructs the controller.
     *
     * @param favouritesService the favourites service
     */
    public UserFavouritesController(UserFavouritesService favouritesService) {
        this.favouritesService = favouritesService;
    }

    /**
     * The caller's favourites: places, areas, and the merged enabled location ids.
     *
     * @param auth the current authentication context
     * @return the list
     */
    @GetMapping
    public FavouritesResponse getFavourites(Authentication auth) {
        return favouritesService.get(auth.getName());
    }

    /**
     * Replaces the caller's whole list.
     *
     * @param request the location and area ids to hold
     * @param auth    the current authentication context
     * @return the new list
     */
    @PutMapping
    public FavouritesResponse putFavourites(@RequestBody FavouritesRequest request, Authentication auth) {
        return favouritesService.replace(auth.getName(), request);
    }

    /**
     * Stars one place.
     *
     * @param locationId the location
     * @param auth       the current authentication context
     * @return the new list
     */
    @PostMapping("/places/{locationId}")
    public FavouritesResponse addPlace(@PathVariable Long locationId, Authentication auth) {
        return favouritesService.addPlace(auth.getName(), locationId);
    }

    /**
     * Un-stars one place.
     *
     * @param locationId the location
     * @param auth       the current authentication context
     * @return the new list
     */
    @DeleteMapping("/places/{locationId}")
    public FavouritesResponse removePlace(@PathVariable Long locationId, Authentication auth) {
        return favouritesService.removePlace(auth.getName(), locationId);
    }

    /**
     * Stars one area (a named area or a sub-region).
     *
     * @param areaId the area
     * @param auth   the current authentication context
     * @return the new list
     */
    @PostMapping("/areas/{areaId}")
    public FavouritesResponse addArea(@PathVariable Long areaId, Authentication auth) {
        return favouritesService.addArea(auth.getName(), areaId);
    }

    /**
     * Un-stars one area.
     *
     * @param areaId the area
     * @param auth   the current authentication context
     * @return the new list
     */
    @DeleteMapping("/areas/{areaId}")
    public FavouritesResponse removeArea(@PathVariable Long areaId, Authentication auth) {
        return favouritesService.removeArea(auth.getName(), areaId);
    }
}
