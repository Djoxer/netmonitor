package dev.djoxer.netmonitor.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.djoxer.netmonitor.MainActivity;
import dev.djoxer.netmonitor.block.BlockManager;
import dev.djoxer.netmonitor.data.LogWriter;
import dev.djoxer.netmonitor.data.RuleRepository;

public class NetVpnService extends VpnService {

    private static final String TAG = "NetVpnService";

    public static final String ACTION_START = "dev.djoxer.netmonitor.START_VPN";
    public static final String ACTION_STOP = "dev.djoxer.netmonitor.STOP_VPN";
    public static final String EXTRA_BLOCK_MODE = "block_mode";

    private static final String CHANNEL_ID = "netmonitor_vpn";
    private static final int NOTIFICATION_ID = 1;
    private static final AtomicBoolean SERVICE_RUNNING = new AtomicBoolean(false);
    private static volatile boolean blockMode = false;
    private static volatile long sessionStartedAtMs = 0L;
    private static ConnectionTracker tracker = new ConnectionTracker();

    private ParcelFileDescriptor vpnInterface;
    private Thread captureThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private UdpForwarder udpForwarder;
    private TcpForwarder tcpForwarder;
    private android.os.Handler sampleHandler;

    private static volatile NetVpnService instance;

    public static boolean isServiceRunning() {
        return instance != null && instance.running.get();
    }

    public static long getSessionStartedAtMs() {
        return sessionStartedAtMs;
    }

    public static long getSessionElapsedMs() {
        long start = sessionStartedAtMs;
        if (start <= 0L || instance == null || !instance.running.get()) return 0L;
        return Math.max(0L, System.currentTimeMillis() - start);
    }

    public static void setBlockModeLive(boolean enabled) {
        blockMode = enabled;
        NetVpnService svc = instance;
        if (svc != null) {
            String mode = enabled ? "Block mode active" : "Forward mode (UDP+TCP)";
            svc.updateNotification(mode);
        }
    }

    public static List<ConnectionInfo> getConnections() {
        return tracker.getConnections();
    }

    public static void clearConnections() {
        tracker.clear();
    }

    public static ConnectionTracker getTracker() {
        return tracker;
    }

    public static boolean isBlockMode() {
        return blockMode;
    }

    public static void setBlockMode(boolean enabled) {
        blockMode = enabled;
    }

    public void applyBlockModeLive(boolean enabled) {
        blockMode = enabled;
        String mode = enabled ? "Block mode active" : "Forward mode (UDP+TCP)";
        updateNotification(mode);
    }

    private final Runnable sampleRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running.get()) return;
            try {
                dev.djoxer.netmonitor.data.TrafficSampler.getInstance()
                        .maybeSample(NetVpnService.this);
            } catch (Exception ignored) {
            }
            if (running.get() && sampleHandler != null) {
                sampleHandler.postDelayed(this,
                        dev.djoxer.netmonitor.data.TrafficSampler.SAMPLE_INTERVAL_MS);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        tracker.init(getSystemService(ConnectivityManager.class), getPackageManager());
        udpForwarder = new UdpForwarder(this, tracker, running);
        tcpForwarder = new TcpForwarder(this, tracker, running);

        LogWriter.getInstance().start(this);
        dev.djoxer.netmonitor.block.ProfileManager.getInstance()
                .ensureDefaultAndLoadAsync(this, null);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopVpn();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null) {
            blockMode = intent.getBooleanExtra(EXTRA_BLOCK_MODE, false);
        }
        startForeground(NOTIFICATION_ID, buildNotification(
                blockMode ? "Block mode" : "Forward mode (UDP+TCP)"));
        startVpn();
        return START_STICKY;
    }

    private void startVpn() {
        if (running.get()) return;
        try {
            Builder builder = new Builder();
            builder.setSession("NetMonitor");
            builder.setMtu(1500);

            builder.addAddress("10.0.0.2", 32);
            builder.addRoute("0.0.0.0", 0);

            boolean ipv6Added = false;
            if (deviceHasIpv6Connectivity()) {
                try {
                    builder.addAddress("fd00:1:fd00:1:fd00:1:fd00:1", 128);
                    builder.addRoute("::", 0);
                    ipv6Added = true;
                    Log.i(TAG, "IPv6 VPN address and default route enabled");
                } catch (Exception e) {
                    Log.w(TAG, "IPv6 VPN setup failed, continuing IPv4-only", e);
                }
            } else {
                Log.i(TAG, "No device IPv6 connectivity – IPv4-only VPN");
            }

            // Never route NetMonitor itself through the VPN
            builder.addDisallowedApplication(getPackageName());

            // Per-app bypass: full system path, no capture / log / block
            for (String pkg : BlockManager.getInstance().getBypassPackages()) {
                try {
                    builder.addDisallowedApplication(pkg);
                    Log.i(TAG, "VPN bypass: " + pkg);
                } catch (Exception e) {
                    Log.w(TAG, "bypass failed for " + pkg, e);
                }
            }

            builder.setBlocking(true);

            vpnInterface = builder.establish();
            if (vpnInterface == null) {
                stopSelf();
                return;
            }

            running.set(true);
            SERVICE_RUNNING.set(true);
            udpForwarder.start();
            tcpForwarder.start();

            sessionStartedAtMs = System.currentTimeMillis();

            if (sampleHandler == null) {
                sampleHandler = new android.os.Handler(android.os.Looper.getMainLooper());
            }
            sampleHandler.removeCallbacks(sampleRunnable);
            sampleHandler.post(sampleRunnable);

            String mode = blockMode ? "Block mode active" : "Forward mode (UDP+TCP)";
            if (!ipv6Added) {
                mode = mode + " · IPv4 only";
            }
            updateNotification(mode);

            captureThread = new Thread(this::captureLoop, "NetMonitor-Capture");
            captureThread.setPriority(Thread.MAX_PRIORITY);
            captureThread.start();
        } catch (Exception e) {
            Log.e(TAG, "startVpn failed", e);
            stopVpn();
            stopSelf();
        }
    }

    private boolean deviceHasIpv6Connectivity() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces == null) return false;
            while (ifaces.hasMoreElements()) {
                NetworkInterface nif = ifaces.nextElement();
                if (!nif.isUp() || nif.isLoopback()) continue;
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet6Address) {
                        Inet6Address v6 = (Inet6Address) addr;
                        if (!v6.isLinkLocalAddress()
                                && !v6.isLoopbackAddress()
                                && !v6.isAnyLocalAddress()) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "IPv6 probe failed", e);
        }
        return false;
    }

    private void captureLoop() {
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(vpnInterface.getFileDescriptor());
            out = new FileOutputStream(vpnInterface.getFileDescriptor());
            udpForwarder.setTunOut(out);
            tcpForwarder.setTunOut(out);

            byte[] buffer = new byte[32767];
            while (running.get()) {
                int length = in.read(buffer);
                if (length <= 0) continue;

                tracker.onPacket(buffer, length);

                if (blockMode) continue;

                IpPacket ip = IpPacket.parse(buffer, length);
                if (ip == null) continue;

                if (ip.protocol == 17) {
                    udpForwarder.handlePacket(buffer, length);
                } else if (ip.protocol == 6) {
                    tracker.tcpSeen.incrementAndGet();
                    tcpForwarder.handlePacket(buffer, length);
                }
            }
        } catch (Exception e) {
            if (running.get()) Log.e(TAG, "captureLoop error", e);
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
            try {
                if (out != null) out.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void stopVpn() {
        running.set(false);
        sessionStartedAtMs = 0L;
        if (sampleHandler != null) {
            sampleHandler.removeCallbacks(sampleRunnable);
        }

        if (udpForwarder != null) udpForwarder.shutdown();
        if (tcpForwarder != null) tcpForwarder.shutdown();
        if (captureThread != null) {
            try {
                captureThread.join(2000);
            } catch (InterruptedException ignored) {
            }
            captureThread = null;
        }
        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (Exception ignored) {
            }
            vpnInterface = null;
        }
        SERVICE_RUNNING.set(false);
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        stopVpn();
        LogWriter.getInstance().stop();
        super.onDestroy();
    }

    private Notification buildNotification(String text) {
        createNotificationChannel();
        PendingIntent pending = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("NetMonitor")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "NetMonitor VPN", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }
}
