package dev.busung.s25uroot

import android.app.Application

class RootMyGalaxyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Application.getProcessName() == packageName) {
            ShizukuController.initializePassiveTracking()
        }
    }
}
