package org.booklore.task;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TriggerContext;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.SimpleTriggerContext;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class JitteredCronTriggerTest {

    /** A context whose "last execution" is fixed, so every call computes from the same basis. */
    private static TriggerContext contextAt(Instant instant) {
        SimpleTriggerContext context = new SimpleTriggerContext();
        context.update(instant, instant, instant);
        return context;
    }

    @Test
    void withoutJitterItFiresExactlyOnTheCronSlot() {
        JitteredCronTrigger trigger = new JitteredCronTrigger("0 0 */2 * * *", 0);
        Instant basis = Instant.parse("2026-08-19T01:00:00Z");

        Instant expected = new CronTrigger("0 0 */2 * * *").nextExecution(contextAt(basis));

        assertThat(trigger.nextExecution(contextAt(basis))).isEqualTo(expected);
    }

    @Test
    void jitterOnlyEverDelaysAndStaysWithinTheWindow() {
        long jitter = 1200;
        JitteredCronTrigger trigger = new JitteredCronTrigger("0 0 */2 * * *", jitter);
        Instant basis = Instant.parse("2026-08-19T01:00:00Z");
        Instant slot = new CronTrigger("0 0 */2 * * *").nextExecution(contextAt(basis));

        for (int i = 0; i < 200; i++) {
            Instant next = trigger.nextExecution(contextAt(basis));
            // Never early: an offset before the slot could fire twice for one slot.
            assertThat(next).isBetween(slot, slot.plusSeconds(jitter));
        }
    }

    @Test
    void repeatedFiringsDoNotLandOnTheSameInstant() {
        JitteredCronTrigger trigger = new JitteredCronTrigger("0 0 */2 * * *", 1200);
        Instant basis = Instant.parse("2026-08-19T01:00:00Z");

        Set<Instant> seen = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            seen.add(trigger.nextExecution(contextAt(basis)));
        }
        // The whole point is that the task does not run at a predictable instant.
        assertThat(seen).hasSizeGreaterThan(1);
    }

    @Test
    void aNegativeJitterIsTreatedAsNone() {
        JitteredCronTrigger trigger = new JitteredCronTrigger("0 0 */2 * * *", -60);
        assertThat(trigger.maxJitterSeconds()).isZero();
    }

    @Test
    void jitterStaysWellInsideTheIntervalSoNoSlotIsSkipped() {
        long jitter = 1200;
        JitteredCronTrigger trigger = new JitteredCronTrigger("0 0 */2 * * *", jitter);
        Instant basis = Instant.parse("2026-08-19T01:00:00Z");
        Instant slot = new CronTrigger("0 0 */2 * * *").nextExecution(contextAt(basis));

        // Computing the following slot from the jittered time must still reach the next cron slot,
        // not skip past it — the failure mode when jitter approaches the interval.
        Instant jittered = trigger.nextExecution(contextAt(basis));
        Instant following = new CronTrigger("0 0 */2 * * *").nextExecution(contextAt(jittered));
        assertThat(Duration.between(slot, following)).isEqualTo(Duration.ofHours(2));
    }

    /**
     * V919 hands each deployment a schedule of the form {@code 0 <minute> <phase>/2 * * *}. Spring has
     * to accept that {@code start/step} syntax or the poller silently fails to schedule at boot.
     */
    @Test
    void theRandomizedPerDeploymentScheduleShapeIsAValidExpression() {
        for (int minute : new int[]{0, 37, 59}) {
            for (int phase : new int[]{0, 1}) {
                String expression = "0 " + minute + " " + phase + "/2 * * *";
                assertThatCode(() -> new JitteredCronTrigger(expression, 1200))
                        .as("expression %s", expression)
                        .doesNotThrowAnyException();
            }
        }
    }

    @Test
    void randomizedSchedulesStillFireEveryTwoHours() {
        JitteredCronTrigger trigger = new JitteredCronTrigger("0 37 1/2 * * *", 0);
        Instant basis = Instant.parse("2026-08-19T00:00:00Z");

        Instant first = trigger.nextExecution(contextAt(basis));
        Instant second = trigger.nextExecution(contextAt(first));

        assertThat(Duration.between(first, second)).isEqualTo(Duration.ofHours(2));
    }
}
