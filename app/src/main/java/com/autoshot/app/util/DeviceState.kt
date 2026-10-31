package com.autoshot.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager

object DeviceState {

    /** 电量百分比；取不到时返回 -1（调用方按「未知则不拦截」处理） */
    fun batteryPercent(context: Context): Int = try {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (t: Throwable) {
        -1
    }

    fun isCharging(context: Context): Boolean = try {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        bm.isCharging
    } catch (t: Throwable) {
        false
    }

    fun isWifiConnected(context: Context): Boolean {
        val caps = activeCapabilities(context) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun isOnline(context: Context): Boolean {
        val caps = activeCapabilities(context) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun activeCapabilities(context: Context): NetworkCapabilities? = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)
    } catch (t: Throwable) {
        null
    }
}
