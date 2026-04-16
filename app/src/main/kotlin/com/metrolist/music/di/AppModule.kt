/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.di

import android.content.Context
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.room.Room
import com.metrolist.music.constants.MaxSongCacheSizeKey
import com.metrolist.music.db.InternalDatabase
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.listentogether.ListenTogetherClient
import com.metrolist.music.listentogether.ListenTogetherManager
import com.metrolist.music.upnp.UpnpCastController
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @Singleton
    @Provides
    fun provideDao(
        database: InternalDatabase,
    ) = database.dao

    @Singleton
    @Provides
    fun provideInternalDatabase(
        @ApplicationContext context: Context,
    ): InternalDatabase = Room
        .databaseBuilder(context, InternalDatabase::class.java, InternalDatabase.DB_NAME)
        .build()

    @Singleton
    @Provides
    fun provideDatabase(
        internalDatabase: InternalDatabase,
    ): MusicDatabase = MusicDatabase(internalDatabase)

    @Singleton
    @Provides
    fun provideDatabaseProvider(
        @ApplicationContext context: Context,
    ): DatabaseProvider = StandaloneDatabaseProvider(context)

    @Singleton
    @Provides
    @PlayerCache
    fun providePlayerCache(
        @ApplicationContext context: Context,
        databaseProvider: DatabaseProvider,
        musicDatabase: MusicDatabase,
    ): SimpleCache {
        val cacheSize = context.dataStore[MaxSongCacheSizeKey] ?: 1024
        return SimpleCache(
            context.filesDir.resolve("exoplayer"),
            com.metrolist.music.playback.MetrolistCacheEvictor(
                when (cacheSize) {
                    -1 -> NoOpCacheEvictor()
                    else -> LeastRecentlyUsedCacheEvictor(cacheSize * 1024 * 1024L)
                },
                musicDatabase
            ),
            databaseProvider,
        )
    }

    @Singleton
    @Provides
    @DownloadCache
    fun provideDownloadCache(
        @ApplicationContext context: Context,
        databaseProvider: DatabaseProvider,
    ): SimpleCache {
        return SimpleCache(
            context.filesDir.resolve("download"),
            NoOpCacheEvictor(),
            databaseProvider
        )
    }

    @Singleton
    @Provides
    fun provideListenTogetherClient(
        @ApplicationContext context: Context,
    ): ListenTogetherClient = ListenTogetherClient(context)

    @Singleton
    @Provides
    fun provideListenTogetherManager(
        @ApplicationContext context: Context,
        client: ListenTogetherClient,
    ): ListenTogetherManager = ListenTogetherManager(client, context)

    /**
     * Dedicated Ktor HttpClient for UPnP (Sonos) control traffic.
     * Local-network calls have different timeout semantics than YouTube
     * traffic: failures should surface quickly so the UI can retry
     * discovery, rather than blocking for 30 s+ on a stale device.
     */
    @Singleton
    @Provides
    @UpnpHttpClient
    fun provideUpnpHttpClient(): HttpClient = HttpClient(CIO) {
        engine {
            requestTimeout = 5_000L
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 3_000L
            requestTimeoutMillis = 5_000L
            socketTimeoutMillis = 5_000L
        }
    }

    @Singleton
    @Provides
    fun provideUpnpCastController(
        @ApplicationContext context: Context,
        @UpnpHttpClient httpClient: HttpClient,
        proxy: com.metrolist.music.upnp.SonosHttpProxy,
    ): UpnpCastController = UpnpCastController(context, httpClient, proxy)

    @Singleton
    @Provides
    fun provideSonosHttpProxy(
        @SonosProxyHttpClient httpClient: HttpClient,
    ): com.metrolist.music.upnp.SonosHttpProxy =
        com.metrolist.music.upnp.SonosHttpProxy(httpClient)

    /**
     * Dedicated HTTP client for the SonosHttpProxy upstream fetcher. Unlike
     * the regular UPnP client (5 s timeouts for SOAP control calls), this
     * one streams audio for entire tracks — could easily run several minutes
     * with the connection idle while the Sonos buffers. We keep a generous
     * connect timeout but leave request/socket timeouts unbounded so the
     * stream isn't killed mid-track.
     */
    @Singleton
    @Provides
    @SonosProxyHttpClient
    fun provideSonosProxyHttpClient(): HttpClient = HttpClient(CIO) {
        engine {
            requestTimeout = 0L // no per-request overall timeout
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000L
            // Leave both request and socket timeouts effectively infinite
            // so a slow Sonos sipping bytes for a 5-minute track doesn't
            // trip the killer coroutine.
            requestTimeoutMillis = Long.MAX_VALUE
            socketTimeoutMillis = Long.MAX_VALUE
        }
    }
}

/** Qualifier for the Ktor HttpClient used by the UPnP / Sonos integration. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UpnpHttpClient

/** Qualifier for the long-lived HttpClient used to stream upstream bytes to Sonos. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SonosProxyHttpClient

