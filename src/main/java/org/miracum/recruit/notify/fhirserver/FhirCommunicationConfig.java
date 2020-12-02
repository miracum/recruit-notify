package org.miracum.recruit.notify.fhirserver;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Read config for fhir communication resource. */
@Configuration
@ConfigurationProperties(prefix = "fhir.communication")
@Data
public class FhirCommunicationConfig {
  private String payloadText;
}
