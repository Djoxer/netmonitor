package dev.djoxer.netmonitor.data.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Per-profile rule. Primary key is composite via packageKey + profileId
 * stored as single key "profileId|packageKey" for simple Room PK.
 */
@Entity(tableName = "profile_rules")
public class ProfileRuleEntity {

    @PrimaryKey
    @NonNull
    public String id;

    public long profileId;

    @NonNull
    public String packageName;

    public int uid;
    public String appName;

    public boolean blockOut;
    public boolean blockIn;
    public boolean bypass;

    /** Whitelist only: app is allowed (not blocked). */
    public boolean allowed;

    public ProfileRuleEntity() {
        this.id = "";
        this.packageName = "";
    }

    public static String makeId(long profileId, String packageName) {
        return profileId + "|" + packageName;
    }
}
