package com.example.venue.member.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.member.entity.MemberRewardCatalogVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface MemberRewardCatalogVersionMapper extends BaseMapper<MemberRewardCatalogVersion> {
    @Select("select * from member_reward_catalog_version where id = #{id} for update")
    MemberRewardCatalogVersion selectByIdForUpdate(@Param("id") Long id);

    @Select("""
        select * from member_reward_catalog_version
        where catalog_item_id = #{itemId} and status = 'PUBLISHED'
          and (effective_start_time is null or effective_start_time <= #{now})
          and (effective_end_time is null or effective_end_time > #{now})
        order by version_no desc limit 1
        """)
    MemberRewardCatalogVersion findCurrentPublished(@Param("itemId") Long itemId,
                                                     @Param("now") LocalDateTime now);

    @Select("select coalesce(max(version_no), 0) from member_reward_catalog_version where catalog_item_id = #{itemId}")
    int maxVersionNo(@Param("itemId") Long itemId);

    @Update("""
        update member_reward_catalog_version
        set status = 'RETIRED'
        where catalog_item_id = #{itemId} and status = 'PUBLISHED'
        """)
    int retirePublished(@Param("itemId") Long itemId);
}
