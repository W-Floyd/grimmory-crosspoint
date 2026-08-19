package org.booklore.model.dto.response;

import org.booklore.model.enums.TaskType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CronConfig {
    private Long id;
    private TaskType taskType;
    private String cronExpression;
    private Boolean enabled;
    /** Random delay in seconds added to each firing; 0 = fire exactly on the cron slot. */
    private Integer jitterSeconds;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

