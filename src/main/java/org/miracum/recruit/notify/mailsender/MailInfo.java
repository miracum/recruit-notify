package org.miracum.recruit.notify.mailsender;

/**
 * Transient data structure contains email from and to and also subject that should be used in email
 * template.
 */
public class MailInfo {

  String from;
  String to;
  String subject;

  public String getFrom() {
    return from;
  }

  public void setFrom(String from) {
    this.from = from;
  }

  public String getTo() {
    return to;
  }

  public void setTo(String to) {
    this.to = to;
  }

  public String getSubject() {
    return subject;
  }

  public void setSubject(String subject) {
    this.subject = subject;
  }
}
