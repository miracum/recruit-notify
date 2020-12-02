package org.miracum.recruit.notify.fhirserver;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Read configured fhir systems from app config. */
@Configuration
@ConfigurationProperties(prefix = "fhir.systems")
@Data
public class FhirSystemsConfig {
  private String screeninglistReference;
  private String studyAcronym;
  private String subscriberSystem;
  private String communication;
}
