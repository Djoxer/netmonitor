package dev.djoxer.netmonitor.block;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory block decisions for the hot path.
 * Global block is handled by NetVpnService; this covers per-app rules.
 */
public class BlockManager {

    public static final class AppRule {
        public final boolean blockOut;
        public final boolean blockIn;
        public final boolean bypass;

        public AppRule(boolean blockOut, boolean blockIn, boolean bypass) {
            this.blockOut = blockOut;
            this.blockIn = blockIn;
            this.bypass = bypass;
        }
    }

    private static final BlockManager INSTANCE = new BlockManager();

    public static BlockManager getInstance() {
        return INSTANCE;
    }

    private final Map<String, AppRule> rules = new ConcurrentHashMap<>();
    private final Map<Integer, String> uidToPackage = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<BlockSchedule>> schedules =
            new ConcurrentHashMap<>();

    private BlockManager() {}

    public void setRule(String key, boolean blockOut, boolean blockIn, boolean bypass) {
        if (key == null || key.isEmpty()) return;
        if (!blockOut && !blockIn && !bypass) {
            rules.remove(key);
        } else {
            rules.put(key, new AppRule(blockOut, blockIn, bypass));
        }
    }

    public AppRule getRule(String key) {
        if (key == null) return null;
        return rules.get(key);
    }

    public void setPermanentBlock(String packageName, boolean blocked) {
        if (packageName == null) return;
        AppRule existing = rules.get(packageName);
        boolean bypass = existing != null && existing.bypass;
        if (blocked) {
            setRule(packageName, true, true, false);
        } else if (bypass) {
            setRule(packageName, false, false, true);
        } else {
            rules.remove(packageName);
        }
    }

    public boolean isPermanentlyBlocked(String packageName) {
        AppRule r = getRule(packageName);
        return r != null && !r.bypass && (r.blockOut || r.blockIn);
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
        rules.clear();
        schedules.clear();
    }

    public boolean isBypass(String key) {
        AppRule r = getRule(key);
        return r != null && r.bypass;
    }

    public boolean isBypassUid(int uid) {
        if (uid <= 0) return false;
        if (isBypass("uid:" + uid)) return true;
        String pkg = uidToPackage.get(uid);
        return pkg != null && isBypass(pkg);
    }

    public List<String> getBypassPackages() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, AppRule> e : rules.entrySet()) {
            if (!e.getValue().bypass) continue;
            String key = e.getKey();
            if (key.startsWith("uid:") || "unknown".equals(key) || key.indexOf('.') < 0) {
                continue;
            }
            out.add(key);
        }
        Collections.sort(out);
        return out;
    }

    public boolean shouldBlockOut(int uid) {
        return directionBlockedUid(uid, true);
    }

    public boolean shouldBlockIn(int uid) {
        return directionBlockedUid(uid, false);
    }

    public boolean shouldBlockOutPackage(String key) {
        return directionBlockedKey(key, true);
    }

    public boolean shouldBlockInPackage(String key) {
        return directionBlockedKey(key, false);
    }

    /** True if out or in is blocked (tile badge / legacy). */
    public boolean shouldBlock(int uid) {
        if (uid <= 0) return false;
        if (shouldBlockPackage("uid:" + uid)) return true;
        String pkg = uidToPackage.get(uid);
        return pkg != null && shouldBlockPackage(pkg);
    }

    public boolean shouldBlockPackage(String packageName) {
        return directionBlockedKey(packageName, true)
                || directionBlockedKey(packageName, false);
    }

    public void loadFromDisk(Map<String, AppRule> permanent,
                             Map<String, List<BlockSchedule>> scheduleMap) {
        rules.clear();
        schedules.clear();
        if (permanent != null) {
            rules.putAll(permanent);
        }
        if (scheduleMap != null) {
            for (Map.Entry<String, List<BlockSchedule>> e : scheduleMap.entrySet()) {
                schedules.put(e.getKey(), new CopyOnWriteArrayList<>(e.getValue()));
            }
        }
    }

    /**
     * Legacy loader used by older RuleRepository calls that only pass package names.
     * Treats each name as blockOut+blockIn.
     */
    public void loadFromDisk(java.util.Set<String> permanent,
                             Map<String, List<BlockSchedule>> scheduleMap) {
        rules.clear();
        schedules.clear();
        if (permanent != null) {
            for (String pkg : permanent) {
                rules.put(pkg, new AppRule(true, true, false));
            }
        }
        if (scheduleMap != null) {
            for (Map.Entry<String, List<BlockSchedule>> e : scheduleMap.entrySet()) {
                schedules.put(e.getKey(), new CopyOnWriteArrayList<>(e.getValue()));
            }
        }
    }

    private boolean directionBlockedUid(int uid, boolean out) {
        if (uid <= 0) {
            return directionBlockedKey("unknown", out);
        }
        if (directionBlockedKey("uid:" + uid, out)) return true;
        String pkg = uidToPackage.get(uid);
        return pkg != null && directionBlockedKey(pkg, out);
    }

    private boolean directionBlockedKey(String key, boolean out) {
        if (key == null || key.isEmpty()) return false;

        AppRule r = rules.get(key);
        if (r != null) {
            if (r.bypass) return false;
            if (out && r.blockOut) return true;
            if (!out && r.blockIn) return true;
        }

        List<BlockSchedule> list = schedules.get(key);
        if (list == null || list.isEmpty()) return false;

        Calendar cal = Calendar.getInstance();
        int javaDay = cal.get(Calendar.DAY_OF_WEEK);
        int dayMon0 = (javaDay + 5) % 7;
        int minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);

        for (BlockSchedule s : list) {
            if (s.isActiveNow(dayMon0, minuteOfDay)) return true;
        }
        return false;
    }
}
