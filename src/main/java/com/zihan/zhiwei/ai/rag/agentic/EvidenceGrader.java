package com.zihan.zhiwei.ai.rag.agentic;

import com.zihan.zhiwei.ai.rag.agentic.model.EvidenceGrade;

public interface EvidenceGrader {
    EvidenceGrade grade(RagState state);
}
