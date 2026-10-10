package com.example.venue.advertising.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.advertising.entity.AdvertisingCampaign;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AdvertisingCampaignMapper extends BaseMapper<AdvertisingCampaign> {
    @Select("SELECT * FROM advertising_campaign WHERE id = #{id} FOR UPDATE")
    AdvertisingCampaign selectByIdForUpdate(@Param("id") Long id);
}
