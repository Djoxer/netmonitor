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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.djoxer.netmonitor.MainActivity;
import dev.djoxer.netmonitor.data.LogWriter;
import dev.djoxer.netmonitor.data.RuleRepository;

public class NetVpnService extends VpnService {

    private static final String TAG = "NetVpnService";

    public static final String ACTION_START = "dev.djoxer.netmonitor.START_VPN";
    public static final String ACTION_STOP  = "dev.djoxer.netmonitor.STOP_VPN";
    public static final String EXTRA_BLOCK_MODE = "block_mode";

    private static final String CHANNEL_ID = "netmonitor_vpn";
    private static final int NOTIFICATION_ID = 1;

    private static volatile boolean blockMode = false;
    private static ConnectionTracker tracker = new ConnectionTracker();

    private ParcelFileDescriptor vpnInterface;
    private Thread captureThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private UdpForwarder udpForwarder;
    private TcpForwarder tcpForwarder;

    public static List<ConnectionInfo> getConnections() {
        return tracker.getConnections();
    }

    public static void clearConnections() {
        tracker.clear();
    }

    public static boolean isBlockMode() {
        return blockMode;
    }

    public static ConnectionTracker getTracker() {
        return tracker;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        tracker.init(getSystemService(ConnectivityManager.class), getPackageManager());
        udpForwarder = new UdpForwarder(this, tracker, running);
        tcpForwarder = new TcpForwarder(this, tracker, running);

        LogWriter.getInstance().start(this);
        new RuleRepository(this).loadIntoMemoryAsync(null);
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
            try {
                builder.addAddress("fd00:1:fd00:1:fd00:1:fd00:1", 128);
                builder.addRoute("::", 0);
            } catch (Exception ignored) {}
            builder.addDisallowedApplication(getPackageName());
            builder.setBlocking(true);

            vpnInterface = builder.establish();
            if (vpnInterface == null) {
                stopSelf();
                return;
            }

            running.set(true);
            udpForwarder.start();
            tcpForwarder.start();
            updateNotification(blockMode ? "Block mode active" : "Forward mode (UDP+TCP)");

            captureThread = new Thread(this::captureLoop, "NetMonitor-Capture");
            captureThread.setPriority(Thread.MAX_PRIORITY);
            captureThread.start();
        } catch (Exception e) {
            Log.e(TAG, "startVpn failed", e);
            stopVpn();
            stopSelf();
        }
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

                // 1) Global block mode
                if (blockMode) continue;

                int version = (buffer[0] >> 4) & 0x0F;
                if (version != 4) continue;

                int protocol = buffer[9] & 0xFF;
                if (protocol == 17) {
                    udpForwarder.handlePacket(buffer, length);
                } else if (protocol == 6) {
                    tracker.tcpSeen.incrementAndGet();
                    tcpForwarder.handlePacket(buffer, length);
                }
            }
        } catch (Exception e) {
            if (running.get()) Log.e(TAG, "captureLoop error", e);
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            try { if (out != null) out.close(); } catch (Exception ignored) {}
        }
    }

    private void stopVpn() {
        running.set(false);
        if (udpForwarder != null) udpForwarder.shutdown();
        if (tcpForwarder != null) tcpForwarder.shutdown();
        if (captureThread != null) {
            try { captureThread.join(2000); } catch (InterruptedException ignored) {}
            captureThread = null;
        }
        if (vpnInterface != null) {
            try { vpnInterface.close(); } catch (Exception ignored) {}
            vpnInterface = null;
        }
    }

    @Override
    public void onDestroy() {
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
