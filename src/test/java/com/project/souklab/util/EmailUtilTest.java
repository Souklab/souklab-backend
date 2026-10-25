package com.project.souklab.util;

import org.mockito.ArgumentCaptor;

import com.project.souklab.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EmailUtilTest {
    @Test
    void sendsAllSmtpNotificationsWithExpectedRecipientsAndSubjects() {
        JavaMailSender sender = mock(JavaMailSender.class);
        AppProperties properties = properties(true);
        EmailUtil email = new EmailUtil(sender, properties, mock(RestTemplate.class));

        email.sendVerificationCode("verify@test", "123");
        email.sendPasswordResetCode("reset@test", "456");
        email.sendOAuthOnlyPasswordResetNotice("oauth@test");
        email.sendPasswordChangedNotice("changed@test");
        email.sendFormateurRequestSubmittedNoticeToAdmin("admin@test", "artisan@test", "A Name", "motivation");
        email.sendFormateurApprovedEmail("approved@test", "approved note");
        email.sendFormateurGrantedEmail("granted@test", "granted note");
        email.sendFormateurRejectedEmail("rejected@test", "rejected note", LocalDateTime.of(2026, 1, 2, 3, 4), true);
        email.sendFormateurRejectedEmail("blocked@test", null, null, false);
        email.sendFormateurRevokedEmail("revoked@test", "reason");
        email.sendAdminWelcomeEmail("admin@test", "initial");

        var captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender, times(11)).send(captor.capture());
        assertThat(captor.getAllValues()).extracting(SimpleMailMessage::getTo)
                .containsExactlyInAnyOrder(
                        new String[]{"verify@test"}, new String[]{"reset@test"}, new String[]{"oauth@test"},
                        new String[]{"changed@test"}, new String[]{"admin@test"}, new String[]{"approved@test"},
                        new String[]{"granted@test"}, new String[]{"rejected@test"}, new String[]{"blocked@test"},
                        new String[]{"revoked@test"}, new String[]{"admin@test"});
        assertThat(captor.getAllValues()).extracting(SimpleMailMessage::getFrom)
                .containsOnly("support@souklab.dz");
    }

    @Test
    void suppressesSmtpFailures() {
        JavaMailSender sender = mock(JavaMailSender.class);
        doThrow(new IllegalStateException("mail unavailable")).when(sender).send(any(SimpleMailMessage.class));
        EmailUtil email = new EmailUtil(sender, properties(true), mock(RestTemplate.class));

        email.sendVerificationCode("to@test", "code");
        email.sendPasswordResetCode("to@test", "code");
        email.sendOAuthOnlyPasswordResetNotice("to@test");
        email.sendPasswordChangedNotice("to@test");
        email.sendFormateurApprovedEmail("to@test", "note");

        verify(sender, times(5)).send(any(SimpleMailMessage.class));
    }

    @Test
    void sendsViaMailerSendAndSuppressesClientAndUnexpectedFailures() {
        AppProperties properties = properties(false);
        RestTemplate rest = mock(RestTemplate.class);
        {
            EmailUtil email = new EmailUtil(mock(JavaMailSender.class), properties, rest);
            when(rest.postForEntity(any(String.class), any(), eq(String.class))).thenReturn(null);

            email.sendVerificationCode("to@test", "code");
            verify(rest).postForEntity(eq("https://mailer.test/send"), any(), eq(String.class));

            reset(rest);
            when(rest.postForEntity(any(String.class), any(), eq(String.class)))
                    .thenThrow(new RestClientException("client failure"));
            email.sendPasswordResetCode("to@test", "code");

            reset(rest);
            when(rest.postForEntity(any(String.class), any(), eq(String.class)))
                    .thenThrow(new IllegalStateException("unexpected failure"));
            email.sendOAuthOnlyPasswordResetNotice("to@test");
            email.sendPasswordChangedNotice("to@test");

            reset(rest);
            when(rest.postForEntity(any(String.class), any(), eq(String.class))).thenReturn(null);
            email.sendFormateurRequestSubmittedNoticeToAdmin("admin@test", "artisan@test", "", "");
            email.sendFormateurApprovedEmail("to@test", "note");
            email.sendFormateurGrantedEmail("to@test", "note");
            email.sendFormateurRejectedEmail("to@test", "note", null, true);
            email.sendFormateurRejectedEmail("to@test", "note", LocalDateTime.of(2026, 1, 2, 3, 4), true);
            email.sendFormateurRejectedEmail("to@test", "note", null, false);
            email.sendFormateurRevokedEmail("to@test", "reason");
            email.sendAdminWelcomeEmail("to@test", "initial");
            verify(rest, times(8)).postForEntity(eq("https://mailer.test/send"), any(), eq(String.class));
        }
    }

    @Test
    void suppressesBothMailerSendFailureCategoriesAcrossAllEmailFlows() {
        AppProperties properties = properties(false);
        RestTemplate rest = mock(RestTemplate.class);
        {
            EmailUtil email = new EmailUtil(mock(JavaMailSender.class), properties, rest);

            when(rest.postForEntity(any(String.class), any(), eq(String.class)))
                    .thenThrow(new RestClientException("provider unavailable"));
            email.sendVerificationCode("to@test", "code");
            email.sendPasswordResetCode("to@test", "code");
            email.sendOAuthOnlyPasswordResetNotice("to@test");
            email.sendPasswordChangedNotice("to@test");
            email.sendFormateurApprovedEmail("to@test", "note");

            reset(rest);
            when(rest.postForEntity(any(String.class), any(), eq(String.class)))
                    .thenThrow(new IllegalArgumentException("invalid provider response"));
            email.sendVerificationCode("to@test", "code");
            email.sendPasswordResetCode("to@test", "code");
            email.sendOAuthOnlyPasswordResetNotice("to@test");
            email.sendPasswordChangedNotice("to@test");
            email.sendFormateurApprovedEmail("to@test", "note");
        }
    }

    @Test
    void sendVerificationCodeSynchronous_propagatesSmtpFailure() {
        JavaMailSender sender = mock(JavaMailSender.class);
        doThrow(new IllegalStateException("mail unavailable")).when(sender).send(any(SimpleMailMessage.class));
        EmailUtil email = new EmailUtil(sender, properties(true), mock(RestTemplate.class));

        assertThatThrownBy(() -> email.sendVerificationCodeSynchronous("to@test", "code"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("mail unavailable");
    }

    @Test
    void sendVerificationCodeSynchronous_sendsViaMailerSendAndPropagatesFailure() {
        AppProperties properties = properties(false);
        RestTemplate rest = mock(RestTemplate.class);
        EmailUtil email = new EmailUtil(mock(JavaMailSender.class), properties, rest);

        email.sendVerificationCodeSynchronous("to@test", "code");
        verify(rest).postForEntity(eq("https://mailer.test/send"), any(), eq(String.class));

        reset(rest);
        when(rest.postForEntity(any(String.class), any(), eq(String.class)))
                .thenThrow(new RestClientException("provider unavailable"));
        assertThatThrownBy(() -> email.sendVerificationCodeSynchronous("to@test", "code"))
                .isInstanceOf(RestClientException.class);
    }

    @Test
    void fallsBackToSenderEmailOrNoreplyWhenSupportEmailNotConfigured() {
        JavaMailSender sender = mock(JavaMailSender.class);
        AppProperties properties = new AppProperties();
        properties.getEmail().setUseSmtp(true);
        properties.getMailersend().setSenderEmail("mailer@test");
        EmailUtil email = new EmailUtil(sender, properties, mock(RestTemplate.class));

        email.sendVerificationCode("user@test", "123");
        var captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(captor.capture());
        assertThat(captor.getValue().getFrom()).isEqualTo("mailer@test");

        properties.getMailersend().setSenderEmail(null);
        var secondCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        email.sendVerificationCode("user2@test", "456");
        verify(sender, times(2)).send(secondCaptor.capture());
        assertThat(secondCaptor.getValue().getFrom()).isEqualTo("noreply@souklab.dz");
    }

    private AppProperties properties(boolean smtp) {
        AppProperties properties = new AppProperties();
        properties.getEmail().setUseSmtp(smtp);
        properties.getSupport().setEmail("support@souklab.dz");
        properties.getMailersend().setApiKey("api-key");
        properties.getMailersend().setApiUrl("https://mailer.test/send");
        properties.getMailersend().setSenderEmail("sender@test");
        properties.getMailersend().setSenderName("Souklab");
        return properties;
    }
}
