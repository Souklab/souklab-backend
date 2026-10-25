package com.project.souklab.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/** Configures bounded HTTP transport for the optional MailerSend integration. */
@Configuration
public class MailerSendConfiguration {

    /**
     * Creates the MailerSend client with externally configured connection and read timeouts.
     *
     * @param mailerSendProperties application configuration containing MailerSend timeouts
     * @return bounded MailerSend HTTP client
     */
    @Bean(name = "mailerSendRestTemplate")
    public RestTemplate mailerSendRestTemplate(MailerSendProperties mailerSendProperties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(mailerSendProperties.getConnectionTimeout());
        requestFactory.setReadTimeout(mailerSendProperties.getReadTimeout());
        return new RestTemplate(requestFactory);
    }
}
