package com.zihan.zhiwei.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zihan.zhiwei.pojo.entity.MemoryAuditEventEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;
public interface MemoryAuditEventMapper extends BaseMapper<MemoryAuditEventEntity> {
 @Select("""
  <script>
  SELECT * FROM memory_audit_event WHERE user_id=#{userId}
  <if test='resourceType != null'>AND resource_type=#{resourceType}</if>
  <if test='resourceId != null'>AND resource_id=#{resourceId}</if>
  ORDER BY created_at DESC,event_id DESC LIMIT #{limit}
  </script>
  """)
 List<MemoryAuditEventEntity> listOwned(@Param("userId") String userId,
  @Param("resourceType") String resourceType, @Param("resourceId") String resourceId,
  @Param("limit") int limit);
}