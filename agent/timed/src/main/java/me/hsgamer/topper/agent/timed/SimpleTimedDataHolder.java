package me.hsgamer.topper.agent.timed;

import me.hsgamer.topper.data.simple.SimpleDataHolder;
import org.jetbrains.annotations.Nullable;

/**
 * A lightweight {@link SimpleDataHolder} used exclusively by timed agents
 * to hold the current-period accumulated values.
 *
 * <p>The default value is {@code null} (meaning no score yet), so that
 * the snapshot filter can exclude players who haven't earned anything in
 * the current period.</p>
 */
public class SimpleTimedDataHolder<K> extends SimpleDataHolder<K, Double> {

    @Override
    public @Nullable Double getDefaultValue() {
        return null;
    }
}
