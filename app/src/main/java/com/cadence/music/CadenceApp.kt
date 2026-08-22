package com.cadence.music

import android.app.Application
import com.cadence.music.data.LibraryRepository
import com.cadence.music.data.db.AppDatabase
import com.cadence.music.data.metadata.ArtResolver
import com.cadence.music.data.prefs.Prefs
import com.cadence.music.data.source.LocalSource
import com.cadence.music.data.source.SubsonicSource
import com.cadence.music.playback.PlayerConnection

class CadenceApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(app: Application) {
    val prefs = Prefs(app)
    val database: AppDatabase = AppDatabase.build(app)
    val localSource = LocalSource(app)
    val subsonic = SubsonicSource { prefs.server }
    val library = LibraryRepository(database, localSource, subsonic, prefs)
    val artResolver = ArtResolver(subsonic)
    val player = PlayerConnection(app)
}
