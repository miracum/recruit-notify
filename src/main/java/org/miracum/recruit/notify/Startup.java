package org.miracum.recruit.notify;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.client.exceptions.FhirClientConnectionException;
import org.hl7.fhir.r4.model.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.listener.RetryListenerSupport;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Component
public class Startup {
    private static final Logger log = LoggerFactory.getLogger(Startup.class);
    private final RetryTemplate retryTemplate;
    @Value("${fhir.subscription.criteria}")
    private String criteria;
    @Value("${fhir.url}")
    private String fhirUrl;
    @Value("${webhook.endpoint}")
    private String endpoint;

    @Autowired
    public Startup(RetryTemplate retryTemplate) {
        this.retryTemplate = retryTemplate;
    }

    @PostConstruct
    private void init() {
        log.info("Creating subscription resource with criteria {} @ {}", criteria, fhirUrl);

        retryTemplate.registerListener(new RetryListenerSupport() {
            @Override
            public <T, E extends Throwable> void onError(RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
                log.warn("Trying to connect to FHIR server caused error; attempt {}", context.getRetryCount(), throwable);
            }
        });

        retryTemplate.execute((RetryCallback<MethodOutcome, FhirClientConnectionException>) retryContext -> createSubscription());

        var outcome = createSubscription();

        log.info("Subscription resource '{}' created", outcome.getId());
    }

    private MethodOutcome createSubscription() {
        var ctx = FhirContext.forR4();
        var client = ctx.newRestfulGenericClient(fhirUrl);

        var channel = new Subscription.SubscriptionChannelComponent()
                .setType(Subscription.SubscriptionChannelType.RESTHOOK)
                .setEndpoint(endpoint)
                .setPayload("application/fhir+json");

        var subscription = new Subscription()
                .setCriteria(criteria)
                .setChannel(channel)
                .setReason("Create notifications based on screening list changes.")
                .setStatus(Subscription.SubscriptionStatus.REQUESTED);

        return client.update()
                .resource(subscription)
                .conditional()
                .where(Subscription.CRITERIA.matchesExactly()
                        .value(criteria))
                .execute();
    }
}
