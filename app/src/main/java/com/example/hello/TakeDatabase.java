package com.example.hello;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {TakeEntity.class}, version = 1, exportSchema = false)
public abstract class TakeDatabase extends RoomDatabase {
    private static final String NAME = "takeone-library.db";
    private static volatile TakeDatabase instance;

    public abstract TakeDao takes();

    public static TakeDatabase get(Context ctx) {
        TakeDatabase db = instance;
        if (db == null) {
            synchronized (TakeDatabase.class) {
                db = instance;
                if (db == null) {
                    db = Room.databaseBuilder(ctx.getApplicationContext(),
                                    TakeDatabase.class, NAME)
                            .fallbackToDestructiveMigration()
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
