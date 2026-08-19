package org.booklore.task.tasks;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.mapper.custom.BookLoreUserTransformer;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.request.TaskCreateRequest;
import org.booklore.model.dto.response.TaskCreateResponse;
import org.booklore.model.dto.settings.OverdriveProperties;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.enums.TaskType;
import org.booklore.model.enums.UserPermission;
import org.booklore.repository.UserRepository;
import org.booklore.service.overdrive.OverDriveService;
import org.booklore.task.TaskStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Polls OverDrive for the users who opted into unattended activity: syncs their cards, borrows holds
 * that have come in, and imports loans that aren't in the library yet.
 *
 * <p><b>Why this task impersonates each user.</b> Everything in {@link OverDriveService} is scoped to
 * the authenticated user — cards, loans, shares and import destinations are all per-user, and a shared
 * card deliberately caches the same loan separately for the owner and each sharee. The task framework
 * runs cron work as the synthetic system user (id {@code -1}), which owns no cards; running the sync
 * under it would write every loan against a user that no one can see. So the pass installs each
 * opted-in user's own security context in turn and drives the ordinary interactive code paths, which
 * is also what keeps an automatic import identical to one the user clicked: same destination, same
 * format rules, same history entry.
 *
 * <p>The operator decides <em>whether and how often</em> this runs (the cron config, off by default,
 * since it makes Grimmory reach out to a third party unattended); each user decides independently
 * whether their own cards are in scope. A user in the work list is still skipped if they have since
 * lost {@link UserPermission#CAN_ACCESS_OVERDRIVE} — the opt-in row outlives the permission, and a
 * revoked permission must stop the automation too.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OverDriveAutoSyncTask implements Task {

    /** Bounds on the randomised gap between one user's sync and the next. */
    private static final long MIN_USER_GAP_MILLIS = 3_000;
    private static final long MAX_USER_GAP_MILLIS = 15_000;

    private final OverDriveService overDriveService;
    private final OverdriveProperties overdriveProperties;
    private final UserRepository userRepository;
    private final BookLoreUserTransformer userTransformer;

    @Override
    public void validatePermissions(BookLoreUser user, TaskCreateRequest request) {
        if (!UserPermission.CAN_ACCESS_TASK_MANAGER.isGranted(user.getPermissions())) {
            throw ApiError.PERMISSION_DENIED.createException(UserPermission.CAN_ACCESS_TASK_MANAGER);
        }
    }

    @Override
    public TaskCreateResponse execute(TaskCreateRequest request) {
        TaskCreateResponse.TaskCreateResponseBuilder builder = TaskCreateResponse.builder()
                .taskId(UUID.randomUUID().toString())
                .taskType(getTaskType());

        if (!overdriveProperties.isEnabled()) {
            log.info("{}: OverDrive integration is disabled; nothing to do", getTaskType());
            return builder.status(TaskStatus.COMPLETED).build();
        }

        long startTime = System.currentTimeMillis();
        List<Long> userIds = overDriveService.autoSyncOptedInUserIds();
        log.info("{}: Task started for {} opted-in user(s)", getTaskType(), userIds.size());

        // Shuffle so the same user is not always first. A fixed id order makes every poll a
        // recognisable, repeating sequence against Libby, and consistently front-loads whoever
        // happens to hold the lowest user id.
        List<Long> shuffled = new ArrayList<>(userIds);
        Collections.shuffle(shuffled);

        int borrowed = 0;
        int imported = 0;
        int failures = 0;
        // The security context is per-thread and this task owns its thread for the whole pass, so it is
        // restored to empty at the end rather than saved and put back.
        try {
            boolean first = true;
            for (Long userId : shuffled) {
                // Space the users out instead of firing every account's sync back-to-back. A burst of
                // chip syncs from one address in the same second is both a poor neighbour and the most
                // machine-like thing this task does; the poll has hours of headroom, so waiting costs
                // nothing. Skipped before the first user, where there is nothing to space from.
                if (!first) {
                    pauseBetweenUsers();
                }
                first = false;
                OverDriveService.AutoSyncOutcome outcome = runForUser(userId);
                borrowed += outcome.holdsBorrowed();
                imported += outcome.loansImported();
                failures += outcome.failures();
            }
            builder.status(TaskStatus.COMPLETED);
        } finally {
            SecurityContextHolder.clearContext();
        }

        log.info("{}: Task completed. {} hold(s) borrowed, {} loan(s) imported, {} failure(s). Duration: {} ms",
                getTaskType(), borrowed, imported, failures, System.currentTimeMillis() - startTime);
        return builder.build();
    }

    /**
     * Run one user's pass under their own identity. A single user's failure — a dead card, an
     * unreachable Libby, a title that won't import — must not abandon the users after them, so the
     * whole pass is contained here.
     */
    private OverDriveService.AutoSyncOutcome runForUser(Long userId) {
        try {
            BookLoreUserEntity entity = userRepository.findByIdWithDetails(userId).orElse(null);
            if (entity == null) {
                log.warn("{}: opted-in user {} no longer exists; skipping", getTaskType(), userId);
                return new OverDriveService.AutoSyncOutcome(0, 0, 0, 0);
            }
            BookLoreUser user = userTransformer.toDTO(entity);
            if (!UserPermission.CAN_ACCESS_OVERDRIVE.isGranted(user.getPermissions())) {
                log.info("{}: user {} no longer has OverDrive access; skipping", getTaskType(), user.getUsername());
                return new OverDriveService.AutoSyncOutcome(0, 0, 0, 0);
            }
            authenticateAs(user);
            OverDriveService.AutoSyncOutcome outcome = overDriveService.runAutoSync();
            if (outcome.holdsBorrowed() > 0 || outcome.loansImported() > 0 || outcome.failures() > 0) {
                log.info("{}: user {} — {} card(s), {} borrowed, {} imported, {} failed",
                        getTaskType(), user.getUsername(), outcome.cardsSynced(), outcome.holdsBorrowed(),
                        outcome.loansImported(), outcome.failures());
            }
            return outcome;
        } catch (Exception e) {
            log.error("{}: auto-sync failed for user {}", getTaskType(), userId, e);
            return new OverDriveService.AutoSyncOutcome(0, 0, 0, 1);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * Wait a random few seconds before starting the next user's sync. Interruption ends the pass
     * rather than being swallowed: an interrupt here means the application is shutting down, and
     * carrying on would hold it open for the rest of the users.
     */
    private void pauseBetweenUsers() {
        long millis = ThreadLocalRandom.current().nextLong(MIN_USER_GAP_MILLIS, MAX_USER_GAP_MILLIS + 1);
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OverDrive auto-sync interrupted between users", e);
        }
    }

    private void authenticateAs(BookLoreUser user) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(user, null, List.of());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }

    @Override
    public TaskType getTaskType() {
        return TaskType.OVERDRIVE_AUTO_SYNC;
    }
}
