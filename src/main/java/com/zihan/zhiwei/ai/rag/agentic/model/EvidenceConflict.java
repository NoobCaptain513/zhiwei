package com.zihan.zhiwei.ai.rag.agentic.model;

public record EvidenceConflict(
        Long leftEvidenceId,
        Long rightEvidenceId,
        String topic,
        String description
) {}
