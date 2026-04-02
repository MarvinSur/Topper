package me.hsgamer.topper.template.topplayernumber.manager;

import me.hsgamer.topper.agent.snapshot.SnapshotAgent;
import me.hsgamer.topper.agent.timed.TimePeriod;
import me.hsgamer.topper.query.core.QueryManager;
import me.hsgamer.topper.query.holder.HolderQuery;
import me.hsgamer.topper.query.simple.SimpleQuery;
import me.hsgamer.topper.query.simple.SimpleQueryContext;
import me.hsgamer.topper.query.simple.SimpleQueryDisplay;
import me.hsgamer.topper.query.snapshot.SnapshotQuery;
import me.hsgamer.topper.template.topplayernumber.TopPlayerNumberTemplate;
import me.hsgamer.topper.template.topplayernumber.holder.NumberTopHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

public class TopQueryManager extends QueryManager<UUID> {

    public TopQueryManager(TopPlayerNumberTemplate template) {

        // ── Alltime: holder-level query ───────────────────────────────────────
        addQuery(new HolderQuery<UUID, Double, NumberTopHolder, UUID>() {
            @Override
            protected Optional<NumberTopHolder> getHolder(@NotNull String name) {
                return template.getTopManager().getHolder(name);
            }

            @Override
            protected @NotNull SimpleQueryDisplay<UUID, Double> getDisplay(@NotNull NumberTopHolder holder) {
                return holder.getValueDisplay();
            }

            @Override
            protected Optional<UUID> getKey(@NotNull UUID actor, @NotNull Context<UUID, Double, NumberTopHolder> context) {
                return Optional.of(actor);
            }
        });

        // ── Alltime: snapshot query ───────────────────────────────────────────
        addQuery(new SnapshotQuery<UUID, Double, UUID>() {
            @Override
            protected Optional<SnapshotAgent<UUID, Double>> getAgent(@NotNull String name) {
                return template.getTopManager().getHolder(name).map(NumberTopHolder::getSnapshotAgent);
            }

            @Override
            protected Optional<SimpleQueryDisplay<UUID, Double>> getDisplay(@NotNull String name) {
                return template.getTopManager().getHolder(name).map(NumberTopHolder::getValueDisplay);
            }

            @Override
            protected Optional<UUID> getKey(@NotNull UUID actor, @NotNull Context<UUID, Double> context) {
                return Optional.of(actor);
            }

            @Override
            protected @NotNull String getDisplayRank(int rank, @NotNull Context<UUID, Double> context) {
                return context.display.getDisplayValue((double) rank, context.parent.args);
            }
        });

        // ── Timed: one pair of queries per TimePeriod ─────────────────────────
        //
        // Top-list  : %topper_money_weekly;top_name;1%
        //             %topper_money_monthly;top_value;3%
        // Personal  : %topper_money_weekly;value%
        //             %topper_money_weekly;rank%
        //             %topper_money_monthly;name%
        for (TimePeriod period : TimePeriod.values()) {
            final String suffix = "_" + period.name(); // "_weekly" | "_monthly"

            // ── Timed top-list snapshot ───────────────────────────────────────
            addQuery(new SnapshotQuery<UUID, Double, UUID>() {
                @Override
                protected Optional<SnapshotAgent<UUID, Double>> getAgent(@NotNull String rawName) {
                    if (!rawName.endsWith(suffix)) return Optional.empty();
                    String holderName = rawName.substring(0, rawName.length() - suffix.length());
                    return template.getTopManager().getHolder(holderName)
                            .flatMap(h -> h.getTimedSnapshotAgent(period))
                            .map(a -> (SnapshotAgent<UUID, Double>) a);
                }

                @Override
                protected Optional<SimpleQueryDisplay<UUID, Double>> getDisplay(@NotNull String rawName) {
                    if (!rawName.endsWith(suffix)) return Optional.empty();
                    String holderName = rawName.substring(0, rawName.length() - suffix.length());
                    return template.getTopManager().getHolder(holderName)
                            .flatMap(h -> h.getTimedValueDisplay(period));
                }

                @Override
                protected Optional<UUID> getKey(@NotNull UUID actor, @NotNull Context<UUID, Double> context) {
                    return Optional.of(actor);
                }

                @Override
                protected @NotNull String getDisplayRank(int rank, @NotNull Context<UUID, Double> context) {
                    return context.display.getDisplayValue((double) rank, context.parent.args);
                }
            });

            // ── Timed personal query (value / rank / name / key for the actor) ─
            addQuery(new SimpleQuery<UUID, TimedPersonalCtx>() {
                {
                    // value  → actor's accumulated timed value this period
                    registerActorAction("value", (actor, ctx) -> {
                        Double val = ctx.holder.getTimedStorageAgent(period)
                                .map(ta -> ta.getTimedHolder().getEntry(actor)
                                        .map(e -> e.getValue()).orElse(null))
                                .orElse(null);
                        return ctx.display.getDisplayValue(val, ctx.parent.args);
                    });

                    // value_raw → same but raw format
                    registerActorAction("value_raw", (actor, ctx) -> {
                        Double val = ctx.holder.getTimedStorageAgent(period)
                                .map(ta -> ta.getTimedHolder().getEntry(actor)
                                        .map(e -> e.getValue()).orElse(null))
                                .orElse(null);
                        return ctx.display.getDisplayValue(val, "raw");
                    });

                    // rank → actor's position in the timed leaderboard (1-based, 0 if not ranked)
                    registerActorAction("rank", (actor, ctx) -> {
                        if (ctx.snapshotAgent == null) return "0";
                        int idx = ctx.snapshotAgent.getSnapshotIndex(actor);
                        if (idx < 0) return "0";
                        return ctx.display.getDisplayValue((double) (idx + 1), ctx.parent.args);
                    });

                    // key → actor's UUID string
                    registerActorAction("key", (actor, ctx) -> ctx.display.getDisplayKey(actor));

                    // name → actor's display name
                    registerActorAction("name", (actor, ctx) -> ctx.display.getDisplayName(actor));
                }

                @Override
                protected Optional<TimedPersonalCtx> getContext(@NotNull String query) {
                    return SimpleQueryContext.fromQuery(query, false).flatMap(sqc -> {
                        if (!sqc.name.endsWith(suffix)) return Optional.empty();
                        String holderName = sqc.name.substring(0, sqc.name.length() - suffix.length());
                        return template.getTopManager().getHolder(holderName).map(holder -> {
                            SimpleQueryDisplay<UUID, Double> display =
                                    holder.getTimedValueDisplay(period).orElseGet(holder::getValueDisplay);
                            @Nullable SnapshotAgent<UUID, Double> snap =
                                    holder.getTimedSnapshotAgent(period)
                                            .map(a -> (SnapshotAgent<UUID, Double>) a)
                                            .orElse(null);
                            return new TimedPersonalCtx(holder, display, snap, sqc);
                        });
                    });
                }
            });
        }
    }

    // ── Inner context class for timed personal queries ────────────────────────

    private static final class TimedPersonalCtx implements SimpleQuery.Context {
        final NumberTopHolder holder;
        final SimpleQueryDisplay<UUID, Double> display;
        final @Nullable SnapshotAgent<UUID, Double> snapshotAgent;
        final SimpleQueryContext parent;

        TimedPersonalCtx(
                NumberTopHolder holder,
                SimpleQueryDisplay<UUID, Double> display,
                @Nullable SnapshotAgent<UUID, Double> snapshotAgent,
                SimpleQueryContext parent
        ) {
            this.holder = holder;
            this.display = display;
            this.snapshotAgent = snapshotAgent;
            this.parent = parent;
        }

        @Override
        public @NotNull String getActionName() {
            return parent.getActionName();
        }
    }
}
