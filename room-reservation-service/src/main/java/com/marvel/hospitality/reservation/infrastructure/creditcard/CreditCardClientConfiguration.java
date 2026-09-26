package com.marvel.hospitality.reservation.infrastructure.creditcard;

import com.marvel.hospitality.reservation.infrastructure.creditcard.generated.ApiClient;
import com.marvel.hospitality.reservation.infrastructure.creditcard.generated.api.DefaultApi;
import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Builds the client generated from docs/contracts/credit-card-payment-api.yaml on top of Boot's
 * {@link RestClient.Builder} (so it gets Boot's Jackson 3 mapper and HTTP observations), with this client's own
 * connect/read timeouts on a JDK {@code HttpClient}.
 *
 * <p>The client is pinned to HTTP/1.1. The JDK client otherwise prefers HTTP/2 and, on plain {@code http}, offers an
 * {@code Upgrade: h2c} on each new connection; the payment service speaks HTTP/1.1, so the offer buys nothing and
 * only adds a protocol negotiation that behaves differently per server (it made the WireMock-based tests flaky).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CreditCardClientProperties.class)
class CreditCardClientConfiguration {

    @Bean
    DefaultApi creditCardPaymentApi(RestClient.Builder restClientBuilder, CreditCardClientProperties properties) {
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withTimeouts(properties.connectTimeout(), properties.readTimeout());
        RestClient restClient = restClientBuilder
                .requestFactory(ClientHttpRequestFactoryBuilder.jdk()
                        .withHttpClientCustomizer(httpClient -> httpClient.version(HttpClient.Version.HTTP_1_1))
                        .build(settings))
                .build();
        ApiClient apiClient = new ApiClient(restClient);
        apiClient.setBasePath(properties.baseUrl().toString());
        return new DefaultApi(apiClient);
    }
}
