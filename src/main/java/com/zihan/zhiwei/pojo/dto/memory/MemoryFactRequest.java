package com.zihan.zhiwei.pojo.dto.memory;
import com.fasterxml.jackson.databind.JsonNode;
import com.zihan.zhiwei.ai.memory.model.MemoryFact;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
public record MemoryFactRequest(@Size(max=64) String userId,
 @NotBlank @Size(max=64) String namespace, @NotBlank @Size(max=255) String subject,
 @NotBlank @Size(max=128) String predicate, @NotNull JsonNode value,
 @NotNull MemoryFact.SourceType sourceType, @Size(max=255) String sourceRef,
 @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal confidence,
 LocalDateTime validFrom, LocalDateTime validTo, @NotBlank @Size(max=64) String actorId,
 @Size(max=500) String reason, @Positive Long expectedVersion) {}