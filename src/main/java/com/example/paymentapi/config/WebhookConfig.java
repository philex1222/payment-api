package com.example.paymentapi.config;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.concurrent.TimeUnit;

/**
 * Configures the dedicated {@link RestClient} used for outbound webhook deliveries.
 *
 * <ul>
 *   <li><b>SSRF / DNS-rebinding protection</b> — the connection manager uses
 *       {@link SsrfSafeDnsResolver}, which rejects hostnames that resolve to
 *       loopback, link-local, or RFC-1918 addresses on every real connection attempt.</li>
 *   <li><b>Timeouts</b> — 5 s connect, 10 s response, so a slow or adversarial
 *       target cannot stall dispatcher threads indefinitely.</li>
 * </ul>
 */
@Configuration
public class WebhookConfig {

    @Bean
    public RestClient webhookRestClient(RestClient.Builder builder) {
        // Connect timeout is set at the socket level (HttpClient 5.3+).
        // RequestConfig.setConnectTimeout(Timeout) is deprecated — the replacement
        // is SocketConfig.setSoTimeout on the connection manager's default socket config.
        var socketConfig = SocketConfig.custom()
                .setSoTimeout(Timeout.of(5, TimeUnit.SECONDS))
                .build();

        var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(new SsrfSafeDnsResolver())
                .setDefaultSocketConfig(socketConfig)
                .build();

        var httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(RequestConfig.custom()
                        // setConnectionRequestTimeout: time waiting for a connection from the pool
                        .setConnectionRequestTimeout(Timeout.of(5, TimeUnit.SECONDS))
                        .setResponseTimeout(Timeout.of(10, TimeUnit.SECONDS))
                        .build())
                .build();

        return builder
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                .build();
    }
}
