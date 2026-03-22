package com.vakildoot

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.objectbox.BoxStore
import com.vakildoot.data.repository.InMemoryDocumentStore
import timber.log.Timber
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Provides the ObjectBox BoxStore — single instance per app lifecycle.
     *
     * PHASE 1: Returns null - persistence disabled
     * - Uses InMemoryDocumentStore instead
     * - All data stored in memory only
     * - Lost when app closes (expected)
     *
     * PHASE 2: Will use real KSP-generated MyObjectBox
     * - Persistent on-disk storage  
     * - Full ObjectBox functionality
     */
    @Provides
    @Singleton
    fun provideBoxStore(@ApplicationContext context: Context): BoxStore? {
        Timber.d("ObjectBox provider (Phase 1: in-memory fallback)")
        Timber.w("⚠️  PHASE 1 MODE: Data stored in RAM only (session-only)")
        Timber.w("    Data will be lost when app closes")
        Timber.w("    To enable persistence: run ./gradlew build in Phase 2")
        return null
    }
    
    /**
     * Provides in-memory document storage for Phase 1
     * 
     * This singleton stores all documents, chunks, and messages in RAM.
     * Perfect for development and testing.
     */
    @Provides
    @Singleton
    fun provideInMemoryStore(): InMemoryDocumentStore {
        Timber.d("Providing InMemoryDocumentStore for Phase 1")
        return InMemoryDocumentStore()
    }
}

