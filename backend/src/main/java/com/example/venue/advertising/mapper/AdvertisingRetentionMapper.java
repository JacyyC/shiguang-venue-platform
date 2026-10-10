package com.example.venue.advertising.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AdvertisingRetentionMapper {
    @Select("""
        SELECT id FROM advertising_event
        WHERE occurred_at < #{cutoff}
        ORDER BY id
        LIMIT #{limit}
        """)
    List<Long> selectExpiredEventIds(@Param("cutoff") LocalDateTime cutoff,
                                     @Param("limit") int limit);

    @Delete({
        "<script>",
        "DELETE FROM advertising_event WHERE id IN",
        "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
        "</script>"
    })
    int deleteEventIds(@Param("ids") List<Long> ids);

    @Select("""
        SELECT id FROM advertising_decision
        WHERE create_time < #{cutoff}
        ORDER BY id
        LIMIT #{limit}
        """)
    List<Long> selectExpiredDecisionIds(@Param("cutoff") LocalDateTime cutoff,
                                        @Param("limit") int limit);

    @Delete({
        "<script>",
        "DELETE FROM advertising_decision WHERE id IN",
        "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
        "</script>"
    })
    int deleteDecisionIds(@Param("ids") List<Long> ids);

    @Select("""
        SELECT id FROM advertising_conversion_callback
        WHERE status = 'PROCESSED'
          AND occurred_at < #{cutoff}
        ORDER BY id
        LIMIT #{limit}
        """)
    List<Long> selectExpiredProcessedCallbackIds(@Param("cutoff") LocalDateTime cutoff,
                                                 @Param("limit") int limit);

    @Delete({
        "<script>",
        "DELETE FROM advertising_conversion_callback WHERE id IN",
        "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
        "</script>"
    })
    int deleteCallbackIds(@Param("ids") List<Long> ids);

    @Select("""
        SELECT id FROM advertising_attribution
        WHERE conversion_occurred_at < #{cutoff}
        ORDER BY id
        LIMIT #{limit}
        """)
    List<Long> selectExpiredAttributionIds(@Param("cutoff") LocalDateTime cutoff,
                                           @Param("limit") int limit);

    @Delete({
        "<script>",
        "DELETE FROM advertising_attribution WHERE id IN",
        "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
        "</script>"
    })
    int deleteAttributionIds(@Param("ids") List<Long> ids);

    @Select("""
        SELECT id FROM advertising_audit_event
        WHERE create_time < #{cutoff}
        ORDER BY create_time, id
        LIMIT #{limit}
        """)
    List<Long> selectExpiredAuditIds(@Param("cutoff") LocalDateTime cutoff,
                                     @Param("limit") int limit);

    @Delete({
        "<script>",
        "DELETE FROM advertising_audit_event WHERE id IN",
        "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
        "</script>"
    })
    int deleteAuditIds(@Param("ids") List<Long> ids);
}
