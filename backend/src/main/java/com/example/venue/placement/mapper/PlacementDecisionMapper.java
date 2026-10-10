package com.example.venue.placement.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.placement.entity.PlacementDecision;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface PlacementDecisionMapper extends BaseMapper<PlacementDecision> {

    /**
     * 确保并锁住 (user, slot, candidate) 的持久化 guard 行。不能依赖空范围间隙锁，
     * 因为并发事务可以同时持有兼容的间隙锁并一起通过首次签发检查。
     */
    @Insert("""
        INSERT INTO placement_decision_guard (user_id, slot_id, candidate_version_id)
        VALUES (#{userId}, #{slotId}, #{candidateVersionId})
        ON DUPLICATE KEY UPDATE user_id = VALUES(user_id)
        """)
    int lockUserCandidateRange(@Param("userId") Long userId,
                               @Param("slotId") Long slotId,
                               @Param("candidateVersionId") Long candidateVersionId);

    @Select("""
        SELECT * FROM placement_decision
        WHERE user_id = #{userId}
          AND slot_id = #{slotId}
          AND candidate_version_id = #{candidateVersionId}
          AND status = 'ISSUED'
          AND expires_at > #{now}
        LIMIT 1
        FOR UPDATE
        """)
    PlacementDecision findReusableIssuedForUpdate(@Param("userId") Long userId,
                                                  @Param("slotId") Long slotId,
                                                  @Param("candidateVersionId") Long candidateVersionId,
                                                  @Param("now") LocalDateTime now);

    @Select("SELECT * FROM placement_decision WHERE token_hash = #{tokenHash} LIMIT 1")
    PlacementDecision findByTokenHash(@Param("tokenHash") String tokenHash);

    @Update("""
        UPDATE placement_decision
        SET token_hash = #{tokenHash}
        WHERE id = #{id} AND status = 'ISSUED' AND expires_at > #{now}
        """)
    int rotateIssuedTokenHash(@Param("id") Long id,
                              @Param("tokenHash") String tokenHash,
                              @Param("now") LocalDateTime now);

    @Select("SELECT * FROM placement_decision WHERE id = #{id} AND user_id = #{userId} LIMIT 1")
    PlacementDecision findByIdAndUser(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 首条满足阈值的 CLIENT_VIEWABLE 将决策标记为 CONSUMED；CAS status='ISSUED' 保证同决策
     * 只接受一次可见曝光，后续令牌即使格式有效也只能记录拒绝或缺口。
     */
    @Update("""
        UPDATE placement_decision
        SET status = 'CONSUMED', consumed_at = #{now}
        WHERE id = #{id} AND status = 'ISSUED'
        """)
    int markConsumed(@Param("id") Long id, @Param("now") LocalDateTime now);
}
