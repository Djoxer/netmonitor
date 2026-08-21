package dev.djoxer.netmonitor.block;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory block decisions for the hot path.
 * Global block is handled by NetVpnService; this covers per-app rules + profile mode.
 */
public class BlockManager {

    public static final int MODE_BLACKLIST = 0;
    public static final int MODE_WHITELIST = 1;

    public static final class AppRule {
        public final boolean blockOut;
        public final boolean blockIn;
        public final boolean bypass;
        public final boolean allowed;

        public AppRule(boolean blockOut, boolean blockIn, boolean bypass, boolean allowed) {
            this.blockOut = blockOut;
            this.blockIn = blockIn;
            this.bypass = bypass;
            this.allowed = allowed;
        }

        public AppRule(boolean blockOut, boolean blockIn, boolean bypass) {
            this(blockOut, blockIn, bypass, false);
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

    private volatile int mode = MODE_BLACKLIST;

    private BlockManager() {}

    public void setMode(int mode) {
        this.mode = (mode == MODE_WHITELIST) ? MODE_WHITELIST : MODE_BLACKLIST;
    }

    public int getMode() {
        return mode;
    }

    public boolean isWhitelistMode() {
        return mode == MODE_WHITELIST;
    }

    public void setRule(String key, boolean blockOut, boolean blockIn, boolean bypass) {
        setRule(key, blockOut, blockIn, bypass, false);
    }

    public void setRule(String key, boolean blockOut, boolean blockIn, boolean bypass,
                        boolean allowed) {
        if (key == null || key.isEmpty()) return;
        if (!blockOut && !blockIn && !bypass && !allowed) {
            rules.remove(key);
        } else {
            rules.put(key, new AppRule(blockOut, blockIn, bypass, allowed));
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
        boolean allowed = existing != null && existing.allowed;
        if (blocked) {
            setRule(packageName, true, true, false, false);
        } else if (bypass || allowed) {
            setRule(packageName, false, false, bypass, allowed);
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

    public boolean isAllowed(String key) {
        AppRule r = getRule(key);
        return r != null && (r.allowed || r.bypass);
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

    public void loadFromDisk(Set<String> permanent,
                             Map<String, List<BlockSchedule>> scheduleMap) {
        rules.clear();
        schedules.clear();
        if (permanent != null) {
            for (String pkg : permanent) {
                rules.put(pkg, new AppRule(true, true, false, false));
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
        if (pkg != null) return directionBlockedKey(pkg, out);
        // Unknown package under whitelist: block
        return mode == MODE_WHITELIST;
    }

    private boolean directionBlockedKey(String key, boolean out) {
        if (key == null || key.isEmpty()) {
            return mode == MODE_WHITELIST;
        }

        AppRule r = rules.get(key);

        if (r != null && r.bypass) {
            return false;
        }

        // Schedules still force full block when active
        if (scheduleActive(key)) {
            return true;
        }

        if (mode == MODE_WHITELIST) {
            if (r != null && r.allowed) return false;
            // Not on allow list → block both directions
            return true;
        }

        // Blacklist
        if (r != null) {
            if (out && r.blockOut) return true;
            if (!out && r.blockIn) return true;
        }
        return false;
    }

    private boolean scheduleActive(String key) {
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
