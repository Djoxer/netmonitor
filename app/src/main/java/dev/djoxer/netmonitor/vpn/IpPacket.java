package dev.djoxer.netmonitor.vpn;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Minimal IPv4 / IPv6 view of a TUN packet (no extension headers).
 */
public final class IpPacket {

    public final int version;      // 4 or 6
    public final int headerLength; // 20 or 40
    public final int protocol;     // next header / protocol
    public final byte[] srcIp;
    public final byte[] dstIp;
    public final String srcIpStr;
    public final String dstIpStr;

    private IpPacket(int version, int headerLength, int protocol,
                     byte[] srcIp, byte[] dstIp, String srcIpStr, String dstIpStr) {
        this.version = version;
        this.headerLength = headerLength;
        this.protocol = protocol;
        this.srcIp = srcIp;
        this.dstIp = dstIp;
        this.srcIpStr = srcIpStr;
        this.dstIpStr = dstIpStr;
    }

    public static IpPacket parse(byte[] data, int length) {
        if (length < 1) return null;
        int version = (data[0] >> 4) & 0x0F;

        if (version == 4) {
            if (length < 20) return null;
            int ihl = (data[0] & 0x0F) * 4;
            if (ihl < 20 || length < ihl) return null;
            int protocol = data[9] & 0xFF;
            byte[] src = {data[12], data[13], data[14], data[15]};
            byte[] dst = {data[16], data[17], data[18], data[19]};
            return new IpPacket(4, ihl, protocol, src, dst, ipv4(src), ipv4(dst));
        }

        if (version == 6) {
            if (length < 40) return null;
            int protocol = data[6] & 0xFF; // next header (no ext headers)
            byte[] src = new byte[16];
            byte[] dst = new byte[16];
            System.arraycopy(data, 8, src, 0, 16);
            System.arraycopy(data, 24, dst, 0, 16);
            return new IpPacket(6, 40, protocol, src, dst, ipv6(src), ipv6(dst));
        }

        return null;
    }

    public boolean isTunLocal() {
        if (version == 4) return dstIpStr.startsWith("10.0.0.");
        // our VPN addr fd00:1:fd00:1:fd00:1:fd00:1
        return dstIpStr.startsWith("fd00:1:fd00:1:") || dstIpStr.equalsIgnoreCase("fd00:1:fd00:1:fd00:1:fd00:1");
    }

    public boolean isSrcTunLocal() {
        if (version == 4) return srcIpStr.startsWith("10.0.0.");
        return srcIpStr.startsWith("fd00:1:fd00:1:");
    }

    private static String ipv4(byte[] ip) {
        return (ip[0] & 0xFF) + "." + (ip[1] & 0xFF) + "." + (ip[2] & 0xFF) + "." + (ip[3] & 0xFF);
    }

    private static String ipv6(byte[] ip) {
        try {
            return InetAddress.getByAddress(ip).getHostAddress();
        } catch (UnknownHostException e) {
            return "?";
        }
    }
}
