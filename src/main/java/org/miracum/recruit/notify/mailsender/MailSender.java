package org.miracum.recruit.notify.mailsender;

import javax.mail.MessagingException;
import javax.mail.internet.AddressException;
import javax.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/** Message will be prepared and will be sent by java mail sender and thymeleaf template. */
public class MailSender {

  private static final Logger LOG = LoggerFactory.getLogger(MailSender.class);

  private final JavaMailSender javaMailSender;

  private final TemplateEngine templateEngine;

  public MailSender(JavaMailSender javaMailSender, TemplateEngine templateEngine) {
    this.javaMailSender = javaMailSender;
    this.templateEngine = templateEngine;
  }

  public boolean sendMail(NotifyInfo notifyInfo, MailInfo mailInfo) {

    var isSentSuccessfully = false;

    try {
      MimeMessage mimeMessage = prepareMessage(notifyInfo, mailInfo);
      javaMailSender.send(mimeMessage);
      isSentSuccessfully = true;
    } catch (MailSendException e) {
      LOG.error(
          "mails could not be send, please check if server is available and credentials "
              + "are valid, sending will be aborted for (receiver: {}, trial: {})",
          mailInfo.to,
          notifyInfo.studyAcronym);
    } catch (MailSenderException e) {
      LOG.error(
          "could not create message object, sending will be aborted for (receiver: {}, trial: {})",
          mailInfo.to,
          notifyInfo.studyAcronym);
    } catch (MailAuthenticationException e) {
      LOG.error("mail authentication failed, please check");
    }

    return isSentSuccessfully;
  }

  private MimeMessage prepareMessage(NotifyInfo notifyInfo, MailInfo mailInfo)
      throws MailSenderException {
    MimeMessage mimeMessage = javaMailSender.createMimeMessage();

    MimeMessageHelper messageHelper;
    try {
      messageHelper = new MimeMessageHelper(mimeMessage, true, "UTF-8");

      messageHelper.setSubject(mailInfo.getSubject());
      messageHelper.setFrom(mailInfo.getFrom());
      messageHelper.setTo(mailInfo.getTo());

      var ctx = new Context();
      ctx.setVariable("studyName", notifyInfo.getStudyAcronym());
      ctx.setVariable("screeningListUrl", notifyInfo.getScreeningListLink());

      var textContent = templateEngine.process("notification-mail.txt", ctx);
      var htmlContent = templateEngine.process("notification-mail.html", ctx);

      messageHelper.setText(textContent, htmlContent);
    } catch (AddressException e) {
      throw new MailSenderException("mail address not valid", e);
    } catch (MessagingException e) {
      throw new MailSenderException("messaging exception", e);
    }

    return mimeMessage;
  }
}
