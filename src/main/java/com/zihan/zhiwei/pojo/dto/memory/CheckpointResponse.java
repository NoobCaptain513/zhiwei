package com.zihan.zhiwei.pojo.dto.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zihan.zhiwei.ai.memory.model.AgentCheckpoint;

/** Public checkpoint view. Executable Agentic RAG state remains server-only. */
public record CheckpointResponse(AgentCheckpoint checkpoint) {
    public CheckpointResponse {
        if (checkpoint != null && checkpoint.checkpointType() == AgentCheckpoint.Type.AGENTIC_RAG) {
            JsonNode state = checkpoint.state();
            if (state != null && state.isObject()) {
                ObjectNode redacted = ((ObjectNode) state).deepCopy();
                redacted.remove("resumeParameters");
                checkpoint = new AgentCheckpoint(
                        checkpoint.id(), checkpoint.runId(), checkpoint.conversationId(), checkpoint.userId(),
                        checkpoint.checkpointType(), checkpoint.nodeName(), redacted, checkpoint.status(),
                        checkpoint.sequenceNo(), checkpoint.version(), checkpoint.resumeAfter(), checkpoint.errorCode(),
                        checkpoint.expiresAt(), checkpoint.createdAt(), checkpoint.updatedAt());
            }
        }
    }
}
