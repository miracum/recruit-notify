package org.miracum.recruit.notify;

import ca.uhn.fhir.util.BundleUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.hl7.fhir.instance.model.api.IAnyResource;
import org.hl7.fhir.instance.model.api.IBaseBundle;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CommunicationRequest;
import org.hl7.fhir.r4.model.CommunicationRequest.CommunicationRequestStatus;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.ListResource;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ResearchStudy;
import org.hl7.fhir.r4.model.ResearchSubject;
import org.miracum.recruit.notify.fhirserver.FhirSystemsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Provide search results from target fhir server. */
@Service
public class FhirServerProvider {
  private static final Logger LOG = LoggerFactory.getLogger(FhirServerProvider.class);

  private final ConfigFhirServer configFhirServer;
  private final FhirSystemsConfig fhirSystemsConfig;

  /** Constructor for Fhir Server providing search results. */
  @Autowired
  public FhirServerProvider(
      ConfigFhirServer configFhirServer, FhirSystemsConfig fhirSystemsConfig) {
    this.configFhirServer = configFhirServer;
    this.fhirSystemsConfig = fhirSystemsConfig;
  }

  /** If previous screening list is available it will be checked if list changed. */
  public ListResource getPreviousScreeningListFromServer(ListResource currentList) {
    var versionId = currentList.getMeta().getVersionId();

    if (versionId == null) {
      LOG.warn("list {} version id is null", currentList.getId());
      return null;
    }

    int lastVersionId = Integer.parseInt(versionId) - 1;
    if (lastVersionId <= 0) {
      return null;
    }

    return configFhirServer
        .getFhirClient()
        .read()
        .resource(ListResource.class)
        .withIdAndVersion(currentList.getIdElement().getIdPart(), Integer.toString(lastVersionId))
        .execute();
  }

  /** Query all research subjects from list. */
  public List<ResearchSubject> getResearchSubjectsFromList(ListResource list) {
    var listBundle =
        configFhirServer
            .getFhirClient()
            .search()
            .forResource(ListResource.class)
            .where(IAnyResource.RES_ID.exactly().identifier(list.getId()))
            .include(IBaseResource.INCLUDE_ALL)
            .returnBundle(Bundle.class)
            .execute();

    var researchSubjectList =
        new ArrayList<>(
            BundleUtil.toListOfResourcesOfType(
                configFhirServer.getFhirClient().getFhirContext(),
                listBundle,
                ResearchSubject.class));

    // Load the subsequent pages
    while (listBundle.getLink(IBaseBundle.LINK_NEXT) != null) {
      listBundle = configFhirServer.getFhirClient().loadPage().next(listBundle).execute();
      researchSubjectList.addAll(
          BundleUtil.toListOfResourcesOfType(
              configFhirServer.getFhirClient().getFhirContext(),
              listBundle,
              ResearchSubject.class));
    }

    return researchSubjectList;
  }

  /** Query research study resource from target fhir server by given id. */
  public ResearchStudy getResearchStudyFromId(String id) {
    return configFhirServer
        .getFhirClient()
        .read()
        .resource(ResearchStudy.class)
        .withId(id)
        .execute();
  }

  /**
   * Query list of practitioners from target fhir server bi list of unique identifiers in given fhir
   * system.
   */
  public List<Practitioner> getSubscriberObjectsFromFhir(List<String> subscribers) {
    LOG.info("retrieve {} subscriber objects from fhir server", subscribers.size());

    List<Practitioner> practitionerObjects = new ArrayList<>();

    for (String subscriberName : subscribers) {
      LOG.debug("subscriber name: {}", subscriberName);

      Bundle listBundlePractitioners =
          configFhirServer
              .getFhirClient()
              .search()
              .forResource(Practitioner.class)
              .where(
                  Practitioner.IDENTIFIER
                      .exactly()
                      .systemAndValues(fhirSystemsConfig.getSubscriberSystem(), subscriberName))
              .returnBundle(Bundle.class)
              .execute();

      List<Practitioner> practitionerList =
          BundleUtil.toListOfResourcesOfType(
              configFhirServer.getFhirClient().getFhirContext(),
              listBundlePractitioners,
              Practitioner.class);

      if (!practitionerList.isEmpty()) {

        practitionerObjects.add(practitionerList.get(0));

        LOG.debug("practitioner: {}", practitionerList.get(0).getIdentifierFirstRep().getValue());
      }
    }

    return practitionerObjects;
  }

  /**
   * Query active communication resource items from target fhir server for given list of values
   * combined with given fhir system.
   */
  public List<CommunicationRequest> getOpenMessagesForSubscribersFromFhir(
      List<String> subscribers) {
    LOG.info("retrieve open messages for {} subscriber names", subscribers.size());

    List<CommunicationRequest> messages = new ArrayList<>();

    // TODO: refactor to use getCommunicationRequestsByStatus (with optional include)
    Bundle listBundleCommunications =
        configFhirServer
            .getFhirClient()
            .search()
            .forResource(CommunicationRequest.class)
            .where(
                CommunicationRequest.STATUS
                    .exactly()
                    .code(CommunicationRequestStatus.ACTIVE.toCode()))
            .and(
                CommunicationRequest.IDENTIFIER.hasSystemWithAnyCode(
                    fhirSystemsConfig.getCommunication()))
            .include(CommunicationRequest.INCLUDE_RECIPIENT.asNonRecursive())
            .returnBundle(Bundle.class)
            .execute();

    List<CommunicationRequest> communicationList =
        BundleUtil.toListOfResourcesOfType(
            configFhirServer.getFhirClient().getFhirContext(),
            listBundleCommunications,
            CommunicationRequest.class);

    if (communicationList.isEmpty()) {
      LOG.warn("No active CommunicationRequest resources found");
      return List.of();
    }

    for (String subscriber : subscribers) {
      for (CommunicationRequest message : communicationList) {

        List<Reference> recipientList = message.getRecipient();

        for (Reference reference : recipientList) {
          if (reference.getResource().fhirType().equals("Practitioner")) {
            Practitioner practitioner = (Practitioner) reference.getResource();

            LOG.debug(
                "Checking if subscriber={} matches communicationRequestReason={} receiver={}",
                subscriber,
                message.getReasonCodeFirstRep().getText(),
                practitioner.getTelecomFirstRep().getValue());

            Optional<Identifier> identifier =
                practitioner.getIdentifier().stream()
                    .filter(
                        rule ->
                            rule.getSystem().equals(fhirSystemsConfig.getSubscriberSystem())
                                && rule.getValue().equals(subscriber))
                    .findFirst();

            if (identifier.isPresent()) {
              LOG.debug("add message to list for receiver {}", subscriber);
              messages.add(message);
            }
          }
        }
      }
    }

    return messages;
  }

  public List<CommunicationRequest> getCommunicationRequestsByStatus(
      CommunicationRequestStatus status) {
    LOG.info("retrieve communication requests with status={} from server", status);

    var listBundleCommunications =
        configFhirServer
            .getFhirClient()
            .search()
            .forResource(CommunicationRequest.class)
            .where(CommunicationRequest.STATUS.exactly().code(status.toCode()))
            .and(
                CommunicationRequest.IDENTIFIER.hasSystemWithAnyCode(
                    fhirSystemsConfig.getCommunication()))
            .count(100)
            .returnBundle(Bundle.class)
            .execute();

    var communicationList =
        BundleUtil.toListOfResourcesOfType(
            configFhirServer.getFhirClient().getFhirContext(),
            listBundleCommunications,
            CommunicationRequest.class);

    if (!communicationList.isEmpty()) {
      LOG.warn("requests wit status={} : {}", status, communicationList.size());
    }

    return communicationList;
  }

  /** Query top 100 communication resources with state entered-in-error. */
  public List<CommunicationRequest> getErrorMessages() {
    return getCommunicationRequestsByStatus(CommunicationRequestStatus.ENTEREDINERROR);
  }

  /**
   * Query communication resources from target fhir server and with given fhir system that are in
   * state active to be delivered.
   */
  public List<CommunicationRequest> getPreparedMessages() {
    return getCommunicationRequestsByStatus(CommunicationRequestStatus.ACTIVE);
  }
}
