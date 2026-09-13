package com.zihan.zhiwei.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zihan.zhiwei.pojo.entity.MemoryConflictEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface MemoryConflictMapper extends BaseMapper<MemoryConflictEntity> {
    @Select("SELECT c.* FROM memory_conflict c JOIN memory_fact f ON f.id=c.fact_id " +
            "WHERE c.id=#{id} AND f.user_id=#{userId} LIMIT 1")
    MemoryConflictEntity selectOwned(@Param("id") long id, @Param("userId") String userId);

    @Select("SELECT c.* FROM memory_conflict c JOIN memory_fact f ON f.id=c.fact_id " +
            "WHERE c.fact_id=#{factId} AND f.user_id=#{userId} ORDER BY c.created_at DESC,c.id DESC")
    List<MemoryConflictEntity> listOwned(@Param("factId") long factId, @Param("userId") String userId);

    @Update("UPDATE memory_conflict c JOIN memory_fact f ON f.id=c.fact_id " +
            "SET c.status=#{status},c.resolution=#{resolution},c.resolved_version_id=#{resolvedVersionId}," +
            "c.resolved_by=#{actorId},c.reason=#{reason},c.resolved_at=#{now} " +
            "WHERE c.id=#{id} AND f.user_id=#{userId} AND c.status='OPEN'")
    int resolveOpen(@Param("id") long id, @Param("userId") String userId, @Param("status") String status,
                    @Param("resolution") String resolution, @Param("resolvedVersionId") Long resolvedVersionId,
                    @Param("actorId") String actorId, @Param("reason") String reason,
                    @Param("now") LocalDateTime now);
}
