package dev.djoxer.netmonitor.vpn;

import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves IP → hostname via DNS response sniffing and TLS SNI.
 */
public class HostnameResolver {

    private static final String TAG = "HostnameResolver";

    // IP string → hostname
    private final Map<String, String> ipToHost = new ConcurrentHashMap<>();

    public String getHostname(String ip) {
        return ipToHost.get(ip);
    }

    public void put(String ip, String host) {
        if (ip == null || host == null || host.isEmpty()) return;
        // ignore obvious junk
        if (host.endsWith(".local") || host.equals("localhost")) return;
        ipToHost.put(ip, host);
    }

    public int size() {
        return ipToHost.size();
    }

    public void clear() {
        ipToHost.clear();
    }

    /**
     * Inspect a raw IPv4 packet (full IP frame) for DNS answers or TLS SNI.
     */
    public void inspectPacket(byte[] data, int length) {
        if (length < 20) return;
        int version = (data[0] >> 4) & 0x0F;
        if (version != 4) return;

        int ipHeaderLen = (data[0] & 0x0F) * 4;
        if (length < ipHeaderLen + 4) return;

        int protocol = data[9] & 0xFF;

        if (protocol == 17) { // UDP – possible DNS
            inspectDns(data, length, ipHeaderLen);
        } else if (protocol == 6) { // TCP – possible TLS ClientHello
            inspectTlsSni(data, length, ipHeaderLen);
        }
    }

    // ---------------------------------------------------------------------
    // DNS (UDP/53 responses)
    // ---------------------------------------------------------------------

    private void inspectDns(byte[] data, int length, int ipHeaderLen) {
        if (length < ipHeaderLen + 8 + 12) return;

        int srcPort = ((data[ipHeaderLen] & 0xFF) << 8) | (data[ipHeaderLen + 1] & 0xFF);
        int dstPort = ((data[ipHeaderLen + 2] & 0xFF) << 8) | (data[ipHeaderLen + 3] & 0xFF);

        // DNS response usually comes FROM port 53
        boolean fromDns = (srcPort == 53);
        boolean toDns = (dstPort == 53);
        if (!fromDns && !toDns) return;

        int dnsOffset = ipHeaderLen + 8;
        if (length < dnsOffset + 12) return;

        try {
            // Only parse responses (QR bit = 1)
            int flags = ((data[dnsOffset + 2] & 0xFF) << 8) | (data[dnsOffset + 3] & 0xFF);
            boolean isResponse = (flags & 0x8000) != 0;
            if (!isResponse) return;

            int questions = ((data[dnsOffset + 4] & 0xFF) << 8) | (data[dnsOffset + 5] & 0xFF);
            int answers = ((data[dnsOffset + 6] & 0xFF) << 8) | (data[dnsOffset + 7] & 0xFF);
            if (answers == 0) return;

            int pos = dnsOffset + 12;

            // Skip questions
            for (int i = 0; i < questions; i++) {
                pos = skipDnsName(data, length, pos);
                if (pos < 0 || pos + 4 > length) return;
                pos += 4; // type + class
            }

            // Parse answers – look for A records (type 1)
            for (int i = 0; i < answers; i++) {
                int nameStart = pos;
                pos = skipDnsName(data, length, pos);
                if (pos < 0 || pos + 10 > length) return;

                int type = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
                int rdLength = ((data[pos + 8] & 0xFF) << 8) | (data[pos + 9] & 0xFF);
                pos += 10;

                if (pos + rdLength > length) return;

                if (type == 1 && rdLength == 4) { // A record
                    String ip = (data[pos] & 0xFF) + "." + (data[pos + 1] & 0xFF) + "." +
                            (data[pos + 2] & 0xFF) + "." + (data[pos + 3] & 0xFF);
                    String host = readDnsName(data, length, nameStart, dnsOffset);
                    if (host != null && !host.isEmpty()) {
                        put(ip, host);
                    }
                }
                pos += rdLength;
            }
        } catch (Exception e) {
            Log.w(TAG, "DNS parse error", e);
        }
    }

    private int skipDnsName(byte[] data, int length, int pos) {
        int jumps = 0;
        while (pos < length) {
            int label = data[pos] & 0xFF;
            if (label == 0) return pos + 1;
            // compression pointer
            if ((label & 0xC0) == 0xC0) return pos + 2;
            pos += 1 + label;
            if (++jumps > 30) return -1;
        }
        return -1;
    }

    private String readDnsName(byte[] data, int length, int pos, int dnsOffset) {
        StringBuilder sb = new StringBuilder();
        int jumps = 0;
        int guard = 0;

        while (pos < length && guard++ < 64) {
            int label = data[pos] & 0xFF;
            if (label == 0) break;

            if ((label & 0xC0) == 0xC0) {
                if (pos + 1 >= length) break;
                int pointer = ((label & 0x3F) << 8) | (data[pos + 1] & 0xFF);
                pos = dnsOffset + pointer;
                if (++jumps > 10) break;
                continue;
            }

            pos++;
            if (pos + label > length) break;
            if (sb.length() > 0) sb.append('.');
            sb.append(new String(data, pos, label, StandardCharsets.UTF_8));
            pos += label;
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------------
    // TLS SNI (ClientHello)
    // ---------------------------------------------------------------------

    private void inspectTlsSni(byte[] data, int length, int ipHeaderLen) {
        if (length < ipHeaderLen + 20) return;

        int dataOffset = ((data[ipHeaderLen + 12] & 0xF0) >> 4) * 4;
        int payloadOffset = ipHeaderLen + dataOffset;
        int payloadLen = length - payloadOffset;
        if (payloadLen < 40) return;

        // TLS record: ContentType=0x16 (Handshake), version 0x0301..0x0303
        if ((data[payloadOffset] & 0xFF) != 0x16) return;
        int tlsMajor = data[payloadOffset + 1] & 0xFF;
        int tlsMinor = data[payloadOffset + 2] & 0xFF;
        if (tlsMajor != 0x03) return;

        int recordLen = ((data[payloadOffset + 3] & 0xFF) << 8) | (data[payloadOffset + 4] & 0xFF);
        int hsStart = payloadOffset + 5;
        if (hsStart + 4 > length) return;

        // HandshakeType = 0x01 (ClientHello)
        if ((data[hsStart] & 0xFF) != 0x01) return;

        try {
            String sni = extractSni(data, hsStart, Math.min(length, hsStart + recordLen));
            if (sni == null || sni.isEmpty()) return;

            // Destination IP of this packet is the server
            String destIp = (data[16] & 0xFF) + "." + (data[17] & 0xFF) + "." +
                    (data[18] & 0xFF) + "." + (data[19] & 0xFF);
            put(destIp, sni);
        } catch (Exception e) {
            Log.w(TAG, "SNI parse error", e);
        }
    }

    private String extractSni(byte[] data, int hsStart, int end) {
        // ClientHello structure (after handshake header 4 bytes):
        // bodyLen already in header; skip: version(2) random(32) sessionId sessionIdLen(1)
        int pos = hsStart + 4;
        if (pos + 2 + 32 + 1 > end) return null;

        pos += 2;  // client version
        pos += 32; // random

        int sessionIdLen = data[pos] & 0xFF;
        pos += 1 + sessionIdLen;
        if (pos + 2 > end) return null;

        int cipherLen = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
        pos += 2 + cipherLen;
        if (pos + 1 > end) return null;

        int compLen = data[pos] & 0xFF;
        pos += 1 + compLen;
        if (pos + 2 > end) return null;

        int extLen = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
        pos += 2;
        int extEnd = Math.min(pos + extLen, end);

        while (pos + 4 <= extEnd) {
            int type = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
            int len = ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
            pos += 4;
            if (pos + len > extEnd) break;

            if (type == 0x0000) { // server_name
                return parseServerNameExtension(data, pos, pos + len);
            }
            pos += len;
        }
        return null;
    }

    private String parseServerNameExtension(byte[] data, int start, int end) {
        if (start + 2 > end) return null;
        int listLen = ((data[start] & 0xFF) << 8) | (data[start + 1] & 0xFF);
        int pos = start + 2;
        int listEnd = Math.min(pos + listLen, end);

        while (pos + 3 <= listEnd) {
            int nameType = data[pos] & 0xFF;
            int nameLen = ((data[pos + 1] & 0xFF) << 8) | (data[pos + 2] & 0xFF);
            pos += 3;
            if (pos + nameLen > listEnd) break;
            if (nameType == 0) { // host_name
                return new String(data, pos, nameLen, StandardCharsets.UTF_8);
            }
            pos += nameLen;
        }
        return null;
    }
}
