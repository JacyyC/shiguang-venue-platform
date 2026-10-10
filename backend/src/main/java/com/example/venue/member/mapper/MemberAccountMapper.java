package com.example.venue.member.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.member.entity.MemberAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface MemberAccountMapper extends BaseMapper<MemberAccount> {
    @Select("""
        select *
        from member_account
        where user_id = #{userId}
          and deleted = 0
        limit 1
        """)
    MemberAccount findByUserId(@Param("userId") Long userId);

    @Select("""
        select *
        from member_account
        where user_id = #{userId}
          and deleted = 0
        limit 1
        for update
        """)
    MemberAccount findByUserIdForUpdate(@Param("userId") Long userId);

    @Select("select count(*) from member_account where deleted = 0")
    long countOverviewMembers();

    @Select("select count(*) from member_account where deleted = 0 and status = 'ACTIVE'")
    long countOverviewActiveMembers();

    @Select("""
        select level_code as levelCode, count(*) as memberCount
        from member_account
        where deleted = 0
        group by level_code
        order by level_code
        """)
    List<Map<String, Object>> selectOverviewLevelDistribution();

    @Select("""
        select *
        from member_account
        where deleted = 0
          and (
            json_contains(profile_tags_json, json_quote('HIGH_VALUE_USER')) = 1
            or total_consumption_amount >= 2000.00
            or growth_value >= 2000
          )
        order by total_consumption_amount desc, last_active_time desc, id desc
        limit #{limit}
        """)
    List<MemberAccount> selectOverviewCoreMembers(@Param("limit") int limit);

    @Select("""
        select *
        from member_account
        where deleted = 0
          and (last_active_time is null or last_active_time < #{dormantBefore})
        order by last_active_time asc, total_consumption_amount desc, id desc
        limit #{limit}
        """)
    List<MemberAccount> selectOverviewDormantMembers(@Param("dormantBefore") LocalDateTime dormantBefore,
                                                      @Param("limit") int limit);
}
