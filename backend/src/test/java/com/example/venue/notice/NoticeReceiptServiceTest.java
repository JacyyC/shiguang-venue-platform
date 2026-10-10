package com.example.venue.notice;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.venue.notice.entity.NoticeAudienceSnapshot;
import com.example.venue.notice.entity.NoticeMessage;
import com.example.venue.notice.entity.NoticeReadReceiptEvent;
import com.example.venue.notice.mapper.NoticeAudienceSnapshotMapper;
import com.example.venue.notice.mapper.NoticeMessageMapper;
import com.example.venue.notice.mapper.NoticeReadReceiptEventMapper;
import com.example.venue.notice.service.NoticeReceiptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.ibatis.annotations.Select;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NoticeReceiptServiceTest {
    @Mock
    private NoticeMessageMapper messageMapper;
    @Mock
    private NoticeAudienceSnapshotMapper audienceMapper;
    @Mock
    private NoticeReadReceiptEventMapper receiptMapper;

    private NoticeReceiptService receiptService;

    @BeforeEach
    void setUp() {
        receiptService = new NoticeReceiptService(messageMapper, audienceMapper, receiptMapper);
    }

    @Test
    void recordReadCreatesReceiptAndUpdatesAudienceOnce() {
        NoticeMessage message = message(91L, 3L, 51L, 61L, 71L);
        NoticeAudienceSnapshot audience = audience(71L, 61L, 51L, 3L);
        when(messageMapper.selectOne(any())).thenReturn(message);
        when(audienceMapper.selectOne(any())).thenReturn(audience);
        when(receiptMapper.findByIdempotencyKey("NOTICE_READ:91:3")).thenReturn(null);

        receiptService.recordRead(91L, 3L, "MESSAGE_CENTER");

        verify(receiptMapper).insert(argThat((NoticeReadReceiptEvent receipt) -> "NOTICE_READ:91:3".equals(receipt.idempotencyKey)
            && Long.valueOf(61L).equals(receipt.batchId)
            && Long.valueOf(71L).equals(receipt.audienceId)
            && Long.valueOf(51L).equals(receipt.noticeId)
            && Long.valueOf(91L).equals(receipt.messageId)
            && Long.valueOf(3L).equals(receipt.userId)
            && "MESSAGE_CENTER".equals(receipt.entry)
            && receipt.readTime != null
            && receipt.receiptNo != null
            && receipt.metadataJson != null
            && receipt.metadataJson.contains("IN_APP_MESSAGE")));
        verify(audienceMapper).updateById(argThat((NoticeAudienceSnapshot updated) -> "READ".equals(updated.readStatus)
            && updated.readTime != null
            && updated.updateTime != null));
    }

    @Test
    void recordReadForBusinessNotificationLeavesNoticeIdNull() {
        NoticeMessage message = message(91L, 3L, 42L, null, null);
        message.type = "RESERVATION";
        when(messageMapper.selectOne(any())).thenReturn(message);
        when(audienceMapper.selectOne(any())).thenReturn(null);
        when(receiptMapper.findByIdempotencyKey("NOTICE_READ:91:3")).thenReturn(null);

        receiptService.recordRead(91L, 3L, "MESSAGE_CENTER");

        verify(receiptMapper).insert(argThat((NoticeReadReceiptEvent receipt) ->
            Long.valueOf(91L).equals(receipt.messageId)
                && receipt.noticeId == null
                && receipt.batchId == null
                && receipt.audienceId == null));
        verify(audienceMapper, never()).updateById(any(NoticeAudienceSnapshot.class));
    }

    @Test
    void recordReadForBroadcastMessageWithoutAudienceFallbacksToMessageBusinessId() {
        NoticeMessage message = message(91L, 3L, 51L, 61L, null);
        when(messageMapper.selectOne(any())).thenReturn(message);
        when(audienceMapper.selectOne(any())).thenReturn(null);
        when(receiptMapper.findByIdempotencyKey("NOTICE_READ:91:3")).thenReturn(null);

        receiptService.recordRead(91L, 3L, "MESSAGE_CENTER");

        verify(receiptMapper).insert(argThat((NoticeReadReceiptEvent receipt) ->
            Long.valueOf(51L).equals(receipt.noticeId)
                && Long.valueOf(61L).equals(receipt.batchId)));
    }

    @Test
    void recordReadIsIdempotent() {
        NoticeMessage message = message(91L, 3L, 99L, null, null);
        NoticeReadReceiptEvent existing = new NoticeReadReceiptEvent();
        existing.id = 1L;
        when(messageMapper.selectOne(any())).thenReturn(message);
        when(receiptMapper.findByIdempotencyKey("NOTICE_READ:91:3")).thenReturn(existing);

        receiptService.recordRead(91L, 3L, "MESSAGE_CENTER");

        verify(receiptMapper, never()).insert(any(NoticeReadReceiptEvent.class));
        verify(audienceMapper, never()).updateById(any(NoticeAudienceSnapshot.class));
    }

    @Test
    void recordReadUsesMessageAudienceIdBeforeWorkerLinksMessage() {
        NoticeMessage message = message(91L, 3L, 51L, 61L, 71L);
        NoticeAudienceSnapshot audience = audience(71L, 61L, 51L, 3L);
        audience.messageId = null;
        when(messageMapper.selectOne(any())).thenReturn(message);
        when(audienceMapper.selectOne(any())).thenReturn(audience);
        when(receiptMapper.findByIdempotencyKey("NOTICE_READ:91:3")).thenReturn(null);

        receiptService.recordRead(91L, 3L, "MESSAGE_CENTER");

        verify(receiptMapper).insert(argThat((NoticeReadReceiptEvent receipt) ->
            Long.valueOf(71L).equals(receipt.audienceId)));
        verify(audienceMapper).updateById(argThat((NoticeAudienceSnapshot updated) ->
            "READ".equals(updated.readStatus) && updated.readTime != null));
    }

    @Test
    void existingReceiptRepairsAudienceReadState() {
        NoticeReadReceiptEvent existing = new NoticeReadReceiptEvent();
        existing.id = 1L;
        existing.audienceId = 71L;
        existing.readTime = LocalDateTime.of(2026, 7, 11, 9, 59);
        NoticeMessage message = message(91L, 3L, 51L, 61L, 71L);
        NoticeAudienceSnapshot audience = audience(71L, 61L, 51L, 3L);
        when(messageMapper.selectOne(any())).thenReturn(message);
        when(receiptMapper.findByIdempotencyKey("NOTICE_READ:91:3")).thenReturn(existing);
        when(audienceMapper.selectOne(any())).thenReturn(audience);

        receiptService.recordRead(91L, 3L, "MESSAGE_CENTER");

        verify(receiptMapper, never()).insert(any(NoticeReadReceiptEvent.class));
        verify(audienceMapper).updateById(argThat((NoticeAudienceSnapshot updated) ->
            "READ".equals(updated.readStatus)
                && existing.readTime.equals(updated.readTime)));
    }

    @Test
    void directDuplicateFirstReadChecksReceiptAfterLockingMessage() {
        NoticeMessage message = message(91L, 3L, 99L, null, null);
        NoticeReadReceiptEvent existing = new NoticeReadReceiptEvent();
        existing.id = 1L;
        existing.messageId = 91L;
        existing.userId = 3L;
        when(messageMapper.selectOne(any())).thenReturn(message);
        when(receiptMapper.findByIdempotencyKey("NOTICE_READ:91:3")).thenReturn(existing);

        receiptService.recordRead(91L, 3L, "MESSAGE_CENTER");

        InOrder order = inOrder(messageMapper, receiptMapper);
        ArgumentCaptor<QueryWrapper<NoticeMessage>> lock = ArgumentCaptor.forClass(QueryWrapper.class);
        order.verify(messageMapper).selectOne(lock.capture());
        order.verify(receiptMapper).findByIdempotencyKey("NOTICE_READ:91:3");
        assertThat(lock.getValue().getSqlSegment().toLowerCase())
            .contains("id", "for update");
        assertThat(lock.getValue().getParamNameValuePairs()).containsValue(91L);
        verify(receiptMapper, never()).insert(any(NoticeReadReceiptEvent.class));
    }

    @Test
    void campaignDuplicateFirstReadRepairsAudienceAfterLockingMessageAndRecheckingReceipt() {
        NoticeMessage message = message(91L, 3L, 51L, 61L, 71L);
        NoticeReadReceiptEvent existing = new NoticeReadReceiptEvent();
        existing.id = 1L;
        existing.audienceId = 71L;
        existing.messageId = 91L;
        existing.userId = 3L;
        existing.readTime = LocalDateTime.of(2026, 7, 11, 9, 59);
        NoticeAudienceSnapshot audience = audience(71L, 61L, 51L, 3L);
        when(messageMapper.selectOne(any())).thenReturn(message);
        when(receiptMapper.findByIdempotencyKey("NOTICE_READ:91:3")).thenReturn(existing);
        when(audienceMapper.selectOne(any())).thenReturn(audience);

        receiptService.recordRead(91L, 3L, "MESSAGE_CENTER");

        InOrder order = inOrder(messageMapper, receiptMapper, audienceMapper);
        order.verify(messageMapper).selectOne(any());
        order.verify(receiptMapper).findByIdempotencyKey("NOTICE_READ:91:3");
        order.verify(audienceMapper).selectOne(any());
        verify(receiptMapper, never()).insert(any(NoticeReadReceiptEvent.class));
        verify(audienceMapper).updateById(argThat((NoticeAudienceSnapshot updated) ->
            "READ".equals(updated.readStatus)));
    }

    @Test
    void receiptIdempotencyRecheckUsesLockingCurrentRead() throws ReflectiveOperationException {
        Method method = NoticeReadReceiptEventMapper.class
            .getDeclaredMethod("findByIdempotencyKey", String.class);
        Select select = method.getAnnotation(Select.class);

        assertThat(select).isNotNull();
        assertThat(String.join("\n", select.value()).toLowerCase())
            .contains("idempotency_key", "for update");
    }

    private NoticeMessage message(Long id, Long userId, Long noticeId, Long batchId, Long audienceId) {
        NoticeMessage message = new NoticeMessage();
        message.id = id;
        message.userId = userId;
        message.businessId = noticeId;
        message.batchId = batchId;
        message.audienceId = audienceId;
        return message;
    }

    private NoticeAudienceSnapshot audience(Long id, Long batchId, Long noticeId, Long userId) {
        NoticeAudienceSnapshot audience = new NoticeAudienceSnapshot();
        audience.id = id;
        audience.batchId = batchId;
        audience.noticeId = noticeId;
        audience.userId = userId;
        audience.readStatus = "UNREAD";
        return audience;
    }
}
