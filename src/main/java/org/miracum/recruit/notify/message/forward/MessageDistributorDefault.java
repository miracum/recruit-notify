package org.miracum.recruit.notify.message.forward;

import static java.util.stream.Collectors.toList;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.hl7.fhir.r4.model.CommunicationRequest;
import org.hl7.fhir.r4.model.CommunicationRequest.CommunicationRequestStatus;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.Reference;
import org.miracum.recruit.notify.FhirServerProvider;
import org.miracum.recruit.notify.fhirserver.FhirSystemsConfig;
import org.miracum.recruit.notify.fhirserver.MessageStatusUpdater;
import org.miracum.recruit.notify.mailconfig.MailerConfig;
import org.miracum.recruit.notify.mailconfig.UserConfig;
import org.miracum.recruit.notify.mailsender.MailInfo;
import org.miracum.recruit.notify.mailsender.MailSender;
import org.miracum.recruit.notify.mailsender.NotifyInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;

/** Distritute open messages that are stored as communication resource to fhir. */
@Service
public class MessageDistributorDefault implements MessageDistributor {

  private static final Logger LOG = LoggerFactory.getLogger(MessageDistributorDefault.class);
  private final UserConfig notificationRuleConfig;
  private final FhirServerProvider fhirServerProvider;
  private final FhirSystemsConfig fhirSystemsConfig;
  private final JavaMailSender appJavaMailSender;
  private final TemplateEngine emailTemplateEngine;
  private final MessageStatusUpdater messageUpdater;
  private final MailerConfig mailerConfig;

  /** Prepare config items and email utils to distribute temporary stored messages. */
  @Autowired
  public MessageDistributorDefault(
      TemplateEngine emailTemplateEngine,
      JavaMailSender appJavaMailSender,
      FhirSystemsConfig fhirSystemsConfig,
      FhirServerProvider fhirServerProvider,
      UserConfig notificationRuleConfig,
      MessageStatusUpdater messageUpdater,
      MailerConfig mailerConfig) {
    this.emailTemplateEngine = emailTemplateEngine;
    this.appJavaMailSender = appJavaMailSender;
    this.fhirSystemsConfig = fhirSystemsConfig;
    this.fhirServerProvider = fhirServerProvider;
    this.notificationRuleConfig = notificationRuleConfig;
    this.messageUpdater = messageUpdater;
    this.mailerConfig = mailerConfig;
  }

  @Override
  public void distribute(String jobKey) {
    LOG.info("job: {}", jobKey);

    var subscriptions =
        notificationRuleConfig.getSubscriptions().stream()
            .filter(rule -> jobKey.equals(rule.getNotify()))
            .collect(toList());

    List<String> subscribers = new ArrayList<>();
    for (var item : subscriptions) {
      LOG.debug("subscription receiver: {}", item.getEmail());
      subscribers.add(item.getEmail());
    }

    List<CommunicationRequest> messages =
        fhirServerProvider.getOpenMessagesForSubscribersFromFhir(subscribers);

    MessageSendingStatus messageSendingStatus = new MessageSendingStatus();
    List<String> messagesSentSuccessfully = new ArrayList<>();
    List<String> messagesSentFailed = new ArrayList<>();

    for (CommunicationRequest message : messages) {

      NotifyInfo notifyInfo = new NotifyInfo();

      notifyInfo.setStudyAcronym(message.getReasonCodeFirstRep().getText());

      MailInfo mailInfo = new MailInfo();

      mailInfo.setFrom(mailerConfig.getFrom());
      mailInfo.setSubject(
          mailerConfig.getSubject().replace("[study_acronym]", notifyInfo.getStudyAcronym()));

      var listIdIsEmpty = true;
      List<Reference> referencesAbout = message.getAbout();
      for (Reference reference : referencesAbout) {

        try {
          if (reference.getReference().contains("List")) {
            var listId = reference.getReferenceElement().getIdPart();
            notifyInfo.setScreeningListLink(replaceScreeningListLinkPlaceholderByListId(listId));
            listIdIsEmpty = false;
            break;
          }
        } catch (NullPointerException e) {
          LOG.warn(
              "no screening list linked to fhir communication, screening list url will be"
                  + " truncated");
        }
      }

      if (listIdIsEmpty) {
        LOG.warn(
            "no screening list linked to fhir communication, screening list url will be "
                + "truncated");
        notifyInfo.setScreeningListLink(replaceScreeningListLinkPlaceholderByListId(""));
      }

      String emailAddress = queryEmailFromPractitioner(message);

      if (emailAddress.equals("")) {
        messagesSentFailed.add(message.getIdElement().getIdPart());
        break;
      }

      mailInfo.setTo(emailAddress);

      if (this.appJavaMailSender != null) {
        var mailSenderDefault = new MailSender(appJavaMailSender, emailTemplateEngine);
        var isSentSuccessfully = mailSenderDefault.sendMail(notifyInfo, mailInfo);

        if (isSentSuccessfully) {
          messagesSentSuccessfully.add(message.getIdElement().getIdPart());
        } else {
          messagesSentFailed.add(message.getIdElement().getIdPart());
        }

      } else {
        LOG.error("sender error");
      }
    }

    messageSendingStatus.setMessagesSentSuccessfully(messagesSentSuccessfully);
    messageSendingStatus.setMessagesSentFailed(messagesSentFailed);

    updateMessageStatusInFhir(messageSendingStatus);
  }

  private void updateMessageStatusInFhir(MessageSendingStatus messageSendingStatus) {
    updateMailsSuccessfullSent(messageSendingStatus);

    updateMailsFailedSending(messageSendingStatus);
  }

  private void updateMailsFailedSending(MessageSendingStatus messageSendingStatus) {
    for (String failedMessage : messageSendingStatus.getMessagesSentFailed()) {
      LOG.debug("mark failed message in fhir");

      messageUpdater.update(failedMessage, CommunicationRequestStatus.ENTEREDINERROR);
    }
  }

  private void updateMailsSuccessfullSent(MessageSendingStatus messageSendingStatus) {
    for (String successfullMessage : messageSendingStatus.getMessagesSentSuccessfully()) {
      LOG.debug("update successfully sent message in fhir");

      messageUpdater.update(successfullMessage, CommunicationRequestStatus.COMPLETED);
    }
  }

  private String replaceScreeningListLinkPlaceholderByListId(String listId) {
    return mailerConfig.getLinkTemplate().replace("[list_id]", listId);
  }

  private String queryEmailFromPractitioner(CommunicationRequest message) {

    String emailAddress = "";

    List<Reference> recipientList = message.getRecipient();
    for (Reference reference : recipientList) {
      if (reference.getResource().fhirType().equals("Practitioner")) {
        Practitioner practitioner = (Practitioner) reference.getResource();

        Optional<Identifier> identifier =
            practitioner.getIdentifier().stream()
                .filter(rule -> rule.getSystem().equals(fhirSystemsConfig.getSubscriberSystem()))
                .findFirst();

        if (identifier.isEmpty()) {
          LOG.warn("message could not be sent because of missing recipient");

        } else {
          emailAddress = identifier.get().getValue();
        }
      }
    }
    return emailAddress;
  }
}
