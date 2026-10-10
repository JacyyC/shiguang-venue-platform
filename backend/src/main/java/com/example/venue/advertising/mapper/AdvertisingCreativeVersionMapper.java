package com.example.venue.advertising.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.advertising.entity.AdvertisingCreativeVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AdvertisingCreativeVersionMapper extends BaseMapper<AdvertisingCreativeVersion> {
    @Select("SELECT * FROM advertising_creative_version WHERE id = #{id} FOR UPDATE")
    AdvertisingCreativeVersion selectByIdForUpdate(@Param("id") Long id);
}
