package org.booklore.task;

import org.springframework.lang.NonNull;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.TriggerContext;
import org.springframework.scheduling.support.CronTrigger;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

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
 * <p><b>A drawn firing is written down.</b> The instant is handed to a callback as it is computed, so
 * it can outlive the process. On the next start the stored firing is honoured rather than recomputed:
 * a restart between the plain cron slot and the jittered firing used to lose that run entirely, since
 * recomputing from "now" skips to the following slot.
 *
 * <p><b>Jitter must stay well under the cron interval.</b> The next slot is computed from the
 * jittered time, so a jitter approaching the interval would push a firing past the following slot and
 * silently skip it. A jitter of a few percent up to a quarter of the interval is a sane range.
 */
public class JitteredCronTrigger implements Trigger {

    /**
     * How long after a missed firing the recovery run is spread over. A restart that lost a slot
     * should still do the work, but starting the instant the application is ready would put a
     * "runs immediately after every deploy" signature on the account — the opposite of what the
     * jitter is for.
     */
    private static final long RECOVERY_SPREAD_SECONDS = 300;

    private final CronTrigger delegate;
    private final long maxJitterSeconds;
    private final Consumer<Instant> onFiringPlanned;

    /** A firing already drawn and written down before this process started, or null. */
    private Instant pending;

    public JitteredCronTrigger(String cronExpression, long maxJitterSeconds) {
        this(cronExpression, maxJitterSeconds, null, instant -> { });
    }

    /**
     * @param pending         a firing drawn by a previous process and not yet run, honoured once
     * @param onFiringPlanned told each firing as it is drawn, so it can be written down
     */
    public JitteredCronTrigger(String cronExpression, long maxJitterSeconds, Instant pending,
                               Consumer<Instant> onFiringPlanned) {
        this.delegate = new CronTrigger(cronExpression);
        this.maxJitterSeconds = Math.max(0, maxJitterSeconds);
        this.pending = pending;
        this.onFiringPlanned = onFiringPlanned;
    }

    @Override
    public Instant nextExecution(@NonNull TriggerContext triggerContext) {
        Instant planned = takePending();
        if (planned == null) {
            Instant base = delegate.nextExecution(triggerContext);
            if (base == null) {
                return null;
            }
            // Bound is exclusive, so +1 makes the full jitter window reachable.
            planned = maxJitterSeconds == 0
                    ? base
                    : base.plusSeconds(ThreadLocalRandom.current().nextLong(maxJitterSeconds + 1));
        }
        onFiringPlanned.accept(planned);
        return planned;
    }

    /**
     * The firing a previous process drew, if it is still worth honouring, consumed so it is used once.
     *
     * <p>Still in the future: keep it, which is the whole point — a restart inside the jitter window
     * no longer skips the slot it was already committed to.
     *
     * <p>Already past: the run was missed, so do it shortly rather than not at all, spread over a few
     * minutes so a fleet of restarts does not fire together.
     */
    private Instant takePending() {
        Instant planned = pending;
        pending = null;
        if (planned == null) {
            return null;
        }
        Instant now = Instant.now();
        if (planned.isAfter(now)) {
            return planned;
        }
        return now.plusSeconds(ThreadLocalRandom.current().nextLong(RECOVERY_SPREAD_SECONDS + 1));
    }

    /** The un-jittered schedule, for logging. */
    public String cronExpression() {
        return delegate.getExpression();
    }

    public long maxJitterSeconds() {
        return maxJitterSeconds;
    }
}
