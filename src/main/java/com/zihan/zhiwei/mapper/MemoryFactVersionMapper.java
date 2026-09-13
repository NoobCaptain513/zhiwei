package com.zihan.zhiwei.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zihan.zhiwei.pojo.entity.MemoryFactVersionEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface MemoryFactVersionMapper extends BaseMapper<MemoryFactVersionEntity> {
    @Select("SELECT COALESCE(MAX(version_no),0) FROM memory_fact_version WHERE fact_id=#{factId}")
    int selectMaxVersionNo(@Param("factId") long factId);

    @Select("SELECT v.* FROM memory_fact_version v JOIN memory_fact f ON f.id=v.fact_id " +
            "WHERE v.fact_id=#{factId} AND f.user_id=#{userId} ORDER BY v.version_no ASC")
    List<MemoryFactVersionEntity> listOwned(@Param("factId") long factId, @Param("userId") String userId);
}
