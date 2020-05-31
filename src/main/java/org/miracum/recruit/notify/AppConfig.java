package org.miracum.recruit.notify;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.exceptions.FhirClientConnectionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.spring5.SpringTemplateEngine;

import java.util.HashMap;

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

        var retryableExceptions = new HashMap<Class<? extends Throwable>, Boolean>();
        retryableExceptions.put(HttpClientErrorException.class, false);
        retryableExceptions.put(HttpServerErrorException.class, true);
        retryableExceptions.put(FhirClientConnectionException.class, true);

        retryTemplate.setRetryPolicy(new SimpleRetryPolicy(maxAttempts, retryableExceptions));

        return retryTemplate;
    }

    @Bean
    public IParser fhirParser(FhirContext ctx) {
        return ctx.newJsonParser();
    }

    @Bean
    public FhirContext fhirContext() {
        return FhirContext.forR4();
    }

    @Bean
    public IGenericClient fhirClient(FhirContext fhirContext) {
        return fhirContext.newRestfulGenericClient(fhirUrl);
    }

    @Bean
    public TemplateEngine emailTemplateEngine() {
        return new SpringTemplateEngine();
    }
}
