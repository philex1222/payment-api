package com.example.paymentapi.config;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Custom DNS resolver that prevents SSRF and DNS-rebinding attacks by
 * rejecting any hostname that resolves to a loopback, link-local, or
 * RFC-1918 private address. Applied at the Apache HttpClient layer so
 * the check runs on every real connection attempt — not just at subscription
 * registration time — eliminating the DNS-rebinding window.
 */
class SsrfSafeDnsResolver implements DnsResolver {

    private static final Logger logger = LoggerFactory.getLogger(SsrfSafeDnsResolver.class);
    private static final DnsResolver DELEGATE = SystemDefaultDnsResolver.INSTANCE;

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        InetAddress[] addresses = DELEGATE.resolve(host);
        for (InetAddress address : addresses) {
            if (address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isAnyLocalAddress()) {
                logger.warn("SSRF blocked: host '{}' resolved to internal address {}", host, address.getHostAddress());
                throw new UnknownHostException(
                        "SSRF policy: host '" + host + "' resolves to a private or internal network address");
            }
        }
        return addresses;
    }

    @Override
    public String resolveCanonicalHostname(String host) throws UnknownHostException {
        return DELEGATE.resolveCanonicalHostname(host);
    }
}
