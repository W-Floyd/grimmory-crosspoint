package org.booklore.task;

import org.springframework.lang.NonNull;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.TriggerContext;
import org.springframework.scheduling.support.CronTrigger;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A cron trigger whose firings are slid later by a random amount, so a task does not run at the same
 * wall-clock instant every time.
 *
 * <p>This exists for tasks that reach out to a third party. A bare cron slot produces requests at
 * exactly {@code HH:00:00} on every run and on every deployment at once — a pattern that is both
 * obviously automated and needlessly bursty. Spreading firings across a window makes the traffic
 * better behaved without changing how often the task runs.
 *
 * <p><b>The offset is never negative.</b> Sliding a firing earlier could place it before the trigger
 * context's last scheduled execution, which risks firing twice for one slot; treating the cron slot
 * as a floor keeps each slot to exactly one run.
 *
 * <p><b>Jitter must stay well under the cron interval.</b> The next slot is computed from the
 * jittered time, so a jitter approaching the interval would push a firing past the following slot and
 * silently skip it. A jitter of a few percent up to a quarter of the interval is a sane range.
 */
public class JitteredCronTrigger implements Trigger {

    private final CronTrigger delegate;
    private final long maxJitterSeconds;

    public JitteredCronTrigger(String cronExpression, long maxJitterSeconds) {
        this.delegate = new CronTrigger(cronExpression);
        this.maxJitterSeconds = Math.max(0, maxJitterSeconds);
    }

    @Override
    public Instant nextExecution(@NonNull TriggerContext triggerContext) {
        Instant base = delegate.nextExecution(triggerContext);
        if (base == null || maxJitterSeconds == 0) {
            return base;
        }
        // Bound is exclusive, so +1 makes the full jitter window reachable.
        return base.plusSeconds(ThreadLocalRandom.current().nextLong(maxJitterSeconds + 1));
    }

    /** The un-jittered schedule, for logging. */
    public String cronExpression() {
        return delegate.getExpression();
    }

    public long maxJitterSeconds() {
        return maxJitterSeconds;
    }
}
