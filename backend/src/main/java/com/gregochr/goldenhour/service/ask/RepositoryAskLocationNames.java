package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/** {@link AskLocationNames} over the enabled locations in the database: one query when asked. */
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
    public Map<Long, String> byId() {
        Map<Long, String> names = new HashMap<>();
        for (LocationEntity location : locations.findAllByEnabledTrueOrderByNameAsc()) {
            if (location.getId() != null && location.getName() != null) {
                names.put(location.getId(), location.getName());
            }
        }
        return names;
    }
}
