package com.example.venue.funnel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.venue.funnel.entity.FunnelEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface FunnelEventMapper extends BaseMapper<FunnelEvent> {
    @Select("""
        select * from funnel_event
        where provider_env = #{providerEnv}
          and source_channel = #{sourceChannel}
          and idempotency_key = #{idempotencyKey}
          and deleted = 0
        limit 1
        """)
    FunnelEvent findByIdempotency(@Param("providerEnv") String providerEnv,
                                  @Param("sourceChannel") String sourceChannel,
                                  @Param("idempotencyKey") String idempotencyKey);

    @Select("""
        <script>
        select session_key
        from funnel_event
        where deleted = 0
          and session_key is not null
          and session_key &lt;&gt; ''
          and event_name in ('RESERVATION_SUBMITTED', 'ORDER_CREATED', 'PAYMENT_INITIATED')
          <choose>
            <when test="reservationId != null and orderId != null">
              and (reservation_id = #{reservationId} or order_id = #{orderId})
            </when>
            <when test="reservationId != null">
              and reservation_id = #{reservationId}
            </when>
            <when test="orderId != null">
              and order_id = #{orderId}
            </when>
            <otherwise>
              and 1 = 0
            </otherwise>
          </choose>
        order by case event_name
                   when 'RESERVATION_SUBMITTED' then 1
                   when 'ORDER_CREATED' then 2
                   else 3
                 end,
                 occurred_at asc,
                 id asc
        limit 1
        </script>
        """)
    String findContinuationSessionKey(@Param("reservationId") Long reservationId,
                                      @Param("orderId") Long orderId);

    @SelectProvider(type = SqlProvider.class, method = "countByEventName")
    List<Map<String, Object>> countByEventName(@Param("startTime") LocalDateTime startTime,
                                               @Param("endTime") LocalDateTime endTime,
                                               @Param("venueId") Long venueId,
                                               @Param("venueIds") List<Long> venueIds,
                                               @Param("sourceChannel") String sourceChannel);

    @SelectProvider(type = SqlProvider.class, method = "countByDropoffReason")
    List<Map<String, Object>> countByDropoffReason(@Param("startTime") LocalDateTime startTime,
                                                   @Param("endTime") LocalDateTime endTime,
                                                   @Param("venueId") Long venueId,
                                                   @Param("venueIds") List<Long> venueIds,
                                                   @Param("sourceChannel") String sourceChannel);

    @SelectProvider(type = SqlProvider.class, method = "countBySourceChannel")
    List<Map<String, Object>> countBySourceChannel(@Param("startTime") LocalDateTime startTime,
                                                   @Param("endTime") LocalDateTime endTime,
                                                   @Param("venueId") Long venueId,
                                                   @Param("venueIds") List<Long> venueIds,
                                                   @Param("sourceChannel") String sourceChannel);

    @SelectProvider(type = SqlProvider.class, method = "topKeywords")
    List<Map<String, Object>> topKeywords(@Param("startTime") LocalDateTime startTime,
                                          @Param("endTime") LocalDateTime endTime,
                                          @Param("venueId") Long venueId,
                                          @Param("venueIds") List<Long> venueIds,
                                          @Param("sourceChannel") String sourceChannel);

    @SelectProvider(type = SqlProvider.class, method = "selectSamples")
    List<FunnelEvent> selectSamples(@Param("startTime") LocalDateTime startTime,
                                    @Param("endTime") LocalDateTime endTime,
                                    @Param("venueId") Long venueId,
                                    @Param("venueIds") List<Long> venueIds,
                                    @Param("eventName") String eventName,
                                    @Param("limit") int limit);

    @SelectProvider(type = SqlProvider.class, method = "selectAnalyticsSamples")
    List<FunnelEvent> selectAnalyticsSamples(@Param("startTime") LocalDateTime startTime,
                                             @Param("endTime") LocalDateTime endTime,
                                             @Param("venueId") Long venueId,
                                             @Param("venueIds") List<Long> venueIds,
                                             @Param("eventName") String eventName,
                                             @Param("sourceChannel") String sourceChannel,
                                             @Param("limit") int limit);

    // DC-01：SQL 端 cohort 查询，固定 EVENT_ORDER 逐步 JOIN 传递 session_key 交集，
    // 替代 Service 端 selectAnalyticsSamples(limit=MAX_VALUE) 拉全量到内存计算 Set.retainAll。
    // F-22：venueScoped=true 时计量链从「场馆详情查看」起步——搜索两步发生在选定场馆之前，
    // 没有本场馆的归属事实，返回 0 会被读成零转化，因此 SQL 端直接不产出这两步。
    @SelectProvider(type = SqlProvider.class, method = "selectCohortStepCounts")
    List<Map<String, Object>> selectCohortStepCounts(@Param("startTime") LocalDateTime startTime,
                                                      @Param("endTime") LocalDateTime endTime,
                                                      @Param("venueId") Long venueId,
                                                      @Param("venueIds") List<Long> venueIds,
                                                      @Param("sourceChannel") String sourceChannel,
                                                      @Param("venueScoped") boolean venueScoped);

    class SqlProvider {
        public String countByEventName(Map<String, Object> params) {
            StringBuilder sql = new StringBuilder("""
                select event_name as eventName, count(distinct session_key) as count
                from funnel_event
                """);
            appendScope(sql, params, true);
            sql.append(" group by event_name");
            return sql.toString();
        }

        public String countByDropoffReason(Map<String, Object> params) {
            StringBuilder sql = new StringBuilder("""
                select dropoff_reason_code as reasonCode, count(1) as count
                from funnel_event
                """);
            appendScope(sql, params, true);
            sql.append(" and dropoff_reason_code is not null and dropoff_reason_code <> ''");
            sql.append(" group by dropoff_reason_code order by count(1) desc");
            return sql.toString();
        }

        public String countBySourceChannel(Map<String, Object> params) {
            StringBuilder sql = new StringBuilder("""
                select coalesce(nullif(source_channel, ''), 'UNKNOWN') as sourceChannel, count(1) as count
                from funnel_event
                """);
            appendScope(sql, params, true);
            sql.append(" group by coalesce(nullif(source_channel, ''), 'UNKNOWN') order by count(1) desc");
            return sql.toString();
        }

        public String topKeywords(Map<String, Object> params) {
            StringBuilder sql = new StringBuilder("""
                select keyword_normalized as keyword, count(1) as count
                from funnel_event
                """);
            appendScope(sql, params, true);
            sql.append(" and keyword_normalized is not null and keyword_normalized <> ''");
            sql.append(" group by keyword_normalized order by count(1) desc limit 10");
            return sql.toString();
        }

        public String selectSamples(Map<String, Object> params) {
            StringBuilder sql = new StringBuilder("select * from funnel_event");
            appendScope(sql, params, false);
            if (hasText(params.get("eventName"))) {
                sql.append(" and event_name = #{eventName}");
            }
            sql.append(" order by occurred_at desc, id desc limit #{limit}");
            return sql.toString();
        }

        public String selectAnalyticsSamples(Map<String, Object> params) {
            StringBuilder sql = new StringBuilder("select * from funnel_event");
            appendScope(sql, params, true);
            if (hasText(params.get("eventName"))) {
                sql.append(" and event_name = #{eventName}");
            }
            sql.append(" order by occurred_at desc, id desc limit #{limit}");
            return sql.toString();
        }

        // DC-01：固定 EVENT_ORDER，与 FunnelAnalyticsService.EVENT_ORDER 一致；
        // 改动其一须同步另一处（Service 用 EVENT_ORDER 构建 metric 顺序，SQL 用 COHORT_STEPS 逐步 JOIN 传递 cohort）。
        private static final String[] COHORT_STEPS = {
            "SEARCH_SUBMITTED", "SEARCH_RESULT_VIEWED", "VENUE_DETAIL_VIEWED",
            "SLOT_SELECTED", "PRICE_PREVIEW_SHOWN", "RESERVATION_SUBMITTED",
            "ORDER_CREATED", "PAYMENT_INITIATED", "PAYMENT_RESULT_RECORDED"
        };
        // F-22：场馆口径的起点。前两步在选定场馆之前发生，场馆范围内不产出这两步（返回 0 会被读成零转化）。
        private static final String VENUE_START_STEP = "VENUE_DETAIL_VIEWED";

        public String selectCohortStepCounts(Map<String, Object> params) {
            // 逐步 JOIN：step{i+1}_sessions = 在 step{i}_sessions 出现过且命中当前 event_name 的 session_key；
            // 起点步骤直接从 funnel_event 取该 event 的 distinct session_key（排除 null），后续每步与前一步 JOIN 传递 cohort 交集。
            // appendScope 复用统一的 deleted/时间窗/venue/sourceChannel 过滤；JOIN 后 s 表只有 session_key，无歧义。
            int startIndex = venueScoped(params) ? stepIndex(VENUE_START_STEP) : 0;
            StringBuilder sql = new StringBuilder();
            for (int i = startIndex; i < COHORT_STEPS.length; i++) {
                if (i > startIndex) {
                    sql.append(", ");
                } else {
                    sql.append("with ");
                }
                sql.append("step").append(i + 1).append("_sessions as (");
                if (i == startIndex) {
                    sql.append(" select distinct session_key from funnel_event");
                    appendScope(sql, params, true);
                    sql.append(" and event_name = '").append(COHORT_STEPS[i]).append("'");
                    sql.append(" and session_key is not null");
                } else {
                    sql.append(" select distinct f.session_key from funnel_event f");
                    sql.append(" join step").append(i).append("_sessions s on f.session_key = s.session_key");
                    appendScope(sql, params, true);
                    sql.append(" and f.event_name = '").append(COHORT_STEPS[i]).append("'");
                }
                sql.append(")");
            }
            boolean firstArm = true;
            for (int i = startIndex; i < COHORT_STEPS.length; i++) {
                if (firstArm) {
                    firstArm = false;
                } else {
                    sql.append(" union all ");
                }
                sql.append("select '").append(COHORT_STEPS[i])
                    .append("' as eventName, count(*) as count from step").append(i + 1).append("_sessions");
            }
            return sql.toString();
        }

        private boolean venueScoped(Map<String, Object> params) {
            return Boolean.TRUE.equals(params.get("venueScoped"));
        }

        private int stepIndex(String eventName) {
            for (int i = 0; i < COHORT_STEPS.length; i++) {
                if (COHORT_STEPS[i].equals(eventName)) {
                    return i;
                }
            }
            throw new IllegalStateException("未知的漏斗起点步骤: " + eventName);
        }

        private void appendScope(StringBuilder sql, Map<String, Object> params, boolean includeSourceChannel) {
            sql.append(" where deleted = 0");
            sql.append(" and occurred_at >= #{startTime}");
            sql.append(" and occurred_at < #{endTime}");
            if (params.get("venueId") != null) {
                sql.append(" and venue_id = #{venueId}");
            } else if (params.get("venueIds") instanceof List<?> venueIds) {
                if (venueIds.isEmpty()) {
                    sql.append(" and 1 = 0");
                } else {
                    sql.append(" and venue_id in (");
                    for (int i = 0; i < venueIds.size(); i++) {
                        if (i > 0) {
                            sql.append(", ");
                        }
                        sql.append("#{venueIds[").append(i).append("]}");
                    }
                    sql.append(")");
                }
            }
            if (includeSourceChannel && hasText(params.get("sourceChannel"))) {
                sql.append(" and source_channel = #{sourceChannel}");
            }
        }

        private boolean hasText(Object value) {
            return value instanceof String text && !text.isBlank();
        }
    }
}
