package com.zihan.zhiwei.ai.rag.agentic;

import com.zihan.zhiwei.ai.rag.agentic.model.RetrievalPlan;

public interface FeedbackQueryRewriter {
    RetrievalPlan rewrite(RagState state);
}
