package com.example.venue.notice;

import com.example.venue.notice.entity.NoticeMessage;
import com.example.venue.notice.entity.NoticeOutbox;
import com.example.venue.notice.mapper.NoticeMessageMapper;
import com.example.venue.notice.mapper.NoticeOutboxMapper;
import com.example.venue.notice.provider.InAppNotificationProvider;
import com.example.venue.notice.provider.NotificationDeliveryCommand;
import com.example.venue.notice.provider.NotificationDeliveryResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class InAppNotificationProviderTest {
    private NoticeMessageMapper messageMapper;
    private NoticeOutboxMapper outboxMapper;
    private InAppNotificationProvider provider;

    @BeforeEach
    void setUp() {
        messageMapper = mock(NoticeMessageMapper.class);
        outboxMapper = mock(NoticeOutboxMapper.class);
        provider = new InAppNotificationProvider(messageMapper, outboxMapper, new ObjectMapper());
    }

    @Test
    void directMessageIsReusedAndMarkedSent() {
        NoticeMessage message = new NoticeMessage();
        message.id = 41L;
        message.userId = 7L;
        message.deliveryStatus = "PENDING";
        when(outboxMapper.lockForInAppDelivery(91L)).thenReturn(claimedOutbox());
        when(messageMapper.selectById(41L)).thenReturn(message);
        when(messageMapper.updateById(message)).thenReturn(1);

        NotificationDeliveryResult result = provider.send(command(41L, 99L, "RESERVATION"));

        assertThat(result.status()).isEqualTo(NotificationDeliveryResult.ACCEPTED);
        assertThat(result.providerRequestId()).isEqualTo("41");
        assertThat(message.deliveryStatus).isEqualTo("SENT");
        verify(messageMapper).updateById(message);
        verify(messageMapper, never()).insert(any(NoticeMessage.class));
        verify(outboxMapper).lockForInAppDelivery(91L);
    }

    @Test
    void commandWithoutMessageOrStableBusinessKeyFailsPermanently() {
        NotificationDeliveryResult result = provider.send(command(null, null, null));

        assertThat(result.status()).isEqualTo(NotificationDeliveryResult.FAILED_PERMANENT);
        assertThat(result.failureCode()).isEqualTo("NOTICE_OUTBOX_PAYLOAD_INVALID");
        verifyNoInteractions(messageMapper, outboxMapper);
    }

    @Test
    void repeatedCampaignOutboxReusesMessageCreatedUnderOutboxLock() {
        NoticeOutbox claimed = claimedOutbox();
        when(outboxMapper.lockForInAppDelivery(91L)).thenReturn(claimed);
        when(messageMapper.selectOne(any())).thenReturn(null).thenAnswer(invocation -> createdCampaignMessage());
        when(messageMapper.insert(any(NoticeMessage.class))).thenAnswer(invocation -> {
            NoticeMessage message = invocation.getArgument(0);
            message.id = 55L;
            return 1;
        });
        NotificationDeliveryCommand command = new NotificationDeliveryCommand(
            91L, "NOTICE:31:7:IN_APP_MESSAGE", provider.descriptor(), 7L, null, 71L,
            "NOTICE", "公告标题", "公告正文",
            "{\"batchId\":31,\"audienceId\":51,\"noticeId\":71}");

        NotificationDeliveryResult first = provider.send(command);
        NotificationDeliveryResult second = provider.send(command);

        assertThat(first.status()).isEqualTo(NotificationDeliveryResult.ACCEPTED);
        assertThat(second.status()).isEqualTo(NotificationDeliveryResult.ACCEPTED);
        verify(messageMapper, times(1)).insert(any(NoticeMessage.class));
        verify(outboxMapper, times(2)).lockForInAppDelivery(91L);
    }

    private NotificationDeliveryCommand command(Long messageId, Long businessId, String businessType) {
        return new NotificationDeliveryCommand(
            91L, "NOTICE_MESSAGE:41", provider.descriptor(), 7L, messageId, businessId,
            businessType, "预约已提交", "请等待", "{}");
    }

    private NoticeOutbox claimedOutbox() {
        NoticeOutbox outbox = new NoticeOutbox();
        outbox.id = 91L;
        outbox.status = "SENDING";
        outbox.attemptCount = 1;
        return outbox;
    }

    private NoticeMessage createdCampaignMessage() {
        NoticeMessage message = new NoticeMessage();
        message.id = 55L;
        message.userId = 7L;
        message.businessId = 71L;
        message.type = "NOTICE";
        message.deliveryStatus = "SENT";
        return message;
    }
}
