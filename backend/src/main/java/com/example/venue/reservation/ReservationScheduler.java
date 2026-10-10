package com.example.venue.reservation;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.venue.order.entity.BizOrder;
import com.example.venue.order.enums.OrderStatus;
import com.example.venue.order.mapper.BizOrderMapper;
import com.example.venue.order.service.OrderService;
import com.example.venue.reservation.service.ReservationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 定时调度器：处理超时未支付订单过期和已支付预约自动完成。
 * <p>
 * 采用主键游标逐批处理，避免全量加载到内存导致 OOM，且不受已处理记录退出筛选集影响。
 */
@Component
public class ReservationScheduler {
    private static final Logger log = LoggerFactory.getLogger(ReservationScheduler.class);

    /** 每批处理的记录数，防止一次性加载过多数据到内存 */
    private static final int BATCH_SIZE = 100;

    private final BizOrderMapper orderMapper;
    private final OrderService orderService;
    private final ReservationService reservationService;

    public ReservationScheduler(BizOrderMapper orderMapper, OrderService orderService, ReservationService reservationService) {
        this.orderMapper = orderMapper;
        this.orderService = orderService;
        this.reservationService = reservationService;
    }

    /**
     * 每 60 秒扫描一批超时未支付订单并执行过期处理。
     * 使用主键游标查询，每批最多处理 {@value BATCH_SIZE} 条，避免全量加载。
     */
    @Scheduled(fixedDelay = 60_000)
    public void expireUnpaidOrders() {
        LocalDateTime now = LocalDateTime.now();
        Long lastScannedId = null;
        int totalProcessed = 0;
        while (true) {
            QueryWrapper<BizOrder> query = new QueryWrapper<BizOrder>()
                .eq("status", OrderStatus.PENDING_PAYMENT.name())
                .lt("expire_time", now)
                .orderByAsc("id");
            if (lastScannedId != null) {
                query.gt("id", lastScannedId);
            }
            Page<BizOrder> page = orderMapper.selectPage(new Page<>(1, BATCH_SIZE), query);
            if (page.getRecords().isEmpty()) {
                break;
            }
            Long nextScannedId = page.getRecords().get(page.getRecords().size() - 1).id;
            for (BizOrder order : page.getRecords()) {
                try {
                    orderService.expireOrder(order);
                    totalProcessed++;
                } catch (Exception ex) {
                    log.error("过期订单处理失败 orderId={}", order.id, ex);
                }
            }
            if (page.getRecords().size() < BATCH_SIZE) {
                break;
            }
            if (nextScannedId == null || lastScannedId != null && nextScannedId <= lastScannedId) {
                log.warn("过期订单批处理游标未前进，停止本轮扫描 lastScannedId={}", lastScannedId);
                break;
            }
            lastScannedId = nextScannedId;
        }
        if (totalProcessed > 0) {
            log.info("本轮过期订单处理完成，共处理 {} 笔", totalProcessed);
        }
    }

    /**
     * 每 300 秒扫描一批已结束的已支付预约并执行自动完成。
     * 委托给 {@link ReservationService#completeStartedReservationsBatch(int)} 分页处理。
     */
    @Scheduled(fixedDelay = 300_000)
    public void completeReservations() {
        reservationService.completeStartedReservationsBatch(BATCH_SIZE);
    }
}
