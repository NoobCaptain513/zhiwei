package com.zihan.zhiwei.ai.rag.agentic;

import com.zihan.zhiwei.ai.rag.agentic.model.AgenticRagResult;

public interface AnswerGenerator {
    AgenticRagResult generateAndVerify(RagState state);
    AgenticRagResult abstain(RagState state);
}
