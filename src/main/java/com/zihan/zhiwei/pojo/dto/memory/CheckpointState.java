package com.zihan.zhiwei.pojo.dto.memory;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
public record CheckpointState(@Min(1) int schemaVersion, @NotBlank String currentNode,
 List<String> completedNodes, List<String> pendingSteps, List<String> toolCallIds,
 Map<String,String> toolResultSummaries, JsonNode resumeParameters) {}