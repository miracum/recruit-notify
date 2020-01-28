package org.miracum.recruit.notify;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

@Configuration
public class AppConfig {

    @Value("${retry.backoffPeriod}")
    private int backoffPeriod;
    @Value("${retry.maxAttempts}")
    private int maxAttempts;
    @Value("${fhir.url}")
    private String fhirUrl;

    @Bean
    public RetryTemplate retryTemplate() {
        var retryTemplate = new RetryTemplate();

        var fixedBackOffPolicy = new FixedBackOffPolicy();
        fixedBackOffPolicy.setBackOffPeriod(backoffPeriod);
        retryTemplate.setBackOffPolicy(fixedBackOffPolicy);

        var retryPolicy = new SimpleRetryPolicy();
        retryPolicy.setMaxAttempts(maxAttempts);
        retryTemplate.setRetryPolicy(retryPolicy);

        return retryTemplate;
    }

    @Bean
    public FhirContext fhirContext() {
        return FhirContext.forR4();
    }

    @Bean
    public IGenericClient fhirClient(FhirContext fhirContext) {
        return fhirContext.newRestfulGenericClient(fhirUrl);
    }
}
