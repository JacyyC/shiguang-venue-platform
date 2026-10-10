package com.example.venue.checkin;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.venue.business.dto.BusinessEventRecordRequest;
import com.example.venue.business.enums.BusinessDomain;
import com.example.venue.business.enums.BusinessEventType;
import com.example.venue.business.enums.BusinessSourceType;
import com.example.venue.business.service.BusinessEventService;
import com.example.venue.checkin.dto.CheckinAdminQuery;
import com.example.venue.checkin.dto.CheckinVerifyRequest;
import com.example.venue.checkin.dto.CompletionDecision;
import com.example.venue.checkin.dto.ManualAttendanceRequest;
import com.example.venue.checkin.dto.NoShowDecisionRequest;
import com.example.venue.checkin.dto.ReservationLifecycleResponse;
import com.example.venue.checkin.dto.RescheduleRequest;
import com.example.venue.checkin.entity.ReservationLifecycle;
import com.example.venue.checkin.entity.ReservationLifecycleEvent;
import com.example.venue.checkin.entity.ReservationRescheduleRequest;
import com.example.venue.checkin.mapper.ReservationLifecycleEventMapper;
import com.example.venue.checkin.mapper.ReservationLifecycleMapper;
import com.example.venue.checkin.mapper.ReservationRescheduleRequestMapper;
import com.example.venue.checkin.service.ReservationLifecycleService;
import com.example.venue.common.BusinessException;
import com.example.venue.common.CurrentUser;
import com.example.venue.order.entity.BizOrder;
import com.example.venue.order.mapper.BizOrderMapper;
import com.example.venue.reservation.entity.Reservation;
import com.example.venue.reservation.enums.ReservationStatus;
import com.example.venue.reservation.mapper.ReservationMapper;
import com.example.venue.system.entity.SysConfig;
import com.example.venue.system.mapper.SysConfigMapper;
import com.example.venue.system.service.SysConfigService;
import com.example.venue.venue.service.VenueAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.dao.DuplicateKeyException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationLifecycleServiceTest {
    @Mock
    ReservationLifecycleMapper lifecycleMapper;
    @Mock
    ReservationLifecycleEventMapper eventMapper;
    @Mock
    ReservationRescheduleRequestMapper rescheduleMapper;
    @Mock
    ReservationMapper reservationMapper;
    @Mock
    BizOrderMapper orderMapper;
    @Mock
    SysConfigMapper configMapper;
    @Mock
    BusinessEventService businessEventService;
    @Mock
    VenueAccessService venueAccessService;

    ReservationLifecycleService service;

    @BeforeEach
    void setUp() {
        SysConfigService sysConfigService = new SysConfigService(configMapper);
        service = new ReservationLifecycleService(
            lifecycleMapper, eventMapper, rescheduleMapper, reservationMapper, orderMapper,
            sysConfigService, businessEventService, venueAccessService);
        org.mockito.Mockito.lenient().when(reservationMapper.selectByIdForUpdate(
            org.mockito.ArgumentMatchers.anyLong())).thenAnswer(invocation ->
            reservationMapper.selectById(invocation.getArgument(0)));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new CurrentUser(7L, "admin", Set.of("VENUE_ADMIN"), Set.of("reservation:checkin:manage")),
            null,
            List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void issueCredentialForPaidReservationCreatesWindowHashAndEvent() {
        when(lifecycleMapper.findByReservationId(99L)).thenReturn(null);
        when(configMapper.findByKey("reservation.checkin.open_before_minutes")).thenReturn(config("30"));
        when(configMapper.findByKey("reservation.checkin.close_after_minutes")).thenReturn(config("30"));
        BizOrder order = order(77L, 99L);
        Reservation reservation = paidReservation(99L, LocalDateTime.of(2026, 7, 3, 10, 0));

        ReservationLifecycleResponse response = service.issueCredentialForPaidReservation(order, reservation);

        ArgumentCaptor<ReservationLifecycle> lifecycleCaptor = ArgumentCaptor.forClass(ReservationLifecycle.class);
        verify(lifecycleMapper).insert(lifecycleCaptor.capture());
        ReservationLifecycle inserted = lifecycleCaptor.getValue();
        assertThat(inserted.reservationId).isEqualTo(99L);
        assertThat(inserted.orderId).isEqualTo(77L);
        assertThat(inserted.userId).isEqualTo(7L);
        assertThat(inserted.venueId).isEqualTo(20L);
        assertThat(inserted.spaceId).isEqualTo(10L);
        assertThat(inserted.credentialStatus).isEqualTo("ISSUED");
        assertThat(inserted.checkinStatus).isEqualTo("NOT_CHECKED_IN");
        assertThat(inserted.attendanceStatus).isEqualTo("PENDING");
        assertThat(inserted.noShowStatus).isEqualTo("NOT_EVALUATED");
        assertThat(inserted.checkinCodeHash).isNotBlank();
        assertThat(inserted.checkinCodeLast4).hasSize(4);
        assertThat(inserted.checkinWindowStart).isEqualTo(reservation.startTime.minusMinutes(30));
        assertThat(inserted.checkinWindowEnd).isEqualTo(reservation.endTime.plusMinutes(30));

        // A26-P1-01：签发响应必须一次性交付明文短码与 qrPayload，否则用户无法核销
        assertThat(response.checkinCode).hasSize(6);
        assertThat(response.checkinCode).matches("\\d{6}");
        assertThat(response.checkinCodeLast4).isEqualTo(inserted.checkinCodeLast4);
        assertThat(response.checkinCode.substring(2)).isEqualTo(inserted.checkinCodeLast4);
        assertThat(response.qrPayload).isEqualTo("CHECKIN:99:" + response.checkinCode);
        // 持久化的 hash 是哈希非明文：hash != 明文 code
        assertThat(inserted.checkinCodeHash).isNotEqualTo(response.checkinCode);
        assertThat(inserted.checkinCodeHash).isEqualTo(hash(response.checkinCode));

        ArgumentCaptor<ReservationLifecycleEvent> eventCaptor = ArgumentCaptor.forClass(ReservationLifecycleEvent.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertThat(eventCaptor.getValue().eventType).isEqualTo("CHECKIN_CREDENTIAL_ISSUED");
        assertThat(eventCaptor.getValue().idempotencyKey).isEqualTo("CHECKIN_CREDENTIAL_ISSUED:99");
    }

    @Test
    void issueCredentialForPaidReservationReturnsExistingLifecycleIfAlreadyIssued() {
        ReservationLifecycle existing = lifecycle(99L, "123456", LocalDateTime.now());
        when(lifecycleMapper.findByReservationId(99L)).thenReturn(existing);
        when(eventMapper.findRecentByReservationId(99L, 5)).thenReturn(List.of());

        ReservationLifecycleResponse response = service.issueCredentialForPaidReservation(order(77L, 99L), paidReservation(99L));

        // A26-P1-01：已签发过的凭证不再重新生成短码，明文在首次签发时一次性交付，此后无法恢复
        assertThat(response.reservationId).isEqualTo(99L);
        assertThat(response.checkinCodeLast4).isEqualTo("3456");
        assertThat(response.checkinCode).isNull();
        assertThat(response.qrPayload).isNull();
        verify(lifecycleMapper, never()).insert(any(ReservationLifecycle.class));
        // toResponse 会查最近事件但不写事件
        verify(eventMapper, never()).insert(any(ReservationLifecycleEvent.class));
    }

    @Test
    void reissueMyCredentialRotatesHashAndReturnsPlaintextOnlyInResponse() {
        LocalDateTime now = LocalDateTime.now();
        Reservation reservation = paidReservation(99L, now.plusHours(1));
        ReservationLifecycle lifecycle = lifecycle(99L, "123456", now.plusHours(1));
        lifecycle.credentialExpiresAt = now.plusHours(3);
        when(reservationMapper.selectByIdForUpdate(99L)).thenReturn(reservation);
        when(lifecycleMapper.findByReservationIdForUpdate(99L)).thenReturn(lifecycle);
        when(lifecycleMapper.update(any(ReservationLifecycle.class), any())).thenReturn(1);
        when(eventMapper.findRecentByReservationId(99L, 5)).thenReturn(List.of());

        ReservationLifecycleResponse response = service.reissueMyCredential(99L, 7L);

        assertThat(response.checkinCode).matches("\\d{6}");
        assertThat(response.qrPayload).isEqualTo("CHECKIN:99:" + response.checkinCode);
        assertThat(lifecycle.checkinCodeHash).isEqualTo(hash(response.checkinCode));
        assertThat(lifecycle.checkinCodeHash).isNotEqualTo(response.checkinCode);
        assertThat(lifecycle.checkinCodeLast4).isEqualTo(response.checkinCode.substring(2));
        verify(lifecycleMapper).update(any(ReservationLifecycle.class), any());
        verify(eventMapper).insert(argThat((ReservationLifecycleEvent event) ->
            "CHECKIN_CREDENTIAL_REISSUED".equals(event.eventType)
                && "USER".equals(event.sourceType)
                && Long.valueOf(7L).equals(event.actorUserId)));
    }

    @Test
    void reissueMyCredentialRetriesDatabaseUniqueKeyCollision() {
        LocalDateTime now = LocalDateTime.now();
        Reservation reservation = paidReservation(99L, now.plusHours(1));
        ReservationLifecycle lifecycle = lifecycle(99L, "123456", now.plusHours(1));
        lifecycle.credentialExpiresAt = now.plusHours(3);
        when(reservationMapper.selectByIdForUpdate(99L)).thenReturn(reservation);
        when(lifecycleMapper.findByReservationIdForUpdate(99L)).thenReturn(lifecycle);
        when(lifecycleMapper.update(any(ReservationLifecycle.class), any()))
            .thenThrow(new DuplicateKeyException("uk_reservation_lifecycle_code_hash"))
            .thenReturn(1);
        when(eventMapper.findRecentByReservationId(99L, 5)).thenReturn(List.of());

        ReservationLifecycleResponse response = service.reissueMyCredential(99L, 7L);

        assertThat(response.checkinCode).matches("\\d{6}");
        assertThat(lifecycle.checkinCodeHash).isEqualTo(hash(response.checkinCode));
        verify(lifecycleMapper, org.mockito.Mockito.times(2)).update(any(ReservationLifecycle.class), any());
        verify(eventMapper).insert(any(ReservationLifecycleEvent.class));
    }

    @Test
    void reissueMyCredentialRejectsAnotherUsersReservationWithoutRotatingCode() {
        Reservation reservation = paidReservation(99L, LocalDateTime.now().plusHours(1));
        reservation.userId = 8L;
        when(reservationMapper.selectByIdForUpdate(99L)).thenReturn(reservation);

        assertThatThrownBy(() -> service.reissueMyCredential(99L, 7L))
            .isInstanceOf(BusinessException.class)
            .hasMessage("预约不存在");

        verify(lifecycleMapper, never()).findByReservationIdForUpdate(99L);
        verify(lifecycleMapper, never()).update(any(ReservationLifecycle.class), any());
    }

    @Test
    void reissueMyCredentialRejectsAlreadyCheckedInReservation() {
        Reservation reservation = paidReservation(99L, LocalDateTime.now().plusHours(1));
        ReservationLifecycle lifecycle = lifecycle(99L, "123456", LocalDateTime.now().plusHours(1));
        lifecycle.checkinStatus = "CHECKED_IN";
        when(reservationMapper.selectByIdForUpdate(99L)).thenReturn(reservation);
        when(lifecycleMapper.findByReservationIdForUpdate(99L)).thenReturn(lifecycle);

        assertThatThrownBy(() -> service.reissueMyCredential(99L, 7L))
            .isInstanceOf(BusinessException.class)
            .hasMessage("当前预约已完成核销");

        verify(lifecycleMapper, never()).update(any(ReservationLifecycle.class), any());
    }

    @Test
    void generateCodeRetriesOnHashCollisionAndReturnsUniqueCode() {
        // A26-P2-03：generateCode 加重试 + 唯一性查询。
        // 缺陷：原 generateCode 不查重不重试，schema 上 checkin_code_hash 是普通索引非唯一键，
        // 历史行永久保留，存量 1 万行时每次签发碰撞概率约 1.1% 且随规模上升。碰撞后可能核销到他人预约。
        // 修复后：第一次生成查重碰撞 → 重试 → 第二次生成查重通过 → 返回唯一 code。
        when(lifecycleMapper.findByReservationId(99L)).thenReturn(null);
        when(configMapper.findByKey("reservation.checkin.open_before_minutes")).thenReturn(config("30"));
        when(configMapper.findByKey("reservation.checkin.close_after_minutes")).thenReturn(config("30"));
        // 第一次查重返回非 null（碰撞），第二次查重返回 null（成功）
        ReservationLifecycle collided = lifecycle(88L, "999999", LocalDateTime.now());
        when(lifecycleMapper.findByCheckinCodeHash(any(String.class)))
            .thenReturn(collided, null);
        BizOrder order = order(77L, 99L);
        Reservation reservation = paidReservation(99L, LocalDateTime.now().plusHours(1).withNano(0));

        ReservationLifecycleResponse response = service.issueCredentialForPaidReservation(order, reservation);

        // 验证最终返回了 6 位明文短码（重试成功后）
        assertThat(response.checkinCode).hasSize(6);
        assertThat(response.checkinCode).matches("\\d{6}");
        // 验证 findByCheckinCodeHash 被调用至少 2 次（第一次碰撞，第二次通过）
        verify(lifecycleMapper, org.mockito.Mockito.times(2)).findByCheckinCodeHash(any(String.class));
        // 验证 lifecycle.insert 被调用（成功生成后写入）
        ArgumentCaptor<ReservationLifecycle> lifecycleCaptor = ArgumentCaptor.forClass(ReservationLifecycle.class);
        verify(lifecycleMapper).insert(lifecycleCaptor.capture());
        // 持久化的 hash 与最终交付的明文 hash 一致
        assertThat(lifecycleCaptor.getValue().checkinCodeHash).isEqualTo(hash(response.checkinCode));
    }

    @Test
    void issueCredentialRetriesDatabaseUniqueKeyCollision() {
        when(lifecycleMapper.findByReservationId(99L)).thenReturn(null);
        when(configMapper.findByKey("reservation.checkin.open_before_minutes")).thenReturn(config("30"));
        when(configMapper.findByKey("reservation.checkin.close_after_minutes")).thenReturn(config("30"));
        when(lifecycleMapper.insert(any(ReservationLifecycle.class)))
            .thenThrow(new DuplicateKeyException("uk_reservation_lifecycle_code_hash"))
            .thenReturn(1);
        BizOrder order = order(77L, 99L);
        Reservation reservation = paidReservation(99L, LocalDateTime.now().plusHours(1).withNano(0));

        ReservationLifecycleResponse response = service.issueCredentialForPaidReservation(order, reservation);

        assertThat(response.checkinCode).matches("\\d{6}");
        verify(lifecycleMapper, org.mockito.Mockito.times(2)).insert(any(ReservationLifecycle.class));
        verify(eventMapper).insert(any(ReservationLifecycleEvent.class));
    }

    @Test
    void generateCodeFailsAfterMaxAttemptsWhenAllCollide() {
        // A26-P2-03：5 次重试后仍碰撞抛 BusinessException，由上层重试或人工介入。
        // 极端情况：6 位数字空间 90 万种，5 次都碰撞的概率为 (1/900000)^5 量级，实际几乎不可能；
        // 此测试构造 always-collide mock 验证兜底行为。
        when(lifecycleMapper.findByReservationId(99L)).thenReturn(null);
        when(configMapper.findByKey("reservation.checkin.open_before_minutes")).thenReturn(config("30"));
        when(configMapper.findByKey("reservation.checkin.close_after_minutes")).thenReturn(config("30"));
        ReservationLifecycle collided = lifecycle(88L, "999999", LocalDateTime.now());
        // 始终返回非 null（每次查重都碰撞）
        when(lifecycleMapper.findByCheckinCodeHash(any(String.class))).thenReturn(collided);
        BizOrder order = order(77L, 99L);
        Reservation reservation = paidReservation(99L, LocalDateTime.now().plusHours(1).withNano(0));

        assertThatThrownBy(() -> service.issueCredentialForPaidReservation(order, reservation))
            .isInstanceOf(BusinessException.class)
            .hasMessage("核销短码生成失败，请重试");

        // 5 次重试后失败，不写 lifecycle、不写 event
        verify(lifecycleMapper, org.mockito.Mockito.times(5)).findByCheckinCodeHash(any(String.class));
        verify(lifecycleMapper, never()).insert(any(ReservationLifecycle.class));
        verify(eventMapper, never()).insert(any(ReservationLifecycleEvent.class));
    }

    @Test
    void verifyMarksPresentInsideGraceWindowAndTransitionsToCheckedIn() {
        LocalDateTime start = LocalDateTime.now().minusMinutes(5).withNano(0);
        ReservationLifecycle lifecycle = lifecycle(99L, "123456", start);
        when(lifecycleMapper.findByCheckinCodeHash(hash("123456"))).thenReturn(lifecycle);
        when(lifecycleMapper.selectByIdForUpdate(501L)).thenReturn(lifecycle);
        when(reservationMapper.selectById(99L)).thenReturn(paidReservation(99L, start));
        when(configMapper.findByKey("reservation.checkin.late_grace_minutes")).thenReturn(config("10"));
        CheckinVerifyRequest request = verifyRequest("123456", "scanner-1");

        ReservationLifecycle verified = service.verify(request);

        assertThat(verified).isSameAs(lifecycle);
        assertThat(lifecycle.attendanceStatus).isEqualTo("PRESENT");
        assertThat(lifecycle.checkinStatus).isEqualTo("CHECKED_IN");
        assertThat(lifecycle.checkedInBy).isEqualTo(7L);
        assertThat(lifecycle.checkinSourceType).isEqualTo("ADMIN");
        assertThat(lifecycle.deviceRef).isEqualTo("scanner-1");
        assertThat(lifecycle.lateMinutes).isZero();
        assertThat(lifecycle.checkedInAt).isNotNull();
        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(lifecycleMapper).updateById(lifecycle);
        verify(eventMapper).insert(argThat((ReservationLifecycleEvent event) ->
            event.eventType.equals("CHECKIN_VERIFIED")
                && event.fromStatus.equals("NOT_CHECKED_IN")
                && event.toStatus.equals("CHECKED_IN")
                && event.idempotencyKey.equals("CHECKIN_VERIFIED:99")));
        verify(businessEventService).record(argThat(event ->
            event.eventType == BusinessEventType.CHECKIN_VERIFIED
                && event.idempotencyKey.equals("CHECKIN_VERIFIED:99")));
    }

    @Test
    void verifyMarksLateAfterLateGraceButInsideCheckinWindow() {
        LocalDateTime start = LocalDateTime.now().minusMinutes(20).withNano(0);
        ReservationLifecycle lifecycle = lifecycle(99L, "123456", start);
        when(lifecycleMapper.findByCheckinCodeHash(hash("123456"))).thenReturn(lifecycle);
        when(lifecycleMapper.selectByIdForUpdate(501L)).thenReturn(lifecycle);
        when(reservationMapper.selectById(99L)).thenReturn(paidReservation(99L, start));
        when(configMapper.findByKey("reservation.checkin.late_grace_minutes")).thenReturn(config("10"));

        service.verify(verifyRequest("123456", "scanner-2"));

        assertThat(lifecycle.attendanceStatus).isEqualTo("LATE");
        assertThat(lifecycle.checkinStatus).isEqualTo("CHECKED_IN");
        assertThat(lifecycle.lateMinutes).isGreaterThanOrEqualTo(10);
        verify(lifecycleMapper).updateById(lifecycle);
    }

    @Test
    void verifyRejectsExpiredWindowWithoutManualOverride() {
        LocalDateTime start = LocalDateTime.now().minusHours(3).withNano(0);
        ReservationLifecycle lifecycle = lifecycle(99L, "123456", start);
        lifecycle.checkinWindowEnd = LocalDateTime.now().minusMinutes(1);
        when(lifecycleMapper.findByCheckinCodeHash(hash("123456"))).thenReturn(lifecycle);
        when(lifecycleMapper.selectByIdForUpdate(501L)).thenReturn(lifecycle);
        when(reservationMapper.selectById(99L)).thenReturn(paidReservation(99L, start));

        assertThatThrownBy(() -> service.verify(verifyRequest("123456", "scanner-3")))
            .isInstanceOf(BusinessException.class)
            .hasMessage("核销窗口已关闭");

        assertThat(lifecycle.checkinStatus).isEqualTo("NOT_CHECKED_IN");
        verify(lifecycleMapper, never()).updateById(any(ReservationLifecycle.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void verifyIsIdempotentWhenAlreadyCheckedIn() {
        ReservationLifecycle lifecycle = lifecycle(99L, "123456", LocalDateTime.now());
        lifecycle.checkinStatus = "CHECKED_IN";
        lifecycle.attendanceStatus = "PRESENT";
        lifecycle.checkedInAt = LocalDateTime.now().minusMinutes(3);
        when(lifecycleMapper.findByCheckinCodeHash(hash("123456"))).thenReturn(lifecycle);
        when(lifecycleMapper.selectByIdForUpdate(501L)).thenReturn(lifecycle);

        ReservationLifecycle verified = service.verify(verifyRequest("123456", "scanner-4"));

        assertThat(verified).isSameAs(lifecycle);
        verify(lifecycleMapper, never()).updateById(any(ReservationLifecycle.class));
        verify(eventMapper, never()).insert(any(ReservationLifecycleEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void verifyAlreadyCheckedInStillRequiresVenueAccessBeforeIdempotentReturn() {
        ReservationLifecycle lifecycle = lifecycle(99L, "123456", LocalDateTime.now());
        lifecycle.checkinStatus = "CHECKED_IN";
        lifecycle.attendanceStatus = "PRESENT";
        lifecycle.checkedInAt = LocalDateTime.now().minusMinutes(3);
        when(lifecycleMapper.findByCheckinCodeHash(hash("123456"))).thenReturn(lifecycle);
        when(lifecycleMapper.selectByIdForUpdate(501L)).thenReturn(lifecycle);

        ReservationLifecycle verified = service.verify(verifyRequest("123456", "scanner-4"));

        assertThat(verified).isSameAs(lifecycle);
        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(lifecycleMapper, never()).updateById(any(ReservationLifecycle.class));
        verify(eventMapper, never()).insert(any(ReservationLifecycleEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void verifyRejectsInvalidatedCredentialBeforeAlreadyCheckedInIdempotency() {
        ReservationLifecycle lifecycle = lifecycle(99L, "123456", LocalDateTime.now());
        lifecycle.credentialStatus = "INVALIDATED";
        lifecycle.checkinStatus = "CHECKED_IN";
        lifecycle.attendanceStatus = "PRESENT";
        lifecycle.checkedInAt = LocalDateTime.now().minusMinutes(3);
        when(lifecycleMapper.findByCheckinCodeHash(hash("123456"))).thenReturn(lifecycle);
        when(lifecycleMapper.selectByIdForUpdate(501L)).thenReturn(lifecycle);

        assertThatThrownBy(() -> service.verify(verifyRequest("123456", "scanner-4")))
            .isInstanceOf(BusinessException.class)
            .hasMessage("核销凭证已失效");

        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(lifecycleMapper, never()).updateById(any(ReservationLifecycle.class));
        verify(eventMapper, never()).insert(any(ReservationLifecycleEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void verifyAcceptsQrCredentialByReservationIdAndShortCodeHash() {
        LocalDateTime start = LocalDateTime.now().minusMinutes(5).withNano(0);
        ReservationLifecycle lifecycle = lifecycle(99L, "123456", start);
        when(lifecycleMapper.findByCheckinCodeHash(hash("123456"))).thenReturn(lifecycle);
        when(lifecycleMapper.selectByIdForUpdate(501L)).thenReturn(lifecycle);
        when(reservationMapper.selectById(99L)).thenReturn(paidReservation(99L, start));
        when(configMapper.findByKey("reservation.checkin.late_grace_minutes")).thenReturn(config("10"));

        ReservationLifecycle verified = service.verify(verifyRequest("CHECKIN:99:123456", "scanner-qr"));

        assertThat(verified).isSameAs(lifecycle);
        assertThat(lifecycle.checkinStatus).isEqualTo("CHECKED_IN");
        assertThat(lifecycle.attendanceStatus).isEqualTo("PRESENT");
        verify(lifecycleMapper).findByCheckinCodeHash(hash("123456"));
        verify(lifecycleMapper).updateById(lifecycle);
    }

    @Test
    void verifyRejectsQrPayloadWhenReservationIdIsTamperedBeforeHashFallbackCanVerifyAnotherReservation() {
        LocalDateTime start = LocalDateTime.now().minusMinutes(5).withNano(0);
        ReservationLifecycle lifecycle = lifecycle(99L, "123456", start);
        when(lifecycleMapper.findByCheckinCodeHash(hash("123456"))).thenReturn(lifecycle);

        assertThatThrownBy(() -> service.verify(verifyRequest("CHECKIN:100:123456", "scanner-qr")))
            .isInstanceOf(BusinessException.class)
            .hasMessage("核销凭证无效");

        verify(venueAccessService, never()).requireVenueManageAccess(20L);
        verify(lifecycleMapper, never()).updateById(any(ReservationLifecycle.class));
        verify(eventMapper, never()).insert(any(ReservationLifecycleEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void verifyRejectsNonPaidReservationToPreventRefundAndConsumeConflict() {
        // A26-P2-01：核销前校验预约状态必须为 PAID，防止 REFUNDING/REFUNDED/EXPIRED/CANCELED 等状态被核销。
        // 缺陷：用户 PAID 后开场前申请退款（预约转 REFUNDING），核销窗口开启后到场即可通过核销，
        // 之后退款照常到账，用户既用了场地又拿回全款（资金风险）。
        LocalDateTime start = LocalDateTime.now().minusMinutes(5).withNano(0);
        ReservationLifecycle lifecycle = lifecycle(99L, "123456", start);
        when(lifecycleMapper.findByCheckinCodeHash(hash("123456"))).thenReturn(lifecycle);
        when(lifecycleMapper.selectByIdForUpdate(501L)).thenReturn(lifecycle);
        Reservation reservation = paidReservation(99L, start);
        // 模拟用户申请退款后预约转 REFUNDING（凭证此时未冻结，核销窗口仍开启）
        reservation.status = ReservationStatus.REFUNDING.name();
        when(reservationMapper.selectById(99L)).thenReturn(reservation);

        assertThatThrownBy(() -> service.verify(verifyRequest("123456", "scanner-5")))
            .isInstanceOf(BusinessException.class)
            .hasMessage("当前预约状态不可核销");

        // 不应推进核销状态，不应记录事件
        assertThat(lifecycle.checkinStatus).isEqualTo("NOT_CHECKED_IN");
        assertThat(lifecycle.attendanceStatus).isEqualTo("PENDING");
        verify(lifecycleMapper, never()).updateById(any(ReservationLifecycle.class));
        verify(eventMapper, never()).insert(any(ReservationLifecycleEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void verifyRejectsCanceledReservationToPreventPostCancellationCheckin() {
        // A26-P2-01：CANCELED 状态预约核销也应被拒绝（管理员取消后用户到场不应通过核销）。
        LocalDateTime start = LocalDateTime.now().minusMinutes(5).withNano(0);
        ReservationLifecycle lifecycle = lifecycle(99L, "123456", start);
        when(lifecycleMapper.findByCheckinCodeHash(hash("123456"))).thenReturn(lifecycle);
        when(lifecycleMapper.selectByIdForUpdate(501L)).thenReturn(lifecycle);
        Reservation reservation = paidReservation(99L, start);
        reservation.status = ReservationStatus.CANCELED.name();
        when(reservationMapper.selectById(99L)).thenReturn(reservation);

        assertThatThrownBy(() -> service.verify(verifyRequest("123456", "scanner-6")))
            .isInstanceOf(BusinessException.class)
            .hasMessage("当前预约状态不可核销");

        verify(lifecycleMapper, never()).updateById(any(ReservationLifecycle.class));
        verify(eventMapper, never()).insert(any(ReservationLifecycleEvent.class));
    }

    @Test
    void manualPassAcceptsWindowExceptionAndRecordsManualOverride() {
        LocalDateTime start = LocalDateTime.now().minusHours(3).withNano(0);
        ReservationLifecycle lifecycle = lifecycle(99L, "123456", start);
        lifecycle.checkinWindowEnd = LocalDateTime.now().minusHours(1);
        when(lifecycleMapper.findByReservationId(99L)).thenReturn(lifecycle);
        when(lifecycleMapper.selectByIdForUpdate(501L)).thenReturn(lifecycle);
        when(reservationMapper.selectById(99L)).thenReturn(paidReservation(99L, start));
        ManualAttendanceRequest request = new ManualAttendanceRequest();
        request.reasonCode = "MANUAL_PASS";
        request.reasonText = "设备异常，人工确认到场";
        request.deviceRef = "front-desk";

        ReservationLifecycle passed = service.manualPass(99L, request);

        assertThat(passed).isSameAs(lifecycle);
        assertThat(lifecycle.attendanceStatus).isEqualTo("MANUAL_PRESENT");
        assertThat(lifecycle.checkinStatus).isEqualTo("CHECKED_IN");
        assertThat(lifecycle.manualOverride).isEqualTo(1);
        assertThat(lifecycle.reasonCode).isEqualTo("MANUAL_PASS");
        assertThat(lifecycle.reasonText).isEqualTo("设备异常，人工确认到场");
        assertThat(lifecycle.deviceRef).isEqualTo("front-desk");
        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(lifecycleMapper).updateById(lifecycle);
        verify(eventMapper).insert(argThat((ReservationLifecycleEvent event) ->
            event.eventType.equals("CHECKIN_EXCEPTION_RECORDED")
                && event.toStatus.equals("MANUAL_PRESENT")
                && event.reasonCode.equals("MANUAL_PASS")));
        verify(businessEventService).record(argThat(event ->
            event.eventType == BusinessEventType.CHECKIN_EXCEPTION_RECORDED
                && event.idempotencyKey.equals("CHECKIN_EXCEPTION_RECORDED:99:MANUAL_PASS")));
    }

    @Test
    void manualPassRejectsInvalidatedCredential() {
        ReservationLifecycle lifecycle = lifecycle(99L, "123456", LocalDateTime.now().minusHours(3));
        lifecycle.credentialStatus = "INVALIDATED";
        when(lifecycleMapper.findByReservationId(99L)).thenReturn(lifecycle);
        when(lifecycleMapper.selectByIdForUpdate(501L)).thenReturn(lifecycle);
        when(reservationMapper.selectById(99L)).thenReturn(paidReservation(99L, lifecycle.checkinWindowStart.plusMinutes(30)));

        assertThatThrownBy(() -> service.manualPass(99L, manualPassRequest()))
            .isInstanceOf(BusinessException.class)
            .hasMessage("当前核销凭证不可人工核销");

        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(lifecycleMapper, never()).updateById(any(ReservationLifecycle.class));
        verify(eventMapper, never()).insert(any(ReservationLifecycleEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void manualPassRejectsCanceledLifecycleEvidence() {
        ReservationLifecycle lifecycle = lifecycle(99L, "123456", LocalDateTime.now().minusHours(3));
        lifecycle.attendanceStatus = "CANCELED";
        lifecycle.checkinStatus = "CANCELED";
        when(lifecycleMapper.findByReservationId(99L)).thenReturn(lifecycle);
        when(lifecycleMapper.selectByIdForUpdate(501L)).thenReturn(lifecycle);
        when(reservationMapper.selectById(99L)).thenReturn(paidReservation(99L, lifecycle.checkinWindowStart.plusMinutes(30)));

        assertThatThrownBy(() -> service.manualPass(99L, manualPassRequest()))
            .isInstanceOf(BusinessException.class)
            .hasMessage("当前预约不可人工核销");

        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(lifecycleMapper, never()).updateById(any(ReservationLifecycle.class));
        verify(eventMapper, never()).insert(any(ReservationLifecycleEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void manualPassRejectsNonPaidReservation() {
        ReservationLifecycle lifecycle = lifecycle(99L, "123456", LocalDateTime.now().minusHours(3));
        Reservation reservation = paidReservation(99L, lifecycle.checkinWindowStart.plusMinutes(30));
        reservation.status = ReservationStatus.COMPLETED.name();
        when(lifecycleMapper.findByReservationId(99L)).thenReturn(lifecycle);
        when(lifecycleMapper.selectByIdForUpdate(501L)).thenReturn(lifecycle);
        when(reservationMapper.selectById(99L)).thenReturn(reservation);

        assertThatThrownBy(() -> service.manualPass(99L, manualPassRequest()))
            .isInstanceOf(BusinessException.class)
            .hasMessage("当前预约状态不可人工核销");

        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(lifecycleMapper, never()).updateById(any(ReservationLifecycle.class));
        verify(eventMapper, never()).insert(any(ReservationLifecycleEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void manualPassRejectsAlreadyCheckedInOrConfirmedNoShowEvidence() {
        ReservationLifecycle lifecycle = lifecycle(99L, "123456", LocalDateTime.now().minusHours(3));
        lifecycle.checkinStatus = "CHECKED_IN";
        lifecycle.attendanceStatus = "PRESENT";
        lifecycle.noShowStatus = "CONFIRMED";
        when(lifecycleMapper.findByReservationId(99L)).thenReturn(lifecycle);
        when(lifecycleMapper.selectByIdForUpdate(501L)).thenReturn(lifecycle);
        when(reservationMapper.selectById(99L)).thenReturn(paidReservation(99L, lifecycle.checkinWindowStart.plusMinutes(30)));

        assertThatThrownBy(() -> service.manualPass(99L, manualPassRequest()))
            .isInstanceOf(BusinessException.class)
            .hasMessage("当前预约不可人工核销");

        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(lifecycleMapper, never()).updateById(any(ReservationLifecycle.class));
        verify(eventMapper, never()).insert(any(ReservationLifecycleEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void processCompletionCandidateMarksUnverifiedLifecycleAsNoShowCandidate() {
        LocalDateTime evaluatedAt = LocalDateTime.of(2026, 7, 17, 12, 0);
        LocalDateTime start = evaluatedAt.minusHours(2);
        Reservation reservation = paidReservation(99L, start);
        ReservationLifecycle lifecycle = lifecycle(99L, "123456", start);
        lifecycle.checkinWindowEnd = evaluatedAt.minusMinutes(60);
        when(lifecycleMapper.findByReservationId(99L)).thenReturn(lifecycle);
        when(lifecycleMapper.selectByIdForUpdate(501L)).thenReturn(lifecycle);
        when(configMapper.findByKey("reservation.no_show.confirm_after_minutes")).thenReturn(config("60"));

        CompletionDecision decision = service.processCompletionCandidate(reservation, evaluatedAt);

        assertThat(decision.shouldComplete()).isFalse();
        assertThat(lifecycle.attendanceStatus).isEqualTo("PENDING");
        assertThat(lifecycle.noShowStatus).isEqualTo("CANDIDATE");
        verify(configMapper).findByKey("reservation.no_show.confirm_after_minutes");
        verify(lifecycleMapper).updateById(lifecycle);
        verify(eventMapper).insert(argThat((ReservationLifecycleEvent event) ->
            event.eventType.equals("NO_SHOW_CANDIDATE_MARKED")
                && event.fromStatus.equals("NOT_EVALUATED")
                && event.toStatus.equals("CANDIDATE")));
        verify(businessEventService).record(argThat(event ->
            event.eventType == BusinessEventType.NO_SHOW_CANDIDATE_MARKED
                && event.idempotencyKey.equals("NO_SHOW_CANDIDATE_MARKED:99")));
    }

    @Test
    void processCompletionCandidateWaitsForConfiguredNoShowDelay() {
        LocalDateTime windowEnd = LocalDateTime.of(2026, 7, 17, 10, 0);
        Reservation reservation = paidReservation(99L, windowEnd.minusHours(2));
        ReservationLifecycle lifecycle = lifecycle(99L, "123456", reservation.startTime);
        lifecycle.checkinWindowEnd = windowEnd;
        when(lifecycleMapper.findByReservationId(99L)).thenReturn(lifecycle);
        when(lifecycleMapper.selectByIdForUpdate(501L)).thenReturn(lifecycle);
        when(configMapper.findByKey("reservation.no_show.confirm_after_minutes")).thenReturn(config("60"));

        CompletionDecision decision = service.processCompletionCandidate(reservation, windowEnd.plusMinutes(59));

        assertThat(decision.shouldComplete()).isFalse();
        assertThat(lifecycle.noShowStatus).isEqualTo("NOT_EVALUATED");
        verify(lifecycleMapper, never()).updateById(any(ReservationLifecycle.class));
        verify(eventMapper, never()).insert(any(ReservationLifecycleEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void processCompletionCandidateDoesNotRepeatExistingNoShowCandidate() {
        LocalDateTime evaluatedAt = LocalDateTime.of(2026, 7, 17, 12, 0);
        Reservation reservation = paidReservation(99L, evaluatedAt.minusHours(2));
        ReservationLifecycle lifecycle = lifecycle(99L, "123456", reservation.startTime);
        lifecycle.checkinWindowEnd = evaluatedAt.minusHours(1);
        lifecycle.noShowStatus = "CANDIDATE";
        when(lifecycleMapper.findByReservationId(99L)).thenReturn(lifecycle);
        when(lifecycleMapper.selectByIdForUpdate(501L)).thenReturn(lifecycle);

        CompletionDecision decision = service.processCompletionCandidate(reservation, evaluatedAt);

        assertThat(decision.shouldComplete()).isFalse();
        assertThat(lifecycle.noShowStatus).isEqualTo("CANDIDATE");
        verify(configMapper, never()).findByKey("reservation.no_show.confirm_after_minutes");
        verify(lifecycleMapper, never()).updateById(any(ReservationLifecycle.class));
        verify(eventMapper, never()).insert(any(ReservationLifecycleEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void processCompletionCandidateCompletesConfirmedNoShow() {
        LocalDateTime start = LocalDateTime.now().minusHours(2).withNano(0);
        Reservation reservation = paidReservation(99L, start);
        ReservationLifecycle lifecycle = lifecycle(99L, "123456", start);
        lifecycle.attendanceStatus = "ABSENT";
        lifecycle.noShowStatus = "CONFIRMED";
        lifecycle.reasonText = "超过核销窗口仍未到场";
        when(lifecycleMapper.findByReservationId(99L)).thenReturn(lifecycle);
        when(lifecycleMapper.selectByIdForUpdate(501L)).thenReturn(lifecycle);

        CompletionDecision decision = service.processCompletionCandidate(reservation, LocalDateTime.now());

        assertThat(decision.shouldComplete()).isTrue();
        assertThat(decision.reasonText()).isEqualTo("爽约已确认，可完成");
        verify(lifecycleMapper, never()).updateById(any(ReservationLifecycle.class));
        verify(eventMapper, never()).insert(any(ReservationLifecycleEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void processCompletionCandidateRefusesOverwriteWhenLifecycleConcurrentlyCheckedIn() {
        // AUD-P2-06：自动爽约候选任务先读到旧状态（PENDING/NOT_CHECKED_IN），行锁后再读到最新状态
        // （CHECKED_IN/MANUAL_PRESENT，并发人工核销已写入），不再用旧实体覆盖为爽约候选
        LocalDateTime evaluatedAt = LocalDateTime.of(2026, 7, 17, 12, 0);
        LocalDateTime start = evaluatedAt.minusHours(2);
        Reservation reservation = paidReservation(99L, start);
        ReservationLifecycle stale = lifecycle(99L, "123456", start);
        stale.checkinWindowEnd = evaluatedAt.minusMinutes(60);
        when(lifecycleMapper.findByReservationId(99L)).thenReturn(stale);
        ReservationLifecycle fresh = lifecycle(99L, "123456", start);
        fresh.checkinWindowEnd = evaluatedAt.minusMinutes(60);
        fresh.checkinStatus = "CHECKED_IN";
        fresh.attendanceStatus = "MANUAL_PRESENT";
        when(lifecycleMapper.selectByIdForUpdate(501L)).thenReturn(fresh);

        CompletionDecision decision = service.processCompletionCandidate(reservation, evaluatedAt);

        assertThat(decision.shouldComplete()).isTrue();
        assertThat(decision.reasonText()).isEqualTo("预约已核销，可完成");
        assertThat(stale.noShowStatus).isEqualTo("NOT_EVALUATED");
        assertThat(fresh.noShowStatus).isEqualTo("NOT_EVALUATED");
        verify(lifecycleMapper, never()).updateById(any(ReservationLifecycle.class));
        verify(eventMapper, never()).insert(any(ReservationLifecycleEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void processCompletionCandidateSkipsRevokedNoShowStatusToAvoidSchedulerCrashLoop() {
        // A26-P2-02：爽约撤销后 noShowStatus=REVOKED，attendanceStatus 仍为 PENDING、checkinStatus 仍为
        // NOT_CHECKED_IN，processCompletionCandidate 守卫若不排除 REVOKED 会重新进入候选标记流程，
        // 写入幂等键 EVENT_NO_SHOW_CANDIDATE_MARKED:99 撞 uk_lifecycle_event_idempotency（之前已写过），
        // 子事务回滚被外层 catch 记 error，每 5 分钟循环一次，预约永不 COMPLETED、订单永不关闭。
        LocalDateTime evaluatedAt = LocalDateTime.of(2026, 7, 17, 12, 0);
        LocalDateTime start = evaluatedAt.minusHours(2);
        Reservation reservation = paidReservation(99L, start);
        ReservationLifecycle lifecycle = lifecycle(99L, "123456", start);
        lifecycle.checkinWindowEnd = evaluatedAt.minusMinutes(60);
        // 爽约已确认 → 撤销：attendanceStatus 回 PENDING、checkinStatus 回 NOT_CHECKED_IN、noShowStatus=REVOKED
        lifecycle.noShowStatus = "REVOKED";
        lifecycle.attendanceStatus = "PENDING";
        lifecycle.checkinStatus = "NOT_CHECKED_IN";
        when(lifecycleMapper.findByReservationId(99L)).thenReturn(lifecycle);
        when(lifecycleMapper.selectByIdForUpdate(501L)).thenReturn(lifecycle);

        CompletionDecision decision = service.processCompletionCandidate(reservation, evaluatedAt);

        // REVOKED 守卫触发：不应进入候选标记流程，不应更新 lifecycle，不应写事件
        assertThat(decision.shouldComplete()).isFalse();
        assertThat(lifecycle.noShowStatus).isEqualTo("REVOKED");
        assertThat(lifecycle.attendanceStatus).isEqualTo("PENDING");
        assertThat(lifecycle.checkinStatus).isEqualTo("NOT_CHECKED_IN");
        verify(configMapper, never()).findByKey("reservation.no_show.confirm_after_minutes");
        verify(lifecycleMapper, never()).updateById(any(ReservationLifecycle.class));
        verify(eventMapper, never()).insert(any(ReservationLifecycleEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void writeLifecycleEventSwallowsDuplicateKeyExceptionToAvoidOuterTransactionRollback() {
        // A26-P2-02：writeLifecycleEvent 加 DuplicateKeyException 兜底，对照 BusinessEventService.record。
        // 缺陷：原 eventMapper.insert 抛 DuplicateKeyException（幂等键撞 uk_lifecycle_event_idempotency）
        // 直接传播到外层 @Transactional 方法，事务回滚导致 lifecycle.updateById 写入的状态也被回滚；
        // 外层 catch 仅记 error 不能恢复，每 5 分钟重复一次。
        // 修复后：catch DuplicateKeyException 记 WARN 后继续，lifecycle.updateById 已先于此执行，
        // 事务正常提交，状态变更持久生效。
        LocalDateTime start = LocalDateTime.now().minusMinutes(5).withNano(0);
        ReservationLifecycle lifecycle = lifecycle(99L, "123456", start);
        when(lifecycleMapper.findByCheckinCodeHash(hash("123456"))).thenReturn(lifecycle);
        when(lifecycleMapper.selectByIdForUpdate(501L)).thenReturn(lifecycle);
        Reservation reservation = paidReservation(99L, start);
        when(reservationMapper.selectById(99L)).thenReturn(reservation);
        when(configMapper.findByKey("reservation.checkin.late_grace_minutes")).thenReturn(config("10"));
        // eventMapper.insert 抛 DuplicateKeyException 模拟幂等键冲突
        org.mockito.Mockito.doThrow(new org.springframework.dao.DuplicateKeyException(
            "Duplicate entry 'CHECKIN_VERIFIED:99' for key 'uk_lifecycle_event_idempotency'"))
            .when(eventMapper).insert(any(ReservationLifecycleEvent.class));

        // verify 应正常完成（不抛 DuplicateKeyException），lifecycle 状态已写入
        ReservationLifecycle verified = service.verify(verifyRequest("123456", "scanner-dupkey"));

        assertThat(verified).isSameAs(lifecycle);
        assertThat(lifecycle.checkinStatus).isEqualTo("CHECKED_IN");
        assertThat(lifecycle.attendanceStatus).isEqualTo("PRESENT");
        // lifecycle.updateById 在 writeLifecycleEvent 之前执行，已写入
        verify(lifecycleMapper).updateById(lifecycle);
        // eventMapper.insert 抛了 DuplicateKeyException 但被 catch
        verify(eventMapper).insert(any(ReservationLifecycleEvent.class));
        // businessEventService.record 仍被调用（writeBusinessEvent 独立于 writeLifecycleEvent）
        verify(businessEventService).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void manualPassRefusesOverwriteWhenLifecycleConcurrentlyCheckedIn() {
        // AUD-P2-06：人工核销先读到旧状态（PENDING/NOT_CHECKED_IN），行锁后再读到最新状态
        // （CHECKED_IN/PRESENT，并发凭证核销已写入），不再用旧实体覆盖当前事实
        LocalDateTime start = LocalDateTime.now().minusHours(3).withNano(0);
        ReservationLifecycle stale = lifecycle(99L, "123456", start);
        when(lifecycleMapper.findByReservationId(99L)).thenReturn(stale);
        ReservationLifecycle fresh = lifecycle(99L, "123456", start);
        fresh.checkinStatus = "CHECKED_IN";
        fresh.attendanceStatus = "PRESENT";
        when(lifecycleMapper.selectByIdForUpdate(501L)).thenReturn(fresh);
        when(reservationMapper.selectById(99L)).thenReturn(paidReservation(99L, start));

        assertThatThrownBy(() -> service.manualPass(99L, manualPassRequest()))
            .isInstanceOf(BusinessException.class)
            .hasMessage("当前预约不可人工核销");

        assertThat(stale.attendanceStatus).isEqualTo("PENDING");
        assertThat(stale.checkinStatus).isEqualTo("NOT_CHECKED_IN");
        verify(lifecycleMapper, never()).updateById(any(ReservationLifecycle.class));
        verify(eventMapper, never()).insert(any(ReservationLifecycleEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void confirmNoShowMarksAbsentAndConfirmedWithoutChangingReservationOrOrder() {
        ReservationLifecycle lifecycle = lifecycle(99L, "123456", LocalDateTime.now().minusHours(2));
        lifecycle.noShowStatus = "CANDIDATE";
        when(lifecycleMapper.findByReservationId(99L)).thenReturn(lifecycle);
        when(lifecycleMapper.selectByIdForUpdate(501L)).thenReturn(lifecycle);
        NoShowDecisionRequest request = noShowRequest("NO_SHOW_CONFIRMED", "超过核销窗口仍未到场");

        ReservationLifecycle confirmed = service.confirmNoShow(99L, request);

        assertThat(confirmed).isSameAs(lifecycle);
        assertThat(lifecycle.attendanceStatus).isEqualTo("ABSENT");
        assertThat(lifecycle.noShowStatus).isEqualTo("CONFIRMED");
        assertThat(lifecycle.reasonCode).isEqualTo("NO_SHOW_CONFIRMED");
        assertThat(lifecycle.reasonText).isEqualTo("超过核销窗口仍未到场");
        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(lifecycleMapper).updateById(lifecycle);
        verify(eventMapper).insert(argThat((ReservationLifecycleEvent event) ->
            event.eventType.equals("NO_SHOW_CONFIRMED")
                && event.fromStatus.equals("CANDIDATE")
                && event.toStatus.equals("CONFIRMED")));
        verify(businessEventService).record(argThat(event ->
            event.eventType == BusinessEventType.NO_SHOW_CONFIRMED
                && event.idempotencyKey.equals("NO_SHOW_CONFIRMED:99")));
        verify(reservationMapper, never()).updateById(any(Reservation.class));
        verify(orderMapper, never()).updateById(any(BizOrder.class));
    }

    @Test
    void confirmNoShowRejectsCheckedInPresentLifecycleEvenWhenMarkedCandidate() {
        ReservationLifecycle lifecycle = lifecycle(99L, "123456", LocalDateTime.now().minusHours(2));
        lifecycle.checkinStatus = "CHECKED_IN";
        lifecycle.attendanceStatus = "PRESENT";
        lifecycle.noShowStatus = "CANDIDATE";
        when(lifecycleMapper.findByReservationId(99L)).thenReturn(lifecycle);
        when(lifecycleMapper.selectByIdForUpdate(501L)).thenReturn(lifecycle);
        NoShowDecisionRequest request = noShowRequest("NO_SHOW_CONFIRMED", "超过核销窗口仍未到场");

        assertThatThrownBy(() -> service.confirmNoShow(99L, request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("当前预约不可确认爽约");

        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(lifecycleMapper, never()).updateById(any(ReservationLifecycle.class));
        verify(eventMapper, never()).insert(any(ReservationLifecycleEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void revokeNoShowRestoresManualPresentWhenRequested() {
        ReservationLifecycle lifecycle = lifecycle(99L, "123456", LocalDateTime.now().minusHours(2));
        lifecycle.attendanceStatus = "ABSENT";
        lifecycle.noShowStatus = "CONFIRMED";
        when(lifecycleMapper.findByReservationId(99L)).thenReturn(lifecycle);
        when(lifecycleMapper.selectByIdForUpdate(501L)).thenReturn(lifecycle);
        when(reservationMapper.selectById(99L)).thenReturn(paidReservation(99L, lifecycle.checkinWindowStart.plusMinutes(30)));

        ReservationLifecycle revoked = service.revokeNoShow(99L, noShowRequest("MANUAL_PRESENT", "管理员复核确认到场"));

        assertThat(revoked).isSameAs(lifecycle);
        assertThat(lifecycle.attendanceStatus).isEqualTo("MANUAL_PRESENT");
        assertThat(lifecycle.noShowStatus).isEqualTo("REVOKED");
        verify(lifecycleMapper).updateById(lifecycle);
        verify(eventMapper).insert(argThat((ReservationLifecycleEvent event) ->
            event.eventType.equals("NO_SHOW_REVOKED")
                && event.fromStatus.equals("CONFIRMED")
                && event.toStatus.equals("REVOKED")));
        verify(businessEventService).record(argThat(event ->
            event.eventType == BusinessEventType.NO_SHOW_REVOKED
                && event.idempotencyKey.equals("NO_SHOW_REVOKED:99")));
    }

    @Test
    void revokeNoShowRestoresPendingByDefault() {
        ReservationLifecycle lifecycle = lifecycle(99L, "123456", LocalDateTime.now().minusHours(2));
        lifecycle.attendanceStatus = "ABSENT";
        lifecycle.noShowStatus = "CONFIRMED";
        when(lifecycleMapper.findByReservationId(99L)).thenReturn(lifecycle);
        when(lifecycleMapper.selectByIdForUpdate(501L)).thenReturn(lifecycle);
        when(reservationMapper.selectById(99L)).thenReturn(paidReservation(99L, lifecycle.checkinWindowStart.plusMinutes(30)));

        service.revokeNoShow(99L, noShowRequest("WRONG_JUDGEMENT", "误判，等待重新核销"));

        assertThat(lifecycle.attendanceStatus).isEqualTo("PENDING");
        assertThat(lifecycle.noShowStatus).isEqualTo("REVOKED");
        verify(lifecycleMapper).updateById(lifecycle);
    }

    @Test
    void revokeNoShowRejectsCompletedReservationWhenRevokeWouldRestorePendingEvidence() {
        ReservationLifecycle lifecycle = lifecycle(99L, "123456", LocalDateTime.now().minusHours(2));
        lifecycle.attendanceStatus = "ABSENT";
        lifecycle.noShowStatus = "CONFIRMED";
        Reservation reservation = paidReservation(99L, lifecycle.checkinWindowStart.plusMinutes(30));
        reservation.status = ReservationStatus.COMPLETED.name();
        when(lifecycleMapper.findByReservationId(99L)).thenReturn(lifecycle);
        when(lifecycleMapper.selectByIdForUpdate(501L)).thenReturn(lifecycle);
        when(reservationMapper.selectById(99L)).thenReturn(reservation);

        assertThatThrownBy(() -> service.revokeNoShow(99L, noShowRequest("WRONG_JUDGEMENT", "误判，等待重新核销")))
            .isInstanceOf(BusinessException.class)
            .hasMessage("已完成预约只能撤销为人工到场");

        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(lifecycleMapper, never()).updateById(any(ReservationLifecycle.class));
        verify(eventMapper, never()).insert(any(ReservationLifecycleEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void revokeNoShowAllowsCompletedReservationWhenManualPresentAndKeepsEvidenceConsistent() {
        ReservationLifecycle lifecycle = lifecycle(99L, "123456", LocalDateTime.now().minusHours(2));
        lifecycle.attendanceStatus = "ABSENT";
        lifecycle.noShowStatus = "CONFIRMED";
        Reservation reservation = paidReservation(99L, lifecycle.checkinWindowStart.plusMinutes(30));
        reservation.status = ReservationStatus.COMPLETED.name();
        when(lifecycleMapper.findByReservationId(99L)).thenReturn(lifecycle);
        when(lifecycleMapper.selectByIdForUpdate(501L)).thenReturn(lifecycle);
        when(reservationMapper.selectById(99L)).thenReturn(reservation);

        ReservationLifecycle revoked = service.revokeNoShow(99L, noShowRequest("MANUAL_PRESENT", "管理员复核确认到场"));

        assertThat(revoked).isSameAs(lifecycle);
        assertThat(lifecycle.attendanceStatus).isEqualTo("MANUAL_PRESENT");
        assertThat(lifecycle.checkinStatus).isEqualTo("CHECKED_IN");
        assertThat(lifecycle.noShowStatus).isEqualTo("REVOKED");
        assertThat(lifecycle.manualOverride).isEqualTo(1);
        assertThat(lifecycle.checkedInAt).isNotNull();
        assertThat(lifecycle.checkedInBy).isEqualTo(7L);
        assertThat(lifecycle.checkinSourceType).isEqualTo("ADMIN");
        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(lifecycleMapper).updateById(lifecycle);
        verify(eventMapper).insert(argThat((ReservationLifecycleEvent event) ->
            event.eventType.equals("NO_SHOW_REVOKED")
                && event.fromStatus.equals("CONFIRMED")
                && event.toStatus.equals("REVOKED")));
    }

    @Test
    void revokeNoShowRejectsLifecycleThatIsNotConfirmedNoShow() {
        ReservationLifecycle lifecycle = lifecycle(99L, "123456", LocalDateTime.now().minusHours(2));
        lifecycle.noShowStatus = "CANDIDATE";
        when(lifecycleMapper.findByReservationId(99L)).thenReturn(lifecycle);
        when(lifecycleMapper.selectByIdForUpdate(501L)).thenReturn(lifecycle);

        assertThatThrownBy(() -> service.revokeNoShow(99L, noShowRequest("WRONG_JUDGEMENT", "误判，等待重新核销")))
            .isInstanceOf(BusinessException.class)
            .hasMessage("当前预约不可撤销爽约");

        verify(venueAccessService).requireVenueManageAccess(20L);
        verify(lifecycleMapper, never()).updateById(any(ReservationLifecycle.class));
        verify(eventMapper, never()).insert(any(ReservationLifecycleEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void verifyRefusesOverwriteWhenLifecycleConcurrentlyCheckedInByManualPass() {
        // A26-P3-02：verify 行锁 lifecycle 行后重读，避免基于过期快照整行回写丢失并发 manualPass 的写入
        // （manual_override=1 被 verify 写回 0）。缺陷：原 verify 用 findByCredential 拿到的快照整行回写，
        // 并发 manualPass 写入的 manual_override=1/checkinStatus=CHECKED_IN 会被 verify 覆盖回 0/旧值。
        // 修复后：selectByIdForUpdate 重读后若已 CHECKED_IN（并发 manualPass），幂等返回不覆盖。
        LocalDateTime start = LocalDateTime.now().minusMinutes(5).withNano(0);
        ReservationLifecycle stale = lifecycle(99L, "123456", start);
        ReservationLifecycle fresh = lifecycle(99L, "123456", start);
        fresh.checkinStatus = "CHECKED_IN";
        fresh.attendanceStatus = "MANUAL_PRESENT";
        fresh.manualOverride = 1;
        when(lifecycleMapper.findByCheckinCodeHash(hash("123456"))).thenReturn(stale);
        when(lifecycleMapper.selectByIdForUpdate(501L)).thenReturn(fresh);

        ReservationLifecycle verified = service.verify(verifyRequest("123456", "scanner-concurrent"));

        assertThat(verified).isSameAs(fresh);
        // 不应回写 lifecycle（幂等返回，不覆盖 manualPass 的 manualOverride=1）
        verify(lifecycleMapper, never()).updateById(any(ReservationLifecycle.class));
        verify(eventMapper, never()).insert(any(ReservationLifecycleEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void confirmNoShowRefusesOverwriteWhenLifecycleConcurrentlyCheckedIn() {
        // A26-P3-02：confirmNoShow 行锁 lifecycle 行后重读，避免基于过期快照通过 isUnverifiedNoShowCandidate
        // 守卫并整行回写覆盖当前事实。缺陷：原 confirmNoShow 用 requireLifecycle 拿到的快照（CANDIDATE/
        // NOT_CHECKED_IN/PENDING）通过守卫，并发 verify 已把 checkinStatus 改为 CHECKED_IN，confirmNoShow
        // 仍用旧快照整行回写 attendanceStatus=ABSENT，覆盖 verify 的 PRESENT。
        // 修复后：selectByIdForUpdate 重读后若已 CHECKED_IN（并发 verify），守卫失败抛"当前预约不可确认爽约"。
        ReservationLifecycle stale = lifecycle(99L, "123456", LocalDateTime.now().minusHours(2));
        stale.noShowStatus = "CANDIDATE";
        ReservationLifecycle fresh = lifecycle(99L, "123456", LocalDateTime.now().minusHours(2));
        fresh.noShowStatus = "CANDIDATE";
        fresh.checkinStatus = "CHECKED_IN";
        fresh.attendanceStatus = "PRESENT";
        when(lifecycleMapper.findByReservationId(99L)).thenReturn(stale);
        when(lifecycleMapper.selectByIdForUpdate(501L)).thenReturn(fresh);
        NoShowDecisionRequest request = noShowRequest("NO_SHOW_CONFIRMED", "超过核销窗口仍未到场");

        assertThatThrownBy(() -> service.confirmNoShow(99L, request))
            .isInstanceOf(BusinessException.class)
            .hasMessage("当前预约不可确认爽约");

        verify(lifecycleMapper, never()).updateById(any(ReservationLifecycle.class));
        verify(eventMapper, never()).insert(any(ReservationLifecycleEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void revokeNoShowRefusesOverwriteWhenLifecycleConcurrentlyRevoked() {
        // A26-P3-02：revokeNoShow 行锁 lifecycle 行后重读，NO_SHOW_CONFIRMED 守卫基于锁后快照。
        // 缺陷：原 revokeNoShow 用 requireLifecycle 拿到的快照（CONFIRMED）通过守卫，并发已撤销把
        // noShowStatus 改为 REVOKED，revokeNoShow 仍用旧快照整行回写覆盖当前事实。
        // 修复后：selectByIdForUpdate 重读后若已 REVOKED（并发撤销），守卫失败抛"当前预约不可撤销爽约"。
        ReservationLifecycle stale = lifecycle(99L, "123456", LocalDateTime.now().minusHours(2));
        stale.attendanceStatus = "ABSENT";
        stale.noShowStatus = "CONFIRMED";
        ReservationLifecycle fresh = lifecycle(99L, "123456", LocalDateTime.now().minusHours(2));
        fresh.attendanceStatus = "PENDING";
        fresh.noShowStatus = "REVOKED";
        when(lifecycleMapper.findByReservationId(99L)).thenReturn(stale);
        when(lifecycleMapper.selectByIdForUpdate(501L)).thenReturn(fresh);

        assertThatThrownBy(() -> service.revokeNoShow(99L, noShowRequest("WRONG_JUDGEMENT", "误判")))
            .isInstanceOf(BusinessException.class)
            .hasMessage("当前预约不可撤销爽约");

        verify(lifecycleMapper, never()).updateById(any(ReservationLifecycle.class));
        verify(eventMapper, never()).insert(any(ReservationLifecycleEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void revokeNoShowRechecksCompletedStatusUnderReservationRowLock() {
        // A26-P3-02：revokeNoShow 的 COMPLETED 守卫基于锁后 reservation 快照。
        // 缺陷：原 revokeNoShow 用 requireReservation（无锁 selectById）拿到的快照（非 COMPLETED）通过守卫，
        // 并发完成把 reservation 改为 COMPLETED，revokeNoShow 仍用旧快照撤销爽约，COMPLETED 预约的
        // attendanceStatus 被回退为 PENDING。
        // 修复后：requireReservationForUpdate（selectByIdForUpdate）重读，若已 COMPLETED（并发完成），
        // 非 manualPresent 守卫失败抛"已完成预约只能撤销为人工到场"。
        ReservationLifecycle lifecycle = lifecycle(99L, "123456", LocalDateTime.now().minusHours(2));
        lifecycle.attendanceStatus = "ABSENT";
        lifecycle.noShowStatus = "CONFIRMED";
        when(lifecycleMapper.findByReservationId(99L)).thenReturn(lifecycle);
        when(lifecycleMapper.selectByIdForUpdate(501L)).thenReturn(lifecycle);
        Reservation stale = paidReservation(99L, lifecycle.checkinWindowStart.plusMinutes(30));
        Reservation fresh = paidReservation(99L, lifecycle.checkinWindowStart.plusMinutes(30));
        fresh.status = ReservationStatus.COMPLETED.name();
        when(reservationMapper.selectById(99L)).thenReturn(stale);
        when(reservationMapper.selectByIdForUpdate(99L)).thenReturn(fresh);

        assertThatThrownBy(() -> service.revokeNoShow(99L, noShowRequest("WRONG_JUDGEMENT", "误判")))
            .isInstanceOf(BusinessException.class)
            .hasMessage("已完成预约只能撤销为人工到场");

        verify(lifecycleMapper, never()).updateById(any(ReservationLifecycle.class));
        verify(eventMapper, never()).insert(any(ReservationLifecycleEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void freezeCredentialForReservationSetsCredentialStatusInvalidated() {
        ReservationLifecycle lifecycle = lifecycle(99L, "123456", LocalDateTime.now());
        when(lifecycleMapper.findByReservationId(99L)).thenReturn(lifecycle);

        service.freezeCredentialForReservation(99L, "预约取消");

        assertThat(lifecycle.credentialStatus).isEqualTo("INVALIDATED");
        assertThat(lifecycle.reasonText).isEqualTo("预约取消");
        verify(lifecycleMapper).updateById(lifecycle);
        verify(eventMapper).insert(argThat((ReservationLifecycleEvent event) ->
            event.eventType.equals("CHECKIN_CREDENTIAL_INVALIDATED")
                && event.toStatus.equals("INVALIDATED")));
    }

    @Test
    void freezeCredentialForReservationDoesNotInsertEventWhenAlreadyInvalidated() {
        ReservationLifecycle lifecycle = lifecycle(99L, "123456", LocalDateTime.now());
        lifecycle.credentialStatus = "INVALIDATED";
        lifecycle.reasonText = "用户取消";
        when(lifecycleMapper.findByReservationId(99L)).thenReturn(lifecycle);

        service.freezeCredentialForReservation(99L, "退款到账");

        assertThat(lifecycle.credentialStatus).isEqualTo("INVALIDATED");
        verify(eventMapper, never()).insert(any(ReservationLifecycleEvent.class));
        verify(lifecycleMapper, never()).updateById(any(ReservationLifecycle.class));
        assertThat(lifecycle.reasonText).isEqualTo("用户取消");
    }

    @Test
    void freezeCredentialForReservationSkipsWhenLifecycleMissing() {
        when(lifecycleMapper.findByReservationId(99L)).thenReturn(null);

        service.freezeCredentialForReservation(99L, "订单超时关闭");

        verify(lifecycleMapper, never()).updateById(any(ReservationLifecycle.class));
        verify(eventMapper, never()).insert(any(ReservationLifecycleEvent.class));
        verify(businessEventService, never()).record(any(BusinessEventRecordRequest.class));
    }

    @Test
    void createRescheduleRequestInsertsPendingRequestAndBusinessEvent() {
        Reservation reservation = paidReservation(99L, LocalDateTime.now().plusDays(2).withHour(10).withMinute(0)
            .withSecond(0).withNano(0));
        reservation.status = ReservationStatus.PENDING_PAYMENT.name();
        RescheduleRequest request = rescheduleRequest(reservation.startTime.plusDays(1), reservation.endTime.plusDays(1));
        when(reservationMapper.selectById(99L)).thenReturn(reservation);
        when(rescheduleMapper.insert(any(ReservationRescheduleRequest.class))).thenAnswer(invocation -> {
            ReservationRescheduleRequest inserted = invocation.getArgument(0);
            inserted.id = 301L;
            return 1;
        });

        ReservationRescheduleRequest created = service.createRescheduleRequest(reservation, request);

        ArgumentCaptor<ReservationRescheduleRequest> requestCaptor =
            ArgumentCaptor.forClass(ReservationRescheduleRequest.class);
        verify(rescheduleMapper).insert(requestCaptor.capture());
        ReservationRescheduleRequest inserted = requestCaptor.getValue();
        assertThat(created).isSameAs(inserted);
        assertThat(inserted.reservationId).isEqualTo(99L);
        assertThat(inserted.userId).isEqualTo(7L);
        assertThat(inserted.venueId).isEqualTo(20L);
        assertThat(inserted.spaceId).isEqualTo(10L);
        assertThat(inserted.fromStartTime).isEqualTo(reservation.startTime);
        assertThat(inserted.fromEndTime).isEqualTo(reservation.endTime);
        assertThat(inserted.toStartTime).isEqualTo(request.startTime);
        assertThat(inserted.toEndTime).isEqualTo(request.endTime);
        assertThat(inserted.status).isEqualTo("PENDING_REVIEW");
        assertThat(inserted.reasonCode).isEqualTo("TIME_CONFLICT");
        assertThat(inserted.reasonText).isEqualTo("时间冲突");
        verify(businessEventService).record(argThat(event ->
            event.eventType == BusinessEventType.RESCHEDULE_REQUESTED
                && event.businessDomain == BusinessDomain.RESERVATION
                && event.entityType.equals("RESERVATION_RESCHEDULE_REQUEST")
                && event.entityId.equals(301L)
                && event.relatedEntityType.equals("RESERVATION")
                && event.relatedEntityId.equals(99L)
                && event.sourceType == BusinessSourceType.USER
                && event.toStatus.equals("PENDING_REVIEW")
                && event.reasonCode.equals("TIME_CONFLICT")
                && event.reasonText.equals("时间冲突")
                && event.idempotencyKey.equals("RESCHEDULE_REQUESTED:301")));
    }

    @Test
    void createRescheduleRequestRejectsExistingPendingRequestWhileReservationIsLocked() {
        Reservation reservation = paidReservation(99L, LocalDateTime.now().plusDays(2).withHour(10).withMinute(0)
            .withSecond(0).withNano(0));
        reservation.status = ReservationStatus.PENDING_PAYMENT.name();
        when(reservationMapper.selectById(99L)).thenReturn(reservation);
        when(rescheduleMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);

        assertThatThrownBy(() -> service.createRescheduleRequest(reservation,
            rescheduleRequest(reservation.startTime.plusDays(1), reservation.endTime.plusDays(1))))
            .isInstanceOf(BusinessException.class)
            .hasMessage("预约已有待审核改期申请");

        verify(reservationMapper).selectByIdForUpdate(99L);
        verify(rescheduleMapper, never()).insert(any(ReservationRescheduleRequest.class));
    }

    @Test
    void approveRescheduleRequestRecordsReviewReasonAndEvent() {
        ReservationRescheduleRequest request = pendingRescheduleRequest();

        service.approveRescheduleRequest(request, "同意改期");

        assertThat(request.status).isEqualTo("APPROVED");
        assertThat(request.reviewedBy).isEqualTo(7L);
        assertThat(request.reviewedAt).isNotNull();
        assertThat(request.reviewReason).isEqualTo("同意改期");
        verify(rescheduleMapper).updateById(request);
        verify(businessEventService).record(argThat(event ->
            event.eventType == BusinessEventType.RESCHEDULE_APPROVED
                && event.entityType.equals("RESERVATION_RESCHEDULE_REQUEST")
                && event.entityId.equals(301L)
                && event.fromStatus.equals("PENDING_REVIEW")
                && event.toStatus.equals("APPROVED")
                && event.reasonText.equals("同意改期")));
    }

    @Test
    void rejectRescheduleRequestRecordsReviewReasonAndEvent() {
        ReservationRescheduleRequest request = pendingRescheduleRequest();

        service.rejectRescheduleRequest(request, "库存不足");

        assertThat(request.status).isEqualTo("REJECTED");
        assertThat(request.reviewedBy).isEqualTo(7L);
        assertThat(request.reviewedAt).isNotNull();
        assertThat(request.reviewReason).isEqualTo("库存不足");
        verify(rescheduleMapper).updateById(request);
        verify(businessEventService).record(argThat(event ->
            event.eventType == BusinessEventType.RESCHEDULE_REJECTED
                && event.entityType.equals("RESERVATION_RESCHEDULE_REQUEST")
                && event.entityId.equals(301L)
                && event.fromStatus.equals("PENDING_REVIEW")
                && event.toStatus.equals("REJECTED")
                && event.reasonText.equals("库存不足")));
    }

    @Test
    void recordCancellationStoresReasonCodeAndReasonTextInLifecycleEvent() {
        Reservation reservation = paidReservation(99L, LocalDateTime.now().plusDays(2).withHour(10).withMinute(0)
            .withSecond(0).withNano(0));
        ReservationLifecycle lifecycle = lifecycle(99L, "123456", reservation.startTime);
        when(lifecycleMapper.findByReservationId(99L)).thenReturn(lifecycle);

        service.recordCancellation(reservation, ReservationStatus.PAID.name(), "TIME_CONFLICT", "时间冲突", "USER");

        assertThat(lifecycle.attendanceStatus).isEqualTo("CANCELED");
        assertThat(lifecycle.checkinStatus).isEqualTo("CANCELED");
        assertThat(lifecycle.reasonCode).isEqualTo("TIME_CONFLICT");
        assertThat(lifecycle.reasonText).isEqualTo("时间冲突");
        verify(lifecycleMapper).updateById(lifecycle);
        verify(eventMapper).insert(argThat((ReservationLifecycleEvent event) ->
            event.eventType.equals("RESERVATION_CANCELED")
                && event.reservationId.equals(99L)
                && event.lifecycleId.equals(501L)
                && event.fromStatus.equals(ReservationStatus.PAID.name())
                && event.toStatus.equals("CANCELED")
                && event.sourceType.equals("USER")
                && event.reasonCode.equals("TIME_CONFLICT")
                && event.reasonText.equals("时间冲突")));
        verify(businessEventService).record(argThat(event ->
            event.eventType == BusinessEventType.RESERVATION_CANCELED
                && event.reasonCode.equals("TIME_CONFLICT")
                && event.reasonText.equals("时间冲突")
                && event.fromStatus.equals(ReservationStatus.PAID.name())
                && event.toStatus.equals("CANCELED")));
    }

    @Test
    void myLifecycleReturnsOnlyOwnedLifecycleResponse() {
        ReservationLifecycle lifecycle = lifecycle(99L, "123456", LocalDateTime.now());
        when(lifecycleMapper.findByReservationId(99L)).thenReturn(lifecycle);

        ReservationLifecycleResponse response = service.myLifecycle(99L, 7L);

        assertThat(response.reservationId).isEqualTo(99L);
        assertThat(response.credentialStatus).isEqualTo("ISSUED");
        assertThat(response.checkinCodeLast4).isEqualTo("3456");
        // A26-P1-01：查询接口不返回明文短码与 qrPayload（仅签发响应一次性交付）
        assertThat(response.checkinCode).isNull();
        assertThat(response.qrPayload).isNull();
    }

    @Test
    void queryEndpointsDoNotReturnCheckinCodePlaintext() {
        // A26-P1-01：myLifecycle/adminLifecycle/myCheckins/adminCheckins/verify/manualPass 等查询接口
        // 均通过 toResponse 构造响应，toResponse 不填充 checkinCode/qrPayload，确保明文不通过查询接口泄露
        ReservationLifecycle lifecycle = lifecycle(99L, "123456", LocalDateTime.now());

        ReservationLifecycleResponse response = service.toResponse(lifecycle);

        assertThat(response.checkinCode).isNull();
        assertThat(response.qrPayload).isNull();
        assertThat(response.checkinCodeLast4).isEqualTo("3456");
    }

    @Test
    void toResponseIncludesRecentLifecycleEventsNewestFirst() {
        ReservationLifecycle lifecycle = lifecycle(99L, "123456", LocalDateTime.now());
        ReservationLifecycleEvent event = new ReservationLifecycleEvent();
        event.id = 900L;
        event.reservationId = 99L;
        event.lifecycleId = 501L;
        event.eventType = "CHECKIN_VERIFIED";
        event.fromStatus = "NOT_CHECKED_IN";
        event.toStatus = "CHECKED_IN";
        event.sourceType = "ADMIN";
        event.reasonCode = "FRONT_DESK";
        event.reasonText = "前台核销";
        event.createTime = LocalDateTime.of(2026, 7, 3, 10, 2);
        when(eventMapper.findRecentByReservationId(99L, 5)).thenReturn(List.of(event));

        ReservationLifecycleResponse response = service.toResponse(lifecycle);

        assertThat(response.events).hasSize(1);
        assertThat(response.events.get(0).eventType).isEqualTo("CHECKIN_VERIFIED");
        assertThat(response.events.get(0).fromStatus).isEqualTo("NOT_CHECKED_IN");
        assertThat(response.events.get(0).toStatus).isEqualTo("CHECKED_IN");
        assertThat(response.events.get(0).sourceType).isEqualTo("ADMIN");
        assertThat(response.events.get(0).reasonCode).isEqualTo("FRONT_DESK");
        assertThat(response.events.get(0).reasonText).isEqualTo("前台核销");
        assertThat(response.events.get(0).createTime).isEqualTo(LocalDateTime.of(2026, 7, 3, 10, 2));
        verify(eventMapper).findRecentByReservationId(99L, 5);
    }

    @Test
    void myLifecycleRejectsOtherUsersLifecycle() {
        ReservationLifecycle lifecycle = lifecycle(99L, "123456", LocalDateTime.now());
        when(lifecycleMapper.findByReservationId(99L)).thenReturn(lifecycle);

        assertThatThrownBy(() -> service.myLifecycle(99L, 8L))
            .isInstanceOf(BusinessException.class)
            .hasMessage("无权查看该核销凭证");
    }

    @Test
    // SHIGUANG-WAVE-03-REMEDIATION-20260917 S02 / F-14：显式 venueId 必须先做授权调用，
    // 且八个领域筛选字段全部进入既有 SQL 条件（HTTP 查询串 → DTO 的绑定层由
    // CheckinAdminQueryBindingTest 用 WebDataBinder 锁定，这里锁定 Service/Mapper 侧合同）。
    @SuppressWarnings({"unchecked", "rawtypes"})
    void adminCheckinsExplicitVenueRequiresAccessAndAppliesDomainPredicates() {
        when(lifecycleMapper.selectPage(any(Page.class), any(QueryWrapper.class)))
            .thenAnswer(invocation -> {
                Page<ReservationLifecycle> page = invocation.getArgument(0);
                page.setRecords(List.of());
                page.setTotal(0);
                return page;
            });
        CheckinAdminQuery query = new CheckinAdminQuery();
        query.setVenueId(20L);
        query.setUserId(7L);
        query.setSpaceId(31L);
        query.setReservationId(104L);
        query.setCredentialStatus("ISSUED");
        query.setAttendanceStatus("CANCELED");
        query.setCheckinStatus("CHECKED_IN");
        query.setNoShowStatus("CONFIRMED");

        service.adminCheckins(query);

        verify(venueAccessService).requireVenueManageAccess(20L);
        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(lifecycleMapper).selectPage(any(Page.class), captor.capture());
        QueryWrapper wrapper = captor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("venue_id");
        assertThat(wrapper.getParamNameValuePairs().values())
            .contains(20L, 7L, 31L, 104L, "ISSUED", "CANCELED", "CHECKED_IN", "CONFIRMED");
    }

    @Test
    // SHIGUANG-WAVE-03-REMEDIATION-20260917 S02 / F-14：显式传入未授权 venueId 必须返回 403，
    // 不能退回「静默忽略参数后按可管理场馆兜底」的基线行为；拒绝路径不执行任何查询。
    void adminCheckinsUnauthorizedVenueIsForbiddenWithoutQuery() {
        doThrow(BusinessException.forbidden("无权管理该场馆数据"))
            .when(venueAccessService).requireVenueManageAccess(99L);
        CheckinAdminQuery query = new CheckinAdminQuery();
        query.setVenueId(99L);

        assertThatThrownBy(() -> service.adminCheckins(query))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("无权管理该场馆数据");

        verify(lifecycleMapper, never()).selectPage(any(Page.class), any(QueryWrapper.class));
    }

    @Test
    // SHIGUANG-WAVE-03-REMEDIATION-20260917 S02 / F-14：未传 venueId 时沿用 manageableVenueIds
    // 收敛语义；系统管理员全平台（返回 null 不收敛）与空场馆范围空页均不改变。
    @SuppressWarnings({"unchecked", "rawtypes"})
    void adminCheckinsWithoutVenueConvergesToManageableVenues() {
        when(venueAccessService.manageableVenueIds()).thenReturn(List.of(20L, 21L));
        when(lifecycleMapper.selectPage(any(Page.class), any(QueryWrapper.class)))
            .thenAnswer(invocation -> {
                Page<ReservationLifecycle> page = invocation.getArgument(0);
                page.setRecords(List.of());
                page.setTotal(0);
                return page;
            });
        CheckinAdminQuery query = new CheckinAdminQuery();
        query.setCredentialStatus("ISSUED");

        service.adminCheckins(query);

        verify(venueAccessService, never()).requireVenueManageAccess(any());
        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(lifecycleMapper).selectPage(any(Page.class), captor.capture());
        QueryWrapper wrapper = captor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("venue_id IN");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(20L, 21L, "ISSUED");
    }

    private CheckinVerifyRequest verifyRequest(String credential, String deviceRef) {
        CheckinVerifyRequest request = new CheckinVerifyRequest();
        request.credential = credential;
        request.deviceRef = deviceRef;
        return request;
    }

    private NoShowDecisionRequest noShowRequest(String reasonCode, String reasonText) {
        NoShowDecisionRequest request = new NoShowDecisionRequest();
        request.reasonCode = reasonCode;
        request.reasonText = reasonText;
        return request;
    }

    private ManualAttendanceRequest manualPassRequest() {
        ManualAttendanceRequest request = new ManualAttendanceRequest();
        request.reasonCode = "MANUAL_PASS";
        request.reasonText = "设备异常，人工确认到场";
        request.deviceRef = "front-desk";
        return request;
    }

    private RescheduleRequest rescheduleRequest(LocalDateTime startTime, LocalDateTime endTime) {
        RescheduleRequest request = new RescheduleRequest();
        request.startTime = startTime;
        request.endTime = endTime;
        request.reasonCode = "TIME_CONFLICT";
        request.reasonText = "时间冲突";
        return request;
    }

    private ReservationRescheduleRequest pendingRescheduleRequest() {
        ReservationRescheduleRequest request = new ReservationRescheduleRequest();
        request.id = 301L;
        request.reservationId = 99L;
        request.userId = 7L;
        request.venueId = 20L;
        request.spaceId = 10L;
        request.fromStartTime = LocalDateTime.now().plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        request.fromEndTime = request.fromStartTime.plusHours(2);
        request.toStartTime = request.fromStartTime.plusDays(1);
        request.toEndTime = request.fromEndTime.plusDays(1);
        request.status = "PENDING_REVIEW";
        request.reasonCode = "TIME_CONFLICT";
        request.reasonText = "时间冲突";
        request.deleted = 0;
        return request;
    }

    private ReservationLifecycle lifecycle(Long reservationId, String code, LocalDateTime reservationStart) {
        ReservationLifecycle lifecycle = new ReservationLifecycle();
        lifecycle.id = 501L;
        lifecycle.reservationId = reservationId;
        lifecycle.orderId = 77L;
        lifecycle.userId = 7L;
        lifecycle.venueId = 20L;
        lifecycle.spaceId = 10L;
        lifecycle.checkinCodeHash = hash(code);
        lifecycle.checkinCodeLast4 = code.substring(code.length() - 4);
        lifecycle.credentialStatus = "ISSUED";
        lifecycle.credentialIssuedAt = LocalDateTime.now().minusHours(1);
        lifecycle.credentialExpiresAt = reservationStart.plusHours(2).plusMinutes(30);
        lifecycle.checkinWindowStart = reservationStart.minusMinutes(30);
        lifecycle.checkinWindowEnd = reservationStart.plusHours(2).plusMinutes(30);
        lifecycle.attendanceStatus = "PENDING";
        lifecycle.checkinStatus = "NOT_CHECKED_IN";
        lifecycle.noShowStatus = "NOT_EVALUATED";
        lifecycle.manualOverride = 0;
        lifecycle.lateMinutes = 0;
        lifecycle.deleted = 0;
        return lifecycle;
    }

    private Reservation paidReservation(Long id) {
        return paidReservation(id, LocalDateTime.now().plusHours(1).withNano(0));
    }

    private Reservation paidReservation(Long id, LocalDateTime startTime) {
        Reservation reservation = new Reservation();
        reservation.id = id;
        reservation.userId = 7L;
        reservation.venueId = 20L;
        reservation.spaceId = 10L;
        reservation.startTime = startTime;
        reservation.endTime = startTime.plusHours(2);
        reservation.status = ReservationStatus.PAID.name();
        reservation.deleted = 0;
        return reservation;
    }

    private BizOrder order(Long id, Long reservationId) {
        BizOrder order = new BizOrder();
        order.id = id;
        order.reservationId = reservationId;
        order.userId = 7L;
        order.venueId = 20L;
        return order;
    }

    private SysConfig config(String value) {
        SysConfig config = new SysConfig();
        config.configValue = value;
        return config;
    }

    private String hash(String credential) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(credential.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
