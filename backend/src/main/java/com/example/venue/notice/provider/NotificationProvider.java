package com.example.venue.notice.provider;

public interface NotificationProvider {
    NotificationProviderDescriptor descriptor();

    NotificationDeliveryResult send(NotificationDeliveryCommand command);
}
