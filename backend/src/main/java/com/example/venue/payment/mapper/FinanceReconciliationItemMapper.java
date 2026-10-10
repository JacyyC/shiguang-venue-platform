package com.example.venue.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.venue.payment.entity.FinanceReconciliationItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

@Mapper
public interface FinanceReconciliationItemMapper extends BaseMapper<FinanceReconciliationItem> {
    @Select("select * from finance_reconciliation_item where reconcile_no = #{reconcileNo} and deleted = 0 limit 1")
    FinanceReconciliationItem findByReconcileNo(@Param("reconcileNo") String reconcileNo);

    @Select("""
        <script>
        select i.*
        from finance_reconciliation_item i
        left join payment_attempt pa on pa.id = i.payment_attempt_id
        where i.deleted = 0
        <if test='providerEnv != null and providerEnv != ""'>
          and i.provider_env = #{providerEnv}
        </if>
        <if test='providerName != null and providerName != ""'>
          and i.provider_name = #{providerName}
        </if>
        <if test='status != null and status != ""'>
          and i.status = #{status}
        </if>
        <if test='orderNo != null and orderNo != ""'>
          and pa.order_no like concat('%', #{orderNo}, '%')
        </if>
        <if test='venueId != null'>
          and pa.venue_id = #{venueId}
        </if>
        <if test='startTime != null'>
          and i.create_time &gt;= #{startTime}
        </if>
        <if test='endTime != null'>
          and i.create_time &lt; #{endTime}
        </if>
        order by i.id desc
        </script>
        """)
    IPage<FinanceReconciliationItem> selectAdminPage(Page<FinanceReconciliationItem> page,
                                                     @Param("providerEnv") String providerEnv,
                                                     @Param("providerName") String providerName,
                                                     @Param("status") String status,
                                                     @Param("orderNo") String orderNo,
                                                     @Param("venueId") Long venueId,
                                                     @Param("startTime") LocalDateTime startTime,
                                                     @Param("endTime") LocalDateTime endTime);
}
