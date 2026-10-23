package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.AreaEntity;
import com.gregochr.goldenhour.entity.LocationAliasEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.repository.AreaRepository;
import com.gregochr.goldenhour.repository.LocationAliasRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.util.PlaceNames;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The one namespace of names Ask understands: location names, location aliases, area names and area aliases
 * (ask-named-areas-plan §4.14). Every comparison is on {@link PlaceNames#words}, so "Hadrians Wall" equals
 * "Hadrian's Wall".
 *
 * <p>Two kinds of rule keep Ask's lookups predictable: no two names are equal, and no area name or alias is
 * hidden inside a place's name (or a place name inside an area's), because an exact place match beats an area.
 * Each method returns a message naming the clash, or empty when the name is free; the caller chooses the
 * exception and status that suit its endpoint.
 */
@Service
public class NameNamespaceGuard {

    private static final int MIN_LENGTH = 2;
    private static final int MAX_LENGTH = 80;

    private final LocationRepository locationRepository;
    private final LocationAliasRepository locationAliasRepository;
    private final AreaRepository areaRepository;

    /**
     * Constructs the guard.
     *
     * @param locationRepository      the locations
     * @param locationAliasRepository the location aliases
     * @param areaRepository          the named areas
     */
    public NameNamespaceGuard(LocationRepository locationRepository,
            LocationAliasRepository locationAliasRepository, AreaRepository areaRepository) {
        this.locationRepository = locationRepository;
        this.locationAliasRepository = locationAliasRepository;
        this.areaRepository = areaRepository;
    }

    /**
     * Checks the shape of a name: 2 to 80 characters once trimmed, with at least {@code minWords} words.
     *
     * @param raw      the name as typed (null reads as blank)
     * @param minWords the fewest words allowed
     * @param what     what it is, for the message ("An area name")
     * @return the trimmed name
     * @throws IllegalArgumentException if the shape is wrong (HTTP 400)
     */
    static String cleanName(String raw, int minWords, String what) {
        String name = raw == null ? "" : raw.strip();
        if (name.length() < MIN_LENGTH || name.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(what + " must be " + MIN_LENGTH + " to " + MAX_LENGTH
                    + " characters");
        }
        if (PlaceNames.words(name).size() < minWords) {
            throw new IllegalArgumentException(what + " must be at least " + minWords
                    + (minWords == 1 ? " word" : " words"));
        }
        return name;
    }

    /**
     * Cleans a list of aliases: each trimmed and shape-checked, blanks dropped, and a name repeated (in its
     * word form) kept once, in the order typed.
     *
     * @param raw      the aliases as typed (null reads as none)
     * @param minWords the fewest words each may have
     * @param what     what each is, for the message ("An area alias")
     * @return the cleaned aliases
     * @throws IllegalArgumentException if any alias has the wrong shape (HTTP 400)
     */
    static List<String> cleanAliases(List<String> raw, int minWords, String what) {
        Map<List<String>, String> byWords = new LinkedHashMap<>();
        if (raw != null) {
            for (String alias : raw) {
                if (alias == null || alias.isBlank()) {
                    continue;
                }
                String clean = cleanName(alias, minWords, what);
                byWords.putIfAbsent(PlaceNames.words(clean), clean);
            }
        }
        return List.copyOf(byWords.values());
    }

    /**
     * Checks a candidate area name or area alias. It may not equal any other area's name or alias, nor equal
     * or sit inside (as a run of whole words) any location name or location alias.
     *
     * @param candidate     the area name or alias
     * @param excludeAreaId the area being edited, whose own names are not a clash; null for a new area
     * @return the clash, or empty
     */
    @Transactional(readOnly = true)
    public Optional<String> areaNameClash(String candidate, Long excludeAreaId) {
        List<String> words = PlaceNames.words(candidate);
        if (words.isEmpty()) {
            return Optional.empty();
        }
        for (AreaEntity area : areaRepository.findAll()) {
            if (Objects.equals(area.getId(), excludeAreaId)) {
                continue;
            }
            if (words.equals(PlaceNames.words(area.getName()))) {
                return Optional.of("'" + candidate + "' is already the named area '" + area.getName() + "'");
            }
            for (String alias : area.getAliases()) {
                if (words.equals(PlaceNames.words(alias))) {
                    return Optional.of("'" + candidate + "' is already a name for the named area '"
                            + area.getName() + "'");
                }
            }
        }
        for (LocationEntity location : locationRepository.findAll()) {
            if (PlaceNames.containsRun(PlaceNames.words(location.getName()), words)) {
                return Optional.of("'" + candidate + "' is part of the location name '" + location.getName() + "'");
            }
        }
        for (LocationAliasEntity alias : locationAliasRepository.findAll()) {
            if (PlaceNames.containsRun(PlaceNames.words(alias.getAlias()), words)) {
                return Optional.of("'" + candidate + "' is part of the location's other name '"
                        + alias.getAlias() + "'");
            }
        }
        return Optional.empty();
    }

    /**
     * Checks a candidate location alias. It may not equal another location's name or alias, nor any area name
     * or alias, nor contain an area name or alias as a run of whole words.
     *
     * @param candidate  the alias
     * @param locationId the location it would belong to; its own other names are not a clash
     * @return the clash, or empty
     */
    @Transactional(readOnly = true)
    public Optional<String> locationAliasClash(String candidate, Long locationId) {
        List<String> words = PlaceNames.words(candidate);
        if (words.isEmpty()) {
            return Optional.empty();
        }
        for (LocationEntity location : locationRepository.findAll()) {
            if (!Objects.equals(location.getId(), locationId)
                    && words.equals(PlaceNames.words(location.getName()))) {
                return Optional.of("'" + candidate + "' is already the location '" + location.getName() + "'");
            }
        }
        for (LocationAliasEntity alias : locationAliasRepository.findAll()) {
            if (!Objects.equals(alias.getLocationId(), locationId)
                    && words.equals(PlaceNames.words(alias.getAlias()))) {
                return Optional.of("'" + candidate + "' is already another location's name ('"
                        + alias.getAlias() + "')");
            }
        }
        return areaContainedIn(candidate, words);
    }

    /**
     * Checks a candidate location name. It may not equal any location alias, area name or area alias, nor
     * contain an area name or alias as a run of whole words, or a place called "Hadrian's Wall" would hide the
     * named area.
     *
     * @param candidate the location name
     * @return the clash, or empty
     */
    @Transactional(readOnly = true)
    public Optional<String> locationNameClash(String candidate) {
        List<String> words = PlaceNames.words(candidate);
        if (words.isEmpty()) {
            return Optional.empty();
        }
        for (LocationAliasEntity alias : locationAliasRepository.findAll()) {
            if (words.equals(PlaceNames.words(alias.getAlias()))) {
                return Optional.of("'" + candidate + "' is already another name for a location ('"
                        + alias.getAlias() + "')");
            }
        }
        return areaContainedIn(candidate, words);
    }

    /** Equal to, or containing as a run, any area name or alias. */
    private Optional<String> areaContainedIn(String candidate, List<String> words) {
        for (AreaEntity area : areaRepository.findAll()) {
            if (PlaceNames.containsRun(words, PlaceNames.words(area.getName()))) {
                return Optional.of("'" + candidate + "' contains the named area '" + area.getName() + "'");
            }
            for (String alias : area.getAliases()) {
                if (PlaceNames.containsRun(words, PlaceNames.words(alias))) {
                    return Optional.of("'" + candidate + "' contains '" + alias + "', a name for the named area '"
                            + area.getName() + "'");
                }
            }
        }
        return Optional.empty();
    }
}
