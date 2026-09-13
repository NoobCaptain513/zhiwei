package com.zihan.zhiwei.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/** Destructive, owner-scoped SQL used only by the verified forget lifecycle. */
public interface MemoryForgetDataMapper {
    @Delete("""
        DELETE f, v, c FROM memory_fact f
        LEFT JOIN memory_fact_version v ON v.fact_id=f.id
        LEFT JOIN memory_conflict c ON c.fact_id=f.id
        WHERE f.user_id=#{userId} AND f.id=#{factId}
        """)
    int purgeFact(@Param("userId") String userId, @Param("factId") long factId);

    @Delete("DELETE FROM conversation_memory_summary WHERE user_id=#{userId} AND conversation_id=#{conversationId}")
    int purgeSummary(@Param("userId") String userId, @Param("conversationId") long conversationId);

    @Delete("DELETE FROM agent_checkpoint WHERE user_id=#{userId} AND id=#{checkpointId}")
    int purgeCheckpoint(@Param("userId") String userId, @Param("checkpointId") long checkpointId);

    default int purgeConversation(String userId, long conversationId) {
        return purgeConversationMessages(userId, conversationId)
                + purgeSummary(userId, conversationId)
                + purgeConversationCheckpoints(userId, conversationId)
                + purgeConversationRow(userId, conversationId);
    }

    @Delete("DELETE m FROM message m JOIN conversation c ON c.id=m.conversation_id " +
            "WHERE c.user_id=#{userId} AND c.id=#{conversationId}")
    int purgeConversationMessages(@Param("userId") String userId, @Param("conversationId") long conversationId);

    @Delete("DELETE FROM agent_checkpoint WHERE user_id=#{userId} AND conversation_id=#{conversationId}")
    int purgeConversationCheckpoints(@Param("userId") String userId, @Param("conversationId") long conversationId);

    @Delete("DELETE FROM conversation WHERE user_id=#{userId} AND id=#{conversationId}")
    int purgeConversationRow(@Param("userId") String userId, @Param("conversationId") long conversationId);

    default int purgeUser(String userId) {
        return purgeUserFacts(userId) + purgeUserMessages(userId) + purgeUserSummaries(userId)
                + purgeUserCheckpoints(userId) + purgeUserConversations(userId);
    }

    @Delete("""
        DELETE f, v, c FROM memory_fact f
        LEFT JOIN memory_fact_version v ON v.fact_id=f.id
        LEFT JOIN memory_conflict c ON c.fact_id=f.id
        WHERE f.user_id=#{userId}
        """)
    int purgeUserFacts(@Param("userId") String userId);

    @Delete("DELETE m FROM message m JOIN conversation c ON c.id=m.conversation_id WHERE c.user_id=#{userId}")
    int purgeUserMessages(@Param("userId") String userId);

    @Delete("DELETE FROM conversation_memory_summary WHERE user_id=#{userId}")
    int purgeUserSummaries(@Param("userId") String userId);

    @Delete("DELETE FROM agent_checkpoint WHERE user_id=#{userId}")
    int purgeUserCheckpoints(@Param("userId") String userId);

    @Delete("DELETE FROM conversation WHERE user_id=#{userId}")
    int purgeUserConversations(@Param("userId") String userId);

    @Delete("""
        DELETE FROM conversation_memory_summary
        WHERE (expires_at IS NOT NULL AND expires_at<=#{now})
           OR (is_deleted=1 AND deleted_at<=#{deletedBefore})
        ORDER BY id LIMIT #{limit}
        """)
    int purgeExpiredSummaries(@Param("now") LocalDateTime now,
                              @Param("deletedBefore") LocalDateTime deletedBefore,
                              @Param("limit") int limit);

    @Delete("""
        DELETE FROM agent_checkpoint
        WHERE (expires_at IS NOT NULL AND expires_at<=#{now})
           OR (is_deleted=1 AND deleted_at<=#{deletedBefore})
        ORDER BY id LIMIT #{limit}
        """)
    int purgeExpiredCheckpoints(@Param("now") LocalDateTime now,
                                @Param("deletedBefore") LocalDateTime deletedBefore,
                                @Param("limit") int limit);

    @Delete("""
        DELETE f, v, c FROM memory_fact f
        LEFT JOIN memory_fact_version v ON v.fact_id=f.id
        LEFT JOIN memory_conflict c ON c.fact_id=f.id
        WHERE f.id IN (
          SELECT id FROM (SELECT id FROM memory_fact WHERE is_deleted=1 AND deleted_at<=#{deletedBefore}
                          ORDER BY id LIMIT #{limit}) expired
        )
        """)
    int purgeDeletedFacts(@Param("deletedBefore") LocalDateTime deletedBefore, @Param("limit") int limit);
}
