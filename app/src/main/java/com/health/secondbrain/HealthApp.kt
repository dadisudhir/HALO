package com.health.secondbrain

import android.app.Application
import com.health.secondbrain.llm.QnnEnvironment

class HealthApp : Application() {
    override fun onCreate() {
        super.onCreate()
        QnnEnvironment.bootstrap(this)
    }
}
