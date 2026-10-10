package com.example.venue.reservation;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.venue.order.entity.BizOrder;
import com.example.venue.order.enums.OrderStatus;
import com.example.venue.order.mapper.BizOrderMapper;
import com.example.venue.order.service.OrderService;
import com.example.venue.reservation.service.ReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationSchedulerTest {
    @Mock
    private BizOrderMapper orderMapper;
    @Mock
    private OrderService orderService;
    @Mock
    private ReservationService reservationService;

    private ReservationScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new ReservationScheduler(orderMapper, orderService, reservationService);
    }

    @Test
    void expireUnpaidOrdersProcessesAdjacentFullBatchesAndIsolatesOneFailure() {
        List<BizOrder> candidates = LongStream.rangeClosed(1, 200)
            .mapToObj(this::expiredPendingOrder)
            .toList();
        when(orderMapper.selectPage(any(Page.class), any(QueryWrapper.class))).thenAnswer(invocation -> {
            Page<BizOrder> page = invocation.getArgument(0);
            QueryWrapper<BizOrder> query = invocation.getArgument(1);
            query.getSqlSegment();
            long lastScannedId = query.getParamNameValuePairs().values().stream()
                .filter(Long.class::isInstance)
                .map(Long.class::cast)
                .max(Long::compareTo)
                .orElse(0L);
            long offset = lastScannedId == 0 ? (page.getCurrent() - 1) * page.getSize() : 0;
            page.setRecords(candidates.stream()
                .filter(candidate -> OrderStatus.PENDING_PAYMENT.name().equals(candidate.status))
                .filter(candidate -> candidate.id > lastScannedId)
                .skip(offset)
                .limit(page.getSize())
                .toList());
            return page;
        });
        doAnswer(invocation -> {
            BizOrder order = invocation.getArgument(0);
            if (order.id.equals(50L)) {
                throw new IllegalStateException("simulated expiration failure");
            }
            order.status = OrderStatus.TIMEOUT.name();
            return null;
        }).when(orderService).expireOrder(any(BizOrder.class));

        scheduler.expireUnpaidOrders();

        assertThat(candidates).filteredOn(candidate -> !candidate.id.equals(50L))
            .allSatisfy(candidate -> assertThat(candidate.status).isEqualTo(OrderStatus.TIMEOUT.name()));
        assertThat(candidates.get(49).status).isEqualTo(OrderStatus.PENDING_PAYMENT.name());
        verify(orderService, times(200)).expireOrder(any(BizOrder.class));
        verify(orderMapper, times(3)).selectPage(any(Page.class), any(QueryWrapper.class));
    }

    private BizOrder expiredPendingOrder(long id) {
        BizOrder order = new BizOrder();
        order.id = id;
        order.status = OrderStatus.PENDING_PAYMENT.name();
        order.expireTime = LocalDateTime.now().minusMinutes(1);
        return order;
    }
}
