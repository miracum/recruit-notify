package org.miracum.recruit.notify;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Configure fhir server items based on fhirUrl given in configuration file. */
@Configuration
public class ConfigFhirServer {

  private final String fhirUrl;

  private FhirContext fhirContext;
  private IParser fhirParser;
  private IGenericClient fhirClient;

  @Autowired
  public ConfigFhirServer(@Value("${fhir.url}") String fhirUrl) {

    this.fhirUrl = fhirUrl;
  }

  @PostConstruct
  private void init() {
    this.fhirContext = FhirContext.forR4();
    this.fhirParser = fhirContext.newJsonParser();
    this.fhirClient = fhirContext.newRestfulGenericClient(fhirUrl);
  }

  public FhirContext getFhirContext() {
    return fhirContext;
  }

  public IParser getFhirParser() {
    return fhirParser;
  }

  @Bean
  public IGenericClient getFhirClient() {
    return fhirClient;
  }
}
