package org.miracum.recruit.notify;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import org.hl7.fhir.r4.model.ListResource;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ResearchStudy;
import org.hl7.fhir.r4.model.ResearchSubject;
import org.hl7.fhir.r4.model.ResearchSubject.ResearchSubjectStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.runner.RunWith;
import org.miracum.recruit.notify.NotificationRuleConfig.MailNotificationRule;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.thymeleaf.TemplateEngine;

import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@RunWith(SpringJUnit4ClassRunner.class)
class NotificationControllerTest {
    private static final String screeningListReferenceSystem = "http://miracum.org/fhir/screening-list-study-reference";
    @Mock
    private JavaMailSender javaMailSender;
    @Mock
    private FhirServerProvider fhirServer;

    private RetryTemplate retryTemplate;
    private String screeningListBody;
    private IParser parser;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        var mimeMessage = new MimeMessage((Session) null);
        when(javaMailSender.createMimeMessage()).thenReturn(mimeMessage);

        retryTemplate = new RetryTemplate();
        var fixedBackOffPolicy = new FixedBackOffPolicy();
        fixedBackOffPolicy.setBackOffPeriod(1);
        retryTemplate.setBackOffPolicy(fixedBackOffPolicy);
        var retryPolicy = new SimpleRetryPolicy();
        retryPolicy.setMaxAttempts(1);
        retryTemplate.setRetryPolicy(retryPolicy);

        var screeningList = new ListResource().setStatus(ListResource.ListStatus.CURRENT)
                .setMode(ListResource.ListMode.WORKING);
        screeningList.addExtension(screeningListReferenceSystem, new Reference("ResearchStudy/0").setDisplay("TEST"));
        screeningList.addEntry().setItem(new Reference("ResearchSubject/0"));
        var fhirCtx = FhirContext.forR4();
        parser = fhirCtx.newJsonParser();
        screeningListBody = parser.encodeResourceToString(screeningList);

        when(fhirServer.getResearchStudyFromId(anyString())).thenReturn(new ResearchStudy());
        when(fhirServer.getResearchSubjectsFromList(any()))
                .thenReturn(List.of(new ResearchSubject().setStatus(ResearchSubjectStatus.CANDIDATE)));
    }

    @Test
    void onListChange_withEmptyRequestBody_shouldNotSendEmail() {
        var config = new NotificationRuleConfig();
        var sut = new NotificationController(config,
                javaMailSender,
                retryTemplate,
                parser,
                screeningListReferenceSystem,
                null,
                fhirServer,
                new TemplateEngine());

        sut.onListChange("1", null);

        verify(javaMailSender, never()).send((MimeMessage) Mockito.any());
    }

    @Test
    void onListChange_withMatchingNotificationRule_shouldSendEmail() {
        List<MailNotificationRule> rules = List.of(new MailNotificationRule() {
            {
                setAcronym("TEST");
                setFrom("from@example.com");
                setTo(List.of("to@example.com"));
            }
        });
        var config = new NotificationRuleConfig() {
            {
                setMail(rules);
            }
        };

        var sut = new NotificationController(config,
                javaMailSender,
                retryTemplate,
                parser,
                screeningListReferenceSystem,
                "",
                fhirServer,
                new TemplateEngine());
        sut.onListChange("1", screeningListBody);

        verify(javaMailSender, times(1)).send((MimeMessage) any());
    }

    @Test
    void onListChange_withNoNotificationRule_shouldNotSendEmail() {
        List<MailNotificationRule> rules = List.of(new MailNotificationRule() {
            {
                setAcronym("NOT-TEST");
            }
        });
        var config = new NotificationRuleConfig() {
            {
                setMail(rules);
            }
        };

        var sut = new NotificationController(config,
                javaMailSender,
                retryTemplate,
                parser,
                screeningListReferenceSystem,
                null,
                fhirServer,
                new TemplateEngine());

        sut.onListChange("1", screeningListBody);

        verify(javaMailSender, never()).send((MimeMessage) any());
    }

    @Test
    void onListChange_withWildCardReceiver_shouldSendEmails() {
        List<MailNotificationRule> rules = List.of(new MailNotificationRule() {
            {
                setAcronym("*");
                setFrom("from@example.com");
                setTo(List.of("to@example.com"));
            }
        });
        var config = new NotificationRuleConfig() {
            {
                setMail(rules);
            }
        };

        var sut = new NotificationController(config,
                javaMailSender,
                retryTemplate,
                parser,
                screeningListReferenceSystem,
                "",
                fhirServer,
                new TemplateEngine());

        sut.onListChange("1", screeningListBody);

        verify(javaMailSender, times(1)).send((MimeMessage) any());
    }

    @Test
    void onListChange_withNoCandidatesInList_shouldNotSendEmails() {
        var screeningList = new ListResource()
                .setStatus(ListResource.ListStatus.CURRENT)
                .setMode(ListResource.ListMode.WORKING);
        screeningList.addExtension(screeningListReferenceSystem, new Reference("ResearchStudy/0").setDisplay("TEST"));
        screeningList.addEntry()
                .setItem(new Reference("ResearchSubject/0"))
                .setItem(new Reference("ResearchSubject/1"));

        when(fhirServer.getResearchSubjectsFromList(any()))
                .thenReturn(List.of(new ResearchSubject().setStatus(ResearchSubjectStatus.ONSTUDY)));

        var config = new NotificationRuleConfig();
        var sut = new NotificationController(config,
                javaMailSender,
                retryTemplate,
                parser,
                screeningListReferenceSystem,
                "",
                fhirServer,
                new TemplateEngine());

        sut.onListChange("1", screeningListBody);

        verify(javaMailSender, never()).send((SimpleMailMessage) any());
    }
}
