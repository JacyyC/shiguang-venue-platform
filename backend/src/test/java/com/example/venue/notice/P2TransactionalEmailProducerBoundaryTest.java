package com.example.venue.notice;

import com.example.venue.notice.service.NoticeService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

class P2TransactionalEmailProducerBoundaryTest {

    @Test
    void noRecipientEvidenceJoinsTheCallingBusinessTransaction() throws NoSuchMethodException {
        Transactional transactional = NoticeService.class.getMethod(
            "recordNoEligibleVenueAdminExclusion",
            String.class, String.class, String.class, Long.class
        ).getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRED);
    }
}
