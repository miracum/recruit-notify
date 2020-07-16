package org.miracum.recruit.notify;

import ca.uhn.fhir.parser.IParser;
import org.hl7.fhir.r4.model.ListResource;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ResearchSubject;
import org.hl7.fhir.r4.model.ResearchSubject.ResearchSubjectStatus;
import org.miracum.recruit.notify.NotificationRuleConfig.MailNotificationRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.listener.RetryListenerSupport;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import javax.mail.MessagingException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toList;

@RestController
public class NotificationController {
    private static final Logger log = LoggerFactory.getLogger(NotificationController.class);

    private final NotificationRuleConfig config;
    private final JavaMailSender mailSender;
    private final RetryTemplate retryTemplate;
    private final String screeningListReferenceSystem;
    private final String messageBodyScreeningListLinkTemplate;
    private final IParser fhirParser;
    private final FhirServerProvider fhirServer;
    private final TemplateEngine templateEngine;

    @Value("${fhir.systems.studyacronym}")
    private String studyAcronymSystem;

    @Autowired
    public NotificationController(NotificationRuleConfig config,
                                  JavaMailSender mailSender,
                                  RetryTemplate retryTemplate,
                                  IParser fhirParser,
                                  @Value("${fhir.systems.screeninglistreference}") String screeningListReferenceSystem,
                                  @Value("${notify.screeningListLinkTemplate}") String msg,
                                  FhirServerProvider fhirServer,
                                  TemplateEngine templateEngine) {
        this.config = config;
        this.mailSender = mailSender;
        this.retryTemplate = retryTemplate;
        this.screeningListReferenceSystem = screeningListReferenceSystem;
        this.messageBodyScreeningListLinkTemplate = msg;
        this.fhirParser = fhirParser;
        this.fhirServer = fhirServer;
        this.templateEngine = templateEngine;
    }

    @PutMapping(value = "/on-list-change/List/{id}", consumes = "application/fhir+json")
    public void onListChange(@PathVariable(value = "id") String resourceId, @RequestBody String body) {
        log.info("onListChange invoked for list with id {}", resourceId);

        if (body == null) {
            log.error("request body is null");
            return;
        }

        var list = fhirParser.parseResource(ListResource.class, body);

        if (!list.hasEntry()) {
            log.warn("Received empty screening list {}, aborting.", list.getId());
            return;
        }

        retryTemplate.registerListener(new RetryListenerSupport() {
            @Override
            public <T, E extends Throwable> void onError(RetryContext context, RetryCallback<T, E> callback,
                                                         Throwable throwable) {
                log.warn("handleSubscription failed. {} attempt.", context.getRetryCount());
            }
        });

        retryTemplate.execute(retryContext -> handleSubscription(list));
    }

    private Void handleSubscription(ListResource list) {
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

        var studyAcronym = "";

        if (studyReference.hasDisplay()) {
            studyAcronym = studyReference.getDisplay();
        } else {
            var study = fhirServer.getResearchStudyFromId(studyReference.getReferenceElement().getIdPart());

            if (study.hasExtension(studyAcronymSystem)) {
                var studyAcronymExtension = study.getExtensionByUrl(studyAcronymSystem);
                studyAcronym = studyAcronymExtension.getValue().toString();
                log.info("Using acronym '{}' from extension as study identifier for {}.",
                        studyAcronym,
                        studyReference.getReference());
            } else {
                log.warn("Study acronym not set for study {}.", studyReference.getReference());
                if (study.hasTitle()) {
                    studyAcronym = study.getTitle();
                    log.info("Using title '{}' as study identifier for {}.",
                            studyAcronym,
                            studyReference.getReference());
                } else {
                    log.error("No identifier available for study {}. Aborting.", studyReference.getReference());
                    return null;
                }
            }
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
            try {
                sendMail(matchingRule, studyAcronym, list.getIdElement().getIdPart());
            } catch (MessagingException e) {
                log.error("Failed to render notification email", e);
            }
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

        var newResearchSubjectIDs = getResearchSubjectIds(newScreenList.getEntry());
        var lastResearchSubjectIDs = getResearchSubjectIds(lastScreenList.getEntry());
        return !newResearchSubjectIDs.equals(lastResearchSubjectIDs);
    }

    private Set<String> getResearchSubjectIds(List<ListResource.ListEntryComponent> entry) {
        return entry.stream()
                .map(item -> item.getItem().getReferenceElement().getIdPart())
                .collect(Collectors.toSet());
    }

    private void sendMail(MailNotificationRule rule, String studyAcronym, String listId) throws MessagingException {
        var subject = String.format("MIRACUM Rekrutierungsunterstützung: neue Vorschläge für die %s Studie",
                studyAcronym);

        // Prepare message using a Spring helper
        var mimeMessage = mailSender.createMimeMessage();
        var message = new MimeMessageHelper(mimeMessage, true, "UTF-8");
        message.setSubject(subject);
        message.setFrom(rule.getFrom());
        message.setTo(rule.getTo().toArray(new String[0]));

        // Prepare the evaluation context
        var screeningListUrl = String.format(messageBodyScreeningListLinkTemplate, listId);

        var ctx = new Context();
        ctx.setVariable("studyName", studyAcronym);
        ctx.setVariable("screeningListUrl", screeningListUrl);

        // render the messages using the Thymeleaf templating engine. This replaces
        // the 'studyName' and 'screeningListUrl' placeholders inside the txt
        // and html files.
        var textContent = templateEngine.process("notification-mail.txt", ctx);
        var htmlContent = templateEngine.process("notification-mail.html", ctx);

        message.setText(textContent, htmlContent);

        this.mailSender.send(mimeMessage);
    }
}
