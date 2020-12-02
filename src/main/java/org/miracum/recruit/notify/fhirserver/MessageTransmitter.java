package org.miracum.recruit.notify.fhirserver;

import java.util.List;
import java.util.UUID;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CommunicationRequest;
import org.hl7.fhir.r4.model.CommunicationRequest.CommunicationRequestPayloadComponent;
import org.hl7.fhir.r4.model.StringType;
import org.miracum.recruit.notify.ConfigFhirServer;
import org.miracum.recruit.notify.logging.LogMethodCalls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;

/** Create list of messages in FHIR server to store them temporary. */
@Service
public class MessageTransmitter {

  private static final Logger LOG = LoggerFactory.getLogger(MessageTransmitter.class);
  private final RetryTemplate retryTemplate;

  final ConfigFhirServer configFhirServer;
  final FhirCommunicationConfig fhirCommunicationConfig;

  /** Prepare config items used to send communciation requests to target fhir server. */
  @Autowired
  public MessageTransmitter(
      ConfigFhirServer configFhirServer,
      FhirCommunicationConfig fhirCommunicationConfig,
      RetryTemplate retryTemplate) {
    this.configFhirServer = configFhirServer;
    this.fhirCommunicationConfig = fhirCommunicationConfig;
    this.retryTemplate = retryTemplate;
  }

  /** Save message list to target fhir server. */
  @LogMethodCalls
  public Bundle transmit(List<CommunicationRequest> messages) {
    LOG.info("transmit message list to fhir server.");

    Bundle result = null;
    try {
      Bundle bundle = new Bundle();
      bundle.setType(Bundle.BundleType.TRANSACTION);

      for (CommunicationRequest message : messages) {

        String topic = message.getReasonCodeFirstRep().getText();
        LOG.debug("message topic: {}", topic);

        CommunicationRequestPayloadComponent payload = createPayloadInfoByConfiguredValue();
        message.addPayload(payload);

        UUID messageUuid = UUID.randomUUID();

        bundle
            .addEntry()
            .setResource(message)
            .setFullUrl("urn:uuid:" + messageUuid)
            .getRequest()
            .setUrl("CommunicationRequest")
            .setMethod(Bundle.HTTPVerb.POST);
      }

      LOG.debug(
          configFhirServer
              .getFhirContext()
              .newJsonParser()
              .setPrettyPrint(true)
              .encodeResourceToString(bundle));

      if (bundle.hasEntry()) {
        result =
            retryTemplate.execute(
                retryContext ->
                    configFhirServer.getFhirClient().transaction().withBundle(bundle).execute());
      }

    } catch (Exception exc) {
      LOG.error("Failed to create CommunicationRequests", exc);
    }

    return result;
  }

  private CommunicationRequestPayloadComponent createPayloadInfoByConfiguredValue() {
    StringType text = new StringType();
    text.setValue(fhirCommunicationConfig.getPayloadText());
    CommunicationRequestPayloadComponent payload = new CommunicationRequestPayloadComponent();
    payload.setContent(text);
    return payload;
  }
}
