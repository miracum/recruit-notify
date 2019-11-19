package org.miracum.recruit.notify;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.exceptions.FhirClientConnectionException;
import org.hl7.fhir.r4.model.ListResource;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ResearchStudy;
import org.miracum.recruit.notify.config.MailNotificationRule;
import org.miracum.recruit.notify.config.NotificationConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.listener.RetryListenerSupport;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static java.util.stream.Collectors.toList;

@RestController
public class NotificationController {
    private static final Logger log = LoggerFactory.getLogger(NotificationController.class);

    private final FhirContext fhirContext = FhirContext.forR4();
    private final NotificationConfiguration config;
    private final JavaMailSender javaMailSender;

    @Value("${fhir.systems.screeninglistreference}")
    private String screeningListReferenceSystem;
    @Value("${fhir.url}")
    private String fhirUrl;
    @Value("${fhir.systems.studyacronym}")
    private String studyAcronymSystem;

    @Autowired
    public NotificationController(NotificationConfiguration config, JavaMailSender javaMailSender) {
        this.config = config;
        this.javaMailSender = javaMailSender;
    }

    @PutMapping(value = "/on-list-change/List/{id}", consumes = {MediaType.APPLICATION_JSON_VALUE})
    public void onListChange(@PathVariable(value = "id") String resourceId, @RequestBody String body) {
        var list = fhirContext.newJsonParser().parseResource(ListResource.class, body);

        log.info("onListChange called for list with id {}", resourceId);

        var retryTemplate = new RetryTemplate();

        var fixedBackOffPolicy = new FixedBackOffPolicy();
        fixedBackOffPolicy.setBackOffPeriod(10000);
        retryTemplate.setBackOffPolicy(fixedBackOffPolicy);

        var retryPolicy = new SimpleRetryPolicy();
        retryPolicy.setMaxAttempts(20);
        retryTemplate.setRetryPolicy(retryPolicy);

        retryTemplate.registerListener(new RetryListenerSupport() {
            @Override
            public <T, E extends Throwable> void onError(RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
                log.warn("Trying to connect to FHIR server caused error. {} attempt.", context.getRetryCount(), throwable);
            }
        });

        retryTemplate.execute(
                (RetryCallback<Void, FhirClientConnectionException>) retryContext -> handleSubscriptionCallback(list));
    }

    private Void handleSubscriptionCallback(ListResource list) {
        // get the ResearchStudy referenced by this changed screening list
        var studyReference = (Reference) list.getExtensionByUrl(screeningListReferenceSystem).getValue();

        var studyAcronym = studyReference.getDisplay();
        if (studyAcronym == null) {

            var study = fhirContext.newRestfulGenericClient(fhirUrl)
                    .read()
                    .resource(ResearchStudy.class)
                    .withId(studyReference.getReferenceElement().getIdPart())
                    .execute();

            var studyAcronymOpt = study.getIdentifier()
                    .stream()
                    .filter(id -> id.getSystem().equals(studyAcronymSystem))
                    .findFirst();

            if (studyAcronymOpt.isEmpty()) {
                log.warn("Study acronym not set for study {}", studyReference.getReference());
                return null;
            }

            studyAcronym = studyAcronymOpt.get().getValue();
        }

        final var s = studyAcronym;

        var matchingRules = config.getMail()
                .stream()
                .filter(rule -> rule.getAcronym().equals(s))
                .collect(toList());

        if (matchingRules.isEmpty()) {
            log.warn("No matching notification rules found for {}", studyAcronym);
        }

        for (var matchingRule : matchingRules) {
            log.info("{} matched. Sending mail to {}", studyAcronym, matchingRule.getTo());
            sendMail(matchingRule, studyAcronym, studyReference.getDisplay());
        }

        return null;
    }

    private void sendMail(MailNotificationRule rule, String studyAcronym, String studyId) {
        var msg = new SimpleMailMessage() {{
            setTo(rule.getTo().toArray(new String[0]));
            setFrom(rule.getFrom());
            setSubject(String.format("%s - Neue Rekrutierungsvorschläge", studyAcronym));
            setText(String.format("Studie %s wurde aktualisiert", studyId));
        }};

        javaMailSender.send(msg);
    }
}
