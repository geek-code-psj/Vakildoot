package com.vakildoot

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.objectbox.BoxStore
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Provides the ObjectBox BoxStore — single instance per app lifecycle.
     *
     * ObjectBox stores all data in:
     *   /data/data/com.vakildoot/objectbox/vakildoot-db/
     *
     * This directory is app-private, encrypted by the Android Keystore
     * at the OS level on devices with hardware-backed encryption (API 28+).
     *
     * For additional security in Phase 3, wrap with SQLCipher or
     * ObjectBox's native encryption extension.
     */
    @Provides
    @Singleton
    fun provideBoxStore(@ApplicationContext context: Context): BoxStore {
        // Production:
        // return MyObjectBox.builder()
        //     .androidContext(context)
        //     .name("vakildoot-db")
        //     .build()
        //
        // Stub — replace with generated MyObjectBox after first build:
        return io.objectbox.BoxStoreBuilder(null)
            .androidContext(context)
            .name("vakildoot-db-stub")
            .build()
    }
}
