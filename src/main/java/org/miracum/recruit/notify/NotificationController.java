package org.miracum.recruit.notify;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.hl7.fhir.r4.model.ListResource;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ResearchSubject;
import org.hl7.fhir.r4.model.ResearchSubject.ResearchSubjectStatus;
import org.miracum.recruit.notify.config.FhirConfig;
import org.miracum.recruit.notify.logging.LogMethodCalls;
import org.miracum.recruit.notify.message.create.MessageCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.listener.RetryListenerSupport;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Define endpoint for receiving PUT request from fhir server sending subscriptions for recruiting
 * list.
 */
@RestController
public class NotificationController {
  private static final Logger LOG = LoggerFactory.getLogger(NotificationController.class);

  private final RetryTemplate retryTemplate;
  private final String screeningListReferenceSystem;

  final MessageCreator messageCreator;

  final FhirServerProvider fhirServer;

  final FhirConfig fhirConfig;

  @Value("${fhir.systems.studyacronym}")
  private String studyAcronymSystem;

  /**
   * Prepare config items and email utils for receiving and handle subscription events from target
   * fhir server.
   */
  @Autowired
  public NotificationController(
      RetryTemplate retryTemplate,
      @Value("${fhir.systems.screeninglistreference}") String screeningListReferenceSystem,
      FhirConfig fhirConfig,
      FhirServerProvider fhirServer,
      MessageCreator messageCreator) {
    this.retryTemplate = retryTemplate;
    this.screeningListReferenceSystem = screeningListReferenceSystem;
    this.fhirConfig = fhirConfig;
    this.fhirServer = fhirServer;
    this.messageCreator = messageCreator;
  }

  /**
   * Expose endpoint that will be assigned to subscription and accepts application/fhir+json
   * content.
   */
  @PutMapping(value = "/on-list-change/List/{id}", consumes = "application/fhir+json")
  public void onListChange(
      @PathVariable(value = "id") String resourceId, @RequestBody String body) {
    LOG.info("onListChange invoked for list with id {}", resourceId);

    if (body == null) {
      LOG.error("request body is null");
      return;
    }

    var list = fhirConfig.getFhirParser().parseResource(ListResource.class, body);

    if (!list.hasEntry()) {
      LOG.warn("Received empty screening list {}, aborting.", list.getId());
      return;
    }

    retryTemplate.registerListener(
        new RetryListenerSupport() {
          @Override
          public <T, E extends Throwable> void onError(
              RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
            LOG.warn("handleSubscription failed. {} attempt.", context.getRetryCount());
          }
        });

    retryTemplate.execute(retryContext -> handleSubscription(list));
  }

  private Void handleSubscription(ListResource list) {
    var studyReferenceExtension = list.getExtensionByUrl(screeningListReferenceSystem);

    if (studyReferenceExtension == null) {
      LOG.warn(
          "studyReferenceExtension not set for {}. Impossible to determine receiver, aborting.",
          list.getId());
      return null;
    }

    if (!hasPatientListChanged(list)) {
      LOG.info("list {} hasn't changed since last time", list.getId());
      return null;
    }

    var studyReference = (Reference) studyReferenceExtension.getValue();

    var researchSubjectList = fhirServer.getResearchSubjectsFromList(list);
    if (!hasPatientListCandidate(researchSubjectList)) {
      LOG.info("list {} doesn't have any candidates", list.getId());
      return null;
    }

    final var acronym = retrieveStudyAcronym(studyReference);

    if (acronym.equals("")) {
      return null;
    }

    String listId = list.getIdElement().getIdPart();
    LOG.info("list id to handle: {}", listId);
    messageCreator.temporaryStoreMessagesInFhir(acronym, listId);

    return null;
  }

  @LogMethodCalls
  private String retrieveStudyAcronym(Reference studyReference) {
    var studyAcronym = "";

    if (studyReference.hasDisplay()) {
      studyAcronym = studyReference.getDisplay();
    } else {
      var study =
          fhirServer.getResearchStudyFromId(studyReference.getReferenceElement().getIdPart());

      if (study.hasExtension(studyAcronymSystem)) {
        var studyAcronymExtension = study.getExtensionByUrl(studyAcronymSystem);
        studyAcronym = studyAcronymExtension.getValue().toString();
        LOG.debug(
            "Using acronym '{}' from extension as study identifier for {}.",
            studyAcronym,
            studyReference.getReference());
      } else {
        LOG.warn("Study acronym not set for study {}.", studyReference.getReference());
        if (study.hasTitle()) {
          studyAcronym = study.getTitle();
          LOG.debug(
              "Using title '{}' as study identifier for {}.",
              studyAcronym,
              studyReference.getReference());
        } else {
          LOG.error(
              "No identifier available for study {}. Aborting.", studyReference.getReference());
          return "";
        }
      }
    }
    return studyAcronym;
  }

  private boolean hasPatientListCandidate(List<ResearchSubject> researchSubjects) {
    return researchSubjects.stream()
        .anyMatch(subject -> subject.getStatus() == ResearchSubjectStatus.CANDIDATE);
  }

  private boolean hasPatientListChanged(ListResource newScreenList) {
    var lastScreenList = fhirServer.getPreviousScreeningListFromServer(newScreenList);
    if (lastScreenList == null) {
      return true;
    }

    var newResearchSubjectIDs = getResearchSubjectIds(newScreenList.getEntry());
    var lastResearchSubjectIDs = getResearchSubjectIds(lastScreenList.getEntry());
    return !newResearchSubjectIDs.equals(lastResearchSubjectIDs);
  }

  private Set<String> getResearchSubjectIds(List<ListResource.ListEntryComponent> entry) {
    return entry.stream()
        .map(item -> item.getItem().getReferenceElement().getIdPart())
        .collect(Collectors.toSet());
  }
}
