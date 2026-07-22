package com.zihan.zhiwei.service;

import com.zihan.zhiwei.pojo.dto.ChatRequest;
import com.zihan.zhiwei.pojo.dto.ChatResponse;
import com.zihan.zhiwei.ai.stream.StreamResult;

import java.util.function.Consumer;

public interface ChatService {

    /** 同步聊天 */
    ChatResponse chat(ChatRequest request);

    /**
     * D15: 流式聊天。
     * 每产出一个 token 就调用 onToken；流结束后返回元数据。
     */
    StreamResult streamChat(ChatRequest request, Consumer<String> onToken);
}