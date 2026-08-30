package com.lenix

import android.app.Application

class LenixApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // App-wide initialization (Room, downloader, native setup) will be wired here
        // in the next phase.
    }
}
