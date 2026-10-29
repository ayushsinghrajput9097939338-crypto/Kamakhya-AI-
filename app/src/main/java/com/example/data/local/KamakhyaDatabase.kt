package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SavedAssetEntity::class, ActionLogEntity::class],
    version = 1,
    exportSchema = false
)
abstract class KamakhyaDatabase : RoomDatabase() {
    abstract fun dao(): KamakhyaDao

    companion object {
        @Volatile
        private var INSTANCE: KamakhyaDatabase? = null

        fun getInstance(context: Context): KamakhyaDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    KamakhyaDatabase::class.java,
                    "kamakhya_local.db"
                )
                    .fallbackToDestructiveMigration(true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
