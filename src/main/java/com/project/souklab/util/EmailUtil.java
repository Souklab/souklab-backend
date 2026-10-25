package com.project.souklab.util;

import com.project.souklab.config.EmailProperties;
import com.project.souklab.config.MailerSendProperties;
import com.project.souklab.config.SupportProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Component
public class EmailUtil {
    private static final Logger LOGGER = LoggerFactory.getLogger(EmailUtil.class);
    private static final String ADMINISTRATOR_NOTE_HEADER = "Administrator Note:\n";
    private static final String APPLICATION_TASK_EXECUTOR = "applicationTaskExecutor";
    private static final String SUBJECT_ACCOUNT_VERIFICATION = "Account verification code";
    private static final String SUBJECT_PASSWORD_RESET = "Password reset verification code";

    private final JavaMailSender mailSender;
    private final EmailProperties emailProperties;
    private final SupportProperties supportProperties;
    private final MailerSendProperties mailerSendProperties;
    private final RestTemplate restTemplate;

    @Autowired
    public EmailUtil(JavaMailSender mailSender,
                     EmailProperties emailProperties,
                     SupportProperties supportProperties,
                     MailerSendProperties mailerSendProperties,
                     @Qualifier("mailerSendRestTemplate") RestTemplate restTemplate) {
        this.mailSender = mailSender;
        this.emailProperties = emailProperties;
        this.supportProperties = supportProperties;
        this.mailerSendProperties = mailerSendProperties;
        this.restTemplate = restTemplate;
    }

    @Async(APPLICATION_TASK_EXECUTOR)
    public void sendVerificationCode(String toEmail, String code) {
        try {
            sendVerificationCodeSynchronous(toEmail, code);
        } catch (Exception ex) {
            LOGGER.error("Failed to send verification code email to {}", toEmail, ex);
        }
    }

    /**
     * Sends the account verification code in the caller's thread, propagating delivery failures.
     * Registration uses this variant so a user is never persisted when the verification email
     * cannot be delivered; enumeration-safe flows keep using the asynchronous fire-and-forget variant.
     */
    public void sendVerificationCodeSynchronous(String toEmail, String code) {
        String subject = SUBJECT_ACCOUNT_VERIFICATION;
        String htmlContent = "<p>Your verification code is: <strong>" + code + "</strong></p><p>This code expires soon.</p>";
        String recipient = toEmail != null ? toEmail.trim() : null;

        if (isSmtpEnabled()) {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(resolveFromAddress());
            message.setTo(recipient);
            message.setSubject(SUBJECT_ACCOUNT_VERIFICATION);
            message.setText("Your verification code is: " + code + "\nThis code expires soon.");
            mailSender.send(message);
            return;
        }

        sendViaMailerSend(recipient, subject, htmlContent);
    }

    @Async(APPLICATION_TASK_EXECUTOR)
    public void sendPasswordResetCode(String toEmail, String code) {
        String subject = SUBJECT_PASSWORD_RESET;
        String htmlContent = "<p>Your Souklab verification code is: <strong>" + code + "</strong>.</p><p>It expires in 15 minutes.</p>";
        String recipient = toEmail != null ? toEmail.trim() : null;

        if (isSmtpEnabled()) {
            try {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(resolveFromAddress());
                message.setTo(recipient);
                message.setSubject(SUBJECT_PASSWORD_RESET);
                message.setText("Your Souklab verification code is: " + code + ". It expires in 15 minutes.");
                mailSender.send(message);
            } catch (Exception ex) {
                LOGGER.error("Failed to send password reset email via SMTP to {}", toEmail, ex);
            }
            return;
        }

        try {
            sendViaMailerSend(recipient, subject, htmlContent);
        } catch (RestClientException ex) {
            LOGGER.error("Failed to send password reset email via MailerSend to {}", toEmail, ex);
        } catch (Exception ex) {
            LOGGER.error("Unexpected error while sending password reset email via MailerSend to {}", toEmail, ex);
        }
    }

    @Async(APPLICATION_TASK_EXECUTOR)
    public void sendOAuthOnlyPasswordResetNotice(String toEmail) {
        String subject = "Password reset request for Souklab account";
        String htmlContent = "<p>Hello,</p>" +
                "<p>We received a password reset request for your Souklab account.</p>" +
                "<p>Your account is linked to Google Sign-In and does not use a separate password. " +
                "Please continue signing in directly with Google.</p>" +
                "<p>If you did not request this, you can safely ignore this email.</p>";
        String recipient = toEmail != null ? toEmail.trim() : null;

        if (isSmtpEnabled()) {
            try {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(resolveFromAddress());
                message.setTo(recipient);
                message.setSubject(subject);
                message.setText("Hello,\n\nWe received a password reset request for your Souklab account.\n\n" +
                        "Your account is linked to Google Sign-In and does not use a separate password. " +
                        "Please continue signing in directly with Google.\n\n" +
                        "If you did not request this, you can safely ignore this email.");
                mailSender.send(message);
            } catch (Exception ex) {
                LOGGER.error("Failed to send OAuth password reset notice email via SMTP to {}", toEmail, ex);
            }
            return;
        }

        try {
            sendViaMailerSend(recipient, subject, htmlContent);
        } catch (RestClientException ex) {
            LOGGER.error("Failed to send OAuth password reset notice email via MailerSend to {}", toEmail, ex);
        } catch (Exception ex) {
            LOGGER.error("Unexpected error while sending OAuth password reset notice email via MailerSend to {}", toEmail, ex);
        }
    }

    @Async(APPLICATION_TASK_EXECUTOR)
    public void sendPasswordChangedNotice(String toEmail) {
        String subject = "Your Souklab password was changed";
        String htmlContent = "<p>Hello,</p>" +
                "<p>Your Souklab account password was successfully changed.</p>" +
                "<p>If you did not make this change, please contact support immediately.</p>";
        String recipient = toEmail != null ? toEmail.trim() : null;

        if (isSmtpEnabled()) {
            try {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(resolveFromAddress());
                message.setTo(recipient);
                message.setSubject(subject);
                message.setText("Hello,\n\nYour Souklab account password was successfully changed.\n\n" +
                        "If you did not make this change, please contact support immediately.");
                mailSender.send(message);
            } catch (Exception ex) {
                LOGGER.error("Failed to send password changed notice email via SMTP to {}", toEmail, ex);
            }
            return;
        }

        try {
            sendViaMailerSend(recipient, subject, htmlContent);
        } catch (RestClientException ex) {
            LOGGER.error("Failed to send password changed notice email via MailerSend to {}", toEmail, ex);
        } catch (Exception ex) {
            LOGGER.error("Unexpected error while sending password changed notice email via MailerSend to {}", toEmail, ex);
        }
    }

    @Async(APPLICATION_TASK_EXECUTOR)
    public void sendFormateurRequestSubmittedNoticeToAdmin(String adminEmail, String artisanEmail, String artisanName, String motivation) {
        String subject = "New Formateur Request Submitted";
        String motivationText = (motivation != null && !motivation.isBlank()) ? motivation : "No motivation message provided.";
        String displayName = (artisanName != null && !artisanName.isBlank()) ? artisanName + " (" + artisanEmail + ")" : artisanEmail;
        String htmlContent = "<p>Hello Admin,</p>" +
                "<p>Artisan <strong>" + displayName + "</strong> has submitted a request to become a Formateur.</p>" +
                "<p><strong>Motivation:</strong></p>" +
                "<blockquote>" + motivationText + "</blockquote>" +
                "<p>Please review the request in the administrator dashboard.</p>";
        String textContent = "Hello Admin,\n\nArtisan " + displayName + " has submitted a request to become a Formateur.\n\n" +
                "Motivation:\n" + motivationText + "\n\nPlease review the request in the administrator dashboard.";
        sendEmail(adminEmail, subject, textContent, htmlContent);
    }

    @Async(APPLICATION_TASK_EXECUTOR)
    public void sendFormateurApprovedEmail(String toEmail, String adminNote) {
        String subject = "Artisan Formateur Status Approved";
        String htmlContent = "<p>Congratulations!</p>" +
                "<p>Your request to become an official Formateur on Souklab has been approved.</p>" +
                "<p><strong>Administrator Note:</strong></p>" +
                "<blockquote>" + adminNote + "</blockquote>" +
                "<p>You may now create and publish masterclasses and workshops on the platform.</p>";
        String textContent = "Congratulations!\n\nYour request to become an official Formateur on Souklab has been approved.\n\n" +
                ADMINISTRATOR_NOTE_HEADER + adminNote + "\n\nYou may now create and publish masterclasses and workshops on the platform.";
        sendEmail(toEmail, subject, textContent, htmlContent);
    }

    @Async(APPLICATION_TASK_EXECUTOR)
    public void sendFormateurGrantedEmail(String toEmail, String adminNote) {
        String subject = "Artisan Formateur Status Granted";
        String htmlContent = "<p>Hello,</p>" +
                "<p>You have been granted official Formateur status on Souklab by an administrator.</p>" +
                "<p><strong>Administrator Note:</strong></p>" +
                "<blockquote>" + adminNote + "</blockquote>" +
                "<p>You may now create and publish masterclasses and workshops on the platform.</p>";
        String textContent = "Hello,\n\nYou have been granted official Formateur status on Souklab by an administrator.\n\n" +
                ADMINISTRATOR_NOTE_HEADER + adminNote + "\n\nYou may now create and publish masterclasses and workshops on the platform.";
        sendEmail(toEmail, subject, textContent, htmlContent);
    }

    @Async(APPLICATION_TASK_EXECUTOR)
    public void sendFormateurRejectedEmail(String toEmail, String adminNote, LocalDateTime cooldownUntil, boolean canReapply) {
        String subject = "Artisan Formateur Request Update";
        String reapplyMessage;
        if (!canReapply) {
            reapplyMessage = "You are permanently blocked from submitting new Formateur requests.";
        } else if (cooldownUntil != null) {
            reapplyMessage = "You may reapply after " + cooldownUntil + ".";
        } else {
            reapplyMessage = "You may reapply at any time.";
        }
        String htmlContent = "<p>Hello,</p>" +
                "<p>Your request to become a Formateur on Souklab was not approved at this time.</p>" +
                "<p><strong>Administrator Note:</strong></p>" +
                "<blockquote>" + adminNote + "</blockquote>" +
                "<p>" + reapplyMessage + "</p>";
        String textContent = "Hello,\n\nYour request to become a Formateur on Souklab was not approved at this time.\n\n" +
                ADMINISTRATOR_NOTE_HEADER + adminNote + "\n\n" + reapplyMessage;
        sendEmail(toEmail, subject, textContent, htmlContent);
    }

    @Async(APPLICATION_TASK_EXECUTOR)
    public void sendFormateurRevokedEmail(String toEmail, String reason) {
        String subject = "Artisan Formateur Status Revoked";
        String htmlContent = "<p>Hello,</p>" +
                "<p>Your Formateur status on Souklab has been revoked by an administrator.</p>" +
                "<p><strong>Reason:</strong></p>" +
                "<blockquote>" + reason + "</blockquote>" +
                "<p>You will no longer be able to create new formations. Existing formations remain unaffected.</p>";
        String textContent = "Hello,\n\nYour Formateur status on Souklab has been revoked by an administrator.\n\n" +
                "Reason:\n" + reason + "\n\nYou will no longer be able to create new formations. Existing formations remain unaffected.";
        sendEmail(toEmail, subject, textContent, htmlContent);
    }

    @Async(APPLICATION_TASK_EXECUTOR)
    public void sendAdminWelcomeEmail(String toEmail, String initialPassword) {
        String subject = "Welcome to Souklab - Administrator Account Initialized";
        String htmlContent = "<p>Hello Administrator,</p>" +
                "<p>Your Souklab system administrator account has been successfully initialized on first boot.</p>" +
                "<p><strong>Login Email:</strong> " + toEmail + "<br/>" +
                "<strong>Initial Password:</strong> " + initialPassword + "</p>" +
                "<p>For security, please log in and immediately change your password via the <code>/api/v1/auth/change-password</code> endpoint.</p>" +
                "<p>Best regards,<br/>Souklab Security Team</p>";
        String textContent = "Hello Administrator,\n\n" +
                "Your Souklab system administrator account has been successfully initialized on first boot.\n\n" +
                "Login Email: " + toEmail + "\n" +
                "Initial Password: " + initialPassword + "\n\n" +
                "For security, please log in and immediately change your password via the /api/v1/auth/change-password endpoint.\n\n" +
                "Best regards,\nSouklab Security Team";
        sendEmail(toEmail, subject, textContent, htmlContent);
    }

    private void sendEmail(String toEmail, String subject, String textContent, String htmlContent) {
        String recipient = toEmail != null ? toEmail.trim() : null;

        if (isSmtpEnabled()) {
            try {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(resolveFromAddress());
                message.setTo(recipient);
                message.setSubject(subject);
                message.setText(textContent);
                mailSender.send(message);
            } catch (Exception ex) {
                LOGGER.error("Failed to send email via SMTP to {}", toEmail, ex);
            }
            return;
        }

        try {
            sendViaMailerSend(recipient, subject, htmlContent);
        } catch (RestClientException ex) {
            LOGGER.error("Failed to send email via MailerSend to {}", toEmail, ex);
        } catch (Exception ex) {
            LOGGER.error("Unexpected error while sending email via MailerSend to {}", toEmail, ex);
        }
    }

    private String resolveFromAddress() {
        if (emailProperties != null && emailProperties.getFrom() != null
                && !emailProperties.getFrom().isBlank()) {
            return emailProperties.getFrom().trim();
        }
        if (supportProperties != null && supportProperties.getEmail() != null
                && !supportProperties.getEmail().isBlank()) {
            return supportProperties.getEmail().trim();
        }
        if (mailerSendProperties != null && mailerSendProperties.getSenderEmail() != null
                && !mailerSendProperties.getSenderEmail().isBlank()) {
            return mailerSendProperties.getSenderEmail().trim();
        }
        return "noreply@souklab.dz";
    }

    private boolean isSmtpEnabled() {
        return emailProperties != null && emailProperties.isUseSmtp();
    }

    private void sendViaMailerSend(String recipientEmail, String yourSubjectVariable, String yourHtmlContentVariable) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + mailerSendProperties.getApiKey());
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> payload = Map.of(
                "from", Map.of("email", mailerSendProperties.getSenderEmail(), "name", mailerSendProperties.getSenderName()),
                "to", List.of(Map.of("email", recipientEmail)),
                "subject", yourSubjectVariable,
                "html", yourHtmlContentVariable
        );

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);
        restTemplate.postForEntity(mailerSendProperties.getApiUrl(), entity, String.class);
    }
}
