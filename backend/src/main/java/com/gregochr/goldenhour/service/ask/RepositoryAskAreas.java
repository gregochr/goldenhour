package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.AreaEntity;
import com.gregochr.goldenhour.entity.LocationAliasEntity;
import com.gregochr.goldenhour.repository.AreaRepository;
import com.gregochr.goldenhour.repository.LocationAliasRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link AskAreas} over the area and location-alias tables. The entities' lazy collections are read and mapped
 * inside one read-only transaction, so nothing lazy escapes. A failure is logged and read as no names: Ask must
 * still answer.
 */
@Component
public class RepositoryAskAreas implements AskAreas {

    private static final Logger LOG = LoggerFactory.getLogger(RepositoryAskAreas.class);

    private final AreaRepository areaRepository;
    private final LocationAliasRepository aliasRepository;

    /**
     * Creates the reader.
     *
     * @param areaRepository  the named areas
     * @param aliasRepository the location aliases
     */
    public RepositoryAskAreas(AreaRepository areaRepository, LocationAliasRepository aliasRepository) {
        this.areaRepository = areaRepository;
        this.aliasRepository = aliasRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Names load() {
        try {
            List<AskSnapshot.Area> areas = new ArrayList<>();
            for (AreaEntity area : areaRepository.findAll()) {
                areas.add(new AskSnapshot.Area(area.getName(), AskAreas.sortAliases(area.getAliases()),
                        area.getLocationIds()));
            }
            areas.sort(Comparator.comparing(AskSnapshot.Area::name, String.CASE_INSENSITIVE_ORDER));
            Map<Long, List<String>> byLocation = new LinkedHashMap<>();
            for (LocationAliasEntity alias : aliasRepository.findAll()) {
                byLocation.computeIfAbsent(alias.getLocationId(), id -> new ArrayList<>()).add(alias.getAlias());
            }
            Map<Long, List<String>> sorted = new LinkedHashMap<>();
            byLocation.forEach((id, names) -> sorted.put(id, AskAreas.sortAliases(names)));
            return new Names(areas, sorted);
        } catch (RuntimeException e) {
            LOG.warn("[ASK] Could not read the named areas and aliases; answering without them: {}", e.toString());
            return Names.NONE;
        }
    }
}
