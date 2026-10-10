package com.example.venue.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.system.entity.SysConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SysConfigMapper extends BaseMapper<SysConfig> {
    @Select("select * from sys_config where config_key = #{key} limit 1")
    SysConfig findByKey(@Param("key") String key);
}
