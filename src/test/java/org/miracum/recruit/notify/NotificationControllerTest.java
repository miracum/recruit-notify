package org.miracum.recruit.notify;

import ca.uhn.fhir.context.FhirContext;
import org.hl7.fhir.r4.model.ListResource;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.runner.RunWith;
import org.miracum.recruit.notify.config.MailNotificationRule;
import org.miracum.recruit.notify.config.NotificationConfiguration;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import javax.mail.internet.MimeMessage;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@RunWith(SpringJUnit4ClassRunner.class)
class NotificationControllerTest {
    private static final String screeningListReferenceSystem = "http://miracum.org/fhir/screening-list-study-reference";
    @Mock
    private JavaMailSender javaMailSender;
    private RetryTemplate retryTemplate;
    private String screeningListBody;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        retryTemplate = new RetryTemplate();
        var fixedBackOffPolicy = new FixedBackOffPolicy();
        fixedBackOffPolicy.setBackOffPeriod(1);
        retryTemplate.setBackOffPolicy(fixedBackOffPolicy);
        var retryPolicy = new SimpleRetryPolicy();
        retryPolicy.setMaxAttempts(1);
        retryTemplate.setRetryPolicy(retryPolicy);


        var screeningList = new ListResource()
                .setStatus(ListResource.ListStatus.CURRENT)
                .setMode(ListResource.ListMode.WORKING);
        screeningList.addExtension(screeningListReferenceSystem,
                new Reference("ResearchStudy/0").setDisplay("TEST"));

        var parser = FhirContext.forR4().newJsonParser();
        screeningListBody = parser.encodeResourceToString(screeningList);
    }

    @Test
    public void onListChange_withEmptyRequestBody_shouldNotSendEmail() {
        var config = new NotificationConfiguration();
        var sut = new NotificationController(config,
                javaMailSender,
                retryTemplate,
                FhirContext.forR4(),
                null,
                null);

        sut.onListChange("1", null);

        verify(javaMailSender, never()).send((MimeMessage) Mockito.any());
    }

    @Test
    public void onListChange_withMatchingNotificationRule_shouldSendEmail() {
        var notificationRule = new MailNotificationRule();
        notificationRule.setAcronym("TEST");
        notificationRule.setFrom("from@example.com");
        notificationRule.setTo(List.of("to@example.com"));

        List<MailNotificationRule> rules = List.of(notificationRule);
        var config = new NotificationConfiguration();
        config.setMail(rules);

        var sut = new NotificationController(config,
                javaMailSender,
                retryTemplate,
                FhirContext.forR4(),
                screeningListReferenceSystem,
                "");

        sut.onListChange("1", screeningListBody);

        verify(javaMailSender, times(1)).send((SimpleMailMessage) any());
    }

    @Test
    public void onListChange_withNoNotificationRule_shouldNotSendEmail() {
        var notificationRule = new MailNotificationRule();
        notificationRule.setAcronym("NOT-TEST");
        List<MailNotificationRule> rules = List.of(notificationRule);
        var config = new NotificationConfiguration();
        config.setMail(rules);

        var sut = new NotificationController(config,
                javaMailSender,
                retryTemplate,
                FhirContext.forR4(),
                screeningListReferenceSystem,
                "");

        sut.onListChange("1", screeningListBody);

        verify(javaMailSender, never()).send((SimpleMailMessage) any());
    }

    @Test
    public void onListChange_withWildCardReceiver_shouldSendEmails() {
        var notificationRule = new MailNotificationRule();
        notificationRule.setAcronym("*");
        notificationRule.setFrom("from@example.com");
        notificationRule.setTo(List.of("to@example.com"));
        List<MailNotificationRule> rules = List.of(notificationRule);

        var config = new NotificationConfiguration();
        config.setMail(rules);

        var sut = new NotificationController(config,
                javaMailSender,
                retryTemplate,
                FhirContext.forR4(),
                screeningListReferenceSystem,
                "");

        sut.onListChange("1", screeningListBody);

        verify(javaMailSender, times(1)).send((SimpleMailMessage) any());
    }
}
