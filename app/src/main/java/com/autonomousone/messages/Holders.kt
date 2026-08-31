package com.autonomousone.messages

/**
 * One tiny shared holder for the APPLICATION context, initialised once in
 * [MainApplication.onCreate] and read by object-singletons (pipeline, factory
 * wiring) that are not handed a Context. Objects stay context-free; they
 * resolve the DB through this at call time. Not for ViewModels/activities.
 */
object Holders {
    @Volatile
    lateinit var appContext: android.content.Context
        private set

    @Volatile
    var initialised: Boolean = false
        private set

    fun init(context: android.content.Context) {
        appContext = context.applicationContext
        initialised = true
    }
}
