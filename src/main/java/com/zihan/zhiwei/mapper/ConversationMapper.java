package com.zihan.zhiwei.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zihan.zhiwei.pojo.entity.Conversation;
import org.apache.ibatis.annotations.Mapper;

/**
 * 会话表 Mapper。
 */
@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {
}