package org.miracum.recruit.notify;

import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.rest.client.exceptions.FhirClientConnectionException;
import org.hl7.fhir.r4.model.ListResource;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ResearchSubject;
import org.hl7.fhir.r4.model.ResearchSubject.ResearchSubjectStatus;
import org.miracum.recruit.notify.config.MailNotificationRule;
import org.miracum.recruit.notify.config.NotificationConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.listener.RetryListenerSupport;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toList;

@RestController
public class NotificationController {
    private static final Logger log = LoggerFactory.getLogger(NotificationController.class);

    private final NotificationConfiguration config;
    private final JavaMailSender javaMailSender;
    private final RetryTemplate retryTemplate;
    private final String screeningListReferenceSystem;
    private final String messageBodyScreeningListLinkTemplate;
    private final IParser fhirParser;
    private FhirServerProvider fhirServer;

    @Value("${fhir.systems.studyacronym}")
    private String studyAcronymSystem;

    @Autowired
    public NotificationController(NotificationConfiguration config,
                                  JavaMailSender javaMailSender,
                                  RetryTemplate retryTemplate,
                                  IParser fhirParser,
                                  @Value("${fhir.systems.screeninglistreference}") String screeningListReferenceSystem,
                                  @Value("${notifications.messageBodyScreeningListLinkTemplate}") String msg,
                                  FhirServerProvider fhirServer) {
        this.config = config;
        this.javaMailSender = javaMailSender;
        this.retryTemplate = retryTemplate;
        this.screeningListReferenceSystem = screeningListReferenceSystem;
        this.messageBodyScreeningListLinkTemplate = msg;
        this.fhirParser = fhirParser;
        this.fhirServer = fhirServer;
    }

    @PutMapping(value = "/on-list-change/List/{id}", consumes = "application/fhir+json")
    public void onListChange(@PathVariable(value = "id") String resourceId, @RequestBody String body) {
        if (body == null) {
            log.error("requestBody is null");
            return;
        }

        var list = fhirParser.parseResource(ListResource.class, body);

        log.info("onListChange called for list with id {}", resourceId);

        retryTemplate.registerListener(new RetryListenerSupport() {
            @Override
            public <T, E extends Throwable> void onError(RetryContext context, RetryCallback<T, E> callback,
                                                         Throwable throwable) {
                log.warn("Trying to connect to FHIR server failed. {} attempt.", context.getRetryCount());
            }
        });

        retryTemplate.execute(
                (RetryCallback<Void, FhirClientConnectionException>) retryContext -> handleSubscriptionCallback(list));
    }

    private Void handleSubscriptionCallback(ListResource list) {
        if (!list.hasEntry()) {
            log.warn("Received empty screening list {}, aborting.", list.getId());
            return null;
        }

        // get the ResearchStudy referenced by this changed screening list
        var studyReferenceExtension = list.getExtensionByUrl(screeningListReferenceSystem);

        if (studyReferenceExtension == null) {
            log.warn("studyReferenceExtension not set for {}. Impossible to determine receiver, aborting.",
                    list.getId());
            return null;
        }

        if (!hasPatientListChanged(list)) {
            log.info("list {} hasn't changed since last time", list.getId());
            return null;
        }

        var studyReference = (Reference) studyReferenceExtension.getValue();

        var researchSubjectList = fhirServer.getResearchSubjectsFromList(list);
        if (!hasPatientListCandidate(researchSubjectList)) {
            log.info("list {} doesn't have any candidates", list.getId());
            return null;
        }

        var studyAcronym = studyReference.getDisplay();
        if (studyAcronym == null) {
            var study = fhirServer.getResearchStudyFromId(studyReference.getReferenceElement().getIdPart());
            var studyAcronymOpt = study.getIdentifier().stream()
                    .filter(id -> id.getSystem().equals(studyAcronymSystem))
                    .findFirst();

            if (studyAcronymOpt.isEmpty()) {
                log.warn("Study acronym not set for study {}", studyReference.getReference());
                return null;
            }

            studyAcronym = studyAcronymOpt.get().getValue();
        }

        final var acronym = studyAcronym;

        // finds all matching entries in the configuration. An entry matches if either
        // the acronym is equal to this changed study's one or if its a wildcard ('*') receiver.
        var matchingRules = config.getMail().stream()
                .filter(rule -> rule.getAcronym().equals(acronym) || rule.getAcronym().equals("*"))
                .collect(toList());

        if (matchingRules.isEmpty()) {
            log.warn("No matching notification rules found for {}", studyAcronym);
            return null;
        }

        for (var matchingRule : matchingRules) {
            log.info("{} matched. Sending mail to {}", studyAcronym, matchingRule.getTo());
            sendMail(matchingRule, studyAcronym, studyReference.getReferenceElement().getIdPart());
        }

        return null;
    }

    private boolean hasPatientListCandidate(List<ResearchSubject> researchSubjects) {
        return researchSubjects
                .stream()
                .anyMatch(subject -> subject.getStatus() == ResearchSubjectStatus.CANDIDATE);
    }

    private boolean hasPatientListChanged(ListResource newScreenList) {
        var lastScreenList = fhirServer.getPreviousScreeningListFromServer(newScreenList);
        if (lastScreenList == null) {
            return true;
        }

        var newResearchSubjectIDs = getResearchSubjectIDs(newScreenList.getEntry());
        var lastResearchSubjectIDs = getResearchSubjectIDs(lastScreenList.getEntry());
        return !newResearchSubjectIDs.equals(lastResearchSubjectIDs);
    }

    private Set<String> getResearchSubjectIDs(List<ListResource.ListEntryComponent> entry) {
        return entry.stream()
                .map((item) -> item.getItem().getReferenceElement().getIdPart())
                .collect(Collectors.toSet());
    }

    private void sendMail(MailNotificationRule rule, String studyAcronym, String studyId) {
        var screeningListLink = String.format(messageBodyScreeningListLinkTemplate, studyId);
        var msg = new SimpleMailMessage() {
            {
                setTo(rule.getTo().toArray(new String[0]));
                setFrom(rule.getFrom());
                setSubject(String.format("%s - Neue Rekrutierungsvorschläge", studyAcronym));
                setText(String.format("Studie %s wurde aktualisiert. Vorschläge einsehbar unter %s.", studyAcronym,
                        screeningListLink));
            }
        };

        javaMailSender.send(msg);
    }
}
