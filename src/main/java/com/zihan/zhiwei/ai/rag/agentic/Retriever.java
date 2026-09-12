package com.zihan.zhiwei.ai.rag.agentic;

import com.zihan.zhiwei.ai.rag.agentic.model.RetrievalResult;

public interface Retriever {
    RetrievalResult retrieve(RagState state);
}
