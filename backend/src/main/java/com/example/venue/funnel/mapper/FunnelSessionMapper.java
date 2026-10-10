package com.example.venue.funnel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.funnel.entity.FunnelSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface FunnelSessionMapper extends BaseMapper<FunnelSession> {
    @Select("select * from funnel_session where session_key = #{sessionKey} and deleted = 0 limit 1")
    FunnelSession findBySessionKey(@Param("sessionKey") String sessionKey);

    @Update("""
        update funnel_session
        set last_seen_at = case when last_seen_at < #{lastSeenAt} then #{lastSeenAt} else last_seen_at end,
            update_time = #{updateTime}
        where session_key = #{sessionKey}
          and deleted = 0
        """)
    int touchLastSeen(@Param("sessionKey") String sessionKey,
                      @Param("lastSeenAt") LocalDateTime lastSeenAt,
                      @Param("updateTime") LocalDateTime updateTime);
}
