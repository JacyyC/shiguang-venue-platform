package com.example.venue.statistics.mapper;

import com.example.venue.statistics.dto.ChartPoint;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface StatisticsMapper {
    @Select("""
        select count(1)
        from reservation
        where (#{venueId} is null or venue_id = #{venueId})
          and date(start_time) = curdate()
          and deleted = 0
          and status in ('PENDING_REVIEW','APPROVED','PENDING_PAYMENT','PAID','COMPLETED')
        """)
    long todayReservations(@Param("venueId") Long venueId);

    @Select("""
        select count(1)
        from reservation
        where (#{venueId} is null or venue_id = #{venueId})
          and status = 'PENDING_REVIEW'
          and deleted = 0
        """)
    long pendingReviews(@Param("venueId") Long venueId);

    // AUD-P2-12 场馆管理员只看该场馆关联用户数（在 reservation 表中出现过的不重复用户），
    // 系统管理员（venueId 为 null）仍看全平台 sys_user 计数，口径与其他看板指标一致。
    @Select("""
        select case
          when #{venueId} is not null then (
            select count(distinct r.user_id)
            from reservation r
            where r.venue_id = #{venueId}
              and r.deleted = 0
          )
          else (select count(1) from sys_user where deleted = 0)
        end
        """)
    long totalUsers(@Param("venueId") Long venueId);

    @Select("""
        select coalesce(sum(amount), 0)
        from (
          select p.net_amount as amount
          from payment_record p
          join biz_order o on o.id = p.order_id
          where p.status = 'SUCCESS'
            and p.pay_time is not null
            and (#{venueId} is null or o.venue_id = #{venueId})
          union all
          select -r.net_amount as amount
          from refund_record r
          join biz_order o on o.id = r.order_id
          where r.status = 'SUCCESS'
            and (#{venueId} is null or o.venue_id = #{venueId})
        ) settlement_fact
        """)
    BigDecimal revenue(@Param("venueId") Long venueId);

    @Select("""
        select s.name as label, count(r.id) as value
        from venue_space s
        left join reservation r on r.space_id = s.id
          and r.deleted = 0
          and r.status in ('PENDING_REVIEW','APPROVED','PENDING_PAYMENT','PAID','COMPLETED')
        where (#{venueId} is null or s.venue_id = #{venueId})
          and s.deleted = 0
        group by s.id, s.name
        order by value desc
        limit 10
        """)
    List<ChartPoint> topSpaces(@Param("venueId") Long venueId);

    // A26-P3-31 分子分母口径对齐：
    //   - 占用状态集合与 ReservationStatus.occupies() 一致，含 REFUNDING（OCCUPYING = PENDING_REVIEW/APPROVED/PENDING_PAYMENT/PAID/REFUNDING）
    //     COMPLETED 已结束不再占用场地；REFUNDING 退款中但占用未释放，仍计入占用。
    //   - 场地过滤与 bookableMinutesToday 分母一致：只统计 s.status='ACTIVE' and v.status='ACTIVE' 的场地，
    //     避免非 ACTIVE 场地的预约进入分子而分母已剔除该场地，导致占用率虚高或分母为 0。
    @Select("""
        select coalesce(sum(timestampdiff(
          minute,
          greatest(r.start_time, curdate()),
          least(r.end_time, timestamp(curdate() + interval 1 day))
        )), 0)
        from reservation r
        join venue_space s on s.id = r.space_id
        join venue v on v.id = r.venue_id
        where (#{venueId} is null or r.venue_id = #{venueId})
          and r.deleted = 0
          and s.deleted = 0 and v.deleted = 0
          and s.status = 'ACTIVE' and v.status = 'ACTIVE'
          and r.status in ('PENDING_REVIEW','APPROVED','PENDING_PAYMENT','PAID','REFUNDING')
          and r.start_time < timestamp(curdate() + interval 1 day)
          and r.end_time > curdate()
        """)
    BigDecimal occupiedMinutesToday(@Param("venueId") Long venueId);

    @Select("""
        with space_window as (
          select s.id as space_id,
                 s.venue_id,
                 case
                   when c.business_status in ('CLOSED', 'INTERNAL_ONLY') then null
                   when c.id is not null then timestamp(curdate(), coalesce(c.open_time, v.open_time, '08:00:00'))
                   else timestamp(curdate(), coalesce(v.open_time, '08:00:00'))
                 end as open_at,
                 case
                   when c.business_status in ('CLOSED', 'INTERNAL_ONLY') then null
                   when c.id is not null then timestamp(curdate(), coalesce(c.close_time, v.close_time, '22:00:00'))
                   else timestamp(curdate(), coalesce(v.close_time, '22:00:00'))
                 end as close_at
          from venue_space s
          join venue v on v.id = s.venue_id
          left join venue_business_calendar c
            on c.venue_id = v.id and c.calendar_date = curdate() and c.deleted = 0
          where (#{venueId} is null or s.venue_id = #{venueId})
            and s.deleted = 0 and v.deleted = 0
            and s.status = 'ACTIVE' and v.status = 'ACTIVE'
        )
        select coalesce(sum(greatest(0, timestampdiff(minute, open_at, close_at) - coalesce((
          select sum(greatest(0, timestampdiff(minute,
            greatest(timestampadd(minute, -b.buffer_before_minutes, b.start_time), w.open_at),
            least(timestampadd(minute, b.buffer_after_minutes, b.end_time), w.close_at)
          )))
          from venue_unavailable_block b
          where b.venue_id = w.venue_id and (b.space_id = w.space_id or b.space_id is null)
            and b.deleted = 0 and b.status = 'ACTIVE'
            and timestampadd(minute, -b.buffer_before_minutes, b.start_time) < w.close_at
            and timestampadd(minute, b.buffer_after_minutes, b.end_time) > w.open_at
        ), 0))), 0)
        from space_window w
        where open_at is not null and close_at is not null and open_at < close_at
        """)
    BigDecimal bookableMinutesToday(@Param("venueId") Long venueId);

    @Select("""
        select label, coalesce(sum(value), 0) as value
        from (
          select date_format(p.pay_time, '%Y-%m-%d') as label, p.net_amount as value
          from payment_record p
          join biz_order o on o.id = p.order_id
          where p.status = 'SUCCESS' and p.pay_time >= date_sub(curdate(), interval 30 day)
            and (#{venueId} is null or o.venue_id = #{venueId})
          union all
          select date_format(r.update_time, '%Y-%m-%d') as label, -r.net_amount as value
          from refund_record r
          join biz_order o on o.id = r.order_id
          where r.status = 'SUCCESS' and r.update_time >= date_sub(curdate(), interval 30 day)
            and (#{venueId} is null or o.venue_id = #{venueId})
        ) settlement_fact
        group by label
        order by label
        """)
    List<ChartPoint> revenueTrend(@Param("venueId") Long venueId);
}
