package dev.djoxer.netmonitor.data;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.djoxer.netmonitor.block.BlockManager;
import dev.djoxer.netmonitor.block.BlockSchedule;
import dev.djoxer.netmonitor.block.ProfileManager;
import dev.djoxer.netmonitor.data.entity.ScheduleEntity;

public class RuleRepository {

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private final AppDatabase db;
    private final Context appContext;

    public interface Callback {
        void onDone();
    }

    public interface SchedulesCallback {
        void onResult(List<BlockSchedule> schedules);
    }

    public RuleRepository(Context context) {
        appContext = context.getApplicationContext();
        db = AppDatabase.getInstance(appContext);
    }

    public void loadIntoMemoryAsync(Runnable onDone) {
        ProfileManager.getInstance().ensureDefaultAndLoadAsync(appContext, () -> {
            if (onDone != null) onDone.run();
        });
    }

    public void setDirectionBlockAsync(String packageName, int uid, String appName,
                                       boolean blockOut, boolean blockIn) {
        BlockManager.AppRule existing = BlockManager.getInstance().getRule(packageName);
        boolean bypass = existing != null && existing.bypass;
        boolean allowed = existing != null && existing.allowed;
        if (bypass) {
            blockOut = false;
            blockIn = false;
        }
        final boolean out = blockOut;
        final boolean in = blockIn;
        ProfileManager.getInstance().upsertRuleForActiveAsync(
                appContext, packageName, uid, appName,
                out, in, bypass, allowed, null);
    }

    public void setBypassAsync(String packageName, int uid, String appName, boolean bypass) {
        BlockManager.AppRule existing = BlockManager.getInstance().getRule(packageName);
        boolean blockOut = false;
        boolean blockIn = false;
        boolean allowed = existing != null && existing.allowed;
        if (!bypass && existing != null) {
            blockOut = existing.blockOut;
            blockIn = existing.blockIn;
        }
        if (bypass) {
            allowed = false;
        }
        ProfileManager.getInstance().upsertRuleForActiveAsync(
                appContext, packageName, uid, appName,
                blockOut, blockIn, bypass, allowed, null);
    }

    public void setAllowedAsync(String packageName, int uid, String appName, boolean allowed) {
        BlockManager.AppRule existing = BlockManager.getInstance().getRule(packageName);
        boolean blockOut = false;
        boolean blockIn = false;
        boolean bypass = existing != null && existing.bypass;
        if (allowed) {
            blockOut = false;
            blockIn = false;
            bypass = false;
        } else if (existing != null) {
            blockOut = existing.blockOut;
            blockIn = existing.blockIn;
        }
        ProfileManager.getInstance().upsertRuleForActiveAsync(
                appContext, packageName, uid, appName,
                blockOut, blockIn, bypass, allowed, null);
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
            long pid = ProfileManager.getInstance().getActiveProfileId(appContext);
            if (pid > 0) {
                for (dev.djoxer.netmonitor.data.entity.ProfileRuleEntity r :
                        db.profileDao().getRulesForProfile(pid)) {
                    r.blockOut = false;
                    r.blockIn = false;
                    db.profileDao().upsertRule(r);
                }
            }
            ProfileManager.getInstance().applyActiveProfileAsync(appContext, () -> {
                if (callback != null) callback.onDone();
            });
        });
    }

    public void clearAllSchedulesAsync(Callback callback) {
        IO.execute(() -> {
            for (ScheduleEntity s : db.scheduleDao().getAll()) {
                db.scheduleDao().deleteById(s.id);
            }
            ProfileManager.getInstance().applyActiveProfileAsync(appContext, () -> {
                if (callback != null) callback.onDone();
            });
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
