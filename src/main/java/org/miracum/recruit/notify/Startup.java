package org.miracum.recruit.notify;

import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.client.api.IGenericClient;
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
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;

@Component
public class Startup {
    private static final Logger log = LoggerFactory.getLogger(Startup.class);
    private final URL webhookEndpoint;
    private final RetryTemplate retryTemplate;
    private final IGenericClient fhirClient;
    @Value("${fhir.subscription.criteria}")
    private String criteria;

    @Autowired
    public Startup(RetryTemplate retryTemplate,
                   IGenericClient fhirClient,
                   @Value("${webhook.endpoint}") URL webhookEndpoint) throws MalformedURLException, URISyntaxException {
        this.retryTemplate = retryTemplate;
        this.fhirClient = fhirClient;

        if (!webhookEndpoint.getPath().endsWith("/on-list-change")) {
            log.warn("Specified Webhook endpoint didn't end with '/on-list-change' in path ({}). Appending it now.",
                    webhookEndpoint);
            var path = webhookEndpoint.getPath();
            path += "/on-list-change";
            this.webhookEndpoint = new URL(webhookEndpoint, path).toURI().normalize().toURL();
        } else {
            this.webhookEndpoint = webhookEndpoint;
        }
    }

    @PostConstruct
    private void init() {
        log.info("Creating subscription resource with criteria '{}' and webhook URL '{}' @ '{}'",
                criteria,
                webhookEndpoint,
                fhirClient.getServerBase());

        retryTemplate.registerListener(new RetryListenerSupport() {
            @Override
            public <T, E extends Throwable> void onError(RetryContext context,
                                                         RetryCallback<T, E> callback,
                                                         Throwable throwable) {
                log.warn("Trying to connect to FHIR server caused '{}'. Attempt {}",
                        throwable.getMessage(),
                        context.getRetryCount());
            }
        });

        var outcome = retryTemplate.execute(retryContext -> createSubscription());
        log.info("Subscription resource '{}' created", outcome.getId());
    }

    private MethodOutcome createSubscription() {
        var channel = new Subscription.SubscriptionChannelComponent()
                .setType(Subscription.SubscriptionChannelType.RESTHOOK)
                .setEndpoint(webhookEndpoint.toString())
                .setPayload("application/fhir+json");

        var subscription = new Subscription()
                .setCriteria(criteria)
                .setChannel(channel)
                .setReason("Create notifications based on screening list changes.")
                .setStatus(Subscription.SubscriptionStatus.REQUESTED);

        return fhirClient.update()
                .resource(subscription)
                .conditional()
                .where(Subscription.CRITERIA.matchesExactly()
                        .value(criteria))
                .execute();
    }
}
