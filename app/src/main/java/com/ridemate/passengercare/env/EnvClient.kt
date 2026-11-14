package com.ridemate.passengercare.env

import com.ridemate.passengercare.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 지금 날씨 + 앞으로 6시간. */
data class WeatherNow(
    val tempC: Double,
    val humidity: Int,
    val precipMm: Double,
    val snowCm: Double,
    val weatherCode: Int,
    /** 다음 6시간 최대 강수확률(%) */
    val pop6h: Int,
    /** 지난 6시간 강수량 합(mm) — 결빙 추정용 */
    val recentPrecipMm: Double,
    /** 다음 6시간 최저 기온 */
    val minTemp6h: Double,
    val source: String
)

/** 고속도로 돌발·작업 정보 한 건. */
data class RoadEvent(
    val type: String,
    val road: String,
    /** "강릉방향" → "강릉" */
    val toward: String,
    val lat: Double?,
    val lng: Double?,
    val text: String,
    val time: String
)

/**
 * 외부 데이터 (APIS.md §4·§5 대체안, 2026-11-12 확인).
 *  - 날씨: Open-Meteo (키 없음). 기상청 단기예보 키를 받으면 교체할 수 있게 이 클래스에만 둔다.
 *  - 도로: 한국도로공사 공공데이터 포털 실시간 돌발상황(burstInfo/realTimeSms).
 *    키는 local.properties EX_API_KEY → BuildConfig. 없으면 포털의 공개 테스트 키("test")를 쓴다.
 * 모든 호출은 IO 스레드, 연결 5초 · 읽기 10초. 실패하면 null을 돌려주고 화면은 "정보 없음"으로 둔다(E09).
 */
object EnvClient {

    suspend fun weather(lat: Double, lng: Double): WeatherNow? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lng" +
                "&current=temperature_2m,relative_humidity_2m,precipitation,snowfall,weather_code" +
                "&hourly=precipitation_probability,precipitation,temperature_2m" +
                "&past_hours=6&forecast_hours=6&timezone=Asia%2FSeoul"
            val o = JSONObject(get(url))
            val c = o.getJSONObject("current")
            val h = o.getJSONObject("hourly")
            val times = h.getJSONArray("time")
            val pop = h.getJSONArray("precipitation_probability")
            val pr = h.getJSONArray("precipitation")
            val temp = h.getJSONArray("temperature_2m")
            val now = c.getString("time").take(13) // yyyy-MM-ddTHH
            var pastPrecip = 0.0
            var pop6 = 0
            var minT = Double.MAX_VALUE
            for (i in 0 until times.length()) {
                val t = times.getString(i).take(13)
                if (t < now) pastPrecip += pr.optDouble(i, 0.0)
                else {
                    pop6 = maxOf(pop6, pop.optInt(i, 0))
                    minT = minOf(minT, temp.optDouble(i, Double.MAX_VALUE))
                }
            }
            WeatherNow(
                tempC = c.getDouble("temperature_2m"),
                humidity = c.getInt("relative_humidity_2m"),
                precipMm = c.optDouble("precipitation", 0.0),
                snowCm = c.optDouble("snowfall", 0.0),
                weatherCode = c.optInt("weather_code", 0),
                pop6h = pop6,
                recentPrecipMm = pastPrecip,
                minTemp6h = if (minT == Double.MAX_VALUE) c.getDouble("temperature_2m") else minT,
                source = "Open-Meteo"
            )
        }.getOrNull()
    }

    suspend fun roadEvents(): List<RoadEvent>? = withContext(Dispatchers.IO) {
        runCatching {
            val key = BuildConfig.EX_API_KEY
            val out = mutableListOf<RoadEvent>()
            for (page in 1..4) {
                val text = get("https://data.ex.co.kr/openapi/burstInfo/realTimeSms?key=$key&type=json&numOfRows=99&pageNo=$page")
                val o = runCatching { JSONObject(text) }.getOrNull() ?: break
                val list = o.optJSONArray("realTimeSMSList") ?: break
                for (i in 0 until list.length()) {
                    val e = list.getJSONObject(i)
                    val type = e.optString("accType")
                    if (type == "이벤트/홍보") continue
                    out += RoadEvent(
                        type = type,
                        road = e.optString("roadNM"),
                        toward = e.optString("startEndTypeCode").removeSuffix("방향").trim(),
                        // 이 API는 경도를 altitude 필드에 준다(실제 응답으로 확인)
                        lat = if (e.isNull("latitude")) null else e.optDouble("latitude"),
                        lng = if (e.isNull("altitude")) null else e.optDouble("altitude"),
                        text = e.optString("smsText").trim(),
                        time = e.optString("accDate") + " " + e.optString("accHour")
                    )
                }
                if (page >= o.optInt("pageSize", 1)) break
            }
            out
        }.getOrNull()
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 10_000
        return try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
