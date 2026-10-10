package com.example.theshrimpapp.features.imageoptimizer.di

import android.content.ContentResolver
import android.content.Context
import com.example.theshrimpapp.features.imageoptimizer.data.DefaultLosslessImageOptimizer
import com.example.theshrimpapp.features.imageoptimizer.domain.LosslessImageOptimizer
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ImageOptimizerModule {

    @Binds
    @Singleton
    abstract fun bindLosslessImageOptimizer(
        implementation: DefaultLosslessImageOptimizer
    ): LosslessImageOptimizer
}

@Module
@InstallIn(SingletonComponent::class)
object ImageOptimizerAndroidModule {
    @Provides
    @Singleton
    fun provideContentResolver(
        @ApplicationContext context: Context
    ): ContentResolver = context.contentResolver
}