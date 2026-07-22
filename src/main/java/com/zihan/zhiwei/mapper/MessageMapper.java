package com.zihan.zhiwei.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zihan.zhiwei.pojo.entity.Message;
import org.apache.ibatis.annotations.Mapper;

/**
 * 消息表 Mapper。
 */
@Mapper
public interface MessageMapper extends BaseMapper<Message> {
}