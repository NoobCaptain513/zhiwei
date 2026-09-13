package com.zihan.zhiwei.pojo.dto.memory;
import com.zihan.zhiwei.ai.memory.model.AgentCheckpoint;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
public record CheckpointRequest(@NotBlank @Size(max=64) String userId,
 @NotBlank @Size(max=64) String runId, @NotNull Long conversationId,
 @NotNull AgentCheckpoint.Type checkpointType, @NotBlank @Size(max=64) String nodeName,
 @NotNull @Valid CheckpointState state, @NotNull AgentCheckpoint.Status status,
 @NotNull @PositiveOrZero Integer sequenceNo, @Positive Long expectedVersion,
 LocalDateTime resumeAfter, @Size(max=64) String errorCode) {}