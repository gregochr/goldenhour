package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * {@link AskLocationNames} over the enabled locations in the database: one query each time it is
 * asked ({@link AskLocationNames#memoised} makes that once per conversation).
 */
@Component
class RepositoryAskLocationNames implements AskLocationNames {

    private final LocationRepository locations;

    /**
     * Creates the lookup.
     *
     * @param locations the location repository
     */
    RepositoryAskLocationNames(LocationRepository locations) {
        this.locations = locations;
    }

    @Override
    public Map<Long, Place> byId() {
        Map<Long, Place> places = new HashMap<>();
        for (LocationEntity location : locations.findAllByEnabledTrueOrderByNameAsc()) {
            if (location.getId() != null && location.getName() != null) {
                places.put(location.getId(),
                        new Place(location.getName(), location.getLat(), location.getLon()));
            }
        }
        return places;
    }
}
