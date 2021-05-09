package org.miracum.recruit.notify.message;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import javax.mail.MessagingException;
import org.apache.logging.log4j.util.Strings;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.CommunicationRequest;
import org.hl7.fhir.r4.model.CommunicationRequest.CommunicationPriority;
import org.hl7.fhir.r4.model.CommunicationRequest.CommunicationRequestPayloadComponent;
import org.hl7.fhir.r4.model.CommunicationRequest.CommunicationRequestStatus;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;
import org.miracum.recruit.notify.FhirServerProvider;
import org.miracum.recruit.notify.fhirserver.FhirSystemsConfig;
import org.miracum.recruit.notify.fhirserver.MessageTransmitter;
import org.miracum.recruit.notify.mailconfig.MailerConfig;
import org.miracum.recruit.notify.mailconfig.UserConfig;
import org.miracum.recruit.notify.mailsender.MailInfo;
import org.miracum.recruit.notify.mailsender.MailSender;
import org.miracum.recruit.notify.mailsender.NotifyInfo;
import org.miracum.recruit.notify.practitioner.PractitionerFilter;
import org.miracum.recruit.notify.practitioner.PractitionerUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;

/** Service to create communication resources in target fhir server. */
@Service
public class MessageCreator {
  private static final Logger LOG = LoggerFactory.getLogger(MessageCreator.class);

  private final JavaMailSender javaMailSender;
  private final TemplateEngine templateEngine;
  private final PractitionerFilter practitionerFilter;
  private final MessageTransmitter messageTransmitter;
  private final UserConfig config;
  private final MailerConfig mailerConfig;
  private final FhirServerProvider fhirServerProvider;
  private final FhirSystemsConfig fhirSystemConfig;

  /** Prepare config items and email utils to use when sending emails just in time (ad hoc). */
  @Autowired
  public MessageCreator(
      JavaMailSender javaMailSender,
      TemplateEngine templateEngine,
      PractitionerFilter practitionerFilter,
      MessageTransmitter messageTransmitter,
      UserConfig config,
      MailerConfig mailerConfig,
      FhirServerProvider fhirServerProvider,
      FhirSystemsConfig fhirSystemConfig) {
    this.javaMailSender = javaMailSender;
    this.templateEngine = templateEngine;
    this.practitionerFilter = practitionerFilter;
    this.messageTransmitter = messageTransmitter;
    this.config = config;
    this.mailerConfig = mailerConfig;
    this.fhirServerProvider = fhirServerProvider;
    this.fhirSystemConfig = fhirSystemConfig;
  }

  /**
   * Based on acronym and list id sending messages to target fhir server to store messages as
   * communication request resources.
   */
  public void temporaryStoreMessagesInFhir(String acronym, String listId) {
    LOG.info("create messages in queue for {}", kv("trial", acronym));

    List<Practitioner> practitionersFhir = retrieveSubscribersByAcronym(acronym);

    if (practitionersFhir.isEmpty()) {
      LOG.info("no practitioners available");
      return;
    }

    var practitionerListContainer =
        practitionerFilter.dividePractitioners(config.getSubscriptions(), practitionersFhir);

    List<CommunicationRequest> messagesAdHoc =
        createMessages(acronym, listId, practitionerListContainer.getAdHocRecipients());
    List<CommunicationRequest> messagesDelayed =
        createMessages(acronym, listId, practitionerListContainer.getScheduledRecipients());

    var notifyInfo = generateNotifyInfo(acronym, listId);

    if (!messagesAdHoc.isEmpty()) {
      sendMessagesAdHoc(messagesAdHoc, practitionerListContainer.getAdHocRecipients(), notifyInfo);
    }

    if (!messagesDelayed.isEmpty()) {
      storeMessagesInFhir(messagesDelayed);
    }
  }

  private NotifyInfo generateNotifyInfo(String acronym, String listId) {
    var notifyInfo = new NotifyInfo();
    notifyInfo.setStudyAcronym(acronym);
    notifyInfo.setScreeningListLink(mailerConfig.getLinkTemplate().replace("[list_id]", listId));
    return notifyInfo;
  }

  private List<Practitioner> retrieveSubscribersByAcronym(String acronym) {
    LOG.debug("retrieve subscribers by {}", kv("trial", acronym));

    List<String> subscribers = readSubscribersFromConfig(acronym);

    if (subscribers.isEmpty()) {
      return new ArrayList<>();
    }

    return fhirServerProvider.getPractitionersByEmail(subscribers);
  }

  private List<String> readSubscribersFromConfig(String acronym) {
    LOG.info("retrieve subscribers from config for {}", kv("trial", acronym));

    var subscribers = new ArrayList<String>();

    for (var trial : config.getTrials()) {
      if (trial.getAcronym().equalsIgnoreCase(acronym)
          || trial.getAcronym().equalsIgnoreCase("*")) {

        for (var subscriptions : trial.getSubscriptions()) {
          LOG.info(
              "add subscriber {} for {} to list",
              kv("email", subscriptions.getEmail()),
              kv("trial", trial.getAcronym()));
          subscribers.add(subscriptions.getEmail());
        }
      }
    }

    return subscribers;
  }

  /**
   * Create CommunicationRequest resources by list of practitioners that should receive an email and
   * acronym and list id (will be linked in email body).
   */
  public List<CommunicationRequest> createMessages(
      String acronym, String listId, List<Practitioner> practitionersFhir) {

    LOG.debug(
        "creating FHIR CommunicationRequest resources for {} for {} practitioners",
        kv("trial", acronym),
        kv("numOfPractitioners", practitionersFhir.size()));

    var result = new ArrayList<CommunicationRequest>();

    for (var practitioner : practitionersFhir) {
      LOG.debug(
          "receiver for {}: {}",
          kv("trial", acronym),
          kv("practitioner", practitioner.getIdElement().getIdPart()));

      var practitionerEmail = PractitionerUtils.getFirstEmailFromPractitioner(practitioner);

      var categoryCoding =
          new Coding()
              .setSystem(fhirSystemConfig.getCommunicationCategory())
              .setCode("notification");

      var payload = createPayload(acronym);
      var communication =
          new CommunicationRequest()
              .setStatus(CommunicationRequestStatus.ACTIVE)
              .setPriority(CommunicationPriority.ROUTINE)
              .addCategory(new CodeableConcept().addCoding(categoryCoding))
              .addPayload(payload)
              .setAuthoredOn(new Date());

      var practitionerReference = new Reference();
      practitionerReference.setReference("Practitioner/" + practitioner.getIdElement().getIdPart());
      practitionerEmail.ifPresent(
          contactPoint -> practitionerReference.setDisplay(contactPoint.getValue()));
      communication.addRecipient(practitionerReference);

      var screeningListReference = new Reference();
      screeningListReference.setReference("List/" + listId);
      communication.addAbout(screeningListReference);

      var reasonCodeList = createReasonCodeByAcronym(acronym);
      communication.setReasonCode(reasonCodeList);

      var identifierList = createAppSpecificIdentifier();
      communication.setIdentifier(identifierList);

      result.add(communication);
    }

    return result;
  }

  private CommunicationRequestPayloadComponent createPayload(String acronym) {
    var payloadText =
        new StringType()
            .setValue(
                String.format("Notification about potential new study candidates for %s", acronym));
    return new CommunicationRequestPayloadComponent().setContent(payloadText);
  }

  private List<CodeableConcept> createReasonCodeByAcronym(String acronym) {
    var reasonCodeList = new ArrayList<CodeableConcept>();
    var reasonCode = new CodeableConcept();
    reasonCode.setText(acronym);
    reasonCodeList.add(reasonCode);
    return reasonCodeList;
  }

  private List<Identifier> createAppSpecificIdentifier() {
    var identifier = new Identifier();
    identifier.setSystem(fhirSystemConfig.getCommunication());

    var communicationUuid = UUID.randomUUID();

    identifier.setValue(communicationUuid.toString());
    var identifierList = new ArrayList<Identifier>();
    identifierList.add(identifier);
    return identifierList;
  }

  private void storeMessagesInFhir(List<CommunicationRequest> messages) {
    var alreadyPreparedMessages = fhirServerProvider.getPreparedMessages();

    var extractedMessages = extractMessagesToPrepare(messages, alreadyPreparedMessages);

    messageTransmitter.transmit(extractedMessages);
  }

  private List<CommunicationRequest> extractMessagesToPrepare(
      List<CommunicationRequest> messages, List<CommunicationRequest> alreadyPreparedMessages) {
    List<CommunicationRequest> extractedMessages = new ArrayList<>();

    for (var messageToPrepare : messages) {

      var referenceIdReceiver = messageToPrepare.getRecipientFirstRep().getReference();
      var idPartReceiver = referenceIdReceiver.substring(referenceIdReceiver.lastIndexOf("/") + 1);

      var messageIsAlreadyPrepared =
          checkIfMessageIsAlreadyPrepared(
              alreadyPreparedMessages, messageToPrepare, idPartReceiver);

      if (!messageIsAlreadyPrepared) {
        extractedMessages.add(messageToPrepare);
      }
    }
    return extractedMessages;
  }

  private boolean checkIfMessageIsAlreadyPrepared(
      List<CommunicationRequest> alreadyPreparedMessages,
      CommunicationRequest messageToPrepare,
      String idPartReceiver) {

    var topic = messageToPrepare.getReasonCodeFirstRep().getText();

    LOG.info(
        "check if message exists for {} and {}",
        kv("practitioner", idPartReceiver),
        kv("acronym", topic));

    var messageIsAlreadyPrepared = false;

    var topicAlreadyExists = false;
    var receiverAlreadyExists = false;

    for (var messagesAlreadyPrepared : alreadyPreparedMessages) {
      topicAlreadyExists = messagesAlreadyPrepared.getReasonCodeFirstRep().getText().equals(topic);
      receiverAlreadyExists =
          checkIfMessageForRecipientIsAlreadyRegistered(messageToPrepare, idPartReceiver);

      if (topicAlreadyExists && receiverAlreadyExists) {
        messageIsAlreadyPrepared = true;
        break;
      }
    }

    LOG.debug("{}", kv("messageIsAlreadyPrepared", messageIsAlreadyPrepared));
    return messageIsAlreadyPrepared;
  }

  private Boolean checkIfMessageForRecipientIsAlreadyRegistered(
      CommunicationRequest messageToPrepare, String idPartReceiver) {
    var messageIsAlreadyPrepared = false;
    var recipientList = messageToPrepare.getRecipient();
    for (var reference : recipientList) {
      if (reference.getReference().contains("Practitioner")) {
        var practitionerId = reference.getReferenceElement().getIdPart();

        if (idPartReceiver.equals(practitionerId)) {
          LOG.debug("message already prepared for {}", kv("practitioner", idPartReceiver));
          messageIsAlreadyPrepared = true;
          break;
        }
      }
    }

    return messageIsAlreadyPrepared;
  }

  // TODO: consolidate redundant code with MessageDistributor.distribute
  private void sendMessagesAdHoc(
      List<CommunicationRequest> messagesAdHoc, List<Practitioner> list, NotifyInfo notifyInfo) {

    for (var message : messagesAdHoc) {
      var email = retrieveEmailAddressOfReceiver(list, message);
      if (Strings.isBlank(email)) {
        LOG.error("receiver not present - mail could not be sent!");
        return;
      }

      var mailInfo = new MailInfo();
      mailInfo.setFrom(mailerConfig.getFrom());
      mailInfo.setTo(email);
      mailInfo.setSubject(
          mailerConfig.getSubject().replace("[study_acronym]", notifyInfo.getStudyAcronym()));

      var mailSender = new MailSender(javaMailSender, templateEngine);
      try {
        mailSender.sendMail(notifyInfo, mailInfo);
      } catch (MessagingException e) {
        LOG.error("failed to send message", e);
      }
    }
  }

  private String retrieveEmailAddressOfReceiver(
      List<Practitioner> practitioners, CommunicationRequest message) {

    var practitionerReference = message.getRecipientFirstRep().getReferenceElement();

    LOG.debug("retrieving email address for {}", kv("practitioner", practitionerReference));

    var practitioner =
        practitioners.stream()
            .filter(p -> p.getIdElement().getIdPart().equals(practitionerReference.getIdPart()))
            .findFirst();

    if (practitioner.isEmpty()) {
      return null;
    }

    return PractitionerUtils.getFirstEmailFromPractitioner(practitioner.get())
        .map(ContactPoint::getValue)
        .orElse(null);
  }
}
