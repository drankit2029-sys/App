package com.example.hello;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {TakeEntity.class}, version = 1, exportSchema = true)
public abstract class TakeDatabase extends RoomDatabase {
    private static final String NAME = "takeone-library.db";
    private static volatile TakeDatabase instance;

    /**
     * Forward migration scaffold for future schema updates (v1 -> v2).
     * Keeps all existing `takes` rows, titles, favorites, and markers intact.
     */
    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            // Example for future v2 columns:
            // database.execSQL("ALTER TABLE takes ADD COLUMN folderId INTEGER NOT NULL DEFAULT 0");
        }
    };

    public abstract TakeDao takes();

    public static TakeDatabase get(Context ctx) {
        TakeDatabase db = instance;
        if (db == null) {
            synchronized (TakeDatabase.class) {
                db = instance;
                if (db == null) {
                    db = Room.databaseBuilder(ctx.getApplicationContext(),
                                    TakeDatabase.class, NAME)
                            .addMigrations(MIGRATION_1_2)
                            .fallbackToDestructiveMigrationOnDowngrade()
                            .build();
                    instance = db;
                }
            }
        }
        return db;
    }

    /** Test-only in-memory instance (Robolectric). */
    public static TakeDatabase inMemory(Context ctx) {
        return Room.inMemoryDatabaseBuilder(ctx, TakeDatabase.class)
                .allowMainThreadQueries()
                .build();
    }
}
