package com.zihan.zhiwei.ai.rag.agentic;

import com.zihan.zhiwei.ai.rag.agentic.model.RetrievalPlan;

public interface QueryPlanner {
    RetrievalPlan plan(RagState state);
}
