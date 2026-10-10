package com.example.venue.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.system.entity.SysDeletedUserSnapshot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SysDeletedUserSnapshotMapper extends BaseMapper<SysDeletedUserSnapshot> {
    @Select("""
        SELECT *
        FROM sys_deleted_user_snapshot
        WHERE deidentified_at IS NULL
          AND COALESCE(deidentify_after, DATE_ADD(deleted_time, INTERVAL 365 DAY)) <= #{now}
          AND id > #{afterId}
        ORDER BY id ASC
        LIMIT #{limit}
        """)
    List<SysDeletedUserSnapshot> selectDeidentificationCandidates(@Param("now") LocalDateTime now,
                                                                   @Param("afterId") long afterId,
                                                                   @Param("limit") int limit);

    @Update("""
        UPDATE sys_deleted_user_snapshot
        SET username = CONCAT('deidentified-', id),
            nickname = NULL,
            phone = NULL,
            email = NULL,
            avatar = NULL,
            status = NULL,
            role_keys = NULL,
            last_login_time = NULL,
            user_create_time = NULL,
            user_update_time = NULL,
            deleted_by_username = NULL,
            deidentify_after = COALESCE(deidentify_after, DATE_ADD(deleted_time, INTERVAL 365 DAY)),
            deidentified_at = #{now}
        WHERE id = #{id}
          AND deidentified_at IS NULL
          AND COALESCE(deidentify_after, DATE_ADD(deleted_time, INTERVAL 365 DAY)) <= #{now}
        """)
    int deidentifyIfDue(@Param("id") Long id, @Param("now") LocalDateTime now);
}
