package com.hmgchallenge.mosida

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.viewinterop.AndroidView
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import org.osmdroid.config.Configuration
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.LocalTime
import kotlin.math.hypot
import kotlin.math.roundToInt

data class ChatMessage(val sender: String, val text: String)
data class Appointment(val who: String, val time: String, val place: String, val lat: Double, val lng: Double)
data class Stopover(val name: String, val habit: String, val detourMinutes: Int, val lat: Double, val lng: Double)
data class Weather(val hour: Int, val temp: Int, val rainMm: Double)
data class Parking(val name: String, val free: Int, val lat: Double, val lng: Double, val km: Double) {
    val short get() = name.substringBefore(' ')
    val walk get() = if (km < 1.2) "도보 ${(km * 1000 / 70).roundToInt().coerceAtLeast(1)}분" else "약속 장소에서 %.1fkm".format(km)
}

enum class Step { Waiting, Thinking, Proposal, Guiding }

// ponytail: 1차 데모용 가짜 데이터(대화·습관·출발지). PC 에이전트 이력·FusedLocation 연동 시 교체
val demoMessages = listOf(
    ChatMessage("민수", "오늘 저녁 시간 돼?"),
    ChatMessage("민수", "7시 반에 성수 어니언에서 보자"),
    ChatMessage("나", "오케이 퇴근하고 바로 갈게"),
)
val demoAppointment = Appointment("민수", "19:30", "어니언 성수", 37.5447, 127.0582)
val usualStop = Stopover("블루보틀 성수", "늘 가던 카페 · 라떼 픽업", 4, 37.5483, 127.0455)
const val usualPlaylist = "퇴근길 플레이리스트"
private const val LOCATION_PERMISSION = "pleos.car.permission.FUSED_LOCATION"

// 현재 위치를 못 받으면 쓰는 기본 출발지(강남역)
val fallbackOrigin = 37.4979 to 127.0276

// 에뮬레이터에서 10.0.2.2 = 맥의 127.0.0.1, 8800 = OpenMausBot 웹훅 수신기(Bot API)
private const val OMB = "http://10.0.2.2:8800"

private fun omb(path: String, body: JSONObject? = null): JSONObject {
    val c = URL(OMB + path).openConnection() as HttpURLConnection
    c.setRequestProperty("Authorization", "Bearer ${BuildConfig.OMB_TOKEN}")
    c.connectTimeout = 5000; c.readTimeout = 60000
    if (body != null) {
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
    }
    if (c.responseCode !in 200..299) error("OpenMausBot ${c.responseCode}: ${c.errorStream?.bufferedReader()?.readText()?.take(120)}")
    return JSONObject(c.inputStream.bufferedReader().readText())
}

// Bot API: 최대 50초 대기 후 답, 아직이면 202 working → thread로 다시 조회
suspend fun askBot(text: String): String = withContext(Dispatchers.IO) {
    val path = "/hooks/api/bots/${BuildConfig.OMB_BOT}/messages"
    var answer = omb(path, JSONObject().put("message", text).put("wait", 50))
    repeat(20) {
        if (answer.has("reply")) return@withContext answer.getString("reply")
        if (answer.optString("status") == "needs_approval") error("봇이 앱에서 승인을 기다리는 중")
        delay(3000)
        answer = omb("$path?thread=${answer.getString("thread")}")
    }
    error("봇 응답 시간 초과")
}

// 추출 형식은 봇 지침에 있음 (Bot API 메시지는 지시가 아닌 정보로 취급됨)
fun appointmentPrompt(msgs: List<ChatMessage>) = msgs.joinToString("\n") { "${it.sender}: ${it.text}" }

// met.no: 키 없는 무료 날씨 API (User-Agent 필수)
suspend fun weatherAt(a: Appointment): Weather = withContext(Dispatchers.IO) {
    val series = getJson("https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=%.4f&lon=%.4f".format(a.lat, a.lng))
        .getJSONObject("properties").getJSONArray("timeseries")
    val hour = a.time.substringBefore(':').toInt()
    val slot = (0 until series.length()).map { series.getJSONObject(it) }
        .first { java.time.Instant.parse(it.getString("time")).atZone(java.time.ZoneId.of("Asia/Seoul")).hour == hour }.getJSONObject("data")
    val temp = slot.getJSONObject("instant").getJSONObject("details").getDouble("air_temperature").roundToInt()
    val rain = slot.optJSONObject("next_1_hours")?.getJSONObject("details")?.optDouble("precipitation_amount", 0.0) ?: 0.0
    Weather(hour, temp, rain)
}

private fun getJson(url: String) = (URL(url).openConnection() as HttpURLConnection).run {
    setRequestProperty("User-Agent", "Mosida-demo/0.1")
    connectTimeout = 5000; readTimeout = 10000
    JSONObject(inputStream.bufferedReader().readText())
}

fun distanceKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double) = hypot((lat1 - lat2) * 111, (lng1 - lng2) * 88)

// 서울시 실시간 주차대수(GetParkingInfo)엔 좌표가 없어서, 주차장 정보(GetParkInfo)에서 같은 주차장 코드로 좌표를 붙임
// → 약속 장소에서 가장 가까운 빈자리 있는 주차장
suspend fun parkingNear(a: Appointment): Parking? = withContext(Dispatchers.IO) {
    fun rows(svc: String, from: Int) = getJson("http://openapi.seoul.go.kr:8088/${BuildConfig.SEOUL_KEY}/json/$svc/$from/${from + 999}/")
        .getJSONObject(svc).getJSONArray("row").let { r -> (0 until r.length()).map { r.getJSONObject(it) } }
    val coords = listOf(1, 1001, 2001).flatMap { rows("GetParkInfo", it) }.filter { it.getDouble("LAT") > 0 }
        .associate { it.getString("PKLT_CD") to (it.getDouble("LAT") to it.getDouble("LOT")) }
    rows("GetParkingInfo", 1).mapNotNull { r ->
        coords[r.getString("PKLT_CD")]?.let { (lat, lng) ->
            Parking(r.getString("PKLT_NM"), (r.getDouble("TPKCT") - r.getDouble("NOW_PRK_VHCL_CNT")).toInt(), lat, lng,
                distanceKm(lat, lng, a.lat, a.lng))
        }
    }.filter { it.free > 0 }.minByOrNull { it.km }
}

// ponytail: 출발 전 예상 시간 = 직선거리×1.4를 시속 20km로. 안내 시작 후엔 내비의 실제 소요시간을 씀
fun estimateDriveMinutes(a: Appointment, origin: Pair<Double, Double>) =
    (hypot((a.lat - origin.first) * 111, (a.lng - origin.second) * 88) * 1.4 / 20 * 60).roundToInt()

// OpenAI 호환 chat/completions (지금은 인하대 안뇽 AI, url·key·model만 바꾸면 GPT)
suspend fun askLlm(system: String, user: String): String = withContext(Dispatchers.IO) {
    val c = URL(BuildConfig.LLM_URL.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection
    c.setRequestProperty("Authorization", "Bearer ${BuildConfig.LLM_KEY}")
    c.setRequestProperty("Content-Type", "application/json")
    c.connectTimeout = 5000; c.readTimeout = 30000
    c.requestMethod = "POST"; c.doOutput = true
    val body = JSONObject().put("model", BuildConfig.LLM_MODEL).put("messages", org.json.JSONArray()
        .put(JSONObject().put("role", "system").put("content", system))
        .put(JSONObject().put("role", "user").put("content", user)))
    c.outputStream.use { it.write(body.toString().toByteArray()) }
    if (c.responseCode !in 200..299) error("LLM ${c.responseCode}: ${c.errorStream?.bufferedReader()?.readText()?.take(120)}")
    JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("choices").getJSONObject(0)
        .getJSONObject("message").getString("content").trim()
}

enum class Answer { Yes, No, Unknown }

// 키워드로 먼저 판단하고, 애매하면 LLM에게 물음
suspend fun classifyAnswer(heard: String, proposal: String): Answer {
    val h = heard.replace(" ", "")
    if (listOf("아니", "다르게", "싫어", "말고").any { it in h }) return Answer.No
    if (listOf("응", "그래", "그렇게", "좋아", "출발", "가자", "오케이").any { it in h }) return Answer.Yes
    return runCatching {
        val r = askLlm("차 안의 비서가 운전자에게 제안을 했다. 운전자의 대답이 동의면 YES, 거절이나 변경 요청이면 NO, 판단 불가면 UNKNOWN 한 단어로만 답해.",
            "제안: $proposal\n운전자: $heard")
        when { "YES" in r.uppercase() -> Answer.Yes; "NO" in r.uppercase() -> Answer.No; else -> Answer.Unknown }
    }.getOrDefault(Answer.Unknown)
}

suspend fun lateReplyDraft(appt: Appointment, lateMinutes: Long): String = runCatching {
    askLlm("운전 중인 사용자를 대신해 약속 상대에게 보낼 카톡 답장 초안을 쓴다. 반말, 한 문장, 이모지 없이.",
        "상대: ${appt.who}, 약속: ${appt.time} ${appt.place}, 약 ${lateMinutes}분 늦음")
}.getOrElse { "${appt.who}야 미안, ${lateMinutes}분 정도 늦을 것 같아!" }

class RoadRoute(val points: List<GeoPoint>, val km: Double, val minutes: Int)

// TMAP 자동차 경로안내: 실시간 교통 반영 소요시간·거리·경로선 (Free: 하루 1,000건)
suspend fun tmapRoute(from: Pair<Double, Double>, to: Pair<Double, Double>, via: Pair<Double, Double>?): RoadRoute = withContext(Dispatchers.IO) {
    val body = JSONObject().put("startX", "${from.second}").put("startY", "${from.first}")
        .put("endX", "${to.second}").put("endY", "${to.first}")
        .put("reqCoordType", "WGS84GEO").put("resCoordType", "WGS84GEO").put("searchOption", "0")
    via?.let { body.put("passList", "${it.second},${it.first}") }
    val c = URL("https://apis.openapi.sk.com/tmap/routes?version=1").openConnection() as HttpURLConnection
    c.setRequestProperty("appKey", BuildConfig.TMAP_KEY)
    c.setRequestProperty("Content-Type", "application/json")
    c.connectTimeout = 5000; c.readTimeout = 15000
    c.requestMethod = "POST"; c.doOutput = true
    c.outputStream.use { it.write(body.toString().toByteArray()) }
    if (c.responseCode !in 200..299) error("TMAP ${c.responseCode}")
    val features = JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("features")
    val total = features.getJSONObject(0).getJSONObject("properties")
    val points = (0 until features.length()).map { features.getJSONObject(it).getJSONObject("geometry") }
        .filter { it.getString("type") == "LineString" }
        .flatMap { g -> g.getJSONArray("coordinates").let { a -> (0 until a.length()).map { a.getJSONArray(it) } } }
        .map { GeoPoint(it.getDouble(1), it.getDouble(0)) }
    RoadRoute(points, total.getDouble("totalDistance") / 1000, (total.getDouble("totalTime") / 60).roundToInt())
}

fun parseAppointment(reply: String): Appointment {
    val j = JSONObject(reply.substring(reply.indexOf('{'), reply.lastIndexOf('}') + 1))
    return demoAppointment.copy(who = j.getString("who"), time = j.getString("time"), place = j.getString("place"),
        lat = j.getDouble("lat"), lng = j.getDouble("lng"))
}

// ponytail: 데모 영상용 시계. 앱 실행 시점을 18:52 퇴근 시간으로 두고 실제 시간만큼 흐름. 실차에선 LocalTime.now()로
private val appStart = System.currentTimeMillis()
fun clockNow(): LocalTime = LocalTime.of(18, 52).plusSeconds((System.currentTimeMillis() - appStart) / 1000)

fun hm(t: LocalTime) = "%02d:%02d".format(t.hour, t.minute)

class Plan(val appt: Appointment, val weather: Weather?, val parking: Parking?, routeSeconds: Int?, origin: Pair<Double, Double>, skipStop: Boolean, roadMinutes: Int? = null) {
    private val now = clockNow()
    private val directMinutes = estimateDriveMinutes(appt, origin)
    private val spareIfDirect = Duration.between(now.plusMinutes(directMinutes.toLong()), LocalTime.parse(appt.time)).toMinutes()
    val stop = usualStop.takeIf { !skipStop && spareIfDirect >= it.detourMinutes + 5 }
    // TMAP 실시간 교통 값을 우선. 에뮬레이터 내비는 출발 위치가 달라(기본 GPS) 소요시간이 어긋나므로 내비 값은 보조로만 씀
    val driveMinutes = roadMinutes ?: routeSeconds?.let { (it + 59) / 60 } ?: (directMinutes + (stop?.detourMinutes ?: 0))
    val departAt: LocalTime = now
    val arriveAt: LocalTime = now.plusMinutes(driveMinutes.toLong())
    val spare = Duration.between(arriveAt, LocalTime.parse(appt.time)).toMinutes()
    val parkName = parking?.short ?: "${appt.place} 근처"
    val spareText = if (spare >= 0) "${spare}분 일찍 도착" else "${-spare}분 늦어요"

    val reasons = listOfNotNull(
        "지금 나가면 ${directMinutes}분 걸려서 ${hm(now.plusMinutes(directMinutes.toLong()))}쯤 도착해요. " +
            if (spareIfDirect >= 0) "${spareIfDirect}분이 남아요." else "${-spareIfDirect}분 늦어요.",
        when {
            weather == null -> "날씨는 확인하지 못했어요."
            weather.rainMm > 0 -> "${weather.hour}시쯤 ${appt.place} 근처에 비 예보(${weather.rainMm}mm)가 있어요. ${parkName}에 세우고 바로 들어가면 덜 맞아요."
            else -> "${weather.hour}시 ${weather.temp}°, 비 소식은 없어요." + (parking?.let { " ${it.name}에 ${it.free}면 비어 있어요." } ?: "")
        },
        stop?.let { "남는 시간에 ${it.habit.substringBefore(" ·")} ${it.name}에서 ${it.habit.substringAfter("· ")}할 수 있어요. ${it.detourMinutes}분이면 돼요." }
            ?: if (skipStop) "말씀대로 들르지 않고 바로 갈게요." else "시간이 빠듯해서 들르지 않고 바로 갈게요.",
    )

    val conclusion = (stop?.let { "평소처럼 ${it.name.substringBefore(' ')} 들렀다가 " } ?: "바로 ") +
        "${parkName}에 세울게요. ${driveMinutes}분 걸리니까 지금 출발할게요."
    val rainText = weather?.takeIf { it.rainMm > 0 }?.let { "${it.hour}시 비 ${it.rainMm}mm" }
}

data class Palette(val dark: Boolean, val panel: Color, val head: Color, val fg: Color, val sub: Color, val line: Color, val btn2: Color)
val DayPalette = Palette(false, Color.White, Color(0xFFF4F4F4), Color(0xFF1D1D1F), Color(0xFF8A8A8E), Color(0xFFE6E6E6), Color(0xFFECECEC))
val NightPalette = Palette(true, Color(0xFF22252B), Color(0xFF2A2E35), Color(0xFFF1F2F4), Color(0xFF9AA0AA), Color(0xFF33373F), Color(0xFF33373F))
val Blue = Color(0xFF3E6AE1)
val StopColor = Color(0xFFF59E0B)
val ParkColor = Color(0xFF10B981)
val DestColor = Color(0xFFE11D48)

// 주간(6~18시)엔 밝은 지도, 그 외엔 어두운 지도
fun paletteNow() = if (clockNow().hour in 6..17) DayPalette else NightPalette

// OSM 기본 타일(키 없음)을 색 보정해서 주간은 회색톤, 야간은 반전된 어두운 지도로 씀
private fun mapFilter(dark: Boolean) = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply {
    setSaturation(0.15f)
    if (dark) postConcat(android.graphics.ColorMatrix(floatArrayOf(
        -0.85f, 0f, 0f, 0f, 235f,  0f, -0.85f, 0f, 0f, 238f,  0f, 0f, -0.85f, 0f, 248f,  0f, 0f, 0f, 1f, 0f)))
})

class MainActivity : ComponentActivity() {
    private lateinit var navi: NaviHelper
    private val naviStatus = mutableStateOf("내비 연결 중")
    private val routeSeconds = mutableStateOf<Int?>(null)
    private lateinit var tts: TextToSpeech
    private lateinit var stt: SpeechToText
    private val voice = mutableStateOf("")
    private val heard = mutableStateOf<String?>(null)
    private var listenAfterSpeech = false
    private lateinit var fused: FusedLocation
    private val origin = mutableStateOf<Pair<Double, Double>?>(null)
    private val locationListener = object : FusedLocationListener {
        // 에뮬레이터 기본 GPS(미국)처럼 국내가 아닌 좌표는 무시
        override fun onChanged(location: LocationData?) {
            location?.takeIf { it.latitude in 33.0..39.0 && it.longitude in 124.0..132.0 }?.let { origin.value = it.latitude to it.longitude }
        }
        override fun onError(e: Exception) {}
    }
    private val ttsListener = object : EventListener {
        override fun onDone() { if (listenAfterSpeech) { listenAfterSpeech = false; voice.value = "듣고 있어요…"; stt.request() } }
        override fun onError(type: ai.pleos.playground.tts.constant.ErrorType) { voice.value = "음성 출력 오류: $type" }
        override fun onStart() {}
        override fun onReady() {}
        override fun onStop() {}
        override fun onUpdatedRms(rms: Double) {}
    }
    private val sttListener = object : ResultListener {
        override fun onUpdated(text: String, isFinal: Boolean) { voice.value = "“$text”"; if (isFinal) heard.value = text }
        override fun onStartedRecognition() { voice.value = "듣고 있어요…" }
        override fun onError(type: ai.pleos.playground.stt.constant.ErrorType) { voice.value = "음성 인식 오류: $type" }
        override fun onUpdatedEpdData(on: Long, off: Long) {}
        override fun onEndedRecognition() {}
        override fun onReady() {}
        override fun onUpdatedRms(rms: Double) {}
    }

    private fun say(text: String, listen: Boolean) {
        voice.value = ""
        heard.value = null
        listenAfterSpeech = listen
        tts.speak(text)
    }
    private val naviListener = object : NaviHelperEventListener {
        override fun onRouteStarted(info: RouteStartInfo) {
            routeSeconds.value = info.duration
            backToApp()
        }
        override fun onWaypointChanged(info: WaypointChangedInfo) { backToApp() }
        override fun onRoutingCompleted() { naviStatus.value = "내비 경로 탐색 완료" }
        override fun onError(errorCode: NaviErrorCode) { naviStatus.value = "내비 오류: $errorCode" }
    }

    // 내비가 앞으로 오므로 3초 지도 보여준 뒤 앱으로 복귀 (SYSTEM_ALERT_WINDOW 필요)
    private fun backToApp() { window.decorView.postDelayed({
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }, 2000) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        navi = NaviHelper(applicationContext)
        navi.initialize(
            onConnected = { navi.addListener(naviListener); naviStatus.value = "내비 연결됨" },
            onFailed = { naviStatus.value = "내비 연결 실패: ${it.message}" },
        )
        tts = TextToSpeech(applicationContext, ai.pleos.playground.tts.constant.Mode.HYBRID)
        tts.initialize(); tts.addEventListener(ttsListener); tts.setLocale(Locale.KOREA)
        stt = SpeechToText(applicationContext, ai.pleos.playground.stt.constant.Mode.HYBRID)
        stt.initialize(); stt.addListener(sttListener); stt.setLocale(Locale.KOREA)
        fused = FusedLocation(applicationContext)
        fused.initialize()
        requestPermissions(arrayOf(LOCATION_PERMISSION), 0)
        window.decorView.postDelayed(::locate, 2000)
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = cacheDir
            osmdroidTileCache = File(cacheDir, "tiles")
        }
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Blue)) {
                MosidaScreen(naviStatus.value, routeSeconds.value, origin.value ?: fallbackOrigin, voice.value, heard.value, ::go, ::say)
            }
        }
    }

    private fun locate() = runCatching { fused.getLastKnownLocation(locationListener) }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        locate()
    }

    override fun onDestroy() {
        navi.removeListener(naviListener)
        navi.release()
        tts.removeEventListener(ttsListener); tts.release()
        stt.removeListener(sttListener); stt.release()
        fused.release()
        super.onDestroy()
    }

    // 경유지를 나중에 따로 넣으면 내비가 재탐색하는 동안 앱이 백그라운드에서 멈춰 복귀를 못 함 → 한 번에 요청
    private fun go(a: Appointment, stop: Stopover?) {
        routeSeconds.value = null
        val dest = RouteInfo(latitude = a.lat, longitude = a.lng, displayLongitude = null, displayLatitude = null,
            poiName = a.place, poiId = "", poiSubId = "0", address = a.place)
        if (stop == null) navi.requestRoute(dest)
        else navi.requestRouteWithWaypoints(RouteWithWaypointsRequest(dest, listOf(RequestWaypointInfo(latitude = stop.lat,
            longitude = stop.lng, waypointIndex = WaypointIndex.FIRST, poiId = "", poiName = stop.name, poiSubId = "0", address = stop.name))))
    }
}

@Composable
fun MosidaScreen(naviStatus: String, routeSeconds: Int?, origin: Pair<Double, Double>, voice: String, heard: String?,
                   onGo: (Appointment, Stopover?) -> Unit, say: (String, Boolean) -> Unit) {
    var step by remember { mutableStateOf(Step.Waiting) }
    var appt by remember { mutableStateOf(demoAppointment) }
    var weather by remember { mutableStateOf<Weather?>(null) }
    var parking by remember { mutableStateOf<Parking?>(null) }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var skipStop by remember { mutableStateOf(false) }
    var lateDraft by remember { mutableStateOf<String?>(null) }
    var road by remember { mutableStateOf<RoadRoute?>(null) }
    val plan = remember(appt, weather, parking, routeSeconds, step, origin, skipStop, road) {
        Plan(appt, weather, parking, routeSeconds, origin, skipStop, road?.minutes)
    }
    suspend fun loadRoad(stop: Stopover?) {
        road = runCatching { tmapRoute(origin, appt.lat to appt.lng, stop?.let { it.lat to it.lng }) }.getOrNull()
    }
    val consent = { onGo(plan.appt, plan.stop); step = Step.Guiding }
    // 첫 거절엔 들르지 않는 대안을 다시 제안, 두 번째 거절이면 처음으로
    val reject: () -> Unit = { if (skipStop) step = Step.Waiting else scope.launch { loadRoad(null); skipStop = true } }

    LaunchedEffect(step, skipStop) {
        when (step) {
            Step.Proposal -> say((if (skipStop) "그럼 " else "") + plan.conclusion +
                (if (skipStop) "" else " 음악은 늘 듣던 ${usualPlaylist}로 틀게요."), true)
            Step.Guiding -> {
                lateDraft = if (plan.spare < 0) lateReplyDraft(plan.appt, -plan.spare) else null
                say("좋아요, 지금 출발할게요." + (lateDraft?.let { " ${plan.appt.who}한테 늦는다고 이렇게 보내면 될까요? $it" } ?: ""), false)
            }
            else -> {}
        }
    }
    LaunchedEffect(heard) {
        val h = heard ?: return@LaunchedEffect
        if (step != Step.Proposal) return@LaunchedEffect
        when (classifyAnswer(h, plan.conclusion)) {
            Answer.Yes -> consent()
            Answer.No -> reject()
            Answer.Unknown -> say("잘 못 들었어요. 이대로 출발할까요?", true)
        }
    }

    val pal = paletteNow()
    val showRoute = step == Step.Proposal || step == Step.Guiding

    Box(Modifier.fillMaxSize().background(if (pal.dark) Color(0xFF1B1E25) else Color(0xFFE8E8E6))) {
        RouteMap(pal.dark, origin, plan, road?.points, showRoute, Modifier.fillMaxSize())
        Row(Modifier.align(Alignment.TopCenter).padding(top = 14.dp).shadow(8.dp, RoundedCornerShape(20.dp))
            .background(pal.panel, RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(hm(clockNow()), color = pal.fg, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            weather?.let { Text("${it.temp}°C", color = pal.fg, fontSize = 18.sp, fontWeight = FontWeight.Medium) }
            plan.rainText?.let { Text(it, color = pal.sub, fontSize = 15.sp) }
        }
        Column(Modifier.padding(18.dp).width(380.dp).shadow(16.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).background(pal.panel)) {
            when (step) {
                Step.Waiting -> {
                    PanelHead(pal, "카카오톡", "새 메시지를 기다리는 중")
                    PanelActions {
                        BlueButton("카톡 수신 시뮬레이션") {
                            step = Step.Thinking
                            skipStop = false
                            scope.launch {
                                status = "메시지에서 약속 찾는 중"
                                appt = runCatching { parseAppointment(askBot(appointmentPrompt(demoMessages))) }
                                    .onSuccess { status = "PC 비서 분석 완료" }
                                    .getOrElse { status = "PC 비서 연결 실패, 예시 데이터 사용"; demoAppointment }
                                status = "날씨와 주차장 확인 중"
                                weather = runCatching { weatherAt(appt) }.getOrNull()
                                parking = runCatching { parkingNear(appt) }.getOrNull()
                                status = "TMAP 실시간 교통으로 경로 계산 중"
                                loadRoad(Plan(appt, weather, parking, null, origin, false).stop)
                                step = Step.Proposal
                            }
                        }
                        Text(naviStatus, color = pal.sub, fontSize = 13.sp)
                    }
                }
                Step.Thinking -> {
                    PanelHead(pal, "${demoMessages[1].sender} · 카카오톡", "“${demoMessages[1].text}”")
                    Text(status, color = pal.sub, fontSize = 15.sp, modifier = Modifier.padding(18.dp))
                }
                Step.Proposal -> {
                    PanelHead(pal, "${appt.who} · 카카오톡", "“${demoMessages[1].text}”")
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("비서 제안", color = Blue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(plan.conclusion, color = pal.fg, fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 23.sp)
                    }
                    RouteRows(pal, plan, times = false)
                    PanelActions {
                        BlueButton("“응, 그렇게 해” · 출발", onClick = consent)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GrayButton(pal, "다르게 갈래", Modifier.weight(1f), onClick = reject)
                            GrayButton(pal, "말하기") { say("네, 말씀하세요.", true) }
                        }
                        if (voice.isNotEmpty()) Text(voice, color = pal.sub, fontSize = 13.sp)
                    }
                    PanelFooter(pal, road?.km, plan)
                }
                Step.Guiding -> {
                    PanelHead(pal, "“응, 그렇게 해” 듣고 출발했어요", "${appt.place} 안내 중")
                    RouteRows(pal, plan, times = true)
                    lateDraft?.let {
                        Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp).fillMaxWidth()
                            .background(pal.head, RoundedCornerShape(8.dp)).padding(12.dp)) {
                            Text("${appt.who}한테 보낼 답장 초안", color = pal.sub, fontSize = 12.sp)
                            Text(it, color = pal.fg, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                    PanelActions { GrayButton(pal, "경로 바꾸기", Modifier.fillMaxWidth()) { step = Step.Waiting } }
                    PanelFooter(pal, road?.km, plan)
                }
            }
        }
        if (showRoute) MediaBar(pal, if (step == Step.Guiding) "평소처럼 이어서 재생 중" else "출발하면 평소처럼 이어서 틀게요",
            Modifier.align(Alignment.BottomEnd).padding(24.dp))
    }
}

@Composable
fun PanelHead(pal: Palette, label: String, title: String) = Column(
    Modifier.fillMaxWidth().background(pal.head).padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(label, color = pal.sub, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    Text(title, color = pal.fg, fontSize = 18.sp, fontWeight = FontWeight.Bold)
}

@Composable
fun RouteRows(pal: Palette, plan: Plan, times: Boolean) {
    @Composable
    fun row(icon: @Composable () -> Unit, top: String, title: String, sub: String) {
        HorizontalDivider(color = pal.line)
        Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) { icon() }
            Column(Modifier.padding(start = 14.dp)) {
                Text(top, color = pal.sub, fontSize = 12.sp)
                Text(title, color = pal.fg, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(sub, color = pal.sub, fontSize = 12.sp)
            }
        }
    }
    val dot = { c: Color -> @Composable { Box(Modifier.size(14.dp).background(c, CircleShape)) } }
    row({ Text("↑", color = pal.sub, fontSize = 20.sp, fontWeight = FontWeight.Bold) },
        if (times) hm(plan.departAt) else "지금", "출발", plan.spareText)
    plan.stop?.let { row(dot(StopColor), "+${it.detourMinutes}분", it.name, it.habit) }
    plan.parking?.let {
        row({ Text("P", color = ParkColor, fontSize = 20.sp, fontWeight = FontWeight.Bold) },
            "${it.free}면", it.name, listOfNotNull(plan.rainText?.let { r -> "$r 예보" }, it.walk).joinToString(" · "))
    }
    row(dot(DestColor), if (times) hm(plan.arriveAt) else plan.appt.time, plan.appt.place, "${plan.appt.who}와 약속")
    HorizontalDivider(color = pal.line)
}

@Composable
fun PanelActions(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)

@Composable
fun PanelFooter(pal: Palette, km: Double?, plan: Plan) = Row(
    Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(km?.let { "%.1f km".format(it) } ?: "", color = pal.sub, fontSize = 14.sp)
    Text("${plan.driveMinutes}분", color = pal.sub, fontSize = 14.sp)
    Text("${hm(plan.arriveAt)} 도착", color = pal.fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
}

@Composable
fun BlueButton(label: String, onClick: () -> Unit) = Text(label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
    modifier = Modifier.fillMaxWidth().background(Blue, RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(vertical = 13.dp).wrapContentWidth())

@Composable
fun GrayButton(pal: Palette, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) = Text(label, color = pal.fg, fontSize = 15.sp,
    fontWeight = FontWeight.Medium, modifier = modifier.background(pal.btn2, RoundedCornerShape(6.dp)).clickable(onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 12.dp).wrapContentWidth())

@Composable
fun MediaBar(pal: Palette, sub: String, modifier: Modifier) = Row(
    modifier.shadow(12.dp, RoundedCornerShape(12.dp)).background(pal.panel, RoundedCornerShape(12.dp)).padding(12.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    Box(Modifier.size(48.dp).background(Brush.linearGradient(listOf(Color(0xFFF97316), Color(0xFFDB2777))), RoundedCornerShape(6.dp)))
    Column {
        Text(usualPlaylist, color = pal.fg, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(sub, color = pal.sub, fontSize = 13.sp)
    }
    Spacer(Modifier.width(8.dp))
    Canvas(Modifier.size(width = 96.dp, height = 18.dp)) {
        val h = size.height; val c = pal.fg
        fun tri(x: Float, right: Boolean) = drawPath(Path().apply {
            if (right) { moveTo(x, 0f); lineTo(x + h * 0.8f, h / 2); lineTo(x, h) } else { moveTo(x + h * 0.8f, 0f); lineTo(x, h / 2); lineTo(x + h * 0.8f, h) }
            close() }, c)
        drawRect(c, Offset(0f, 0f), androidx.compose.ui.geometry.Size(3.dp.toPx(), h)); tri(4.dp.toPx(), false)
        val mid = size.width / 2
        drawRect(c, Offset(mid - 6.dp.toPx(), 0f), androidx.compose.ui.geometry.Size(4.dp.toPx(), h))
        drawRect(c, Offset(mid + 2.dp.toPx(), 0f), androidx.compose.ui.geometry.Size(4.dp.toPx(), h))
        tri(size.width - 4.dp.toPx() - h * 0.8f, true); drawRect(c, Offset(size.width - 3.dp.toPx(), 0f), androidx.compose.ui.geometry.Size(3.dp.toPx(), h))
    }
}

// 지도 위 핀: 색 점 + 이름표를 비트맵으로 그림
fun pinIcon(ctx: android.content.Context, color: Int, label: String?, dark: Boolean): BitmapDrawable {
    val d = ctx.resources.displayMetrics.density
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14 * d; this.color = if (dark) 0xFFE8EAED.toInt() else 0xFF222222.toInt(); isFakeBoldText = true }
    val r = 10 * d
    val tagW = label?.let { text.measureText(it) + 20 * d } ?: 0f
    val w = (2 * r + 4 * d + if (label != null) 8 * d + tagW else 0f).toInt()
    val h = (30 * d).toInt()
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = android.graphics.Color.WHITE; c.drawCircle(r + 2 * d, h / 2f, r + 2 * d, p)
    p.color = color; c.drawCircle(r + 2 * d, h / 2f, r - 1 * d, p)
    if (label != null) {
        val x = 2 * r + 12 * d
        p.color = if (dark) 0xFF22262E.toInt() else android.graphics.Color.WHITE
        c.drawRoundRect(RectF(x, 1 * d, x + tagW, h - 1 * d), 6 * d, 6 * d, p)
        c.drawText(label, x + 10 * d, h / 2f + 5 * d, text)
    }
    return BitmapDrawable(ctx.resources, bmp)
}

@Composable
fun RouteMap(dark: Boolean, origin: Pair<Double, Double>, plan: Plan, road: List<GeoPoint>?, showRoute: Boolean, modifier: Modifier) =
    AndroidView(modifier = modifier, factory = { ctx ->
        MapView(ctx).apply {
            setMultiTouchControls(true)
            isTilesScaledToDpi = true
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(15.0)
        }
    }, update = { mv ->
        val ctx = mv.context
        mv.overlayManager.tilesOverlay.setColorFilter(mapFilter(dark))
        mv.overlays.clear()
        fun pin(lat: Double, lng: Double, color: Int, label: String?) = mv.overlays.add(Marker(mv).apply {
            position = GeoPoint(lat, lng); icon = pinIcon(ctx, color, label, dark); setAnchor(0.0f, 0.5f); setInfoWindow(null)
        })
        val start = GeoPoint(origin.first, origin.second)
        if (showRoute) {
            val line = road ?: listOfNotNull(start, plan.stop?.let { GeoPoint(it.lat, it.lng) }, GeoPoint(plan.appt.lat, plan.appt.lng))
            mv.overlays.add(Polyline(mv).apply {
                setPoints(line)
                outlinePaint.color = 0xFF3E7BFA.toInt(); outlinePaint.strokeWidth = 12f * ctx.resources.displayMetrics.density / 2
                outlinePaint.strokeCap = Paint.Cap.ROUND; outlinePaint.strokeJoin = Paint.Join.ROUND
            })
            plan.stop?.let { pin(it.lat, it.lng, 0xFFF59E0B.toInt(), it.name) }
            plan.parking?.let { p -> pin(p.lat, p.lng, 0xFF10B981.toInt(), "P ${p.short} · ${p.free}면") }
            pin(plan.appt.lat, plan.appt.lng, 0xFFE11D48.toInt(), plan.appt.place)
            // 왼쪽 패널에 가리지 않게, 경로 왼쪽에 빈 영역을 하나 더 넣어서 화면을 맞춤
            val box = BoundingBox.fromGeoPoints(line + listOfNotNull(plan.parking?.let { GeoPoint(it.lat, it.lng) }))
            val pad = GeoPoint(box.centerLatitude, box.lonWest - box.longitudeSpan * 0.9)
            mv.post { mv.zoomToBoundingBox(BoundingBox.fromGeoPoints(line + pad + listOfNotNull(plan.parking?.let { GeoPoint(it.lat, it.lng) })).increaseByScale(1.25f), false) }
        } else mv.post { mv.controller.setCenter(start) }
        pin(origin.first, origin.second, 0xFF3E7BFA.toInt(), null)
        mv.invalidate()
    })
