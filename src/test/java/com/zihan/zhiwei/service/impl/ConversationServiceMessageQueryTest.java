package com.zihan.zhiwei.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.zihan.zhiwei.common.exception.BusinessException;
import com.zihan.zhiwei.mapper.ConversationMapper;
import com.zihan.zhiwei.mapper.MessageMapper;
import com.zihan.zhiwei.pojo.entity.Conversation;
import com.zihan.zhiwei.pojo.entity.Message;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ConversationServiceMessageQueryTest {
    private final ConversationMapper conversationMapper = mock(ConversationMapper.class);
    private final MessageMapper messageMapper = mock(MessageMapper.class);
    private final ConversationServiceImpl service = new ConversationServiceImpl(conversationMapper, messageMapper);

    @Test
    void rejectsUnknownOwnerBeforeReadingMessages() {
        when(conversationMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.listMessagesAfter("mallory", 10L, 20L, 5))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(messageMapper);
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsOnlyRequestedNumberInAscendingIdOrder() {
        Conversation conversation = new Conversation();
        conversation.setId(10L); conversation.setUserId("alice");
        when(conversationMapper.selectOne(any())).thenReturn(conversation);
        when(messageMapper.selectList(any())).thenReturn(List.of(message(23), message(21), message(22)));

        List<Message> result = service.listMessagesAfter("alice", 10L, 20L, 2);

        assertThat(result).extracting(Message::getId).containsExactly(21L, 22L);
        ArgumentCaptor<Wrapper<Message>> wrapper = ArgumentCaptor.forClass(Wrapper.class);
        verify(messageMapper).selectList(wrapper.capture());
        assertThat(wrapper.getValue().getSqlSegment()).contains("id").contains("ORDER BY").contains("LIMIT");
    }

    private Message message(long id) {
        Message message = new Message();
        message.setId(id); message.setConversationId(10L); message.setRole("assistant"); message.setContent("m" + id);
        return message;
    }
}
