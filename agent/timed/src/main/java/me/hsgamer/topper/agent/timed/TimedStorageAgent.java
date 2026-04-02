package me.hsgamer.topper.agent.timed;

import me.hsgamer.hscore.logger.common.LogLevel;
import me.hsgamer.hscore.logger.common.Logger;
import me.hsgamer.hscore.logger.provider.LoggerProvider;
import me.hsgamer.topper.agent.core.Agent;
import me.hsgamer.topper.agent.core.DataEntryAgent;
import me.hsgamer.topper.data.core.DataEntry;
import me.hsgamer.topper.data.core.DataHolder;
import me.hsgamer.topper.storage.core.DataStorage;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * A storage agent that persists timed (weekly/monthly) leaderboard data
 * and automatically resets the data when the period rolls over.
 *
 * <p>The agent writes accumulated values to a dedicated storage (e.g. a separate
 * file or SQL table suffixed with "_weekly" / "_monthly").  When the wall-clock
 * passes the next reset boundary, it wipes the storage and the in-memory holder
 * so the next period starts from zero.</p>
 *
 * <p>Values accumulate: every {@link #onUpdate} call adds the delta
 * (newValue - oldValue) to the timed entry.  Negative deltas are ignored so
 * that administrative reductions don't subtract from the leaderboard.</p>
 */
public class TimedStorageAgent<K, V extends Number> implements Agent, DataEntryAgent<K, V>, Runnable {
    private static final Logger LOGGER = LoggerProvider.getLogger(TimedStorageAgent.class);

    private final DataStorage<K, Double> storage;
    private final DataHolder<K, Double> timedHolder;
    private final TimePeriod period;

    // Pending values to be saved (key → accumulated value)
    private final AtomicReference<Map<K, Double>> storeMap = new AtomicReference<>(new ConcurrentHashMap<>());
    private final Queue<Map.Entry<K, Double>> queue = new ConcurrentLinkedQueue<>();
    private final AtomicReference<Map<K, Double>> savingMap = new AtomicReference<>();
    private final AtomicBoolean saving = new AtomicBoolean(false);

    // Next reset epoch millis
    private final AtomicLong nextResetMillis = new AtomicLong(0);
    private final AtomicBoolean pendingReset = new AtomicBoolean(false);

    private int maxEntryPerCall = 10;
    private ZoneId zoneId = ZoneId.systemDefault();

    public TimedStorageAgent(DataStorage<K, Double> storage, DataHolder<K, Double> timedHolder, TimePeriod period) {
        this.storage = storage;
        this.timedHolder = timedHolder;
        this.period = period;
    }

    // ─── Agent lifecycle ─────────────────────────────────────────────────────

    @Override
    public void start() {
        storage.onRegister();
        // Load persisted timed data into the timed holder
        try {
            storage.load().forEach((key, value) -> timedHolder.getOrCreateEntry(key).setValue(value, false));
        } catch (Exception e) {
            LOGGER.log(LogLevel.ERROR, "Failed to load timed entries for period " + period.name(), e);
        }
        scheduleNextReset();
    }

    @Override
    public void stop() {
        storage.onUnregister();
    }

    @Override
    public void beforeStop() {
        save(true);
    }

    // ─── Save loop ────────────────────────────────────────────────────────────

    private void save(boolean urgent) {
        if (saving.get() && !urgent) return;
        saving.set(true);

        storeMap.getAndSet(new ConcurrentHashMap<>())
                .forEach((key, value) -> queue.add(new AbstractMap.SimpleEntry<>(key, value)));

        Map<K, Double> map = savingMap.updateAndGet(old -> old == null ? new HashMap<>() : old);

        int idx = 0;
        while (urgent || maxEntryPerCall <= 0 || idx < maxEntryPerCall) {
            Map.Entry<K, Double> entry = queue.poll();
            if (entry == null) break;
            // Merge: keep the higher value if two updates collide
            map.merge(entry.getKey(), entry.getValue(), Double::max);
            idx++;
        }

        if (map.isEmpty()) {
            savingMap.set(null);
            saving.set(false);
            return;
        }

        Optional<DataStorage.Modifier<K, Double>> optModifier = storage.modify();
        if (!optModifier.isPresent()) {
            saving.set(false);
            return;
        }

        DataStorage.Modifier<K, Double> modifier = optModifier.get();
        try {
            modifier.save(new HashMap<>(map));
            modifier.commit();
            savingMap.set(null);
        } catch (Throwable t) {
            LOGGER.log(LogLevel.ERROR, "Failed to save timed entries for period " + period.name(), t);
            modifier.rollback();
        } finally {
            saving.set(false);
        }
    }

    // ─── Reset logic ─────────────────────────────────────────────────────────

    private void scheduleNextReset() {
        ZonedDateTime now = ZonedDateTime.now(zoneId);
        ZonedDateTime next = period.nextReset(now);
        nextResetMillis.set(next.toInstant().toEpochMilli());
    }

    /**
     * Called during the periodic run; checks if a reset is due.
     */
    private void checkReset() {
        long now = System.currentTimeMillis();
        if (now < nextResetMillis.get()) return;

        pendingReset.set(true);
        scheduleNextReset();
    }

    /**
     * Wipes all timed data: removes all entries from the timed holder and clears storage.
     * Safe to call from the server's main thread (runs at tick, not async).
     */
    public void performReset() {
        if (!pendingReset.compareAndSet(true, false)) return;

        // Clear in-memory timed holder
        new ArrayList<>(timedHolder.getEntryMap().keySet())
                .forEach(timedHolder::removeEntry);

        // Clear queued saves so stale data isn't written
        queue.clear();
        storeMap.set(new ConcurrentHashMap<>());
        savingMap.set(null);

        // Wipe the storage
        Optional<DataStorage.Modifier<K, Double>> optModifier = storage.modify();
        optModifier.ifPresent(modifier -> {
            try {
                Set<K> allKeys = new HashSet<>(storage.keys());
                if (!allKeys.isEmpty()) modifier.remove(allKeys);
                modifier.commit();
            } catch (Throwable t) {
                LOGGER.log(LogLevel.ERROR, "Failed to reset timed storage for period " + period.name(), t);
                modifier.rollback();
            }
        });
    }

    public boolean isPendingReset() {
        return pendingReset.get();
    }

    // ─── DataEntryAgent ───────────────────────────────────────────────────────

    /**
     * When the "real" holder entry is updated, accumulate the delta into the timed holder.
     * We only add positive deltas (gains), never subtract.
     */
    @Override
    public void onUpdate(DataEntry<K, V> entry, V oldValue, V newValue) {
        if (newValue == null) return;
        double next = newValue.doubleValue();
        double prev = oldValue != null ? oldValue.doubleValue() : 0.0;
        double delta = next - prev;
        if (delta <= 0) return;

        K key = entry.getKey();
        // Get current timed value, then add delta
        double current = timedHolder.getEntry(key)
                .map(DataEntry::getValue)
                .map(v -> v != null ? v : 0.0)
                .orElse(0.0);
        double updated = current + delta;
        timedHolder.getOrCreateEntry(key).setValue(updated);
        // Schedule for persistence
        storeMap.get().merge(key, updated, Double::max);
    }

    @Override
    public void onCreate(DataEntry<K, V> entry) {
        // Ensure a timed entry exists for every main entry
        timedHolder.getOrCreateEntry(entry.getKey());
    }

    @Override
    public void onRemove(DataEntry<K, V> entry) {
        // Don't remove timed data when a player goes offline – keep their score
    }

    // ─── Runnable (periodic tick) ─────────────────────────────────────────────

    @Override
    public void run() {
        checkReset();
        save(false);
    }

    // ─── Getters / setters ────────────────────────────────────────────────────

    public DataHolder<K, Double> getTimedHolder() {
        return timedHolder;
    }

    public TimePeriod getPeriod() {
        return period;
    }

    public void setMaxEntryPerCall(int max) {
        this.maxEntryPerCall = max;
    }

    public void setZoneId(ZoneId zoneId) {
        this.zoneId = zoneId;
        scheduleNextReset(); // recalculate with new zone
    }

    public ZoneId getZoneId() {
        return zoneId;
    }

    public long getNextResetMillis() {
        return nextResetMillis.get();
    }
}
