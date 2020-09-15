package org.miracum.recruit.notify;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "notify.rules")
public class NotificationRuleConfig {

  List<MailNotificationRule> mail;

  public List<MailNotificationRule> getMail() {
    return mail;
  }

  public NotificationRuleConfig setMail(List<MailNotificationRule> mail) {
    this.mail = mail;
    return this;
  }

  @Override
  public String toString() {
    return "NotificationRuleConfig{" + "mail=" + mail + '}';
  }

  public static class MailNotificationRule {

    private String acronym;
    private String from;
    private List<String> to;

    public String getAcronym() {
      return acronym;
    }

    public void setAcronym(String acronym) {
      this.acronym = acronym;
    }

    public List<String> getTo() {
      return to;
    }

    public void setTo(List<String> receivers) {
      this.to = receivers;
    }

    public String getFrom() {
      return from;
    }

    public void setFrom(String from) {
      this.from = from;
    }

    @Override
    public String toString() {
      return "MailNotificationRule{"
          + "acronym='"
          + acronym
          + '\''
          + ", from='"
          + from
          + '\''
          + ", to="
          + to
          + '}';
    }
  }
}
