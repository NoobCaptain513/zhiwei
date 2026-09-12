package com.zihan.zhiwei.ai.rag.agentic.model;

public enum NextAction {
    ANSWER,
    REWRITE,
    EXPAND_RECALL,
    SWITCH_STRATEGY,
    SWITCH_SOURCE,
    ABSTAIN
}
