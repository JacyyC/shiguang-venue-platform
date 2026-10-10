package com.example.venue.member.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.member.entity.MemberLevel;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MemberLevelMapper extends BaseMapper<MemberLevel> {
    @Select("""
        select *
        from member_level
        where growth_threshold <= #{growthValue}
          and status = 'ACTIVE'
          and deleted = 0
        order by growth_threshold desc, sort_order desc, id desc
        limit 1
        """)
    MemberLevel findActiveLevelForGrowth(@Param("growthValue") int growthValue);

    @Select("""
        select *
        from member_level
        where level_code = #{levelCode}
          and deleted = 0
        limit 1
        """)
    MemberLevel findByCode(@Param("levelCode") String levelCode);

    @Select("""
        select *
        from member_level
        where status = 'ACTIVE'
          and deleted = 0
        order by growth_threshold asc, sort_order asc, id asc
        """)
    List<MemberLevel> findActiveLevels();
}
