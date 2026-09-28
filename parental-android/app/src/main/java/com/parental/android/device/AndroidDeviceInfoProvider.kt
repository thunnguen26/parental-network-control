package com.parental.android.device

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale

/**
 * Thu thập thông tin thiết bị Android theo ĐÚNG đặc tả Mục 3.1
 * docs/PROTOCOL.md (yêu cầu nhiệm vụ):
 *
 * - `macAddress`: Android ID (định danh bền vững per-app-signature, an toàn
 *   quyền riêng tư Android 8+) — UPPERCASE làm MAC giả lập; fallback địa chỉ
 *   MAC interface wlan0 nếu đọc được.
 * - `deviceName`: `Build.MANUFACTURER + " " + Build.MODEL` (ví dụ
 *   "Samsung SM-A536E") — capitalize từng từ như quy ước Android.
 * - `osVersion`: `"Android " + Build.VERSION.RELEASE`.
 * - `ipAddress`: IP LAN hiện hành của interface wifi/cell (non-loopback).
 * - `clientId`: UUID bền vững lưu DataStore (sinh MỘT LẦN — khớp quy ước
 *   "không sinh lại UUID mới mỗi lần bật" của PROTOCOL.md).
 */
object AndroidDeviceInfoProvider {

    /** Thông tin thiết bị cho HANDSHAKE/PAIR_REQUEST. */
    data class DeviceInfo(
        val deviceName: String,
        val deviceType: String,
        val osVersion: String,
        val ipAddress: String,
        val macAddress: String
    )

    /** Tên thiết bị: Manufacturer + Model, capitalize từng từ. */
    fun deviceName(): String =
        capitalizeWords("${Build.MANUFACTURER} ${Build.MODEL}").trim()

    /** Hệ điều hành: "Android <release>" (API 33+ có SDK_INT_FULL nhưng giữ tương thích). */
    fun osVersion(): String = "Android " + Build.VERSION.RELEASE

    /** deviceType theo protocol: MOBILE. */
    fun deviceType(): String = "MOBILE"

    /**
     * MAC giả lập: Android ID UPPERCASE (bền vững, không cần quyền location
     * như WifiManager trên Android 6+). Fallback: MAC wlan0 nếu đọc được.
     */
    @SuppressLint("HardwareIds")
    fun macAddress(context: Context): String {
        val androidId = try {
            Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ANDROID_ID
            )
        } catch (_: Exception) {
            null
        }
        if (!androidId.isNullOrBlank()) {
            return androidId.uppercase(Locale.ROOT)
        }
        return hardwareMacAddress() ?: "UNKNOWN-MAC"
    }

    /** IP LAN hiện hành — interface non-loopback, ưu tiên wlan (wifi). */
    fun localIpAddress(): String = try {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "0.0.0.0"
        var wifiIp: String? = null
        var anyIp: String? = null
        for (ni in Collections.list(interfaces)) {
            if (!ni.isUp || ni.isLoopback) continue
            for (addr in ni.inetAddresses) {
                val host = addr.hostAddress ?: continue
                if (host.contains(":")) continue // bỏ IPv6
                if (ni.name.startsWith("wlan") || ni.name.startsWith("eth")) {
                    if (wifiIp == null) wifiIp = host
                } else if (anyIp == null) {
                    anyIp = host
                }
            }
        }
        wifiIp ?: anyIp ?: "0.0.0.0"
    } catch (_: Exception) {
        "0.0.0.0"
    }

    /** Đọc MAC wlan0 qua /sys (không cần quyền); null khi không có. */
    private fun hardwareMacAddress(): String? = try {
        val content = java.io.File("/sys/class/net/wlan0/address").readText().trim()
        if (content.matches(Regex("([0-9a-f]{2}:){5}[0-9a-f]{2}"))) {
            content.uppercase(Locale.ROOT).replace(':', '-')
        } else null
    } catch (_: Exception) {
        null
    }

    /** Dựng DeviceInfo đầy đủ cho handshake. */
    fun collect(context: Context): DeviceInfo = DeviceInfo(
        deviceName = deviceName(),
        deviceType = deviceType(),
        osVersion = osVersion(),
        ipAddress = localIpAddress(),
        macAddress = macAddress(context)
    )

    private fun capitalizeWords(input: String): String = input.split(" ")
        .joinToString(" ") { word ->
            if (word.isEmpty()) word
            else word[0].uppercase(Locale.ROOT) + word.substring(1).lowercase(Locale.ROOT)
        }
}
