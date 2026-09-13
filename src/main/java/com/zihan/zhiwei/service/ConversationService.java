package com.zihan.zhiwei.service;

import java.util.List;

import com.zihan.zhiwei.pojo.entity.Conversation;
import com.zihan.zhiwei.pojo.entity.Message;

public interface ConversationService {
    /**
     * 获取或创建会话
     */
    Conversation getOrCreate(String userId, Long conversationId);
    /**
     * 查询会话下的消息历史
     */
    List<Message> listMessages(Long conversationId);

    /**
     * Owner-scoped read of messages strictly after the supplied message ID.
     */
    List<Message> listMessagesAfter(String userId, Long conversationId, Long afterMessageId, int limit);

    /**
     * 保存一条消息
     */
    Message saveMessage(Long conversationId, String role, String content);
}
