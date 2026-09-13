package com.zihan.zhiwei.pojo.dto.memory;
import com.fasterxml.jackson.databind.JsonNode;
import com.zihan.zhiwei.ai.memory.model.MemoryConflict;
import jakarta.validation.constraints.*;
public record ResolveMemoryConflictRequest(@NotNull MemoryConflict.Resolution resolution,
 JsonNode mergedValue, @NotBlank @Size(max=64) String actorId, @NotBlank @Size(max=500) String reason,
 @NotNull @Positive Long expectedVersion) {}