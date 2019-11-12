package org.miracum.recruit.notify.config;

import java.util.List;

public class MailNotificationRule {
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
}
