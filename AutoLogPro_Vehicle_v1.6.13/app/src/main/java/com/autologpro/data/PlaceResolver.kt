package com.autologpro.data

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

/**
 * 도착 좌표 → "서울특별시 서초구 양재동" 수준의 장소 문자열. 실패하면 null(공란으로 두고 정차 때 입력).
 * 차량(AAOS)에는 Geocoder 백엔드가 없거나 네트워크가 없을 수 있어 항상 null을 각오해야 한다.
 * 정확한 번지는 저장하지 않는다(위치 최소화). 주소 문자열 형식은 실기기에서 확인 필요.
 */
class PlaceResolver(private val context: Context) {
    suspend fun resolve(lat: Double, lon: Double): String? {
        if (!Geocoder.isPresent()) return null
        val g = Geocoder(context, Locale.KOREA)
        val line = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                suspendCancellableCoroutine<List<Address>> { c ->
                    g.getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) = c.resume(addresses)
                        override fun onError(errorMessage: String?) = c.resume(emptyList())
                    })
                }
            } else {
                @Suppress("DEPRECATION")
                withContext(Dispatchers.IO) { g.getFromLocation(lat, lon, 1).orEmpty() }
            }
        }.getOrNull()?.firstOrNull()?.getAddressLine(0) ?: return null
        return line.removePrefix("대한민국 ").split(" ").dropLastWhile { w -> w.any(Char::isDigit) }
            .joinToString(" ").ifBlank { null }
    }
}
