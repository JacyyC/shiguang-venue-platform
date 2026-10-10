package com.example.venue.notice.provider;

import com.example.venue.provider.dto.ProviderRegistryRegistration;
import com.example.venue.provider.service.ProviderObservabilityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class SmtpSandboxProviderRegistrySync implements ApplicationRunner {
    private final ProviderObservabilityService providerObservabilityService;
    private final SmtpSandboxNotificationProvider smtpProvider;
    private final ObjectMapper objectMapper;

    public SmtpSandboxProviderRegistrySync(
        ProviderObservabilityService providerObservabilityService,
        SmtpSandboxNotificationProvider smtpProvider,
        ObjectMapper objectMapper
    ) {
        this.providerObservabilityService = providerObservabilityService;
        this.smtpProvider = smtpProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        NotificationProviderDescriptor descriptor = smtpProvider.descriptor();
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("sandbox", true);
        metadata.put("acceptanceOnly", true);
        metadata.put("internetDelivery", false);
        providerObservabilityService.upsertRegistry(new ProviderRegistryRegistration(
            descriptor.providerKey(),
            descriptor.providerDomain(),
            descriptor.providerEnv(),
            descriptor.providerName(),
            descriptor.displayName(),
            descriptor.configVersion(),
            descriptor.enabled(),
            metadata.toString()));
    }
}
