package com.example.paymentapi.config;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitInterceptorTest {

    private RateLimitInterceptor generalInterceptor;
    private RateLimitInterceptor loginInterceptor;

    @BeforeEach
    void setUp() {
        RateLimitProperties general = new RateLimitProperties();
        general.setLimit(5);
        general.setRefreshPeriod(60_000L);
        general.setTimeout(0L);
        generalInterceptor = new RateLimitInterceptor(general, RateLimitInterceptor.Strategy.GENERAL);

        RateLimitProperties login = new RateLimitProperties();
        login.setLimit(2);
        login.setRefreshPeriod(60_000L);
        login.setTimeout(0L);
        loginInterceptor = new RateLimitInterceptor(login, RateLimitInterceptor.Strategy.LOGIN);
    }

    @Test
    void requestUnderLimit_passes() throws Exception {
        MockHttpServletRequest request = requestFromIp("10.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(generalInterceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(response.getStatus()).isNotEqualTo(HttpServletResponse.SC_FORBIDDEN);
    }

    @Test
    void requestOverGeneralLimit_returns429() throws Exception {
        MockHttpServletRequest request = requestFromIp("10.0.0.2");
        // exhaust all 5 permits
        for (int i = 0; i < 5; i++) {
            generalInterceptor.preHandle(request, new MockHttpServletResponse(), new Object());
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean result = generalInterceptor.preHandle(request, response, new Object());
        assertThat(result).isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
    }

    @Test
    void loginStrategyHasTighterLimit() throws Exception {
        MockHttpServletRequest request = requestFromIp("10.0.0.3");
        // exhaust login limit (2) — general limit (5) would still allow more
        for (int i = 0; i < 2; i++) {
            loginInterceptor.preHandle(request, new MockHttpServletResponse(), new Object());
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean result = loginInterceptor.preHandle(request, response, new Object());
        assertThat(result).isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
    }

    @Test
    void differentIps_haveIndependentBuckets() throws Exception {
        MockHttpServletRequest ipA = requestFromIp("10.0.0.4");
        MockHttpServletRequest ipB = requestFromIp("10.0.0.5");
        // exhaust ipA
        for (int i = 0; i < 5; i++) {
            generalInterceptor.preHandle(ipA, new MockHttpServletResponse(), new Object());
        }
        // ipB should still pass
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(generalInterceptor.preHandle(ipB, response, new Object())).isTrue();
    }

    @Test
    void rateLimitHeaders_areSet() throws Exception {
        MockHttpServletRequest request = requestFromIp("10.0.0.6");
        MockHttpServletResponse response = new MockHttpServletResponse();
        generalInterceptor.preHandle(request, response, new Object());
        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("5");
        assertThat(response.getHeader("X-RateLimit-Remaining")).isNotNull();
        assertThat(response.getHeader("X-RateLimit-Reset")).isNotNull();
    }

    @Test
    void clearRateLimiters_resetsAllBuckets() throws Exception {
        MockHttpServletRequest request = requestFromIp("10.0.0.7");
        for (int i = 0; i < 5; i++) {
            generalInterceptor.preHandle(request, new MockHttpServletResponse(), new Object());
        }
        generalInterceptor.clearRateLimiters();
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(generalInterceptor.preHandle(request, response, new Object())).isTrue();
    }

    private MockHttpServletRequest requestFromIp(String ip) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr(ip);
        return req;
    }
}
