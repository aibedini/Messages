package com.autonomousone.messages.gateway

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * "The app is in the foreground" as a cheap, process-wide fact.
 *
 * STAGE-4 §3 uses it as one of the four inputs to the socket activity window. It is deliberately
 * only a resumed-activity count: it is not durable, it does not outlive the process, and nothing
 * outside the wake-source policy may treat it as truth.
 */
object AppForegroundTracker {

    private val installed = AtomicBoolean(false)
    private val startedActivities = AtomicInteger(0)

    @Volatile
    private var foreground = false

    val isForeground: Boolean get() = foreground

    fun install(application: Application) {
        if (!installed.compareAndSet(false, true)) return
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    startedActivities.incrementAndGet()
                    foreground = true
                }

                override fun onActivityStopped(activity: Activity) {
                    if (startedActivities.decrementAndGet() <= 0) {
                        startedActivities.set(0)
                        foreground = false
                    }
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            }
        )
    }
}
