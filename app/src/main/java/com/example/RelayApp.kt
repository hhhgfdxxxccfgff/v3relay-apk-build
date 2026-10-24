package com.example

import android.app.Application
import com.example.util.CrashGuardian

class RelayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashGuardian.install(this)
    }
}
