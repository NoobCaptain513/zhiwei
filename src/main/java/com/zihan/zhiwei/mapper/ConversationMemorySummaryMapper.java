package com.zihan.zhiwei.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zihan.zhiwei.pojo.entity.ConversationMemorySummary;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

public interface ConversationMemorySummaryMapper extends BaseMapper<ConversationMemorySummary> {
    @Select("SELECT * FROM conversation_memory_summary WHERE user_id=#{userId} AND conversation_id=#{conversationId} AND is_deleted=0 LIMIT 1")
    ConversationMemorySummary selectOwned(@Param("userId") String userId, @Param("conversationId") long conversationId);

    @Select("SELECT * FROM conversation_memory_summary WHERE user_id=#{userId} AND id=#{id} LIMIT 1")
    ConversationMemorySummary selectOwnedIncludingDeleted(@Param("userId") String userId, @Param("id") long id);

    @Update("""
        UPDATE conversation_memory_summary SET summary=#{row.summary}, open_loops=#{row.openLoops},
        decisions=#{row.decisions}, entities=#{row.entities}, covered_through_message_id=#{row.coveredThroughMessageId},
        source_message_count=#{row.sourceMessageCount}, expires_at=#{row.expiresAt}, status=#{row.status},
        version=version+1 WHERE id=#{id} AND user_id=#{userId} AND version=#{expectedVersion} AND is_deleted=0
        """)
    int casUpdate(@Param("row") ConversationMemorySummary row, @Param("id") long id,
                  @Param("expectedVersion") long expectedVersion, @Param("userId") String userId);

    @Update("UPDATE conversation_memory_summary SET status='DELETED', is_deleted=1, deleted_at=#{now}, version=version+1 " +
            "WHERE id=#{id} AND user_id=#{userId} AND version=#{expectedVersion} AND is_deleted=0")
    int casSoftDelete(@Param("id") long id, @Param("userId") String userId,
                      @Param("expectedVersion") long expectedVersion, @Param("now") LocalDateTime now);

    @Update("UPDATE conversation_memory_summary SET status='ACTIVE', is_deleted=0, deleted_at=NULL, version=version+1 " +
            "WHERE id=#{id} AND user_id=#{userId} AND version=#{expectedVersion} AND is_deleted=1")
    int casRestore(@Param("id") long id, @Param("userId") String userId,
                   @Param("expectedVersion") long expectedVersion, @Param("now") LocalDateTime now);
}
