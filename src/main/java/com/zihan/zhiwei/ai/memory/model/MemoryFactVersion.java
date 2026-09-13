package com.zihan.zhiwei.ai.memory.model;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
public record MemoryFactVersion(Long id, Long factId, Integer versionNo, JsonNode value,
 String normalizedValueHash, MemoryFact.SourceType sourceType, String sourceRef, BigDecimal confidence,
 LocalDateTime validFrom, LocalDateTime validTo, LocalDateTime recordedAt, String createdBy,
 String changeReason) {}