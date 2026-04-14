package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.HotTopic;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory simulation state for Hot Topics.
 *
 * <p>When enabled, {@link HotTopicAggregator} returns simulated topics instead
 * of running real detectors. State is volatile — resets on server restart.
 * This is admin tooling only, not user-facing state.
 */
@Service
public class HotTopicSimulationService {

    private volatile boolean enabled = false;
    private final Set<String> activeTypes = ConcurrentHashMap.newKeySet();

    /**
     * Returns true when simulation mode is active.
     *
     * @return {@code true} if simulation is currently enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Sets the master simulation toggle.
     *
     * @param enabled {@code true} to activate simulation; {@code false} to deactivate
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Returns a snapshot of the currently active topic types.
     *
     * @return immutable copy of active type identifiers
     */
    public Set<String> getActiveTypes() {
        return Set.copyOf(activeTypes);
    }

    /**
     * Activates or deactivates an individual topic type.
     *
     * @param type   topic type identifier, e.g. "BLUEBELL"
     * @param active {@code true} to activate; {@code false} to deactivate
     */
    public void setTypeActive(String type, boolean active) {
        if (active) {
            activeTypes.add(type);
        } else {
            activeTypes.remove(type);
        }
    }

    /**
     * Returns simulated {@link HotTopic} records for all active types.
     * Each type has a hardcoded realistic example matching what a photographer
     * would see if the phenomenon were real.
     *
     * <p>Returns an empty list when simulation is not enabled.
     *
     * @param fromDate start of the forecast window (inclusive)
     * @param toDate   end of the forecast window (inclusive)
     * @return list of simulated hot topics; never null
     */
    public List<HotTopic> getSimulatedTopics(LocalDate fromDate, LocalDate toDate) {
        if (!enabled) {
            return List.of();
        }

        LocalDate tomorrow = fromDate.plusDays(1);
        LocalDate dayAfter = fromDate.plusDays(2);

        return ALL_SIMULATIONS.stream()
                .filter(sim -> activeTypes.contains(sim.type()))
                .map(sim -> sim.withDates(fromDate, tomorrow, dayAfter))
                .toList();
    }

    /**
     * Returns the full catalogue of simulatable topic types, including those not
     * yet implemented as real detectors. Each entry includes the current active flag.
     *
     * @return list of all simulatable types with active state
     */
    public List<SimulatableType> getAllTypes() {
        return ALL_SIMULATIONS.stream()
                .map(sim -> new SimulatableType(
                        sim.type(),
                        sim.label(),
                        activeTypes.contains(sim.type())))
                .toList();
    }

    /**
     * Describes a simulatable topic type and its current active state.
     *
     * @param type   topic type identifier
     * @param label  human-readable label shown in the admin UI
     * @param active {@code true} if this type is currently selected for simulation
     */
    public record SimulatableType(String type, String label, boolean active) { }

    // ── Simulation catalogue ─────────────────────────────────────────────────

    /**
     * Template for a single simulated topic. Holds static data plus a day offset
     * (0 = today, 1 = tomorrow, 2 = day after) used when generating the live record.
     */
    private record SimulationTemplate(
            String type,
            String label,
            String detail,
            String description,
            int priority,
            String filterAction,
            List<String> regions,
            int dayOffset) {

        /**
         * Materialises this template into a {@link HotTopic} using the supplied date references.
         *
         * @param today    the current date
         * @param tomorrow today plus one day
         * @param dayAfter today plus two days
         * @return a live {@link HotTopic} record
         */
        HotTopic withDates(LocalDate today, LocalDate tomorrow, LocalDate dayAfter) {
            LocalDate date = switch (dayOffset) {
                case 0 -> today;
                case 2 -> dayAfter;
                default -> tomorrow;
            };
            String dayLabel = switch (dayOffset) {
                case 0 -> "today";
                case 2 -> date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.UK);
                default -> "tomorrow";
            };
            return new HotTopic(type, label,
                    detail + " " + dayLabel + " — " + String.join(", ", regions),
                    date, priority, filterAction, regions, description);
        }
    }

    private static final List<SimulationTemplate> ALL_SIMULATIONS = List.of(
            new SimulationTemplate(
                    "BLUEBELL", "Bluebell conditions",
                    "Misty and still — perfect conditions",
                    "Bluebell season runs mid-April to mid-May. We score mist, wind, light and"
                            + " recent rain to find the best mornings for woodland and"
                            + " open-fell bluebell photography.",
                    1, "BLUEBELL",
                    List.of("Northumberland", "The Lake District"), 1),
            new SimulationTemplate(
                    "KING_TIDE", "King tide",
                    "Rare extreme tidal range — exceptional coastal foreground",
                    "King tides occur when a new or full moon coincides with the moon's closest"
                            + " approach to Earth, producing exceptionally large tidal ranges."
                            + " Only happens 5–10 times per year — rare dramatic foreground at"
                            + " coastal locations.",
                    1, null,
                    List.of("Northumberland", "The North Yorkshire Coast"), 2),
            new SimulationTemplate(
                    "SPRING_TIDE", "Spring tide",
                    "Aligned with sunrise",
                    "Spring tides happen around each new and full moon, producing the biggest"
                            + " tidal ranges. Higher water at coastal locations means more"
                            + " dramatic foreground and wave action.",
                    2, null,
                    List.of("The North Yorkshire Coast"), 2),
            new SimulationTemplate(
                    "STORM_SURGE", "Storm surge",
                    "Onshore wind + high tide — dramatic coastal conditions",
                    "Low pressure and onshore winds push water levels above predicted heights."
                            + " Combined with high tide, this creates crashing waves and"
                            + " dramatic coastal conditions — even midday.",
                    2, null,
                    List.of("Northumberland"), 1),
            new SimulationTemplate(
                    "AURORA", "Aurora possible",
                    "Kp 5 forecast tonight — 12 dark-sky locations",
                    "The aurora borealis is occasionally visible from northern England when"
                            + " solar activity is high. Best seen from dark-sky locations with"
                            + " a clear northern horizon.",
                    1, null,
                    List.of("Northumberland"), 0),
            new SimulationTemplate(
                    "DUST", "Elevated dust",
                    "Saharan dust at sunset",
                    "Saharan dust carried north by upper winds scatters light at sunrise and"
                            + " sunset, producing unusually vivid orange and red skies.",
                    3, null,
                    List.of("Northumberland", "The North Yorkshire Coast"), 2),
            new SimulationTemplate(
                    "INVERSION", "Cloud inversion",
                    "Strong inversion forecast at elevated locations",
                    "A temperature inversion traps cloud below elevated viewpoints, creating a"
                            + " 'sea of clouds' effect. Best seen from high ground overlooking"
                            + " water at dawn.",
                    2, null,
                    List.of("The North York Moors"), 1),
            new SimulationTemplate(
                    "SUPERMOON", "Supermoon",
                    "Full moon at perigee — moonrise over the coast",
                    "A supermoon occurs when the full moon coincides with its closest approach"
                            + " to Earth, appearing larger and brighter. Moonrise over the coast"
                            + " or behind a landmark is a classic shot.",
                    3, null,
                    List.of("Northumberland", "The North Yorkshire Coast"), 0),
            new SimulationTemplate(
                    "SNOW_FRESH", "Fresh snow",
                    "Snow overnight — sunrise on white landscapes",
                    "Overnight snowfall transforms familiar landscapes. Low morning temperatures"
                            + " mean the snow holds — sunrise on fresh white ground is a"
                            + " rare opportunity.",
                    1, null,
                    List.of("The Lake District", "The North York Moors"), 1),
            new SimulationTemplate(
                    "SNOW_MIST", "Fresh snow with mist",
                    "Overnight snow and morning mist — extraordinary conditions",
                    "Overnight snow combined with morning mist creates ethereal conditions."
                            + " Trees and landmarks emerge from fog against a white landscape"
                            + " — among the most dramatic photography conditions possible.",
                    1, null,
                    List.of("The Lake District", "The North York Moors"), 1),
            new SimulationTemplate(
                    "SNOW_TOPS", "Snow on the fells",
                    "Tops white above 600m",
                    "When the freezing level drops below the peaks, fell tops are capped with"
                            + " snow while valleys stay green. A classic Lake District and"
                            + " Pennine composition.",
                    2, null,
                    List.of("The Lake District"), 1),
            new SimulationTemplate(
                    "NLC", "Noctilucent cloud season",
                    "NLC season active — check north-facing locations after sunset",
                    "Noctilucent clouds are extremely high ice clouds that catch sunlight after"
                            + " sunset, glowing electric blue on the northern horizon."
                            + " Visible late May to early August.",
                    3, null,
                    List.of("Northumberland"), 0),
            new SimulationTemplate(
                    "METEOR", "Meteor shower",
                    "Perseids peak tonight — 8 Bortle 3 or darker locations",
                    "During a meteor shower, shooting stars are more frequent than usual."
                            + " Best photographed from dark-sky locations with a wide-angle"
                            + " lens on a long exposure.",
                    2, null,
                    List.of("Northumberland"), 0),
            new SimulationTemplate(
                    "EQUINOX", "Equinox alignment",
                    "Sun rises due east — aligns with river valleys",
                    "At the spring and autumn equinoxes the sun rises almost exactly due east"
                            + " and sets almost exactly due west — a rare solar alignment with"
                            + " valleys, rivers and roads.",
                    3, null,
                    List.of("Tyne and Wear"), 1),
            new SimulationTemplate(
                    "CLEARANCE", "Dramatic clearance",
                    "Cloud clearing forecast during golden hour",
                    "A clearance occurs when cloud parts rapidly during golden hour, creating"
                            + " shafts of dramatic light and contrast between the bright sky"
                            + " and retreating dark cloud.",
                    2, null,
                    List.of("Northumberland", "The North Yorkshire Coast"), 2)
    );
}
