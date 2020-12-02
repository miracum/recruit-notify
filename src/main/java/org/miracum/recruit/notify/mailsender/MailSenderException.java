package org.miracum.recruit.notify.mailsender;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Exception containing cause of failing during preparation or sending email with smtp. */
public class MailSenderException extends Exception {

  private static final long serialVersionUID = -8679268580116111661L;
  private static final Logger LOG = LoggerFactory.getLogger(MailSenderException.class);

  public MailSenderException(String message, Throwable cause) {
    super(message, cause);
    LOG.error("{}{}", message, System.lineSeparator(), cause);
  }
}
