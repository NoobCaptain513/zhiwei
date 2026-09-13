package com.zihan.zhiwei.pojo.dto.memory;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.List;
public record MemorySummaryRequest(@Size(max=64) String userId, @NotBlank String summary,
 List<String> openLoops, List<String> decisions, List<String> entities,
 @NotNull @Positive Long coveredThroughMessageId, @NotNull @PositiveOrZero Integer sourceMessageCount,
 @Positive Long expectedVersion, LocalDateTime expiresAt, @NotBlank @Size(max=64) String actorId,
 @Size(max=500) String reason) {}