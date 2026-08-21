package dev.djoxer.netmonitor.data;

import android.content.Context;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.djoxer.netmonitor.block.BlockManager;
import dev.djoxer.netmonitor.block.BlockSchedule;
import dev.djoxer.netmonitor.data.entity.BlockRuleEntity;
import dev.djoxer.netmonitor.data.entity.ScheduleEntity;

public class RuleRepository {

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private final AppDatabase db;

    public interface Callback {
        void onDone();
    }

    public interface SchedulesCallback {
        void onResult(List<BlockSchedule> schedules);
    }

    public RuleRepository(Context context) {
        db = AppDatabase.getInstance(context);
    }

    public void loadIntoMemoryAsync(Runnable onDone) {
        IO.execute(() -> {
            Map<String, BlockManager.AppRule> permanent = new HashMap<>();
            Map<String, List<BlockSchedule>> scheduleMap = new HashMap<>();

            for (BlockRuleEntity r : db.blockRuleDao().getAll()) {
                permanent.put(
                        r.packageName,
                        new BlockManager.AppRule(r.blockOut, r.blockIn, r.bypass)
                );
                if (r.uid > 0) {
                    BlockManager.getInstance().registerUid(r.uid, r.packageName);
                }
            }

            for (ScheduleEntity s : db.scheduleDao().getAll()) {
                List<BlockSchedule> list = scheduleMap.get(s.packageName);
                if (list == null) {
                    list = new ArrayList<>();
                    scheduleMap.put(s.packageName, list);
                }
                list.add(new BlockSchedule(
                        s.id, s.packageName, s.daysMask, s.startMinute, s.endMinute));
            }

            BlockManager.getInstance().loadFromDisk(permanent, scheduleMap);
            if (onDone != null) onDone.run();
        });
    }

    public void setDirectionBlockAsync(String packageName, int uid, String appName,
                                       boolean blockOut, boolean blockIn) {
        IO.execute(() -> {
            BlockRuleEntity existing = null;
            for (BlockRuleEntity r : db.blockRuleDao().getAll()) {
                if (packageName.equals(r.packageName)) {
                    existing = r;
                    break;
                }
            }
            boolean bypass = existing != null && existing.bypass;
            final boolean out = bypass ? false : blockOut;
            final boolean in = bypass ? false : blockIn;

            db.blockRuleDao().upsert(new BlockRuleEntity(
                    packageName, uid, appName, out, in, bypass));
            BlockManager.getInstance().setRule(packageName, out, in, bypass);
            if (uid > 0) {
                BlockManager.getInstance().registerUid(uid, packageName);
            }
        });
    }

    public void setBypassAsync(String packageName, int uid, String appName, boolean bypass) {
        IO.execute(() -> {
            boolean blockOut = false;
            boolean blockIn = false;
            if (!bypass) {
                for (BlockRuleEntity r : db.blockRuleDao().getAll()) {
                    if (packageName.equals(r.packageName)) {
                        blockOut = r.blockOut;
                        blockIn = r.blockIn;
                        break;
                    }
                }
            }
            final boolean out = blockOut;
            final boolean in = blockIn;
            final boolean bypassFlag = bypass;

            db.blockRuleDao().upsert(new BlockRuleEntity(
                    packageName, uid, appName, out, in, bypassFlag));
            BlockManager.getInstance().setRule(packageName, out, in, bypassFlag);
            if (uid > 0) {
                BlockManager.getInstance().registerUid(uid, packageName);
            }
        });
    }

    public void setPermanentBlockAsync(String packageName, int uid, String appName,
                                       boolean blocked) {
        setDirectionBlockAsync(packageName, uid, appName, blocked, blocked);
    }

    public void getSchedulesAsync(String packageName, SchedulesCallback callback) {
        IO.execute(() -> {
            List<ScheduleEntity> entities = db.scheduleDao().getForPackage(packageName);
            List<BlockSchedule> result = new ArrayList<>();
            for (ScheduleEntity s : entities) {
                result.add(new BlockSchedule(
                        s.id, s.packageName, s.daysMask, s.startMinute, s.endMinute));
            }
            if (callback != null) {
                callback.onResult(result);
            }
        });
    }

    public void addScheduleAsync(String packageName, int daysMask, int startMinute, int endMinute,
                                 Callback callback) {
        IO.execute(() -> {
            ScheduleEntity entity = new ScheduleEntity(
                    packageName, daysMask, startMinute, endMinute);
            long id = db.scheduleDao().insert(entity);
            entity.id = (int) id;
            reloadPackageSchedules(packageName);
            if (callback != null) callback.onDone();
        });
    }

    public void deleteScheduleAsync(int scheduleId, String packageName, Callback callback) {
        IO.execute(() -> {
            db.scheduleDao().deleteById(scheduleId);
            reloadPackageSchedules(packageName);
            if (callback != null) callback.onDone();
        });
    }

    private void reloadPackageSchedules(String packageName) {
        List<ScheduleEntity> entities = db.scheduleDao().getForPackage(packageName);
        List<BlockSchedule> list = new ArrayList<>();
        for (ScheduleEntity s : entities) {
            list.add(new BlockSchedule(
                    s.id, s.packageName, s.daysMask, s.startMinute, s.endMinute));
        }
        BlockManager.getInstance().setSchedules(packageName, list);
    }

    public void clearPermanentBlocksAsync(Callback callback) {
        IO.execute(() -> {
            for (BlockRuleEntity r : db.blockRuleDao().getAll()) {
                r.blockOut = false;
                r.blockIn = false;
                db.blockRuleDao().upsert(r);
            }
            Map<String, BlockManager.AppRule> permanent = new HashMap<>();
            Map<String, List<BlockSchedule>> scheduleMap = new HashMap<>();
            for (BlockRuleEntity r : db.blockRuleDao().getAll()) {
                permanent.put(r.packageName,
                        new BlockManager.AppRule(r.blockOut, r.blockIn, r.bypass));
            }
            for (ScheduleEntity s : db.scheduleDao().getAll()) {
                scheduleMap.computeIfAbsent(s.packageName, k -> new ArrayList<>())
                        .add(new BlockSchedule(
                                s.id, s.packageName, s.daysMask, s.startMinute, s.endMinute));
            }
            BlockManager.getInstance().loadFromDisk(permanent, scheduleMap);
            if (callback != null) callback.onDone();
        });
    }

    public void clearAllSchedulesAsync(Callback callback) {
        IO.execute(() -> {
            for (ScheduleEntity s : db.scheduleDao().getAll()) {
                db.scheduleDao().deleteById(s.id);
            }
            Map<String, BlockManager.AppRule> permanent = new HashMap<>();
            for (BlockRuleEntity r : db.blockRuleDao().getAll()) {
                permanent.put(r.packageName,
                        new BlockManager.AppRule(r.blockOut, r.blockIn, r.bypass));
            }
            BlockManager.getInstance().loadFromDisk(permanent, new HashMap<>());
            if (callback != null) callback.onDone();
        });
    }

    public void clearLogAsync(Callback callback) {
        IO.execute(() -> {
            db.logEventDao().clear();
            if (callback != null) callback.onDone();
        });
    }

    public void deleteOldLogsAsync(long olderThanMillis, Callback callback) {
        IO.execute(() -> {
            db.logEventDao().deleteOlderThan(System.currentTimeMillis() - olderThanMillis);
            if (callback != null) callback.onDone();
        });
    }
}
