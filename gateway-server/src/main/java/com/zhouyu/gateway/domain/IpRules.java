package com.zhouyu.gateway.domain;

import io.netty.util.NetUtil;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;

/** Literal IP addresses and CIDR ranges. Deny rules take precedence. */
public record IpRules(List<Range> allow, List<Range> deny) {
    public static final IpRules NONE = new IpRules(List.of(), List.of());

    public static IpRules parse(List<String> allow, List<String> deny) {
        return new IpRules(parse(allow), parse(deny));
    }

    private static List<Range> parse(List<String> values) {
        if (values == null) return List.of();
        if (values.size() > 64) throw new IllegalArgumentException("每个 IP 名单最多 64 条");
        return values.stream().map(Range::parse).toList();
    }

    public boolean permits(InetSocketAddress peer) {
        if (allow.isEmpty() && deny.isEmpty()) return true;
        if (peer == null || peer.getAddress() == null) return false;
        byte[] address = peer.getAddress().getAddress();
        return deny.stream().noneMatch(rule -> rule.matches(address))
                && (allow.isEmpty() || allow.stream().anyMatch(rule -> rule.matches(address)));
    }

    public record Range(byte[] address, int prefix) {
        private static Range parse(String value) {
            if (value == null) throw invalid();
            var parts = value.strip().split("/", -1);
            String literal = parts[0];
            if (parts.length > 2 || !literal.matches("[0-9a-fA-F:.]{1,45}")) throw invalid();
            byte[] address = NetUtil.createByteArrayFromIpAddressString(literal);
            if (address == null) throw invalid();
            int prefix = address.length * 8;
            if (parts.length == 2) {
                if (!parts[1].matches("[0-9]{1,3}")) throw invalid();
                prefix = Integer.parseInt(parts[1]);
                if (prefix > address.length * 8) throw invalid();
            }
            if (address.length == 16 && prefix >= 96 && Arrays.equals(address, 0, 12,
                    new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1}, 0, 12)) {
                return new Range(Arrays.copyOfRange(address, 12, 16), prefix - 96);
            }
            return new Range(address, prefix);
        }

        private static IllegalArgumentException invalid() {
            return new IllegalArgumentException("请输入有效的 IPv4、IPv6 或 CIDR 网段");
        }

        private boolean matches(byte[] peer) {
            if (address.length != peer.length) return false;
            int bytes = prefix / 8;
            if (!Arrays.equals(address, 0, bytes, peer, 0, bytes)) return false;
            int rest = prefix % 8;
            return rest == 0 || ((address[bytes] ^ peer[bytes]) & (0xFF << (8 - rest))) == 0;
        }
    }
}
