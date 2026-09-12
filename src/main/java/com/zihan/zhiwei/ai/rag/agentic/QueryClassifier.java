package com.zihan.zhiwei.ai.rag.agentic;

import com.zihan.zhiwei.ai.rag.agentic.model.AgenticRagRequest;
import com.zihan.zhiwei.ai.rag.agentic.model.QueryClassification;

public interface QueryClassifier {
    QueryClassification classify(AgenticRagRequest request);
}
