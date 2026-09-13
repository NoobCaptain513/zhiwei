package com.zihan.zhiwei.pojo.dto.memory;
import com.zihan.zhiwei.ai.memory.model.MemoryForgetJob;
import jakarta.validation.constraints.*;
public record MemoryForgetRequest(@NotBlank @Size(max=64) String userId,
 @NotNull MemoryForgetJob.ScopeType scopeType, @Size(max=255) String scopeId,
 @NotBlank @Size(max=64) String requestedBy, @NotBlank @Size(max=500) String reason) {}