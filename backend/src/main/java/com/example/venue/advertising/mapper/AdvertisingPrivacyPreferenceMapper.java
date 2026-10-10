package com.example.venue.advertising.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.advertising.entity.AdvertisingPrivacyPreference;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AdvertisingPrivacyPreferenceMapper extends BaseMapper<AdvertisingPrivacyPreference> {
    @Select("SELECT * FROM advertising_privacy_preference WHERE user_id = #{userId} LIMIT 1")
    AdvertisingPrivacyPreference findByUserId(@Param("userId") Long userId);

    @Select("SELECT * FROM advertising_privacy_preference WHERE user_id = #{userId} LIMIT 1 FOR UPDATE")
    AdvertisingPrivacyPreference findByUserIdForUpdate(@Param("userId") Long userId);
}
