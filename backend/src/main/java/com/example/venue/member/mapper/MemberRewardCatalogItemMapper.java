package com.example.venue.member.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.member.entity.MemberRewardCatalogItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MemberRewardCatalogItemMapper extends BaseMapper<MemberRewardCatalogItem> {
    @Select("select * from member_reward_catalog_item where id = #{id} and deleted = 0 for update")
    MemberRewardCatalogItem selectByIdForUpdate(@Param("id") Long id);
}
