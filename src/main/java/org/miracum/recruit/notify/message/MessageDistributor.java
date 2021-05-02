package org.miracum.recruit.notify.message;

import static java.util.stream.Collectors.toList;
import static net.logstash.logback.argument.StructuredArguments.kv;

import com.google.common.base.Strings;
import java.util.ArrayList;
import java.util.List;
import javax.mail.MessagingException;
import org.hl7.fhir.r4.model.CommunicationRequest;
import org.hl7.fhir.r4.model.CommunicationRequest.CommunicationRequestStatus;
import org.hl7.fhir.r4.model.Practitioner;
import org.miracum.recruit.notify.FhirServerProvider;
import org.miracum.recruit.notify.fhirserver.MessageStatusUpdater;
import org.miracum.recruit.notify.mailconfig.MailerConfig;
import org.miracum.recruit.notify.mailconfig.UserConfig;
import org.miracum.recruit.notify.mailsender.MailInfo;
import org.miracum.recruit.notify.mailsender.MailSender;
import org.miracum.recruit.notify.mailsender.NotifyInfo;
import org.miracum.recruit.notify.practitioner.PractitionerUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;

/** Distritute open messages that are stored as communication resource to fhir. */
@Service
public class MessageDistributor {

  private static final Logger LOG = LoggerFactory.getLogger(MessageDistributor.class);
  private final UserConfig notificationRuleConfig;
  private final FhirServerProvider fhirServerProvider;
  private final JavaMailSender appJavaMailSender;
  private final TemplateEngine emailTemplateEngine;
  private final MessageStatusUpdater messageUpdater;
  private final MailerConfig mailerConfig;

  /** Prepare config items and email utils to distribute temporary stored messages. */
  @Autowired
  public MessageDistributor(
      TemplateEngine emailTemplateEngine,
      JavaMailSender appJavaMailSender,
      FhirServerProvider fhirServerProvider,
      UserConfig notificationRuleConfig,
      MessageStatusUpdater messageUpdater,
      MailerConfig mailerConfig) {
    this.emailTemplateEngine = emailTemplateEngine;
    this.appJavaMailSender = appJavaMailSender;
    this.fhirServerProvider = fhirServerProvider;
    this.notificationRuleConfig = notificationRuleConfig;
    this.messageUpdater = messageUpdater;
    this.mailerConfig = mailerConfig;
  }

  public void distribute(String jobKey) {
    LOG.info("begin distributing messages for {}", kv("job", jobKey));

    var subscriptions =
        notificationRuleConfig.getSubscriptions().stream()
            .filter(rule -> jobKey.equals(rule.getNotify()))
            .collect(toList());

    List<String> subscribers = new ArrayList<>();
    for (var item : subscriptions) {
      subscribers.add(item.getEmail());
    }

    LOG.debug("{} subscribe to schedule of {} ", kv("subscribers", subscribers), kv("job", jobKey));

    var openMessages = fhirServerProvider.getOpenMessagesForSubscribers(subscribers);

    // TODO: strongly type this list by using the CommunicationRequest object instead of just the
    // string id
    List<String> messagesSentSuccessfully = new ArrayList<>();
    List<String> messagesSentFailed = new ArrayList<>();

    for (var message : openMessages) {
      var notifyInfo = new NotifyInfo();
      notifyInfo.setStudyAcronym(message.getReasonCodeFirstRep().getText());

      var mailInfo = new MailInfo();
      mailInfo.setFrom(mailerConfig.getFrom());
      mailInfo.setSubject(
          mailerConfig.getSubject().replace("[study_acronym]", notifyInfo.getStudyAcronym()));

      var listId = "";
      var referencesAbout = message.getAbout();
      for (var reference : referencesAbout) {
        if (reference.hasReference() && reference.getReference().contains("List")) {
          listId = reference.getReferenceElement().getIdPart();
          break;
        }
      }

      if (Strings.isNullOrEmpty(listId)) {
        LOG.error(
            "Failed to retrieve the screening list resource associated with {}. "
                + "Setting id to an empty string in link template.",
            kv("communicationRequestId", message.getId()));
      }

      notifyInfo.setScreeningListLink(replaceScreeningListLinkPlaceholderByListId(listId));

      var emailAddress = queryEmailFromPractitioner(message);
      if (Strings.isNullOrEmpty(emailAddress)) {
        LOG.error(
            "adding {} to failed message list because no receiver email could be retrieved",
            kv("message", message.getId()));
        messagesSentFailed.add(message.getIdElement().getIdPart());
        break;
      }

      mailInfo.setTo(emailAddress);

      var mailSender = new MailSender(appJavaMailSender, emailTemplateEngine);
      try {
        mailSender.sendMail(notifyInfo, mailInfo);
        messagesSentSuccessfully.add(message.getIdElement().getIdPart());
      } catch (MessagingException e) {
        LOG.error(
            "failed to send {} {}", kv("message", message.getId()), kv("to", mailInfo.getTo()), e);
        messagesSentFailed.add(message.getIdElement().getIdPart());
      }
    }

    updateMessageStatus(messagesSentFailed, CommunicationRequestStatus.ONHOLD);
    updateMessageStatus(messagesSentSuccessfully, CommunicationRequestStatus.COMPLETED);
  }

  private void updateMessageStatus(List<String> messages, CommunicationRequestStatus status) {
    for (var message : messages) {
      LOG.debug("updating {} in server to {}", kv("message", message), kv("status", status));
      messageUpdater.update(message, status);
    }
  }

  private String replaceScreeningListLinkPlaceholderByListId(String listId) {
    return mailerConfig.getLinkTemplate().replace("[list_id]", listId);
  }

  private String queryEmailFromPractitioner(CommunicationRequest message) {
    var recipientList = message.getRecipient();
    for (var reference : recipientList) {
      if (reference.getResource().fhirType().equals("Practitioner")) {
        var practitioner = (Practitioner) reference.getResource();
        var email = PractitionerUtils.getFirstEmailFromPractitioner(practitioner);
        if (email.isEmpty()) {
          LOG.warn(
              "{} could not be sent because of missing email address of {}",
              kv("message", message.getId()),
              kv("practitioner", practitioner.getId()));
        } else {
          return email.get().getValue();
        }
      }
    }

    return null;
  }
}
