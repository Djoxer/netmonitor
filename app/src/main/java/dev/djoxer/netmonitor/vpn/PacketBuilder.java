package dev.djoxer.netmonitor.vpn;

import java.nio.ByteBuffer;

public final class PacketBuilder {

    private PacketBuilder() {}

    public static byte[] buildUdp(byte[] srcIp, int srcPort,
                                  byte[] dstIp, int dstPort,
                                  byte[] payload, int offset, int len) {
        int totalLen = 20 + 8 + len;
        byte[] packet = new byte[totalLen];
        ByteBuffer buf = ByteBuffer.wrap(packet);

        buf.put((byte) 0x45);
        buf.put((byte) 0);
        buf.putShort((short) totalLen);
        buf.putShort((short) 0);
        buf.putShort((short) 0x4000);
        buf.put((byte) 64);
        buf.put((byte) 17);
        buf.putShort((short) 0);
        buf.put(srcIp);
        buf.put(dstIp);

        int ipCs = ChecksumUtils.ipChecksum(packet, 0, 20);
        packet[10] = (byte) (ipCs >> 8);
        packet[11] = (byte) ipCs;

        buf.putShort((short) srcPort);
        buf.putShort((short) dstPort);
        buf.putShort((short) (8 + len));
        buf.putShort((short) 0);
        buf.put(payload, offset, len);

        return packet;
    }

    public static byte[] buildTcp(byte[] srcIp, int srcPort,
                                  byte[] dstIp, int dstPort,
                                  int seq, int ack,
                                  byte flags,
                                  byte[] payload, int offset, int len,
                                  boolean withMss) {
        if (payload == null) len = 0;

        int tcpHeaderLen = withMss ? 24 : 20;
        int totalLen = 20 + tcpHeaderLen + len;
        byte[] packet = new byte[totalLen];
        ByteBuffer buf = ByteBuffer.wrap(packet);

        // IP
        buf.put((byte) 0x45);
        buf.put((byte) 0);
        buf.putShort((short) totalLen);
        buf.putShort((short) 0);
        buf.putShort((short) 0x4000);
        buf.put((byte) 64);
        buf.put((byte) 6);
        buf.putShort((short) 0);
        buf.put(srcIp);
        buf.put(dstIp);

        int ipCs = ChecksumUtils.ipChecksum(packet, 0, 20);
        packet[10] = (byte) (ipCs >> 8);
        packet[11] = (byte) ipCs;

        // TCP
        int tcpStart = 20;
        buf.position(tcpStart);
        buf.putShort((short) srcPort);
        buf.putShort((short) dstPort);
        buf.putInt(seq);
        buf.putInt(ack);
        buf.put((byte) ((tcpHeaderLen / 4) << 4));
        buf.put(flags);
        buf.putShort((short) 65535);
        buf.putShort((short) 0);
        buf.putShort((short) 0);

        if (withMss) {
            buf.put((byte) 2);
            buf.put((byte) 4);
            buf.putShort((short) 1460);
        }

        if (len > 0) {
            buf.put(payload, offset, len);
        }

        int tcpCs = ChecksumUtils.tcpChecksum(srcIp, dstIp, packet, tcpStart, tcpHeaderLen + len);
        packet[tcpStart + 16] = (byte) (tcpCs >> 8);
        packet[tcpStart + 17] = (byte) tcpCs;

        return packet;
    }
}
