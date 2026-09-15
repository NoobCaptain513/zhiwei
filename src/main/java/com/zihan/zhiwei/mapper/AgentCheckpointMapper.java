package com.zihan.zhiwei.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zihan.zhiwei.pojo.entity.AgentCheckpointEntity;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

public interface AgentCheckpointMapper extends BaseMapper<AgentCheckpointEntity> {
    @Select("SELECT * FROM agent_checkpoint WHERE user_id=#{userId} AND id=#{id} AND is_deleted=0 LIMIT 1")
    AgentCheckpointEntity selectOwned(@Param("userId") String userId, @Param("id") long id);

    @Select("SELECT * FROM agent_checkpoint WHERE user_id=#{userId} AND id=#{id} LIMIT 1")
    AgentCheckpointEntity selectOwnedIncludingDeleted(@Param("userId") String userId, @Param("id") long id);

    @Select("""
        <script>
        SELECT * FROM agent_checkpoint WHERE user_id=#{userId} AND is_deleted=0
        <if test='conversationId != null'>AND conversation_id=#{conversationId}</if>
        <if test='status != null'>AND status=#{status}</if>
        ORDER BY updated_at DESC,id DESC LIMIT #{limit}
        </script>
        """)
    List<AgentCheckpointEntity> listOwned(@Param("userId") String userId,
            @Param("conversationId") Long conversationId, @Param("status") String status,
            @Param("limit") int limit);

    @Select("""
        SELECT COUNT(1) FROM agent_checkpoint
        WHERE user_id=#{userId} AND run_id=#{runId} AND is_deleted=0
          AND (sequence_no > #{sequenceNo} OR (sequence_no = #{sequenceNo} AND id > #{checkpointId}))
        """)
    int countLaterInRun(@Param("userId") String userId, @Param("runId") String runId,
                        @Param("sequenceNo") int sequenceNo, @Param("checkpointId") long checkpointId);

    @Update("""
        UPDATE agent_checkpoint SET node_name=#{row.nodeName}, state_json=#{row.stateJson}, status=#{row.status},
        resume_after=#{row.resumeAfter}, error_code=#{row.errorCode}, expires_at=#{row.expiresAt}, version=version+1
        WHERE id=#{id} AND user_id=#{userId} AND version=#{expectedVersion} AND status=#{expectedStatus} AND is_deleted=0
        """)
    int casTransition(@Param("row") AgentCheckpointEntity row, @Param("id") long id,
                      @Param("userId") String userId, @Param("expectedVersion") long expectedVersion,
                      @Param("expectedStatus") String expectedStatus);

    @Update("""
        UPDATE agent_checkpoint SET node_name=#{row.nodeName}, state_json=#{row.stateJson},
        sequence_no=#{row.sequenceNo}, error_code=NULL, resume_after=NULL, expires_at=#{row.expiresAt},
        version=version+1
        WHERE id=#{id} AND user_id=#{userId} AND version=#{expectedVersion}
          AND status='RUNNING' AND is_deleted=0
        """)
    int casProgress(@Param("row") AgentCheckpointEntity row, @Param("id") long id,
                    @Param("userId") String userId, @Param("expectedVersion") long expectedVersion);

    @Update("UPDATE agent_checkpoint SET is_deleted=1, deleted_at=#{now}, version=version+1 " +
            "WHERE id=#{id} AND user_id=#{userId} AND version=#{expectedVersion} AND is_deleted=0")
    int casSoftDelete(@Param("id") long id, @Param("userId") String userId,
                      @Param("expectedVersion") long expectedVersion, @Param("now") LocalDateTime now);

    @Update("UPDATE agent_checkpoint SET is_deleted=0, deleted_at=NULL, version=version+1, updated_at=#{now} " +
            "WHERE id=#{id} AND user_id=#{userId} AND version=#{expectedVersion} AND is_deleted=1")
    int casRestore(@Param("id") long id, @Param("userId") String userId,
                   @Param("expectedVersion") long expectedVersion, @Param("now") LocalDateTime now);
}
