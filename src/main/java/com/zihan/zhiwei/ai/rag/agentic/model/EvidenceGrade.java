package com.zihan.zhiwei.ai.rag.agentic.model;

import java.util.List;

public record EvidenceGrade(
        boolean sufficient,
        List<Long> acceptedEvidenceIds,
        List<String> coveredSubQuestions,
        List<String> gaps,
        List<EvidenceConflict> conflicts,
        NextAction nextAction,
        String reason
) {
    public EvidenceGrade {
        acceptedEvidenceIds = acceptedEvidenceIds == null ? List.of() : List.copyOf(acceptedEvidenceIds);
        coveredSubQuestions = coveredSubQuestions == null ? List.of() : List.copyOf(coveredSubQuestions);
        gaps = gaps == null ? List.of() : List.copyOf(gaps);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        nextAction = nextAction == null ? NextAction.REWRITE : nextAction;
    }
}
