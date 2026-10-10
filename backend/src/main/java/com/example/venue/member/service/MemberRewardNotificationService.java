package com.example.venue.member.service;

import com.example.venue.notice.service.NoticeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
public class MemberRewardNotificationService {
    private final NoticeService noticeService;

    public MemberRewardNotificationService(NoticeService noticeService) {
        this.noticeService = noticeService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSucceeded(MemberRewardSucceededEvent event) {
        try {
            noticeService.createMessage(event.userId(), "积分兑换成功",
                "您已成功兑换「" + event.itemName() + "」，优惠券已发放至账户。",
                "MEMBER_REWARD", event.redemptionId());
        } catch (RuntimeException ignored) {
            // Notification is secondary and must not change the committed redemption result.
        }
    }
}
