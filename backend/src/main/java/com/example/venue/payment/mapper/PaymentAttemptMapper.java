package com.example.venue.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.venue.payment.entity.PaymentAttempt;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface PaymentAttemptMapper extends BaseMapper<PaymentAttempt> {
    @Select("select * from payment_attempt where payment_no = #{paymentNo} and deleted = 0 limit 1")
    PaymentAttempt findByPaymentNo(@Param("paymentNo") String paymentNo);

    @Select("""
        select * from payment_attempt
        where order_id = #{orderId}
          and deleted = 0
          and status in ('CREATED','PENDING')
        order by id desc
        limit 1
        """)
    PaymentAttempt findActiveByOrderId(@Param("orderId") Long orderId);

    @Select("""
        select * from payment_attempt
        where order_id = #{orderId}
          and deleted = 0
        order by id desc
        limit 1
        """)
    PaymentAttempt findLatestByOrderId(@Param("orderId") Long orderId);

    @Select("""
        <script>
        select *
        from payment_attempt
        where deleted = 0
        <if test='providerEnv != null and providerEnv != ""'>
          and provider_env = #{providerEnv}
        </if>
        <if test='providerName != null and providerName != ""'>
          and provider_name = #{providerName}
        </if>
        <if test='status != null and status != ""'>
          and status = #{status}
        </if>
        <if test='orderNo != null and orderNo != ""'>
          and order_no like concat('%', #{orderNo}, '%')
        </if>
        <if test='venueId != null'>
          and venue_id = #{venueId}
        </if>
        <if test='startTime != null'>
          and create_time &gt;= #{startTime}
        </if>
        <if test='endTime != null'>
          and create_time &lt; #{endTime}
        </if>
        order by id desc
        </script>
        """)
    IPage<PaymentAttempt> selectAdminPage(Page<PaymentAttempt> page,
                                          @Param("providerEnv") String providerEnv,
                                          @Param("providerName") String providerName,
                                          @Param("status") String status,
                                          @Param("orderNo") String orderNo,
                                          @Param("venueId") Long venueId,
                                          @Param("startTime") LocalDateTime startTime,
                                          @Param("endTime") LocalDateTime endTime);

    /**
     * A26-P0-03：把订单当前活跃的支付意图（CREATED/PENDING）原子置为 CLOSED，
     * 用于改期重定价后让旧支付意图失效，避免后续回调撞金额校验或被复用。
     *
     * <p>语义对齐 {@link PaymentAttemptStatus#CLOSED}：本地终态，不再可结算；
     * 调用方负责在订单行锁事务内调用，确保关闭与订单定价同步写入。
     * 受影响行数 = 0 表示该订单当前无活跃 attempt（如尚未创建支付意图或已被关闭/超时）。
     */
    @Update("""
        update payment_attempt
        set status = 'CLOSED',
            failure_code = #{failureCode},
            failure_message = #{failureMessage},
            paid_amount = 0,
            fee_amount = 0,
            net_amount = 0,
            update_time = #{updateTime}
        where order_id = #{orderId}
          and deleted = 0
          and status in ('CREATED','PENDING')
        """)
    int closeActiveByOrderId(@Param("orderId") Long orderId,
                             @Param("failureCode") String failureCode,
                             @Param("failureMessage") String failureMessage,
                             @Param("updateTime") LocalDateTime updateTime);

    /**
     * A26-P3-06：原子地把活跃 attempt（CREATED/PENDING）推进到 Provider 创建成功后的状态。
     *
     * <p>检查后写非原子的 {@code updateById} 会被并发回调结算后的 SUCCEEDED 状态覆盖回 PENDING 并清零
     * paidAmount；改用条件更新（WHERE status IN ('CREATED','PENDING')）确保仅活跃 attempt 被更新。
     * 受影响行数 = 0 表示 attempt 已被并发回调结算为 SUCCEEDED（或被关闭/超时/失败），调用方不可覆盖。
     */
    @Update("""
        update payment_attempt
        set provider_env = #{providerEnv},
            provider_name = #{providerName},
            channel = #{channel},
            status = #{status},
            currency = #{currency},
            config_version = #{configVersion},
            request_summary_json = #{requestSummaryJson},
            response_summary_json = #{responseSummaryJson},
            failure_code = null,
            failure_message = null,
            update_time = #{updateTime}
        where id = #{id}
          and deleted = 0
          and status in ('CREATED','PENDING')
        """)
    int markCreateSucceededIfActive(@Param("id") Long id,
                                    @Param("providerEnv") String providerEnv,
                                    @Param("providerName") String providerName,
                                    @Param("channel") String channel,
                                    @Param("status") String status,
                                    @Param("currency") String currency,
                                    @Param("configVersion") String configVersion,
                                    @Param("requestSummaryJson") String requestSummaryJson,
                                    @Param("responseSummaryJson") String responseSummaryJson,
                                    @Param("updateTime") LocalDateTime updateTime);
}
