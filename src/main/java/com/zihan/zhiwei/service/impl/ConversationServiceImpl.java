package com.zihan.zhiwei.service.impl;

import com.zihan.zhiwei.service.ConversationService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zihan.zhiwei.common.exception.BusinessException;
import com.zihan.zhiwei.common.exception.ErrorCode;
import com.zihan.zhiwei.pojo.entity.Conversation;
import com.zihan.zhiwei.pojo.entity.Message;
import com.zihan.zhiwei.mapper.ConversationMapper;
import com.zihan.zhiwei.mapper.MessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
/**
 * 会话与消息读写服务。
 */
@Service
@RequiredArgsConstructor
public class ConversationServiceImpl implements ConversationService{

    private final ConversationMapper conversationMapper;

    private final MessageMapper messageMapper;
    
    @Transactional
    public Conversation getOrCreate(String userId, Long conversationId) {
        if (conversationId == null) {
            Conversation conversation = new Conversation();
            conversation.setUserId(userId);
            conversation.setTitle("新对话");
            conversationMapper.insert(conversation);
            return conversation;
        }
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        if (!conversation.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该会话");
        }
        return conversation;
    }
    public List<Message> listMessages(Long conversationId) {
        return messageMapper.selectList(
                new LambdaQueryWrapper<Message>()
                        .eq(Message::getConversationId, conversationId)
                        .orderByAsc(Message::getCreateTime)
        );
    }
    @Transactional
    public Message saveMessage(Long conversationId, String role, String content) {
        Message message = new Message();
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content);
        messageMapper.insert(message);
        return message;
    }
}
