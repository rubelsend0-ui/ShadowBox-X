package com.shadowbox.x

import android.app.Application
import com.shadowbox.x.di.AppContainer

class ShadowBoxApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.onAppStart()
    }
}
