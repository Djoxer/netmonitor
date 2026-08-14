package dev.djoxer.netmonitor.vpn;

public class ConnectionInfo {

    public final String protocol;   // TCP / UDP
    public final String destIp;
    public final int destPort;
    public final long timestamp;
    public int packetCount;
    public long bytes;

    public ConnectionInfo(String protocol, String destIp, int destPort) {
        this.protocol = protocol;
        this.destIp = destIp;
        this.destPort = destPort;
        this.timestamp = System.currentTimeMillis();
        this.packetCount = 1;
        this.bytes = 0;
    }

    public String getKey() {
        return protocol + "|" + destIp + "|" + destPort;
    }

    @Override
    public String toString() {
        return protocol + "  " + destIp + ":" + destPort +
                "  (" + packetCount + " pkts, " + bytes + " B)";
    }
}
