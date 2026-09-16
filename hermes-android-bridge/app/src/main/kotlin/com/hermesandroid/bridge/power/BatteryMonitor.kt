package com.hermesandroid.bridge.power

import android.content.Context
import android.os.BatteryManager

/**
 * Battery level and charge state, read from the platform rather than the screen.
 *
 * The alternative is scraping the level out of the status-bar accessibility
 * node, which is fragile in two independent ways: OEM skins phrase it
 * differently, and the phrasing changes with device language ("87%",
 * "电量剩余 53。", "電池電量為百分之 87。"). Any parser built from a handful of
 * samples eventually mis-reads — and a mis-read level is worse than no level
 * for anything that alarms on it, because it looks authoritative.
 *
 * Both values here come from [BatteryManager], so neither depends on the
 * accessibility service running and neither needs a version gate:
 * `getIntProperty` is API 21, `isCharging` is API 23, and this app's minSdk
 * is 26.
 */
object BatteryMonitor {

    /**
     * Remaining capacity as a whole percentage in 0..100, or null when the
     * device will not say.
     *
     * `getIntProperty` reports failure as [Integer.MIN_VALUE] when
     * targetSdkVersion >= P — and this app targets 34 — so MIN_VALUE means
     * "no answer", never a level. Anything else outside 0..100 is treated the
     * same way rather than passed on as a number no caller can act on.
     *
     * The property read goes through a binder call that rethrows system-server
     * failures as RuntimeException; a battery service in a bad state should
     * cost the caller one unavailable reading, not the whole request.
     */
    fun percentage(context: Context): Int? {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return null
        val value = runCatching {
            manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrNull() ?: return null
        return if (value in 0..100) value else null
    }

    /**
     * Whether the battery is currently charging, or null if there is no
     * battery service.
     *
     * Named for what it actually reports. The platform's definition is "plugged
     * in and supplying enough power that the level is going up, or full" — not
     * merely that a cable is attached. A device on a supply too weak to charge
     * reports false while plugged in. That is the value a low-battery alarm
     * wants: a phone that is plugged in but still draining should alarm.
     */
    fun charging(context: Context): Boolean? {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return null
        return runCatching { manager.isCharging }.getOrNull()
    }
}
