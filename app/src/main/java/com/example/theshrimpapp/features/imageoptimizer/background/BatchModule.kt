package com.example.theshrimpapp.features.imageoptimizer.background

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module @InstallIn(SingletonComponent::class)
object BatchModule {
    @Provides @Singleton fun database(@ApplicationContext context: Context): BatchDatabase =
        Room.databaseBuilder(context, BatchDatabase::class.java, "batches.db")
            .fallbackToDestructiveMigration(true)
            .build()
    @Provides fun dao(database: BatchDatabase): BatchDao = database.batches()
    @Provides @Singleton fun workManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)
}
