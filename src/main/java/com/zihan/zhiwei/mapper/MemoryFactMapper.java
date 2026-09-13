package com.zihan.zhiwei.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zihan.zhiwei.pojo.entity.MemoryFactEntity;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

public interface MemoryFactMapper extends BaseMapper<MemoryFactEntity> {
    @Select("SELECT * FROM memory_fact WHERE id=#{id} AND user_id=#{userId} AND is_deleted=0 LIMIT 1")
    MemoryFactEntity selectOwned(@Param("id") long id, @Param("userId") String userId);

    @Select("SELECT * FROM memory_fact WHERE id=#{id} AND user_id=#{userId} LIMIT 1")
    MemoryFactEntity selectOwnedIncludingDeleted(@Param("id") long id, @Param("userId") String userId);

    @Select("SELECT * FROM memory_fact WHERE identity_hash=#{identityHash} AND user_id=#{userId} AND is_deleted=0 LIMIT 1")
    MemoryFactEntity selectByIdentity(@Param("identityHash") String identityHash, @Param("userId") String userId);

    @Select("""
        <script>
        SELECT * FROM memory_fact WHERE user_id=#{userId} AND is_deleted=0
        <if test='namespace != null'>AND namespace=#{namespace}</if>
        <if test='subject != null'>AND subject=#{subject}</if>
        <if test='predicate != null'>AND predicate=#{predicate}</if>
        <if test='state != null'>AND state=#{state}</if>
        ORDER BY updated_at DESC, id DESC LIMIT #{limit}
        </script>
        """)
    List<MemoryFactEntity> listOwned(@Param("userId") String userId, @Param("namespace") String namespace,
                                     @Param("subject") String subject, @Param("predicate") String predicate,
                                     @Param("state") String state, @Param("limit") int limit);

    @Update("UPDATE memory_fact SET current_version_id=#{versionId}, state=#{state} " +
            "WHERE id=#{id} AND user_id=#{userId} AND version=#{expectedVersion} AND current_version_id IS NULL AND is_deleted=0")
    int casSetInitialVersion(@Param("id") long id, @Param("userId") String userId,
                             @Param("expectedVersion") long expectedVersion, @Param("versionId") long versionId,
                             @Param("state") String state);

    @Update("UPDATE memory_fact SET current_version_id=#{versionId}, state=#{state}, version=version+1 " +
            "WHERE id=#{id} AND user_id=#{userId} AND version=#{expectedVersion} AND is_deleted=0")
    int casActivateVersion(@Param("id") long id, @Param("userId") String userId,
                           @Param("expectedVersion") long expectedVersion, @Param("versionId") long versionId,
                           @Param("state") String state);

    @Update("UPDATE memory_fact SET state='CONFLICTED', version=version+1 " +
            "WHERE id=#{id} AND user_id=#{userId} AND version=#{expectedVersion} AND is_deleted=0")
    int casMarkConflicted(@Param("id") long id, @Param("userId") String userId,
                          @Param("expectedVersion") long expectedVersion);

    @Update("UPDATE memory_fact SET state='DELETED', is_deleted=1, deleted_at=#{now}, version=version+1 " +
            "WHERE id=#{id} AND user_id=#{userId} AND version=#{expectedVersion} AND is_deleted=0")
    int casSoftDelete(@Param("id") long id, @Param("userId") String userId,
                      @Param("expectedVersion") long expectedVersion, @Param("now") LocalDateTime now);

    @Update("UPDATE memory_fact SET state='ACTIVE', is_deleted=0, deleted_at=NULL, version=version+1 " +
            "WHERE id=#{id} AND user_id=#{userId} AND version=#{expectedVersion} AND is_deleted=1")
    int casRestore(@Param("id") long id, @Param("userId") String userId,
                   @Param("expectedVersion") long expectedVersion, @Param("now") LocalDateTime now);
}
