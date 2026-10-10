package com.example.venue.advertising.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.advertising.entity.AdvertisingAdvertiser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AdvertisingAdvertiserMapper extends BaseMapper<AdvertisingAdvertiser> {
    @Select("SELECT * FROM advertising_advertiser WHERE id = #{id} FOR UPDATE")
    AdvertisingAdvertiser selectByIdForUpdate(@Param("id") Long id);
}
