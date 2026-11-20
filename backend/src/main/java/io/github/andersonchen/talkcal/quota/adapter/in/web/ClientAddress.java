package io.github.andersonchen.talkcal.quota.adapter.in.web;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * 照 IP 計數時用的 key。
 *
 * 來源用 request.getRemoteAddr()，不自己讀 X-Forwarded-For：server.forward-headers-strategy=native 時，
 * Tomcat 只在請求來自內部網段（nginx、Caddy）時才照 X-Forwarded-For 改寫 remoteAddr，而且從右邊數回來，
 * 跳過內部網段，取第一個外部的位址，使用者自己在最左邊塞的假位址輪不到。
 * 自己讀這個 header 的話，取最左邊會被偽造，取最右邊在有 Caddy 時又會變成 Caddy 的位址。
 */
final class ClientAddress {

    private ClientAddress() {
    }

    /**
     * IPv6 的使用者通常分到一整個 /64，裡面的位址可以隨意換（手機的 privacy extension 也會自己換），
     * 照完整位址算的話，換一個就又有新的次數，所以 IPv6 只看前 64 位元。無法解析的位址直接當成 key
     */
    static String key(String remoteAddr) {
        try {
            if (InetAddress.ofLiteral(remoteAddr) instanceof Inet6Address v6) {
                byte[] prefix = v6.getAddress();
                Arrays.fill(prefix, 8, 16, (byte) 0);
                return InetAddress.getByAddress(prefix).getHostAddress() + "/64";
            }
            return remoteAddr;
        } catch (IllegalArgumentException | UnknownHostException e) {
            return remoteAddr;
        }
    }
}
