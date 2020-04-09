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
        try (InputStream inputStream = new FileInputStream(configFilePath)) {
            NotificationConfiguration config = yaml.load(inputStream);
            this.mail = config.getMail();
            LOG.info("Notification config loaded successfully");
        } catch (FileNotFoundException e) {
            LOG.error("Configuration file not found. Searched at {}", configFilePath, e);
            throw e;
        } catch (IOException e) {
            LOG.error("Failed to load configuration. Using {}", configFilePath, e);
            throw e;
        }
    }

    public List<MailNotificationRule> getMail() {
        return mail;
    }

    public void setMail(List<MailNotificationRule> mail) {
        this.mail = mail;
    }
}
