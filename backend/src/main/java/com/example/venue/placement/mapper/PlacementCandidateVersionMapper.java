package com.example.venue.placement.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.placement.entity.PlacementCandidateVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface PlacementCandidateVersionMapper extends BaseMapper<PlacementCandidateVersion> {

    /**
     * 读取已启用版位下满足半开时间窗 effective_from &lt;= now &lt; effective_until 的已发布候选版本，
     * 按 priority DESC, effective_from DESC, id ASC 稳定排序。调用方在锁外读取候选集后逐个走
     * Reader 重校验与频控 guard。
     */
    @Select("""
        SELECT * FROM placement_candidate_version
        WHERE slot_id = #{slotId}
          AND status = 'PUBLISHED'
          AND effective_from <= #{now}
          AND effective_until > #{now}
        ORDER BY priority DESC, effective_from DESC, id ASC
        LIMIT #{limit}
        """)
    List<PlacementCandidateVersion> findPublishedForDecision(@Param("slotId") Long slotId,
                                                             @Param("now") LocalDateTime now,
                                                             @Param("limit") int limit);
}
