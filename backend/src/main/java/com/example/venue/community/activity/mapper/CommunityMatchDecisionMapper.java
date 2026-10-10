package com.example.venue.community.activity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.community.activity.entity.CommunityMatchDecision;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CommunityMatchDecisionMapper extends BaseMapper<CommunityMatchDecision> {
    @Select("""
        select * from community_match_decision
        where source_request_id = #{sourceId}
          and candidate_request_id = #{candidateId}
          and source_version = #{sourceVersion}
          and candidate_version = #{candidateVersion}
          and rule_version = #{ruleVersion}
        limit 1
        """)
    CommunityMatchDecision findReplay(@Param("sourceId") Long sourceId,
                                      @Param("candidateId") Long candidateId,
                                      @Param("sourceVersion") Long sourceVersion,
                                      @Param("candidateVersion") Long candidateVersion,
                                      @Param("ruleVersion") String ruleVersion);

    @Select("""
        select d.* from community_match_decision d
        join community_companion_request r
          on r.id = d.candidate_request_id
         and r.version_no = d.candidate_version
         and r.status = 'PUBLISHED'
        where d.source_request_id = #{sourceId}
          and d.source_version = #{sourceVersion}
          and d.rule_version = #{ruleVersion}
          and d.outcome = 'MATCHED'
        order by d.score desc, d.candidate_request_id asc
        limit #{limit}
        """)
    List<CommunityMatchDecision> listMatches(@Param("sourceId") Long sourceId,
                                             @Param("sourceVersion") Long sourceVersion,
                                             @Param("ruleVersion") String ruleVersion,
                                             @Param("limit") int limit);
}
