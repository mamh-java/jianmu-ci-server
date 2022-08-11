package dev.jianmu.trigger.service;

import dev.jianmu.trigger.aggregate.custom.webhook.CustomWebhookDefinitionVersion;
import dev.jianmu.trigger.aggregate.custom.webhook.CustomWebhookInstance;

import java.util.List;

public interface WebhookOnlyService {
    String getOnly(List<CustomWebhookDefinitionVersion.Event> events, List<CustomWebhookInstance.EventInstance> eventInstances);
}
