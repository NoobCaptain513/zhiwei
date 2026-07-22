package com.zihan.zhiwei.pojo.entity;


import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息表实体。
 */
@Data
@TableName("message")
public class Message {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属会话ID */
    private Long conversationId;

    /** 角色：user / assistant / system */
    private String role;

    /** 消息内容 */
    private String content;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 逻辑删除：0未删除 1已删除 */
    @TableLogic
    private Integer isDeleted;
}
