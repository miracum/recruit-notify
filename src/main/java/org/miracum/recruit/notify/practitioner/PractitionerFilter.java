package org.miracum.recruit.notify.practitioner;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.logging.log4j.util.Strings;
import org.hl7.fhir.r4.model.ContactPoint.ContactPointSystem;
import org.hl7.fhir.r4.model.Practitioner;
import org.miracum.recruit.notify.mailconfig.UserConfig.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Data structure to distinguish recipients that will receive notification ad hoc (just in time) or
 * delayed because of assigned to timer event in app config.
 */
@Service
public class PractitionerFilter {

  private static final Logger LOG = LoggerFactory.getLogger(PractitionerFilter.class);

  /**
   * Divide given practitioner list and given subscriptions from config to divide practitioners in
   * those who will receive an email just in time and those who have subscribed to special timer
   * event that triggers sending the emails.
   */
  public PractitionerListContainer dividePractitioners(
      List<Subscription> listSubscriptions, List<Practitioner> practitionerList) {

    var filteredAdHocSubscriptions = filterAdHocSubscriptions(listSubscriptions);
    var filteredDelayedSubscriptions = filterDelayedSubscriptions(listSubscriptions);

    PractitionerListContainer practitionerListContainer = new PractitionerListContainer();

    practitionerListContainer.setAdHocRecipients(
        extractRecipients(filteredAdHocSubscriptions, practitionerList));
    practitionerListContainer.setScheduledRecipients(
        extractRecipients(filteredDelayedSubscriptions, practitionerList));

    LOG.debug(
        "number of adhoc recipients: {}", practitionerListContainer.getAdHocRecipients().size());
    LOG.debug(
        "number of scheduled recipients: {}",
        practitionerListContainer.getScheduledRecipients().size());

    return practitionerListContainer;
  }

  private List<Practitioner> extractRecipients(
      List<Subscription> filteredSubscriptions, List<Practitioner> practitionerList) {

    var recipients = new ArrayList<Practitioner>();
    for (var practitioner : practitionerList) {
      for (var subscription : filteredSubscriptions) {
        var contactPoint =
            practitioner.getTelecom().stream()
                .filter(
                    com ->
                        com.getSystem().equals(ContactPointSystem.EMAIL)
                            && com.getValue().equals(subscription.getEmail()))
                .findFirst();

        if (contactPoint.isPresent()) {
          recipients.add(practitioner);
        }
      }
    }

    return recipients;
  }

  private List<Subscription> filterAdHocSubscriptions(List<Subscription> listSubscriptions) {
    return listSubscriptions.stream()
        .filter(subscription -> Strings.isBlank(subscription.getNotify()))
        .collect(Collectors.toList());
  }

  private List<Subscription> filterDelayedSubscriptions(List<Subscription> listSubscriptions) {
    return listSubscriptions.stream()
        .filter(subscription -> Strings.isNotBlank(subscription.getNotify()))
        .collect(Collectors.toList());
  }
}
