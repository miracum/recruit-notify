package org.miracum.recruit.notify.mailsender;

/** Data structure to store study acronym and referring screening list link. */
public class NotifyInfo {

  String studyAcronym;
  String screeningListLink;

  public String getStudyAcronym() {
    return studyAcronym;
  }

  public void setStudyAcronym(String studyAcronym) {
    this.studyAcronym = studyAcronym;
  }

  public String getScreeningListLink() {
    return screeningListLink;
  }

  public void setScreeningListLink(String screeningListLink) {
    this.screeningListLink = screeningListLink;
  }

  @Override
  public String toString() {
    return "NotifyInfo [studyAcronym="
        + studyAcronym
        + ", screeningListLink="
        + screeningListLink
        + "]";
  }
}
