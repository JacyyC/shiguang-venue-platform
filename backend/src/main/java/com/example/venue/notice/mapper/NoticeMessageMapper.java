package com.example.venue.notice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.notice.entity.NoticeMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface NoticeMessageMapper extends BaseMapper<NoticeMessage> {
    @Update("update notice_message set read_flag = 1, read_time = now() where user_id = #{userId} and read_flag = 0")
    int markAllRead(@Param("userId") Long userId);

    @Update("update notice_message set read_flag = 1, read_time = now() where id = #{id} and user_id = #{userId}")
    int markRead(@Param("id") Long id, @Param("userId") Long userId);

    @Select("select count(1) from notice_message where user_id = #{userId} and read_flag = 0")
    long unreadCount(@Param("userId") Long userId);

    @Select("select * from notice_message where id = #{id} for update")
    NoticeMessage findByIdForUpdate(@Param("id") Long id);

    @Select("""
        select distinct u.id
        from sys_user u
        join sys_user_role ur on ur.user_id = u.id
        join sys_role r on r.id = ur.role_id
        where u.deleted = 0
          and u.status = 'ACTIVE'
          and r.deleted = 0
          and r.status = 'ACTIVE'
          and r.role_key = 'USER'
        """)
    List<Long> activeOrdinaryUserIds();
}
