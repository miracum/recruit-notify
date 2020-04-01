package org.miracum.recruit.notify.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import javax.annotation.PostConstruct;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Component
public class NotificationConfiguration {
    private static final Logger LOG = LoggerFactory.getLogger(NotificationConfiguration.class);

    private List<MailNotificationRule> mail = new ArrayList<>();

    @Value("${notifications.rulePath}")
    private String configFilePath;

    @PostConstruct
    public void init() throws IOException {
        LOG.info("Reading config file.");
        var yaml = new Yaml(new Constructor(NotificationConfiguration.class));
        InputStream inputStream = null;
        try {
            inputStream = new FileInputStream(configFilePath);
        } catch (FileNotFoundException e) {
            LOG.error("Failed to read configuration file.", e);
            throw e;
        } finally {
            if (inputStream != null) {
                inputStream.close();
            }
        }
        NotificationConfiguration config = yaml.load(inputStream);
        this.mail = config.getMail();
    }

    public List<MailNotificationRule> getMail() {
        return mail;
    }

    public void setMail(List<MailNotificationRule> mail) {
        this.mail = mail;
    }
}
