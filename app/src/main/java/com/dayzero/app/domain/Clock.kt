package com.dayzero.app.domain

import javax.inject.Inject
import javax.inject.Singleton

/** Time source injected everywhere so tests can control "now". */
interface Clock { fun now(): Long }

@Singleton
class RealClock @Inject constructor() : Clock {
    override fun now(): Long = System.currentTimeMillis()
}
