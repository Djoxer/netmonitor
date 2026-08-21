package dev.djoxer.netmonitor.data.entity;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "profiles")
public class ProfileEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    public String name;

    /** 0 = blacklist, 1 = whitelist */
    public int mode;

    public ProfileEntity(String name, int mode) {
        this.name = name;
        this.mode = mode;
    }

    public static final int MODE_BLACKLIST = 0;
    public static final int MODE_WHITELIST = 1;
}
