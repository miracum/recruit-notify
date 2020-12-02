package org.miracum.recruit.notify.fhirserver;

/** Update Communication Requests in target FHIR server. */
public interface FhirCommunicationUpdater {

  void update(String relativeId);
}
