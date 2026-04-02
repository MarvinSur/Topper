package me.hsgamer.topper.agent.timed;

import java.time.DayOfWeek;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;

/**
 * Represents a reset period for timed leaderboards.
 */
public enum TimePeriod {

    /**
     * Resets every Monday at 00:00 (start of the week).
     */
    WEEKLY {
        @Override
        public ZonedDateTime nextReset(ZonedDateTime now) {
            ZonedDateTime nextMonday = now
                    .with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
                    .truncatedTo(ChronoUnit.DAYS);
            // If we are exactly on Monday 00:00, jump to next Monday
            if (nextMonday.equals(now.truncatedTo(ChronoUnit.DAYS)) && now.truncatedTo(ChronoUnit.DAYS).getDayOfWeek() == DayOfWeek.MONDAY) {
                nextMonday = nextMonday.plusWeeks(1);
            }
            return nextMonday;
        }

        @Override
        public ZonedDateTime currentPeriodStart(ZonedDateTime now) {
            return now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .truncatedTo(ChronoUnit.DAYS);
        }

        @Override
        public String name() {
            return "weekly";
        }
    },

    /**
     * Resets on the 1st of every month at 00:00.
     */
    MONTHLY {
        @Override
        public ZonedDateTime nextReset(ZonedDateTime now) {
            ZonedDateTime firstOfNextMonth = now
                    .with(TemporalAdjusters.firstDayOfNextMonth())
                    .truncatedTo(ChronoUnit.DAYS);
            return firstOfNextMonth;
        }

        @Override
        public ZonedDateTime currentPeriodStart(ZonedDateTime now) {
            return now.with(TemporalAdjusters.firstDayOfMonth())
                    .truncatedTo(ChronoUnit.DAYS);
        }

        @Override
        public String name() {
            return "monthly";
        }
    };

    /**
     * Returns the next reset time after the given time.
     */
    public abstract ZonedDateTime nextReset(ZonedDateTime now);

    /**
     * Returns the start of the current period (e.g. this Monday, this 1st).
     */
    public abstract ZonedDateTime currentPeriodStart(ZonedDateTime now);

    /**
     * Returns the lowercase identifier used in placeholders and storage suffixes.
     */
    public abstract String name();

    /**
     * Milliseconds until the next reset from now.
     */
    public long millisUntilReset(ZonedDateTime now) {
        return java.time.Duration.between(now, nextReset(now)).toMillis();
    }
}
