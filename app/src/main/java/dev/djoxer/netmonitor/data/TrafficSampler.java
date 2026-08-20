package dev.djoxer.netmonitor.data;

import android.content.Context;
import android.util.Log;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import dev.djoxer.netmonitor.data.entity.TrafficSampleEntity;
import dev.djoxer.netmonitor.vpn.ConnectionInfo;
import dev.djoxer.netmonitor.vpn.ConnectionTracker;
import dev.djoxer.netmonitor.vpn.NetVpnService;

/**
 * Records cumulative traffic snapshots for the 24h chart.
 */
public class TrafficSampler {

    private static final String TAG = "TrafficSampler";
    public static final long SAMPLE_INTERVAL_MS = 60_000L;
    public static final long RETENTION_MS = 24L * 60 * 60 * 1000;

    private static final TrafficSampler INSTANCE = new TrafficSampler();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private final AtomicLong lastSampleAt = new AtomicLong(0);

    public static TrafficSampler getInstance() {
        return INSTANCE;
    }

    public void maybeSample(Context context) {
        long now = System.currentTimeMillis();
        long last = lastSampleAt.get();
        if (last > 0 && now - last < SAMPLE_INTERVAL_MS) return;
        if (!lastSampleAt.compareAndSet(last, now)) return;

        final Context app = context.getApplicationContext();
        IO.execute(() -> {
            try {
                long out = 0;
                long in = 0;
                for (ConnectionInfo c : NetVpnService.getConnections()) {
                    out += c.bytesOut;
                    in += c.bytesIn;
                }
                ConnectionTracker t = NetVpnService.getTracker();
                long v4 = t.bytesIpv4.get();
                long v6 = t.bytesIpv6.get();

                TrafficSampleEntity e = new TrafficSampleEntity();
                e.timestamp = now;
                e.bytesOut = out;
                e.bytesIn = in;
                e.bytesV4 = v4;
                e.bytesV6 = v6;

                AppDatabase db = AppDatabase.getInstance(app);
                db.trafficSampleDao().insert(e);
                db.trafficSampleDao().deleteOlderThan(now - RETENTION_MS);
            } catch (Exception ex) {
                Log.w(TAG, "sample failed", ex);
            }
        });
    }

    public void loadLast24h(Context context, Callback callback) {
        final Context app = context.getApplicationContext();
        IO.execute(() -> {
            long since = System.currentTimeMillis() - RETENTION_MS;
            List<TrafficSampleEntity> list =
                    AppDatabase.getInstance(app).trafficSampleDao().getSince(since);
            if (callback != null) callback.onLoaded(list);
        });
    }

    public interface Callback {
        void onLoaded(List<TrafficSampleEntity> samples);
    }
}
