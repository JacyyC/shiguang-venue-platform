package com.example.venue;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@MapperScan({
    "com.example.venue.user.mapper",
    "com.example.venue.system.mapper",
    "com.example.venue.venue.mapper",
    "com.example.venue.resource.mapper",
    "com.example.venue.reservation.mapper",
    "com.example.venue.order.mapper",
    "com.example.venue.business.mapper",
    "com.example.venue.inventory.mapper",
    "com.example.venue.member.mapper",
    "com.example.venue.promotion.mapper",
    "com.example.venue.checkin.mapper",
    "com.example.venue.funnel.mapper",
    "com.example.venue.payment.mapper",
    "com.example.venue.notice.mapper",
    "com.example.venue.statistics.mapper",
    "com.example.venue.siteops.mapper",
    "com.example.venue.feedback.mapper",
    "com.example.venue.provider.mapper",
    "com.example.venue.community.mapper",
    "com.example.venue.community.activity.mapper",
    "com.example.venue.placement.mapper",
    "com.example.venue.advertising.mapper"
})
@SpringBootApplication
public class VenueReservationApplication {
    public static void main(String[] args) {
        SpringApplication.run(VenueReservationApplication.class, args);
    }
}
