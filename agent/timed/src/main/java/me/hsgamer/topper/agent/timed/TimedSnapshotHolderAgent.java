package me.hsgamer.topper.agent.timed;

import me.hsgamer.topper.agent.snapshot.SnapshotHolderAgent;
import me.hsgamer.topper.data.core.DataHolder;

/**
 * A {@link SnapshotHolderAgent} that operates on the timed (weekly/monthly) data holder.
 * It is a thin wrapper — all snapshot logic is inherited from {@link SnapshotHolderAgent}.
 * This class exists purely to give the timed snapshot a distinct type so callers
 * can distinguish it from the alltime snapshot.
 */
public class TimedSnapshotHolderAgent<K> extends SnapshotHolderAgent<K, Double> {

    private final TimePeriod period;

    public TimedSnapshotHolderAgent(DataHolder<K, Double> timedHolder, TimePeriod period) {
        super(timedHolder);
        this.period = period;
        // Only rank entries that actually have a value
        setDataFilter(entry -> entry.getValue() != null && entry.getValue() > 0);
    }

    public TimePeriod getPeriod() {
        return period;
    }
}
