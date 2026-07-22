package com.zihan.zhiwei.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话表实体。
 */
@Data
@TableName("conversation")
public class Conversation {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户ID */
    private String userId;

    /** 会话标题 */
    private String title;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;

    /** 逻辑删除：0未删除 1已删除 */
    @TableLogic
    private Integer isDeleted;
}