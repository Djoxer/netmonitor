package dev.djoxer.netmonitor.data.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "block_rules")
public class BlockRuleEntity {

    @PrimaryKey
    @NonNull
    public String packageName;

    public int uid;
    public String appName;

    public boolean blockOut;
    public boolean blockIn;
    public boolean bypass;

    public BlockRuleEntity(@NonNull String packageName, int uid, String appName,
                           boolean blockOut, boolean blockIn, boolean bypass) {
        this.packageName = packageName;
        this.uid = uid;
        this.appName = appName;
        this.blockOut = blockOut;
        this.blockIn = blockIn;
        this.bypass = bypass;
    }

    /** Convenience: both directions same flag, no bypass. */
    public BlockRuleEntity(@NonNull String packageName, int uid, String appName,
                           boolean blockedBoth) {
        this(packageName, uid, appName, blockedBoth, blockedBoth, false);
    }
}
