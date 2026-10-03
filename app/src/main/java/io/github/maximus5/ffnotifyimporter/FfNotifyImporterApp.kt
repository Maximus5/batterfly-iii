package io.github.maximus5.ffnotifyimporter

import android.app.Application
import io.github.maximus5.ffnotifyimporter.data.MessageHistoryDb
import io.github.maximus5.ffnotifyimporter.data.MessageStore
import io.github.maximus5.ffnotifyimporter.data.RetentionController
import io.github.maximus5.ffnotifyimporter.prefs.RetentionPrefs

class FfNotifyImporterApp : Application() {

    override fun onCreate() {
        super.onCreate()
        RetentionController.applyOnStartup(
            retentionDays = RetentionPrefs(this).getRetentionDays(),
            store = MessageStore.shared,
            db = MessageHistoryDb.getInstance(this),
        )
    }
}
