package com.example.venue.venue.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.venue.entity.VenueAdminRelation;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface VenueAdminRelationMapper extends BaseMapper<VenueAdminRelation> {
    @Select("""
        select count(1)
        from venue_admin_relation relation
        join venue venue on venue.id = relation.venue_id
        where relation.user_id = #{userId}
          and relation.venue_id = #{venueId}
          and venue.deleted = 0
        """)
    int countRelation(@Param("userId") Long userId, @Param("venueId") Long venueId);

    @Select("""
        select relation.venue_id
        from venue_admin_relation relation
        join venue venue on venue.id = relation.venue_id
        where relation.user_id = #{userId}
          and venue.deleted = 0
        """)
    List<Long> findVenueIdsByUserId(@Param("userId") Long userId);

    @Select("select count(1) from venue where id = #{venueId} and deleted = 0")
    int countValidVenue(@Param("venueId") Long venueId);

    @Select("""
        select distinct u.id
        from sys_user u
        join sys_user_role ur on ur.user_id = u.id
        join sys_role r on r.id = ur.role_id
        where u.deleted = 0
          and u.status = 'ACTIVE'
          and r.deleted = 0
          and r.status = 'ACTIVE'
          and (
            r.role_key = 'SYSTEM_ADMIN'
            or (
              r.role_key = 'VENUE_ADMIN'
              and exists (
                select 1
                from venue_admin_relation var
                where var.user_id = u.id
                  and var.venue_id = #{venueId}
              )
            )
          )
        """)
    List<Long> findUserIdsByVenueId(@Param("venueId") Long venueId);

    @Delete("delete from venue_admin_relation where user_id = #{userId}")
    int deleteByUserId(@Param("userId") Long userId);
}
