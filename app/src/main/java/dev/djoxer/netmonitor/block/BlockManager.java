package dev.djoxer.netmonitor.block;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory block decisions for the hot path.
 * Global block is handled by NetVpnService; this covers per-app rules.
 */
public class BlockManager {

    private static final BlockManager INSTANCE = new BlockManager();

    public static BlockManager getInstance() {
        return INSTANCE;
    }

    /** packageName → permanently blocked */
    private final Set<String> permanentBlocks = ConcurrentHashMap.newKeySet();

    /** uid → packageName cache */
    private final Map<Integer, String> uidToPackage = new ConcurrentHashMap<>();

    /** packageName → schedules (mode A: active window = blocked) */
    private final Map<String, CopyOnWriteArrayList<BlockSchedule>> schedules = new ConcurrentHashMap<>();

    private BlockManager() {}

    public void setPermanentBlock(String packageName, boolean blocked) {
        if (packageName == null) return;
        if (blocked) permanentBlocks.add(packageName);
        else permanentBlocks.remove(packageName);
    }

    public boolean isPermanentlyBlocked(String packageName) {
        return packageName != null && permanentBlocks.contains(packageName);
    }

    public void registerUid(int uid, String packageName) {
        if (uid > 0 && packageName != null) {
            uidToPackage.put(uid, packageName);
        }
    }

    public void setSchedules(String packageName, List<BlockSchedule> list) {
        if (packageName == null) return;
        if (list == null || list.isEmpty()) {
            schedules.remove(packageName);
        } else {
            schedules.put(packageName, new CopyOnWriteArrayList<>(list));
        }
    }

    public void clearAll() {
        permanentBlocks.clear();
        schedules.clear();
        // keep uid cache
    }

    public Set<String> getPermanentlyBlockedPackages() {
        return permanentBlocks;
    }

    /**
     * @return true if this app must be blocked right now
     */
    public boolean shouldBlock(int uid) {
        if (uid <= 0) return false;

        // Direct uid-key rules (system processes without package)
        if (shouldBlockPackage("uid:" + uid)) return true;

        String pkg = uidToPackage.get(uid);
        if (pkg == null) return false;
        return shouldBlockPackage(pkg);
    }

    public boolean shouldBlockPackage(String packageName) {
        if (packageName == null) return false;

        if (permanentBlocks.contains(packageName)) return true;

        List<BlockSchedule> list = schedules.get(packageName);
        if (list == null || list.isEmpty()) return false;

        Calendar cal = Calendar.getInstance();
        int javaDay = cal.get(Calendar.DAY_OF_WEEK); // Sun=1..Sat=7
        int dayMon0 = (javaDay + 5) % 7; // Mon=0..Sun=6
        int minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);

        for (BlockSchedule s : list) {
            if (s.isActiveNow(dayMon0, minuteOfDay)) return true;
        }
        return false;
    }

    /** Replace all rules from DB load */
    public void loadFromDisk(Set<String> permanent, Map<String, List<BlockSchedule>> scheduleMap) {
        permanentBlocks.clear();
        schedules.clear();
        if (permanent != null) permanentBlocks.addAll(permanent);
        if (scheduleMap != null) {
            for (Map.Entry<String, List<BlockSchedule>> e : scheduleMap.entrySet()) {
                schedules.put(e.getKey(), new CopyOnWriteArrayList<>(e.getValue()));
            }
        }
    }
}
