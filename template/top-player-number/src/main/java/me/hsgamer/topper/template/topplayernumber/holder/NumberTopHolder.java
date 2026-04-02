package me.hsgamer.topper.template.topplayernumber.holder;

import me.hsgamer.topper.agent.core.Agent;
import me.hsgamer.topper.agent.core.DataEntryAgent;
import me.hsgamer.topper.agent.snapshot.SnapshotAgent;
import me.hsgamer.topper.agent.snapshot.SnapshotHolderAgent;
import me.hsgamer.topper.agent.storage.StorageAgent;
import me.hsgamer.topper.agent.timed.SimpleTimedDataHolder;
import me.hsgamer.topper.agent.timed.TimePeriod;
import me.hsgamer.topper.agent.timed.TimedSnapshotHolderAgent;
import me.hsgamer.topper.agent.timed.TimedStorageAgent;
import me.hsgamer.topper.agent.update.UpdateAgent;
import me.hsgamer.topper.data.core.DataEntry;
import me.hsgamer.topper.data.simple.SimpleDataHolder;
import me.hsgamer.topper.query.display.number.NumberDisplay;
import me.hsgamer.topper.template.topplayernumber.TopPlayerNumberTemplate;
import me.hsgamer.topper.template.topplayernumber.manager.EntryConsumeManager;
import me.hsgamer.topper.value.core.ValueProvider;
import me.hsgamer.topper.value.core.ValueWrapper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class NumberTopHolder extends SimpleDataHolder<UUID, Double> implements me.hsgamer.topper.agent.core.AgentHolder<UUID, Double> {
    public static final String GROUP = "topper";

    private final String name;
    private final Settings settings;
    private final NumberDisplay<UUID, Double> valueDisplay;
    private final List<Agent> agents;
    private final List<DataEntryAgent<UUID, Double>> entryAgents;
    private final StorageAgent<UUID, Double> storageAgent;
    private final UpdateAgent<UUID, Double> updateAgent;
    private final SnapshotHolderAgent<UUID, Double> snapshotAgent;
    private final Double defaultValue;

    // Timed agents: one entry per enabled TimePeriod
    private final Map<TimePeriod, TimedStorageAgent<UUID, Double>> timedStorageAgents;
    private final Map<TimePeriod, TimedSnapshotHolderAgent<UUID>> timedSnapshotAgents;
    private final Map<TimePeriod, NumberDisplay<UUID, Double>> timedValueDisplays;

    public NumberTopHolder(TopPlayerNumberTemplate template, String name, Settings settings) {
        this.name = name;
        this.settings = settings;

        ValueWrapper<Double> defaultValueWrapper = settings.defaultValue();
        switch (defaultValueWrapper.state) {
            case HANDLED:
                this.defaultValue = defaultValueWrapper.value;
                break;
            case ERROR:
                template.logWarning("Error when parsing default value in " + name + " - " + defaultValueWrapper.errorMessage, defaultValueWrapper.throwable);
                this.defaultValue = null;
                break;
            default:
                this.defaultValue = null;
                break;
        }

        List<Agent> agents = new ArrayList<>();
        List<DataEntryAgent<UUID, Double>> entryAgents = new ArrayList<>();

        // ── Value display (alltime) ────────────────────────────────────────────
        this.valueDisplay = buildDisplay(template, settings);

        // ── Storage agent ─────────────────────────────────────────────────────
        this.storageAgent = new StorageAgent<>(template.getTopManager().buildStorage(name));
        storageAgent.setMaxEntryPerCall(template.getSettings().taskSaveEntryPerTick());
        agents.add(storageAgent);
        agents.add(storageAgent.getLoadAgent(this));
        agents.add(template.createTask(storageAgent, TaskType.STORAGE, settings.valueProvider()));
        entryAgents.add(storageAgent);

        // ── Update agent ──────────────────────────────────────────────────────
        ValueProvider<UUID, Double> valueProvider = template.createValueProvider(settings.valueProvider()).orElseGet(() -> {
            template.logWarning("No value provider found for " + name);
            return ValueProvider.empty();
        });
        boolean showErrors = settings.showErrors();
        boolean resetOnError = settings.resetOnError();
        this.updateAgent = new UpdateAgent<>(this, valueProvider);
        this.updateAgent.setFilter(settings::filter);
        if (resetOnError) {
            updateAgent.setErrorHandler((uuid, valueWrapper) -> {
                if (showErrors && valueWrapper.state == ValueWrapper.State.ERROR) {
                    template.logWarning("Error on getting value for " + name + " from " + uuid + " - " + valueWrapper.errorMessage, valueWrapper.throwable);
                }
                return ValueWrapper.handled(defaultValue);
            });
        } else if (showErrors) {
            updateAgent.setErrorHandler((uuid, valueWrapper) -> {
                if (valueWrapper.state == ValueWrapper.State.ERROR) {
                    template.logWarning("Error on getting value for " + name + " from " + uuid + " - " + valueWrapper.errorMessage, valueWrapper.throwable);
                }
            });
        }
        updateAgent.setMaxSkips(template.getSettings().taskUpdateMaxSkips());
        entryAgents.add(updateAgent);
        agents.add(template.createTask(updateAgent.getUpdateRunnable(template.getSettings().taskUpdateEntryPerTick()), TaskType.UPDATE, settings.valueProvider()));
        agents.add(template.createTask(updateAgent.getSetRunnable(), TaskType.SET, settings.valueProvider()));

        // ── Alltime snapshot agent ────────────────────────────────────────────
        this.snapshotAgent = new SnapshotHolderAgent<>(this);
        boolean reverseOrder = settings.reverse();
        snapshotAgent.setComparator(reverseOrder ? Comparator.naturalOrder() : Comparator.reverseOrder());
        snapshotAgent.setDataFilter(entry -> entry.getValue() != null);
        agents.add(snapshotAgent);
        entryAgents.add(snapshotAgent);
        agents.add(template.createTask(snapshotAgent, TaskType.SNAPSHOT, settings.valueProvider()));

        // ── Entry consume ─────────────────────────────────────────────────────
        entryAgents.add(new DataEntryAgent<UUID, Double>() {
            @Override
            public void onUpdate(DataEntry<UUID, Double> entry, Double oldValue, Double newValue) {
                template.getEntryConsumeManager().consume(new EntryConsumeManager.Context(
                        GROUP,
                        name,
                        entry.getKey(),
                        oldValue,
                        newValue
                ));
            }
        });

        // ── Timed agents (weekly / monthly) ───────────────────────────────────
        Map<TimePeriod, TimedStorageAgent<UUID, Double>> timedStorageMap = new EnumMap<>(TimePeriod.class);
        Map<TimePeriod, TimedSnapshotHolderAgent<UUID>> timedSnapshotMap = new EnumMap<>(TimePeriod.class);
        Map<TimePeriod, NumberDisplay<UUID, Double>> timedDisplayMap = new EnumMap<>(TimePeriod.class);

        for (TimePeriod period : settings.timedPeriods()) {
            // Separate in-memory holder for this period
            SimpleTimedDataHolder<UUID> timedHolder = new SimpleTimedDataHolder<>();

            // Storage for this period (e.g. "money_weekly", "money_monthly")
            TimedStorageAgent<UUID, Double> timedStorage = new TimedStorageAgent<>(
                    template.getTopManager().buildTimedStorage(name, period),
                    timedHolder,
                    period
            );
            timedStorage.setMaxEntryPerCall(template.getSettings().taskSaveEntryPerTick());
            timedStorage.setZoneId(template.getSettings().zoneId());

            // Snapshot for this period
            TimedSnapshotHolderAgent<UUID> timedSnapshot = new TimedSnapshotHolderAgent<>(timedHolder, period);
            timedSnapshot.setComparator(reverseOrder ? Comparator.naturalOrder() : Comparator.reverseOrder());

            // Display reuses the same formatting as alltime
            timedDisplayMap.put(period, buildDisplay(template, settings));

            // Wire agents
            agents.add(timedStorage);
            agents.add(template.createTask(timedStorage, TaskType.TIMED_STORAGE, settings.valueProvider()));
            agents.add(timedSnapshot);
            agents.add(template.createTask(timedSnapshot, TaskType.TIMED_SNAPSHOT, settings.valueProvider()));

            // timedStorage listens to the MAIN holder's entry updates (to accumulate deltas)
            entryAgents.add(timedStorage);

            timedStorageMap.put(period, timedStorage);
            timedSnapshotMap.put(period, timedSnapshot);
        }

        this.timedStorageAgents = Collections.unmodifiableMap(timedStorageMap);
        this.timedSnapshotAgents = Collections.unmodifiableMap(timedSnapshotMap);
        this.timedValueDisplays = Collections.unmodifiableMap(timedDisplayMap);

        template.modifyAgents(this, agents, entryAgents);
        this.agents = Collections.unmodifiableList(agents);
        this.entryAgents = Collections.unmodifiableList(entryAgents);
    }

    // ── Shared display builder ────────────────────────────────────────────────

    private NumberDisplay<UUID, Double> buildDisplay(TopPlayerNumberTemplate template, Settings settings) {
        return new NumberDisplay<UUID, Double>() {
            @Override
            public @NotNull String getDisplayName(@Nullable UUID uuid) {
                return Optional.ofNullable(uuid).map(template.getNameProviderManager()::getName).orElse(settings.displayNullName());
            }

            @Override
            public @NotNull String getDisplayKey(@Nullable UUID uuid) {
                return uuid != null ? uuid.toString() : settings.displayNullUuid();
            }

            @Override
            public @NotNull String getDisplayNullValue() {
                return settings.displayNullValue();
            }

            @Override
            public @NotNull String getDisplayValue(@Nullable Double value, @NotNull String formatQuery) {
                if (formatQuery.isEmpty()) {
                    formatQuery = settings.defaultValueDisplay();
                }
                return super.getDisplayValue(value, formatQuery);
            }
        };
    }

    // ── Agent accessors ───────────────────────────────────────────────────────

    @Override
    public @Nullable Double getDefaultValue() {
        return defaultValue;
    }

    @Override
    public List<Agent> getAgents() {
        return agents;
    }

    @Override
    public List<DataEntryAgent<UUID, Double>> getEntryAgents() {
        return entryAgents;
    }

    public StorageAgent<UUID, Double> getStorageAgent() {
        return storageAgent;
    }

    public UpdateAgent<UUID, Double> getUpdateAgent() {
        return updateAgent;
    }

    public SnapshotAgent<UUID, Double> getSnapshotAgent() {
        return snapshotAgent;
    }

    public NumberDisplay<UUID, Double> getValueDisplay() {
        return valueDisplay;
    }

    // ── Timed accessors ───────────────────────────────────────────────────────

    public Optional<TimedStorageAgent<UUID, Double>> getTimedStorageAgent(TimePeriod period) {
        return Optional.ofNullable(timedStorageAgents.get(period));
    }

    public Optional<TimedSnapshotHolderAgent<UUID>> getTimedSnapshotAgent(TimePeriod period) {
        return Optional.ofNullable(timedSnapshotAgents.get(period));
    }

    public Optional<NumberDisplay<UUID, Double>> getTimedValueDisplay(TimePeriod period) {
        return Optional.ofNullable(timedValueDisplays.get(period));
    }

    public Map<TimePeriod, TimedStorageAgent<UUID, Double>> getTimedStorageAgents() {
        return timedStorageAgents;
    }

    public Map<TimePeriod, TimedSnapshotHolderAgent<UUID>> getTimedSnapshotAgents() {
        return timedSnapshotAgents;
    }

    public String getName() {
        return name;
    }

    public Settings getSettings() {
        return settings;
    }

    // ── Task types ────────────────────────────────────────────────────────────

    public enum TaskType {
        STORAGE,
        SET,
        SNAPSHOT,
        UPDATE,
        TIMED_STORAGE,
        TIMED_SNAPSHOT
    }

    // ── Settings interface ────────────────────────────────────────────────────

    public interface Settings {
        ValueWrapper<Double> defaultValue();

        String displayNullName();

        String displayNullUuid();

        String displayNullValue();

        default String defaultValueDisplay() {
            return "";
        }

        boolean showErrors();

        boolean resetOnError();

        boolean reverse();

        UpdateAgent.FilterResult filter(UUID uuid);

        Map<String, Object> valueProvider();

        /**
         * Which timed periods are enabled for this holder.
         * Return an empty list to disable timed leaderboards (default behaviour).
         */
        default List<TimePeriod> timedPeriods() {
            return Collections.emptyList();
        }
    }

    // ── MapSettings ───────────────────────────────────────────────────────────

    public static abstract class MapSettings implements Settings {
        protected final Map<String, Object> map;

        protected MapSettings(Map<String, Object> map) {
            this.map = map;
        }

        @Override
        public ValueWrapper<Double> defaultValue() {
            Object value = map.get("default-value");
            if (value == null) {
                return ValueWrapper.notHandled();
            }
            try {
                double numberValue = Double.parseDouble(Objects.toString(value));
                return ValueWrapper.handled(numberValue);
            } catch (Exception e) {
                return ValueWrapper.error("Invalid number: \"" + value + "\". Fallback to null", e);
            }
        }

        @Override
        public String defaultValueDisplay() {
            return Optional.ofNullable(map.get("default-value-display"))
                    .map(Object::toString)
                    .orElse(Settings.super.defaultValueDisplay());
        }

        @Override
        public String displayNullName() {
            return Optional.ofNullable(map.get("null-name"))
                    .map(Object::toString)
                    .orElse("---");
        }

        @Override
        public String displayNullUuid() {
            return Optional.ofNullable(map.get("null-uuid"))
                    .map(Object::toString)
                    .orElse("---");
        }

        @Override
        public String displayNullValue() {
            return Optional.ofNullable(map.get("null-value"))
                    .map(Object::toString)
                    .orElse("---");
        }

        @Override
        public boolean showErrors() {
            return Optional.ofNullable(map.get("show-errors"))
                    .map(Object::toString)
                    .map(String::toLowerCase)
                    .map(Boolean::parseBoolean)
                    .orElse(false);
        }

        @Override
        public boolean resetOnError() {
            return Optional.ofNullable(map.get("reset-on-error"))
                    .map(Object::toString)
                    .map(String::toLowerCase)
                    .map(Boolean::parseBoolean)
                    .orElse(true);
        }

        @Override
        public boolean reverse() {
            return Optional.ofNullable(map.get("reverse"))
                    .map(String::valueOf)
                    .map(Boolean::parseBoolean)
                    .orElse(false);
        }

        @Override
        public Map<String, Object> valueProvider() {
            return map;
        }

        /**
         * Reads "timed" key from config map.
         * Accepts a list of strings, e.g.:
         * <pre>
         * timed:
         *   - weekly
         *   - monthly
         * </pre>
         */
        @Override
        @SuppressWarnings("unchecked")
        public List<TimePeriod> timedPeriods() {
            Object raw = map.get("timed");
            if (!(raw instanceof List)) return Collections.emptyList();
            List<TimePeriod> result = new ArrayList<>();
            for (Object item : (List<?>) raw) {
                String s = Objects.toString(item, "").trim().toLowerCase();
                for (TimePeriod p : TimePeriod.values()) {
                    if (p.name().equals(s)) {
                        result.add(p);
                        break;
                    }
                }
            }
            return result;
        }

        public Map<String, Object> map() {
            return map;
        }
    }
}
