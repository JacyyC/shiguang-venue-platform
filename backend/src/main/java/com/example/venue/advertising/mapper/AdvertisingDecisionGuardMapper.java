package com.example.venue.advertising.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.advertising.entity.AdvertisingDecisionGuard;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AdvertisingDecisionGuardMapper extends BaseMapper<AdvertisingDecisionGuard> {
    @Select("""
        SELECT * FROM advertising_decision_guard
        WHERE user_id = #{userId} AND slot_code = #{slotCode} AND scope_key = #{scopeKey}
        LIMIT 1
        """)
    AdvertisingDecisionGuard findScope(@Param("userId") Long userId,
                                       @Param("slotCode") String slotCode,
                                       @Param("scopeKey") String scopeKey);

    @Insert("""
        INSERT INTO advertising_decision_guard (user_id, slot_code, scope_key)
        VALUES (#{userId}, #{slotCode}, #{scopeKey})
        """)
    int insertScope(@Param("userId") Long userId,
                    @Param("slotCode") String slotCode,
                    @Param("scopeKey") String scopeKey);

    @Select("""
        SELECT * FROM advertising_decision_guard
        WHERE user_id = #{userId} AND slot_code = #{slotCode} AND scope_key = #{scopeKey}
        LIMIT 1 FOR UPDATE
        """)
    AdvertisingDecisionGuard lockScope(@Param("userId") Long userId,
                                       @Param("slotCode") String slotCode,
                                       @Param("scopeKey") String scopeKey);
}
