package org.miracum.recruit.notify;

import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.util.BundleUtil;
import org.hl7.fhir.instance.model.api.IBaseBundle;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.ListResource;
import org.hl7.fhir.r4.model.ResearchStudy;
import org.hl7.fhir.r4.model.ResearchSubject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class FhirServerProvider {
    private static final Logger log = LoggerFactory.getLogger(NotificationController.class);

    private final IGenericClient fhirClient;

    @Autowired
    public FhirServerProvider(IGenericClient fhirClient) {
        this.fhirClient = fhirClient;
    }

    public ListResource getPreviousScreeningListFromServer(ListResource currentList) {
        var versionId = currentList.getMeta().getVersionId();

        if (versionId == null) {
            log.warn("list {} version id is null", currentList.getId());
            return null;
        }

        int lastVersionId = Integer.parseInt(versionId) - 1;
        if (lastVersionId <= 0) {
            return null;
        }

        return fhirClient.read()
                .resource(ListResource.class)
                .withIdAndVersion(currentList.getIdElement().getIdPart(), Integer.toString(lastVersionId))
                .execute();
    }

    public List<ResearchSubject> getResearchSubjectsFromList(ListResource list) {
        var listBundle = fhirClient.search()
                .forResource(ListResource.class)
                .where(ListResource.RES_ID.exactly().identifier(list.getId()))
                .include(ListResource.INCLUDE_ALL)
                .returnBundle(Bundle.class)
                .execute();

        var researchSubjectList = new ArrayList<>(BundleUtil.toListOfResourcesOfType(fhirClient.getFhirContext(),
                listBundle,
                ResearchSubject.class));

        // Load the subsequent pages
        while (listBundle.getLink(IBaseBundle.LINK_NEXT) != null) {
            listBundle = fhirClient
                    .loadPage()
                    .next(listBundle)
                    .execute();
            researchSubjectList.addAll(BundleUtil.toListOfResourcesOfType(fhirClient.getFhirContext(),
                    listBundle,
                    ResearchSubject.class));
        }

        return researchSubjectList;
    }

    public ResearchStudy getResearchStudyFromId(String id) {
        return fhirClient.read()
                .resource(ResearchStudy.class)
                .withId(id)
                .execute();
    }
}
