package org.miracum.recruit.notify;

import static net.logstash.logback.argument.StructuredArguments.kv;

import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.util.BundleUtil;
import java.util.ArrayList;
import java.util.List;
import org.hl7.fhir.instance.model.api.IAnyResource;
import org.hl7.fhir.instance.model.api.IBaseBundle;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CommunicationRequest;
import org.hl7.fhir.r4.model.CommunicationRequest.CommunicationRequestStatus;
import org.hl7.fhir.r4.model.ListResource;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.ResearchStudy;
import org.hl7.fhir.r4.model.ResearchSubject;
import org.miracum.recruit.notify.fhirserver.FhirSystemsConfig;
import org.miracum.recruit.notify.practitioner.PractitionerUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Provide search results from target fhir server. */
@Service
public class FhirServerProvider {
  private static final Logger LOG = LoggerFactory.getLogger(FhirServerProvider.class);

  private final IGenericClient fhirClient;
  private final FhirSystemsConfig fhirSystemsConfig;

  /** Constructor for Fhir Server providing search results. */
  @Autowired
  public FhirServerProvider(IGenericClient fhirClient, FhirSystemsConfig fhirSystemsConfig) {
    this.fhirClient = fhirClient;
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

    return fhirClient
        .read()
        .resource(ListResource.class)
        .withIdAndVersion(currentList.getIdElement().getIdPart(), Integer.toString(lastVersionId))
        .execute();
  }

  /** Query all research subjects from list. */
  public List<ResearchSubject> getResearchSubjectsFromList(ListResource list) {
    var listBundle =
        fhirClient
            .search()
            .forResource(ListResource.class)
            .where(IAnyResource.RES_ID.exactly().identifier(list.getId()))
            .include(IBaseResource.INCLUDE_ALL)
            .returnBundle(Bundle.class)
            .execute();

    var researchSubjectList =
        new ArrayList<>(
            BundleUtil.toListOfResourcesOfType(
                fhirClient.getFhirContext(), listBundle, ResearchSubject.class));

    // Load the subsequent pages
    while (listBundle.getLink(IBaseBundle.LINK_NEXT) != null) {
      listBundle = fhirClient.loadPage().next(listBundle).execute();
      researchSubjectList.addAll(
          BundleUtil.toListOfResourcesOfType(
              fhirClient.getFhirContext(), listBundle, ResearchSubject.class));
    }

    return researchSubjectList;
  }

  /** Query research study resource from target fhir server by given id. */
  public ResearchStudy getResearchStudyFromId(String id) {
    return fhirClient.read().resource(ResearchStudy.class).withId(id).execute();
  }

  /**
   * Query list of practitioners from target fhir server bi list of unique identifiers in given fhir
   * system.
   */
  public List<Practitioner> getPractitionersByEmail(List<String> subscribers) {
    var practitionerObjects = new ArrayList<Practitioner>();

    for (var subscriberName : subscribers) {
      LOG.debug("processing {}", kv("subscriberName", subscriberName));

      var listBundlePractitioners =
          fhirClient
              .search()
              .forResource(Practitioner.class)
              .where(Practitioner.EMAIL.exactly().code(subscriberName))
              .returnBundle(Bundle.class)
              .execute();

      var practitionerList =
          BundleUtil.toListOfResourcesOfType(
              fhirClient.getFhirContext(), listBundlePractitioners, Practitioner.class);

      if (!practitionerList.isEmpty()) {
        practitionerObjects.add(practitionerList.get(0));
      } else {
        LOG.warn("Failed to retrieve Practitioner resource with {}", kv("email", subscriberName));
      }
    }

    return practitionerObjects;
  }

  /**
   * Query active CommunicationRequests from FHIR server for given list of subscriber's email
   * addresses
   */
  public List<CommunicationRequest> getOpenMessagesForSubscribers(List<String> subscribers) {
    LOG.info("retrieving open messages for {}", kv("numSubscribers", subscribers.size()));

    List<CommunicationRequest> messages = new ArrayList<>();

    // TODO: refactor to use getCommunicationRequestsByStatus (with optional include)
    var activeCommunicationRequests =
        fhirClient
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

    var communicationList =
        BundleUtil.toListOfResourcesOfType(
            fhirClient.getFhirContext(), activeCommunicationRequests, CommunicationRequest.class);

    if (communicationList.isEmpty()) {
      LOG.info("no active CommunicationRequest resources found");
      return List.of();
    }

    for (var subscriber : subscribers) {
      for (var message : communicationList) {
        var recipientList = message.getRecipient();
        for (var reference : recipientList) {
          if (reference.getResource().fhirType().equals("Practitioner")) {
            var practitioner = (Practitioner) reference.getResource();

            LOG.debug(
                "checking if {} matches {} {}",
                kv("subscriber", subscriber),
                kv("communicationRequestReason", message.getReasonCodeFirstRep().getText()),
                kv("practitionerEmail", practitioner.getTelecomFirstRep().getValue()));

            if (PractitionerUtils.hasEmail(practitioner, subscriber)) {
              LOG.debug(
                  "add {} to list for receiver {}",
                  kv("message", message.getId()),
                  kv("subscriber", subscriber));
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
    LOG.info("retrieving CommunicationRequest with {} from server", kv("status", status));

    var listBundleCommunications =
        fhirClient
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
            fhirClient.getFhirContext(), listBundleCommunications, CommunicationRequest.class);

    if (!communicationList.isEmpty()) {
      LOG.debug(
          "number of requests with {}  {}", kv("status", status), kv("count", communicationList.size()));
    }

    return communicationList;
  }

  /** Query top 100 communication resources with state entered-in-error. */
  public List<CommunicationRequest> getErrorMessages() {
    return getCommunicationRequestsByStatus(CommunicationRequestStatus.ONHOLD);
  }

  /**
   * Query communication resources from target fhir server and with given fhir system that are in
   * state active to be delivered.
   */
  public List<CommunicationRequest> getPreparedMessages() {
    return getCommunicationRequestsByStatus(CommunicationRequestStatus.ACTIVE);
  }

  public Bundle executeTransaction(Bundle transaction) {
    return fhirClient.transaction().withBundle(transaction).execute();
  }
}
