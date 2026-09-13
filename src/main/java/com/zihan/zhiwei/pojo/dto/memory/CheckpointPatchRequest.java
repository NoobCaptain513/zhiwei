package com.zihan.zhiwei.pojo.dto.memory;

import com.zihan.zhiwei.ai.memory.model.AgentCheckpoint;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public record CheckpointPatchRequest(
        @Size(max = 64) String userId,
        @NotNull AgentCheckpoint.Status status,
        @Size(max = 64) String nodeName,
        @Valid CheckpointState state,
        @Size(max = 64) String errorCode,
        LocalDateTime resumeAfter,
        @jakarta.validation.constraints.NotBlank @Size(max = 64) String actorId,
        @jakarta.validation.constraints.NotBlank @Size(max = 500) String reason) {
}
