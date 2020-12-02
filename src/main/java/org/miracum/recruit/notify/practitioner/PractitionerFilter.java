package org.miracum.recruit.notify.practitioner;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.logging.log4j.util.Strings;
import org.hl7.fhir.r4.model.Practitioner;
import org.miracum.recruit.notify.fhirserver.FhirSystemsConfig;
import org.miracum.recruit.notify.logging.LogMethodCalls;
import org.miracum.recruit.notify.mailconfig.UserConfig.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Data structure to distinguish receipients that will receive notification ad hoc (just in time) or
 * delayed because of assigned to timer event in app config.
 */
@Service
public class PractitionerFilter {

  private static final Logger LOG = LoggerFactory.getLogger(PractitionerFilter.class);
  private final FhirSystemsConfig fhirSystemsConfig;

  @Autowired
  public PractitionerFilter(FhirSystemsConfig fhirSystemsConfig) {
    this.fhirSystemsConfig = fhirSystemsConfig;
  }

  /**
   * Divide given practitioner list and given subscriptions from config to divide practitioners in
   * those who will receive an email just in time and those who have subscribed to special timer
   * event that triggers sending the emails.
   */
  @LogMethodCalls
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

        var identifier =
            practitioner.getIdentifier().stream()
                .filter(
                    rule ->
                        rule.getSystem().equals(fhirSystemsConfig.getSubscriberSystem())
                            && rule.getValue().equals(subscription.getEmail()))
                .findFirst();

        if (identifier.isPresent()) {
          recipients.add(practitioner);
        }
      }
    }

    return recipients;
  }

  @LogMethodCalls
  private List<Subscription> filterAdHocSubscriptions(List<Subscription> listSubscriptions) {
    return listSubscriptions.stream()
        .filter(subscription -> Strings.isBlank(subscription.getNotify()))
        .collect(Collectors.toList());
  }

  @LogMethodCalls
  private List<Subscription> filterDelayedSubscriptions(List<Subscription> listSubscriptions) {
    return listSubscriptions.stream()
        .filter(subscription -> Strings.isNotBlank(subscription.getNotify()))
        .collect(Collectors.toList());
  }
}
