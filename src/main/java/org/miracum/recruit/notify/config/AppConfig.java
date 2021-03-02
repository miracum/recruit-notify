package org.miracum.recruit.notify.config;

import ca.uhn.fhir.rest.client.exceptions.FhirClientConnectionException;
import java.util.HashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

/** Configure backoffPeriod and maxAttempts for application. */
@Configuration
public class AppConfig {
  private final long backoffPeriod;
  private final int maxAttempts;

  @Autowired
  public AppConfig(
      @Value("${notify.retry.backoffPeriodMs}") long backoffPeriodMs,
      @Value("${notify.retry.maxAttempts}") int maxAttempts) {
    this.backoffPeriod = backoffPeriodMs;
    this.maxAttempts = maxAttempts;
  }

  /** RetryTemplate. */
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
}
