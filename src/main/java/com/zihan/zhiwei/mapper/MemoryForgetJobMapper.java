package com.zihan.zhiwei.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zihan.zhiwei.pojo.entity.MemoryForgetJobEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface MemoryForgetJobMapper extends BaseMapper<MemoryForgetJobEntity> {
    @Select("SELECT * FROM memory_forget_job WHERE job_id=#{jobId} AND user_id=#{userId} LIMIT 1")
    MemoryForgetJobEntity selectOwned(@Param("userId") String userId, @Param("jobId") String jobId);
}