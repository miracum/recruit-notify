package org.miracum.recruit.notify.fhirserver;

import java.util.List;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Practitioner;
import org.miracum.recruit.notify.ConfigFhirServer;
import org.miracum.recruit.notify.logging.LogMethodCalls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Create list of practitioners in FHIR server if they don't exist yet. */
@Service
public class PractitionerTransmitterDefault {

  private static final Logger LOG = LoggerFactory.getLogger(PractitionerTransmitterDefault.class);

  final ConfigFhirServer configFhirServer;

  final FhirSystemsConfig fhirSystemConfig;

  /** Init instance with config fhir server and fhir system config. */
  @Autowired
  public PractitionerTransmitterDefault(
      ConfigFhirServer configFhirServer, FhirSystemsConfig fhirSystemConfig) {

    this.configFhirServer = configFhirServer;
    this.fhirSystemConfig = fhirSystemConfig;
  }

  /** Save practitioner list to target fhir server. */
  @LogMethodCalls
  public Bundle transmit(List<Practitioner> practitioners) {
    LOG.info("transmit practitioner list to fhir server.");

    Bundle bundle = new Bundle();
    bundle.setType(Bundle.BundleType.TRANSACTION);

    for (Practitioner practitioner : practitioners) {
      List<Identifier> identifierList = practitioner.getIdentifier();

      var identifierItem =
          identifierList.stream()
              .filter(rule -> rule.getSystem().equals(fhirSystemConfig.getSubscriberSystem()))
              .findFirst();

      if (identifierItem.isPresent()) {
        var identifier = identifierItem.get();

        bundle
            .addEntry()
            .setFullUrl(practitioner.getIdElement().getValue())
            .setResource(practitioner)
            .getRequest()
            .setUrl("Practitioner")
            .setIfNoneExist(
                String.format("identifier=%s|%s", identifier.getSystem(), identifier.getValue()))
            .setMethod(Bundle.HTTPVerb.POST);
      }
    }

    return configFhirServer.getFhirClient().transaction().withBundle(bundle).execute();
  }
}
