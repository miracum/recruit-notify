package org.miracum.recruit.notify;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import org.hl7.fhir.r4.model.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Component
public class Startup {
    private static final Logger log = LoggerFactory.getLogger(Startup.class);

    @Value("${fhir.subscription.criteria}")
    private String criteria;
    @Value("${fhir.url}")
    private String fhirUrl;
    @Value("${webhook.endpoint}")
    private String endpoint;

    @PostConstruct
    private void init() {
        log.info("Creating subscription resource with criteria {} @ {}", criteria, fhirUrl);

        var ctx = FhirContext.forR4();
        IGenericClient client = ctx.newRestfulGenericClient(fhirUrl);

        var channel = new Subscription.SubscriptionChannelComponent()
                .setType(Subscription.SubscriptionChannelType.RESTHOOK)
                .setEndpoint(endpoint)
                .setPayload("application/fhir+json");

        var subscription = new Subscription()
                .setCriteria(criteria)
                .setChannel(channel)
                .setReason("Create notifications based on screening list changes.")
                .setStatus(Subscription.SubscriptionStatus.REQUESTED);

        var outcome = client.update()
                .resource(subscription)
                .conditional()
                .where(Subscription.CRITERIA.matchesExactly()
                        .value(criteria))
                .execute();

        log.info("Subscription resource created: {}; id: {}", outcome.getCreated(), outcome.getId());
    }
}
