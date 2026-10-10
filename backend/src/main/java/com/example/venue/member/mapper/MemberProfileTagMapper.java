package com.example.venue.member.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.member.entity.MemberProfileTag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface MemberProfileTagMapper extends BaseMapper<MemberProfileTag> {
    @Select("""
        select *
        from member_profile_tag
        where user_id = #{userId}
          and status = 'ACTIVE'
        order by generated_at desc, id desc
        """)
    List<MemberProfileTag> findActiveByUserId(@Param("userId") Long userId);

    @Update("""
        update member_profile_tag
        set status = 'INACTIVE',
            update_time = current_timestamp
        where user_id = #{userId}
          and status = 'ACTIVE'
        """)
    int deactivateTagsByUserId(@Param("userId") Long userId);
}
