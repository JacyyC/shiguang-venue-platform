package com.example.venue.siteops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.siteops.entity.SiteShift;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SiteShiftMapper extends BaseMapper<SiteShift> {
    @Insert("insert ignore into site_shift_assignment_guard (assigned_user_id) values (#{assignedUserId})")
    int insertAssignmentGuard(@Param("assignedUserId") Long assignedUserId);

    @Select("select assigned_user_id from site_shift_assignment_guard where assigned_user_id = #{assignedUserId} for update")
    Long lockAssignmentGuard(@Param("assignedUserId") Long assignedUserId);
}
