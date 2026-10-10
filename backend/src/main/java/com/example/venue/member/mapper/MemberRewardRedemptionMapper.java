package com.example.venue.member.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.member.entity.MemberRewardRedemption;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MemberRewardRedemptionMapper extends BaseMapper<MemberRewardRedemption> {
    @Select("""
        select * from member_reward_redemption
        where user_id = #{userId} and idempotency_key = #{key}
        limit 1 for update
        """)
    MemberRewardRedemption findCommandForUpdate(@Param("userId") Long userId, @Param("key") String key);

    @Select("select * from member_reward_redemption where id = #{id} for update")
    MemberRewardRedemption selectByIdForUpdate(@Param("id") Long id);
}
