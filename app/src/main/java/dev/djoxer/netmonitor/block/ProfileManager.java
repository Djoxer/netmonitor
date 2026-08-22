package dev.djoxer.netmonitor.block;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.djoxer.netmonitor.data.AppDatabase;
import dev.djoxer.netmonitor.data.entity.ProfileEntity;
import dev.djoxer.netmonitor.data.entity.ProfileRuleEntity;

/**
 * Loads/saves profiles and applies the active profile into BlockManager.
 */
public class ProfileManager {

    private static final String TAG = "ProfileManager";
    private static final String PREFS = "netmonitor_profiles";
    private static final String KEY_ACTIVE_ID = "active_profile_id";

    private static final ProfileManager INSTANCE = new ProfileManager();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    public static ProfileManager getInstance() {
        return INSTANCE;
    }

    private ProfileManager() {}

    public interface Callback {
        void onDone();
    }

    public interface ProfilesCallback {
        void onResult(List<ProfileEntity> profiles, long activeId);
    }

    public long getActiveProfileId(Context context) {
        return prefs(context).getLong(KEY_ACTIVE_ID, -1L);
    }

    public void setActiveProfileId(Context context, long id) {
        prefs(context).edit().putLong(KEY_ACTIVE_ID, id).apply();
    }

    private SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Ensure at least one "Default" blacklist profile exists and is active.
     * Migrates legacy block_rules into Default once if profile_rules empty.
     */
    public void ensureDefaultAndLoadAsync(Context context, Callback done) {
        final Context app = context.getApplicationContext();
        IO.execute(() -> {
            try {
                AppDatabase db = AppDatabase.getInstance(app);
                List<ProfileEntity> profiles = db.profileDao().getAllProfiles();

                if (profiles == null || profiles.isEmpty()) {
                    ProfileEntity def = new ProfileEntity("Default", ProfileEntity.MODE_BLACKLIST);
                    long id = db.profileDao().insertProfile(def);
                    def.id = id;
                    setActiveProfileId(app, id);

                    // One-shot migrate from legacy block_rules if present
                    try {
                        List<dev.djoxer.netmonitor.data.entity.BlockRuleEntity> legacy =
                                db.blockRuleDao().getAll();
                        if (legacy != null) {
                            for (dev.djoxer.netmonitor.data.entity.BlockRuleEntity r : legacy) {
                                ProfileRuleEntity pr = new ProfileRuleEntity();
                                pr.profileId = id;
                                pr.packageName = r.packageName;
                                pr.uid = r.uid;
                                pr.appName = r.appName;
                                pr.blockOut = r.blockOut;
                                pr.blockIn = r.blockIn;
                                pr.bypass = r.bypass;
                                pr.allowed = false;
                                pr.id = ProfileRuleEntity.makeId(id, r.packageName);
                                db.profileDao().upsertRule(pr);
                            }
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "legacy migrate skipped", e);
                    }
                } else if (getActiveProfileId(app) < 0) {
                    setActiveProfileId(app, profiles.get(0).id);
                }

                applyActiveProfileLocked(app);
            } catch (Exception e) {
                Log.e(TAG, "ensureDefault failed", e);
            }
            if (done != null) done.onDone();
        });
    }

    public void applyActiveProfileAsync(Context context, Callback done) {
        final Context app = context.getApplicationContext();
        IO.execute(() -> {
            applyActiveProfileLocked(app);
            if (done != null) done.onDone();
        });
    }

    private void applyActiveProfileLocked(Context app) {
        AppDatabase db = AppDatabase.getInstance(app);
        long activeId = getActiveProfileId(app);
        ProfileEntity profile = activeId > 0 ? db.profileDao().getProfile(activeId) : null;
        if (profile == null) {
            List<ProfileEntity> all = db.profileDao().getAllProfiles();
            if (all != null && !all.isEmpty()) {
                profile = all.get(0);
                setActiveProfileId(app, profile.id);
                activeId = profile.id;
            }
        }

        int mode = profile != null ? profile.mode : BlockManager.MODE_BLACKLIST;
        BlockManager.getInstance().setMode(mode);

        Map<String, BlockManager.AppRule> map = new HashMap<>();
        if (activeId > 0) {
            List<ProfileRuleEntity> rules = db.profileDao().getRulesForProfile(activeId);
            if (rules != null) {
                for (ProfileRuleEntity r : rules) {
                    map.put(r.packageName, new BlockManager.AppRule(
                            r.blockOut, r.blockIn, r.bypass, r.allowed));
                    if (r.uid > 0) {
                        BlockManager.getInstance().registerUid(r.uid, r.packageName);
                    }
                }
            }
        }

        // Keep existing schedules in BlockManager (loaded elsewhere)
        BlockManager.getInstance().loadFromDisk(map, new HashMap<>());
        // Re-apply schedules from DB
        try {
            Map<String, List<BlockSchedule>> scheduleMap = new HashMap<>();
            for (dev.djoxer.netmonitor.data.entity.ScheduleEntity s :
                    db.scheduleDao().getAll()) {
                scheduleMap.computeIfAbsent(s.packageName, k -> new ArrayList<>())
                        .add(new BlockSchedule(
                                s.id, s.packageName, s.daysMask, s.startMinute, s.endMinute));
            }
            for (Map.Entry<String, List<BlockSchedule>> e : scheduleMap.entrySet()) {
                BlockManager.getInstance().setSchedules(e.getKey(), e.getValue());
            }
        } catch (Exception e) {
            Log.w(TAG, "reload schedules", e);
        }
    }

    public void listProfilesAsync(Context context, ProfilesCallback callback) {
        final Context app = context.getApplicationContext();
        IO.execute(() -> {
            List<ProfileEntity> list = AppDatabase.getInstance(app).profileDao().getAllProfiles();
            long active = getActiveProfileId(app);
            if (callback != null) callback.onResult(list != null ? list : new ArrayList<>(), active);
        });
    }

    public void createProfileAsync(Context context, String name, int mode, Callback done) {
        final Context app = context.getApplicationContext();
        IO.execute(() -> {
            ProfileEntity p = new ProfileEntity(name, mode);
            long id = AppDatabase.getInstance(app).profileDao().insertProfile(p);
            p.id = id;
            if (done != null) done.onDone();
        });
    }

    public void activateProfileAsync(Context context, long profileId, Callback done) {
        final Context app = context.getApplicationContext();
        IO.execute(() -> {
            setActiveProfileId(app, profileId);
            applyActiveProfileLocked(app);
            if (done != null) done.onDone();
        });
    }

    public void deleteProfileAsync(Context context, long profileId, Callback done) {
        final Context app = context.getApplicationContext();
        IO.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(app);
            db.profileDao().deleteRulesForProfile(profileId);
            db.profileDao().deleteProfile(profileId);
            if (getActiveProfileId(app) == profileId) {
                List<ProfileEntity> left = db.profileDao().getAllProfiles();
                if (left != null && !left.isEmpty()) {
                    setActiveProfileId(app, left.get(0).id);
                } else {
                    ProfileEntity def = new ProfileEntity("Default", ProfileEntity.MODE_BLACKLIST);
                    long id = db.profileDao().insertProfile(def);
                    setActiveProfileId(app, id);
                }
            }
            applyActiveProfileLocked(app);
            if (done != null) done.onDone();
        });
    }

    public void resetProfileAsync(Context context, long profileId, Callback done) {
        final Context app = context.getApplicationContext();
        IO.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(app);
            db.profileDao().deleteRulesForProfile(profileId);
            if (getActiveProfileId(app) == profileId) {
                applyActiveProfileLocked(app);
            }
            if (done != null) done.onDone();
        });
    }

    public void setProfileModeAsync(Context context, long profileId, int mode, Callback done) {
        final Context app = context.getApplicationContext();
        IO.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(app);
            ProfileEntity p = db.profileDao().getProfile(profileId);
            if (p != null) {
                p.mode = mode;
                db.profileDao().updateProfile(p);
                if (getActiveProfileId(app) == profileId) {
                    applyActiveProfileLocked(app);
                }
            }
            if (done != null) done.onDone();
        });
    }

    public void upsertRuleForActiveAsync(Context context, String packageName, int uid,
                                         String appName, boolean blockOut, boolean blockIn,
                                         boolean bypass, boolean allowed, Callback done) {
        final Context app = context.getApplicationContext();
        IO.execute(() -> {
            long profileId = getActiveProfileId(app);
            if (profileId < 0) {
                if (done != null) done.onDone();
                return;
            }
            ProfileRuleEntity pr = new ProfileRuleEntity();
            pr.profileId = profileId;
            pr.packageName = packageName;
            pr.uid = uid;
            pr.appName = appName;
            pr.blockOut = blockOut;
            pr.blockIn = blockIn;
            pr.bypass = bypass;
            pr.allowed = allowed;
            pr.id = ProfileRuleEntity.makeId(profileId, packageName);
            AppDatabase.getInstance(app).profileDao().upsertRule(pr);

            BlockManager.getInstance().setRule(packageName, blockOut, blockIn, bypass, allowed);
            if (uid > 0) {
                BlockManager.getInstance().registerUid(uid, packageName);
            }
            if (done != null) done.onDone();
        });
    }
}
