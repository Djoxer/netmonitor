package dev.djoxer.netmonitor.vpn;

public class ConnectionInfo {

    public final String protocol;
    public final String destIp;
    public final int destPort;
    public final long timestamp;

    public int uid = -1;
    public String packageName = null;
    public String appName = null;

    public int packetCount = 1;
    public long bytes = 0;

    public ConnectionInfo(String protocol, String destIp, int destPort) {
        this.protocol = protocol;
        this.destIp = destIp;
        this.destPort = destPort;
        this.timestamp = System.currentTimeMillis();
    }

    public String getKey() {
        return protocol + "|" + destIp + "|" + destPort;
    }

    @Override
    public String toString() {
        String appPart;
        if (appName != null) {
            appPart = appName;
        } else if (packageName != null) {
            appPart = packageName;
        } else if (uid > 0) {
            appPart = "uid:" + uid;
        } else {
            appPart = "unknown";
        }

        return appPart + "  →  " + protocol + " " + destIp + ":" + destPort +
                "  (" + packetCount + " pkts)";
    }
}
