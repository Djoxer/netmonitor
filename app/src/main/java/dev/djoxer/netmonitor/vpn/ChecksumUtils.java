package dev.djoxer.netmonitor.vpn;

public final class ChecksumUtils {

    private ChecksumUtils() {}

    public static int ipChecksum(byte[] data, int offset, int length) {
        int sum = 0;
        for (int i = offset; i < offset + length - 1; i += 2) {
            sum += ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
        }
        if ((length & 1) != 0) {
            sum += (data[offset + length - 1] & 0xFF) << 8;
        }
        while ((sum >> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return ~sum & 0xFFFF;
    }

    public static int transportChecksumV4(byte[] srcIp, byte[] dstIp, int protocol,
                                          byte[] packet, int transportOffset, int transportLength) {
        int sum = 0;
        sum += ((srcIp[0] & 0xFF) << 8) | (srcIp[1] & 0xFF);
        sum += ((srcIp[2] & 0xFF) << 8) | (srcIp[3] & 0xFF);
        sum += ((dstIp[0] & 0xFF) << 8) | (dstIp[1] & 0xFF);
        sum += ((dstIp[2] & 0xFF) << 8) | (dstIp[3] & 0xFF);
        sum += protocol;
        sum += transportLength;
        return finishSum(sum, packet, transportOffset, transportLength);
    }

    public static int transportChecksumV6(byte[] srcIp, byte[] dstIp, int protocol,
                                          byte[] packet, int transportOffset, int transportLength) {
        int sum = 0;
        for (int i = 0; i < 16; i += 2) {
            sum += ((srcIp[i] & 0xFF) << 8) | (srcIp[i + 1] & 0xFF);
        }
        for (int i = 0; i < 16; i += 2) {
            sum += ((dstIp[i] & 0xFF) << 8) | (dstIp[i + 1] & 0xFF);
        }
        sum += (transportLength >> 16) & 0xFFFF;
        sum += transportLength & 0xFFFF;
        sum += protocol;
        return finishSum(sum, packet, transportOffset, transportLength);
    }

    @Deprecated
    public static int tcpChecksum(byte[] srcIp, byte[] dstIp,
                                  byte[] packet, int tcpOffset, int tcpLength) {
        return transportChecksumV4(srcIp, dstIp, 6, packet, tcpOffset, tcpLength);
    }

    private static int finishSum(int sum, byte[] packet, int offset, int length) {
        for (int i = offset; i < offset + length - 1; i += 2) {
            sum += ((packet[i] & 0xFF) << 8) | (packet[i + 1] & 0xFF);
        }
        if ((length & 1) != 0) {
            sum += (packet[offset + length - 1] & 0xFF) << 8;
        }
        while ((sum >> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return ~sum & 0xFFFF;
    }
}
