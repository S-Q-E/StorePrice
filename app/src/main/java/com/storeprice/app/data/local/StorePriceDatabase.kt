package com.storeprice.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ProductEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class StorePriceDatabase : RoomDatabase() {

    abstract fun productDao(): ProductDao

    companion object {
        private const val DATABASE_NAME = "storeprice.db"

        @Volatile
        private var instance: StorePriceDatabase? = null

        fun get(context: Context): StorePriceDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    StorePriceDatabase::class.java,
                    DATABASE_NAME,
                ).build().also { instance = it }
            }
    }
}
