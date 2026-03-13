package com.zapret2.app.di

import android.content.Context
import android.content.SharedPreferences
import com.zapret2.app.data.NetworkStatsManager
import com.zapret2.app.data.UpdateManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSharedPreferences(@ApplicationContext context: Context): SharedPreferences {
        return context.getSharedPreferences("zapret2_prefs", Context.MODE_PRIVATE)
    }

    @Provides
    @Singleton
    fun provideUpdateManager(@ApplicationContext context: Context): UpdateManager {
        return UpdateManager(context)
    }

    @Provides
    @Singleton
    fun provideNetworkStatsManager(@ApplicationContext context: Context): NetworkStatsManager {
        return NetworkStatsManager(context)
    }
}
