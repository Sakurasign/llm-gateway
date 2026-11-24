package com.zhouyu.gateway.domain;

import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;

public record AccountProxy(String protocol, String host, int port, String username, String password) {
    public HttpClient apply(HttpClient client) {
        return client.proxy(spec -> {
            var address = spec.type("socks5".equals(protocol) ? ProxyProvider.Proxy.SOCKS5 : ProxyProvider.Proxy.HTTP)
                    .host(host).port(port).connectTimeoutMillis(10_000);
            if (username != null) address.username(username).password(ignored -> password == null ? "" : password);
        });
    }

    @Override public String toString() { return "AccountProxy[" + protocol + "://" + host + ":" + port + "]"; }
}
