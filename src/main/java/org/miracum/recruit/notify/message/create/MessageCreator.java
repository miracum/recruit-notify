package org.miracum.recruit.notify.message.create;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.logging.log4j.util.Strings;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.CommunicationRequest;
import org.hl7.fhir.r4.model.CommunicationRequest.CommunicationRequestStatus;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.ContactPoint.ContactPointSystem;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.Reference;
import org.miracum.recruit.notify.FhirServerProvider;
import org.miracum.recruit.notify.fhirserver.FhirSystemsConfig;
import org.miracum.recruit.notify.fhirserver.MessageTransmitter;
import org.miracum.recruit.notify.logging.LogMethodCalls;
import org.miracum.recruit.notify.mailconfig.MailerConfig;
import org.miracum.recruit.notify.mailconfig.UserConfig;
import org.miracum.recruit.notify.mailsender.MailInfo;
import org.miracum.recruit.notify.mailsender.MailSender;
import org.miracum.recruit.notify.mailsender.NotifyInfo;
import org.miracum.recruit.notify.practitioner.PractitionerFilter;
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
  @LogMethodCalls
  public void temporaryStoreMessagesInFhir(String acronym, String listId) {
    LOG.info("create messages in queue for trial: {}", acronym);

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

    NotifyInfo notifyInfo = generateNotifyInfo(acronym, listId);

    if (!messagesAdHoc.isEmpty()) {
      sendMessagesAdHoc(messagesAdHoc, practitionerListContainer.getAdHocRecipients(), notifyInfo);
    }

    if (!messagesDelayed.isEmpty()) {
      storeMessagesInFhir(messagesDelayed);
    }
  }

  private NotifyInfo generateNotifyInfo(String acronym, String listId) {
    NotifyInfo notifyInfo = new NotifyInfo();
    notifyInfo.setStudyAcronym(acronym);
    notifyInfo.setScreeningListLink(mailerConfig.getLinkTemplate().replace("[list_id]", listId));
    return notifyInfo;
  }

  private List<Practitioner> retrieveSubscribersByAcronym(String acronym) {

    LOG.debug("retrieve subscribers by acronym: {}", acronym);

    List<String> subscribers = readSubscribersFromConfig(acronym);

    if (subscribers.isEmpty()) {
      return new ArrayList<>();
    }

    return fhirServerProvider.getSubscriberObjectsFromFhir(subscribers);
  }

  private List<String> readSubscribersFromConfig(String acronym) {
    LOG.info("retrieve subscribers from config for acronym \"{}\"", acronym);

    var subscribers = new ArrayList<String>();

    for (var trial : config.getTrials()) {
      if (trial.getAcronym().equalsIgnoreCase(acronym)
          || trial.getAcronym().equalsIgnoreCase("*")) {

        for (var subscriptions : trial.getSubscriptions()) {
          LOG.info(
              "add subscriber email={} for trial={} to list",
              subscriptions.getEmail(),
              trial.getAcronym());
          subscribers.add(subscriptions.getEmail());
        }
      }
    }

    return subscribers;
  }

  /**
   * Create communciation request resources by list of practitioners that should receive an email
   * and acronym and list id (will be linked in email body).
   */
  public List<CommunicationRequest> createMessages(
      String acronym, String listId, List<Practitioner> practitionersFhir) {

    LOG.debug(
        "create fhir communication resources for trial: {} for {} practitioners",
        acronym,
        practitionersFhir.size());

    List<CommunicationRequest> result = new ArrayList<>();

    for (Practitioner practitioner : practitionersFhir) {

      LOG.debug(
          "receiver for trial \"{}\": {}",
          acronym,
          practitioner.getIdentifierFirstRep().getValue());
      LOG.debug("message for {}", practitioner.getIdentifierFirstRep().getValue());

      CommunicationRequest communication = new CommunicationRequest();
      communication.setStatus(CommunicationRequestStatus.ACTIVE);

      Reference practitionerReference = new Reference();
      practitionerReference.setReference("Practitioner/" + practitioner.getIdElement().getIdPart());
      communication.addRecipient(practitionerReference);

      Reference screeningListReference = new Reference();
      screeningListReference.setReference("List/" + listId);
      communication.addAbout(screeningListReference);

      List<CodeableConcept> reasonCodeList = createReasonCodeByAcronym(acronym);
      communication.setReasonCode(reasonCodeList);

      List<Identifier> identifierList = createAppSpecificIdentifier();
      communication.setIdentifier(identifierList);

      result.add(communication);
    }

    return result;
  }

  private List<CodeableConcept> createReasonCodeByAcronym(String acronym) {
    List<CodeableConcept> reasonCodeList = new ArrayList<>();
    CodeableConcept reasonCode = new CodeableConcept();
    reasonCode.setText(acronym);
    reasonCodeList.add(reasonCode);
    return reasonCodeList;
  }

  private List<Identifier> createAppSpecificIdentifier() {
    Identifier identifier = new Identifier();
    identifier.setSystem(fhirSystemConfig.getCommunication());

    UUID communicationUuid = UUID.randomUUID();

    identifier.setValue(communicationUuid.toString());
    List<Identifier> identifierList = new ArrayList<>();
    identifierList.add(identifier);
    return identifierList;
  }

  private void storeMessagesInFhir(List<CommunicationRequest> messages) {

    List<CommunicationRequest> alreadyPreparedMessages = fhirServerProvider.getPreparedMessages();

    List<CommunicationRequest> extractedMessages =
        extractMessagesToPrepare(messages, alreadyPreparedMessages);

    messageTransmitter.transmit(extractedMessages);
  }

  private List<CommunicationRequest> extractMessagesToPrepare(
      List<CommunicationRequest> messages, List<CommunicationRequest> alreadyPreparedMessages) {
    List<CommunicationRequest> extractedMessages = new ArrayList<>();

    for (CommunicationRequest messageToPrepare : messages) {

      String referenceIdReceiver = messageToPrepare.getRecipientFirstRep().getReference();
      String idPartReceiver =
          referenceIdReceiver.substring(referenceIdReceiver.lastIndexOf("/") + 1);

      boolean messageIsAlreadyPrepared =
          checkIfMessageIsAlreadyPrepared(
              alreadyPreparedMessages, messageToPrepare, idPartReceiver);

      if (!messageIsAlreadyPrepared) {
        extractedMessages.add(messageToPrepare);
      }
    }
    return extractedMessages;
  }

  private Boolean checkIfMessageIsAlreadyPrepared(
      List<CommunicationRequest> alreadyPreparedMessages,
      CommunicationRequest messageToPrepare,
      String idPartReceiver) {

    String topic = messageToPrepare.getReasonCodeFirstRep().getText();

    LOG.info("check if message exists for idPartReceiver: {} and topic: {}", idPartReceiver, topic);

    var messageIsAlreadyPrepared = false;

    var topicAlreadyExists = false;
    var receiverAlreadyExists = false;

    for (CommunicationRequest messagesAlreadyPrepared : alreadyPreparedMessages) {

      topicAlreadyExists = messagesAlreadyPrepared.getReasonCodeFirstRep().getText().equals(topic);

      receiverAlreadyExists =
          checkIfMessageForRecipientIsAlreadyRegistered(messageToPrepare, idPartReceiver);

      if (topicAlreadyExists && receiverAlreadyExists) {
        messageIsAlreadyPrepared = true;
        break;
      }
    }

    LOG.debug("messageIsAlreadyPrepared :{}", messageIsAlreadyPrepared);
    return messageIsAlreadyPrepared;
  }

  private Boolean checkIfMessageForRecipientIsAlreadyRegistered(
      CommunicationRequest messageToPrepare, String idPartReceiver) {
    var messageIsAlreadyPrepared = false;
    List<Reference> recipientList = messageToPrepare.getRecipient();
    for (Reference reference : recipientList) {
      if (reference.getReference().contains("Practitioner")) {
        String referenceString = reference.getReference();
        String practitionerId = referenceString.substring(referenceString.lastIndexOf("/") + 1);

        if (idPartReceiver.equals(practitionerId)) {
          LOG.debug("message already prepared for idPartReceiver {}", idPartReceiver);
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

    for (CommunicationRequest message : messagesAdHoc) {

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
      mailSender.sendMail(notifyInfo, mailInfo);
    }
  }

  private String retrieveEmailAddressOfReceiver(
      List<Practitioner> practitioners, CommunicationRequest message) {

    var practitionerReference = message.getRecipientFirstRep().getReferenceElement();

    LOG.debug("practitioner reference: {}", practitionerReference);

    var practitioner =
        practitioners.stream()
            .filter(p -> p.getIdElement().getIdPart().equals(practitionerReference.getIdPart()))
            .findFirst();

    if (practitioner.isEmpty()) {
      return null;
    }

    return practitioner.get().getTelecom().stream()
        .filter(telecom -> telecom.getSystem() == ContactPointSystem.EMAIL)
        .map(ContactPoint::getValue)
        .findFirst()
        .orElse(null);
  }
}
