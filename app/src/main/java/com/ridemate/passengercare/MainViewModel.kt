package com.ridemate.passengercare

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ridemate.passengercare.care.ActionStatus
import com.ridemate.passengercare.care.BeltAlarm
import com.ridemate.passengercare.care.BeltAlert
import com.ridemate.passengercare.care.CareAlerter
import com.ridemate.passengercare.care.ExitAlarm
import com.ridemate.passengercare.care.ExitAlarmKind
import com.ridemate.passengercare.care.ExitAlert
import com.ridemate.passengercare.care.VoiceCommand
import com.ridemate.passengercare.care.VoiceInput
import com.ridemate.passengercare.care.VoiceParser
import com.ridemate.passengercare.care.RestCandidate
import com.ridemate.passengercare.care.RestFinder
import com.ridemate.passengercare.care.RestResult
import com.ridemate.passengercare.data.RestAreaRepository
import com.ridemate.passengercare.env.EnvClient
import com.ridemate.passengercare.vehicle.NaviGateway
import com.ridemate.passengercare.care.Position
import com.ridemate.passengercare.env.EnvJudge
import com.ridemate.passengercare.env.EnvState
import com.ridemate.passengercare.env.Weather
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.ridemate.passengercare.vehicle.CareScene
import kotlinx.coroutines.Job
import com.ridemate.passengercare.care.CabinAction
import com.ridemate.passengercare.care.CabinPlanner
import com.ridemate.passengercare.data.Passenger
import com.ridemate.passengercare.data.PassengerStore
import com.ridemate.passengercare.data.PassengerGroup
import com.ridemate.passengercare.data.PlaceStore
import com.ridemate.passengercare.data.SavedPlace
import com.ridemate.passengercare.data.SeatAssignment
import com.ridemate.passengercare.vehicle.DriveState
import com.ridemate.passengercare.vehicle.SeatPosition
import com.ridemate.passengercare.vehicle.VehicleGateway
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val gateway = VehicleGateway(application)
    private val store = PassengerStore(application)
    private val placeStore = PlaceStore(application)

    val seats = gateway.seats
    val log = gateway.log
    val invertOccupancy = gateway.invertOccupancy
    val drive = gateway.drive
    val driveSimulated = gateway.driveSimulated
    val beltSimulated = gateway.beltSimulated
    val hvacReady = gateway.hvacReady
    val passengers: StateFlow<List<Passenger>> = store.passengers

    /** 이번 주행에서 운전자가 확정한 좌석 배정. */
    private val _assignments = MutableStateFlow<Map<SeatPosition, SeatAssignment>>(emptyMap())
    val assignments: StateFlow<Map<SeatPosition, SeatAssignment>> = _assignments.asStateFlow()

    /** 출발 전 확인: 운전자가 직접 누른 항목 id */
    private val _manualChecked = MutableStateFlow<Set<String>>(emptySet())
    val manualChecked: StateFlow<Set<String>> = _manualChecked.asStateFlow()

    /** 출발 전 확인: 적용한 세팅과 결과 */
    private val _actions = MutableStateFlow<List<Pair<CabinAction, ActionStatus>>>(emptyList())
    val actions: StateFlow<List<Pair<CabinAction, ActionStatus>>> = _actions.asStateFlow()

    /** 출발을 누른 뒤 운행 종료까지. 이 동안만 벨트 경보를 낸다. */
    private val _tripActive = MutableStateFlow(false)
    val tripActive: StateFlow<Boolean> = _tripActive.asStateFlow()

    /** 지금 벨트가 풀려 있는 동승자(센서 그대로) */
    private val rawBeltAlerts: StateFlow<List<BeltAlert>> =
        combine(_assignments, gateway.seats, gateway.drive, _tripActive) { a, s, d, trip ->
            if (trip) BeltAlarm.targets(a, s, d) else emptyList()
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** A1 · 경보 대상: 풀린 상태가 3초 이상 이어진 동승자(잠깐 고쳐 매는 경우는 경보하지 않는다) */
    private val _beltAlerts = MutableStateFlow<List<BeltAlert>>(emptyList())
    val beltAlerts: StateFlow<List<BeltAlert>> = _beltAlerts.asStateFlow()

    /** 하차 확인 화면에 들어왔는지(= 도착) */
    private val _arrived = MutableStateFlow(false)
    val arrived: StateFlow<Boolean> = _arrived.asStateFlow()

    /** 하차 확인에서 운전자가 눌러 '내림'으로 확인한 좌석 */
    private val _confirmedOut = MutableStateFlow<Set<SeatPosition>>(emptySet())
    val confirmedOut: StateFlow<Set<SeatPosition>> = _confirmedOut.asStateFlow()

    private val _belongingsChecked = MutableStateFlow(false)
    val belongingsChecked: StateFlow<Boolean> = _belongingsChecked.asStateFlow()

    private data class TripFlags(val active: Boolean, val arrived: Boolean, val out: Set<SeatPosition>)

    private val tripFlags = combine(_tripActive, _arrived, _confirmedOut) { a, b, c -> TripFlags(a, b, c) }

    /** A2 차내 잔류 / A3 운전자만 하차 (날씨 반영 전) */
    private val exitAlertBase: StateFlow<ExitAlert?> =
        combine(_assignments, gateway.seats, gateway.drive, tripFlags, _actions) { a, s, d, t, acts ->
            val locked = acts.filter { (act, st) -> act is CabinAction.LockWindow && st == ActionStatus.DONE }
                .map { it.first.position }.toSet()
            ExitAlarm.evaluate(a, s, d, t.active, t.arrived, t.out, locked)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val alerter = CareAlerter(application) { gateway.note(it) }
    private var cabinLightByApp = false

    // ---- 화면 마이크 버튼 (입력 채널 ②) ----

    sealed interface VoiceUi {
        data object Idle : VoiceUi
        data object Listening : VoiceUi
        /** 1-a: 알아듣지 못했거나 음성 인식을 못 쓸 때 큰 버튼으로 고르기 */
        data class Choices(val reason: String) : VoiceUi
        data class Done(val message: String) : VoiceUi
        /** M7 쉬어갈 곳 상위 2곳 */
        data class RestResults(val result: RestResult, val positionLabel: String) : VoiceUi
        /** 긴급 병원 안내 시작 + 되돌리기 */
        data class HospitalStarted(val message: String, val previousName: String?) : VoiceUi
    }

    // ---- 긴급 병원 (기획서 S10 · M9 긴급) ----

    /** 등록한 긴급 병원(분만 병원 등). 길 안내 앱의 즐겨찾기·최근 목적지에서 고른다 */
    val hospital: StateFlow<SavedPlace?> = placeStore.hospital
    val naviBookmarks get() = navi.bookmarks
    val naviRecents get() = navi.recents

    fun refreshPlaces() = navi.requestPlaces()

    fun setHospital(place: SavedPlace?) {
        placeStore.setHospital(place)
        gateway.note("긴급 병원 → ${place?.name ?: "해제"}")
    }

    /** 임산부가 타고 있으면 주행 화면에 긴급 버튼을 늘 보여준다 */
    val pregnantAboard: StateFlow<Boolean> = _assignments.map { a ->
        a.values.any {
            (it as? SeatAssignment.Known)?.passenger?.group == PassengerGroup.PREGNANT ||
                (it as? SeatAssignment.Guest)?.group == PassengerGroup.PREGNANT
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 긴급 안내 전 목적지(되돌리기용) */
    private var beforeHospital: SavedPlace? = null

    private fun goHospital() {
        val h = hospital.value
        if (h == null) {
            val msg = "긴급 병원이 등록되어 있지 않아요. 정차 중에 홈 화면에서 등록해 주세요."
            alerter.say(msg)
            _voice.value = VoiceUi.Done(msg)
            scheduleIdle(6_000L) { it is VoiceUi.Done }
            return
        }
        val (ok, previous) = navi.goTo(h)
        if (!ok) {
            val msg = "내비가 연결되어 있지 않아 안내할 수 없어요. ${h.name}"
            alerter.say(msg)
            _voice.value = VoiceUi.Done(msg)
            scheduleIdle(6_000L) { it is VoiceUi.Done }
            return
        }
        beforeHospital = previous
        viewModelScope.launch { delay(5_000); navi.refresh() }
        val msg = "${h.name}으로 바로 안내를 시작했어요."
        gateway.note("긴급 · $msg (되돌릴 곳: ${previous?.name ?: "안내 종료"})")
        alerter.alert(msg)
        _voice.value = VoiceUi.HospitalStarted(msg, previous?.name)
        // 되돌리기 버튼은 20초 동안만 보여준다
        scheduleIdle(20_000L) { it is VoiceUi.HospitalStarted }
    }

    /** "취소" 한 번으로 되돌린다: 직전 목적지가 있으면 그곳으로, 없으면 안내 종료 */
    fun undoHospital() {
        val prev = beforeHospital
        navi.undoGoTo(prev)
        beforeHospital = null
        val msg = prev?.let { "${it.name}으로 다시 안내해요." } ?: "긴급 안내를 취소했어요."
        gateway.note("긴급 취소 · $msg")
        alerter.say(msg)
        _voice.value = VoiceUi.Done(msg)
        scheduleIdle(4_000L) { it is VoiceUi.Done }
        viewModelScope.launch {
            delay(5_000)
            navi.refresh()
            // 안내 취소를 내비가 받지 않으면(에뮬레이터 등) 직접 끄도록 알려준다
            delay(2_000)
            if (prev == null && navi.destination.value?.name == hospital.value?.name && hospital.value != null) {
                val m = "내비 화면에서 '안내 종료'를 눌러 주세요."
                gateway.note("긴급 취소 · 내비가 안내 취소를 받지 않음")
                _voice.value = VoiceUi.Done(m)
                scheduleIdle(6_000L) { it is VoiceUi.Done }
            }
        }
    }

    // ---- M7 쉬어갈 곳 ----

    private val restAreas by lazy { RestAreaRepository.load(getApplication()) }
    /** Pleos 길 안내 앱 연결: 현재 위치·목적지·경유지·도착 이벤트 */
    private val navi = NaviGateway(application) { gateway.note(it) }
    val naviDestination = navi.destination
    val naviConnected = navi.isConnected

    /** 0..n-1 = 시연용 위치, n = 내비 현재 위치 */
    private val _demoPosition = MutableStateFlow(0)

    /** 지금 쓰는 위치. 내비 위치를 골랐지만 아직 없으면 첫 시연 위치 */
    private fun positionFor(mode: Int, loc: NaviGateway.NaviLocation?): Position =
        if (mode >= RestFinder.demoPositions.size) {
            loc?.let { Position(it.lat, it.lng, it.heading, "현재 위치(내비) · ${it.address.take(14)}") } ?: RestFinder.demoPositions[0]
        } else RestFinder.demoPositions[mode]

    private fun currentPosition(): Position = positionFor(_demoPosition.value, navi.location.value)

    val demoPositionLabel: StateFlow<String> = combine(_demoPosition, navi.location) { m, l -> positionFor(m, l).label }
        .stateIn(viewModelScope, SharingStarted.Eagerly, RestFinder.demoPositions[0].label)

    /** 내비가 도착을 알렸을 때 화면을 하차 확인으로 바꾸기 위한 신호 */
    private val _autoArrive = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)
    val autoArrive: kotlinx.coroutines.flow.SharedFlow<String> = _autoArrive
    private val _nextStop = MutableStateFlow<RestCandidate?>(null)
    val nextStop: StateFlow<RestCandidate?> = _nextStop.asStateFlow()

    fun cycleDemoPosition() {
        val withNavi = navi.location.value != null
        val count = RestFinder.demoPositions.size + if (withNavi) 1 else 0
        _demoPosition.update { (it + 1) % count }
        gateway.note("위치 → ${currentPosition().label}")
        refreshEnv()
    }

    // ---- M12 날씨·노면 (화면 표시만, 소리 없음) ----

    private val _envReal = MutableStateFlow(EnvState.EMPTY)
    private val _envSim = MutableStateFlow<Pair<Weather, Boolean>?>(null)
    private val _roadSim = MutableStateFlow(false)
    val roadSimulated: StateFlow<Boolean> = _roadSim.asStateFlow()
    /** 시연용 날씨 이름. null = 실제 값 */
    val weatherSimLabel: StateFlow<String?> = _envSim
        .map { it?.let { (w, ice) -> w.label + if (ice) "·결빙" else "" } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val env: StateFlow<EnvState> = combine(_envReal, _envSim, _roadSim, _demoPosition) { real, sim, road, pos ->
        var e = if (sim == null) real else EnvJudge.simulate(real, sim.first, sim.second)
        if (road) e = EnvJudge.simulateRoad(e, positionFor(pos, navi.location.value))
        e
    }.stateIn(viewModelScope, SharingStarted.Eagerly, EnvState.EMPTY)

    /** A2·A3 + 폭염·한파면 외기 온도를 문구에 넣고 더 자주 알린다 */
    val exitAlert: StateFlow<ExitAlert?> = combine(exitAlertBase, env) { a, e ->
        val extreme = e.weather == Weather.HEAT || e.weather == Weather.COLD
        if (a != null && extreme) a.copy(outsideTempC = e.tempC?.let { kotlin.math.round(it).toInt() }) else a
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun toggleRoadSim() {
        _roadSim.update { !it }
        gateway.note("[시뮬레이션] 도로 → ${if (_roadSim.value) "앞 7km 작업" else "실제 값"}")
    }

    /** 출발·도착·위치 변경 때 한 번 받는다. 실패하면 이전 값을 "최신 아님"으로 둔다(E09). */
    fun refreshEnv() {
        val position = currentPosition()
        viewModelScope.launch {
            val w = EnvClient.weather(position.lat, position.lng)
            val events = EnvClient.roadEvents()
            val now = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date())
            if (w == null && events == null) {
                _envReal.update { it.copy(stale = true) }
                gateway.note("날씨·도로 조회 실패 → 이전 값 사용")
                return@launch
            }
            val state = EnvJudge.judge(w, events, position, now)
            _envReal.value = state
            gateway.note("날씨·도로 · ${state.summary} (돌발 ${events?.size ?: 0}건, 폐쇄 ${state.closedPlaces})")
        }
    }

    /** 시연용 날씨 순환: 실제 → 비 → 눈·결빙 → 폭염 → 실제 */
    fun cycleWeatherSim() {
        _envSim.update {
            when (it?.first) {
                null -> Weather.RAIN to false
                Weather.RAIN -> Weather.SNOW to true
                Weather.SNOW -> Weather.HEAT to false
                else -> null
            }
        }
        gateway.note("[시뮬레이션] 날씨 → ${_envSim.value?.let { it.first.label + if (it.second) " · 결빙" else "" } ?: "실제 값"}")
    }

    /** 하차 확인의 날씨 항목(우산·미끄럼) 체크 */
    private val _envChecked = MutableStateFlow<Set<String>>(emptySet())
    val envChecked: StateFlow<Set<String>> = _envChecked.asStateFlow()
    fun toggleEnvItem(id: String) = _envChecked.update { if (id in it) it - id else it + id }

    private fun findRest() {
        val position = currentPosition()
        val result = RestFinder.find(restAreas, position, _assignments.value, env.value, restDue.value?.remainingMin)
        gateway.note("쉬어갈 곳 · 조건 ${result.needs.map { it.label }} → ${result.candidates.map { it.area.displayName }}")
        alerter.say(result.spoken)
        _voice.value = VoiceUi.RestResults(result, position.label)
        // 고르지 않으면 20초 뒤 조용히 닫는다(E06). 다시 묻지 않는다.
        scheduleIdle(20_000L) { it is VoiceUi.RestResults }
    }

    fun chooseRest(c: RestCandidate) {
        _nextStop.value = c
        // 내비가 연결돼 있으면 경유지로 넣는다(안내 중이 아니면 그곳으로 안내 시작)
        val routing = navi.destination.value != null
        val sent = navi.goVia(c.area.lat, c.area.lng, c.area.displayName, c.area.id, routing)
        if (sent) viewModelScope.launch { delay(5_000); navi.refresh() }
        val how = when {
            !sent -> "다음 휴식지로 정했어요"
            routing -> "내비 경유지로 추가했어요"
            else -> "내비 안내를 시작했어요"
        }
        val msg = "$how · ${c.area.displayName} · ${c.distanceLabel}"
        gateway.note("쉬어갈 곳 선택 · $msg")
        alerter.say("${c.area.name}, $how.")
        _voice.value = VoiceUi.Done(msg)
        scheduleIdle(4_000L) { it is VoiceUi.Done }
    }

    private val voiceInput = VoiceInput(application)
    private val _voice = MutableStateFlow<VoiceUi>(VoiceUi.Idle)
    val voice: StateFlow<VoiceUi> = _voice.asStateFlow()
    val scene = gateway.scene
    private var voiceTimeout: Job? = null

    /** @return true면 화면이 음성 인식 화면을 띄운다. false면 이미 버튼(1-a)을 보여줬다. */
    fun onMicTap(): Boolean {
        val reason = voiceInput.unavailableReason()
        if (reason != null) {
            gateway.note("음성 인식 없음 → 버튼으로")
            showChoices("$reason · 버튼으로 골라 주세요")
            return false
        }
        _voice.value = VoiceUi.Listening
        return true
    }

    fun voiceIntent() = voiceInput.intent()

    fun onVoiceResult(candidates: List<String>?) {
        gateway.note("음성 인식 결과: ${candidates?.take(3)}")
        val cmd = candidates?.let { VoiceParser.parse(it) }
        if (cmd != null) runCommand(cmd)
        else showChoices(if (candidates.isNullOrEmpty()) "잘 듣지 못했어요" else "\"${candidates.first()}\" · 무엇을 할까요?")
    }

    private fun showChoices(reason: String) {
        _voice.value = VoiceUi.Choices(reason)
        // E06: 10초 동안 고르지 않으면 조용히 닫는다. 다시 묻지 않는다.
        scheduleIdle(10_000L) { it is VoiceUi.Choices }
    }

    fun runCommand(cmd: VoiceCommand) {
        when (cmd) {
            VoiceCommand.SLEEP -> { gateway.applyScene(CareScene.SLEEP); if (_tripActive.value) _sleptThisTrip.value = true }
            VoiceCommand.QUIET -> gateway.applyScene(CareScene.QUIET)
            VoiceCommand.MOTION -> {
                gateway.applyScene(CareScene.MOTION)
                gateway.note("호출 · ${cmd.label}")
                alerter.say(cmd.reply)
                // 기획서 M6 + M7 즉시 모드: 멀미는 바로 설 곳이 필요하다
                val position = currentPosition()
                val result = RestFinder.findNearestStop(restAreas, position, _assignments.value, env.value)
                _voice.value = VoiceUi.RestResults(result, position.label)
                scheduleIdle(20_000L) { it is VoiceUi.RestResults }
                return
            }
            VoiceCommand.RESTORE -> gateway.restoreScene()
            VoiceCommand.ETA -> {
                // 기획서 M5: 아이 눈높이로 바꿔 말한다 (만화 한 편 ≈ 20분)
                val d = navi.destination.value
                val msg = if (d == null) "내비에 목적지가 없어서 남은 시간을 알 수 없어요."
                else {
                    val cartoons = (d.remainMin + 19) / 20
                    "${d.name}까지 약 ${d.remainMin}분 남았어요. 만화 ${cartoons}편 보면 도착해요."
                }
                gateway.note("호출 · ${cmd.label} → $msg")
                alerter.say(msg)
                _voice.value = VoiceUi.Done(msg)
                scheduleIdle(6_000L) { it is VoiceUi.Done }
                return
            }
            VoiceCommand.COMFORT -> {
                // 기획서 M9 · #편안한주행: 내비에 편안한 경로 옵션을 요청
                val ok = navi.comfortableRoute()
                val msg = if (ok) "편안한 길로 바꿔 달라고 내비에 요청했어요." else "내비가 연결되어 있지 않아 경로를 바꿀 수 없어요."
                gateway.note("호출 · ${cmd.label} → $msg")
                alerter.say(msg)
                _voice.value = VoiceUi.Done(msg)
                scheduleIdle(5_000L) { it is VoiceUi.Done }
                return
            }
            VoiceCommand.REST -> {
                gateway.note("호출 · ${cmd.label}")
                findRest()
                return
            }
            VoiceCommand.HOSPITAL -> {
                gateway.note("호출 · ${cmd.label}")
                goHospital()
                return
            }
        }
        gateway.note("호출 · ${cmd.label}")
        alerter.say(cmd.reply)
        _voice.value = VoiceUi.Done(cmd.reply)
        scheduleIdle(4_000L) { it is VoiceUi.Done }
    }

    fun dismissVoice() {
        voiceTimeout?.cancel()
        _voice.value = VoiceUi.Idle
    }

    private fun scheduleIdle(ms: Long, onlyIf: (VoiceUi) -> Boolean) {
        voiceTimeout?.cancel()
        voiceTimeout = viewModelScope.launch {
            delay(ms)
            if (onlyIf(_voice.value)) _voice.value = VoiceUi.Idle
        }
    }

    init {
        gateway.start()
        navi.start()
        refreshEnv()
        viewModelScope.launch { beltAlarmLoop() }
        viewModelScope.launch { exitAlarmLoop() }
    }

    /** A2·A3: 새로 생기면 바로 1회, 이후 10초마다 반복. 끄는 버튼 없음. A2는 실내등도 켠다. */
    private suspend fun exitAlarmLoop() {
        var last: ExitAlert? = null
        var lastSound = 0L
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val current = exitAlert.value
            if (current != null) {
                val changed = current.kind != last?.kind || current.positions != last?.positions
                if (changed || now - lastSound >= ExitAlarm.repeatMs(current)) {
                    alerter.alert(current.spoken)
                    lastSound = now
                }
                if (current.kind == ExitAlarmKind.A2_LEFT_IN_CAR && !cabinLightByApp) {
                    cabinLightByApp = true
                    gateway.setCabinLight(true)
                }
            }
            last = current
            delay(500)
        }
    }

    fun arrive() {
        _arrived.value = true
        _confirmedOut.value = emptySet()
        _belongingsChecked.value = false
        _envChecked.value = emptySet()
        refreshEnv()
        gateway.note("도착 · 하차 확인 시작")
    }

    fun cancelArrive() {
        _arrived.value = false
    }

    fun toggleConfirmedOut(position: SeatPosition) {
        _confirmedOut.update { if (position in it) it - position else it + position }
    }

    fun toggleBelongings() {
        _belongingsChecked.update { !it }
    }

    /**
     * 경보 소리 규칙: 새로 풀리면 바로 1회, 이후 15초마다 반복, 풀린 지 90초가 지나면 소리는 멈추고 화면 표시만.
     * 운전자가 끄는 버튼은 두지 않는다(안전 경보). 벨트를 매거나 정차하면 멈춘다.
     */
    private suspend fun beltAlarmLoop() {
        val since = mutableMapOf<SeatPosition, Long>()
        var lastKeys = emptySet<SeatPosition>()
        var lastSound = 0L
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val raw = rawBeltAlerts.value
            since.keys.retainAll(raw.map { it.position }.toSet())
            raw.forEach { since.getOrPut(it.position) { now } }
            val current = raw.filter { now - since.getValue(it.position) >= BeltAlarm.DEBOUNCE_MS }
            if (current != _beltAlerts.value) _beltAlerts.value = current
            val keys = current.map { it.position }.toSet()
            val audible = current.filter { now - since.getValue(it.position) <= BeltAlarm.SOUND_MAX_MS }
            val hasNew = (keys - lastKeys).isNotEmpty()
            if (audible.isNotEmpty() && (hasNew || now - lastSound >= BeltAlarm.SOUND_REPEAT_MS)) {
                alerter.alert(audible.joinToString(" ") { it.spoken })
                lastSound = now
            }
            lastKeys = keys
            delay(500)
        }
    }

    // ---- M7 권장 휴식 표시 (화면만, 소리 없음) ----

    /** 권장 휴식까지 남은 시간. interval = 동승자별 설정 중 가장 짧은 값, 없으면 2시간(운전자 기준) */
    data class RestDue(val remainingMin: Int, val intervalMin: Int, val basis: String)

    private var restBaseMs = 0L
    private val _clockOffsetMin = MutableStateFlow(0)
    private val _restDue = MutableStateFlow<RestDue?>(null)
    val restDue: StateFlow<RestDue?> = _restDue.asStateFlow()

    private fun updateRestDue() {
        if (!_tripActive.value) { _restDue.value = null; return }
        val custom = _assignments.value.values.filterIsInstance<SeatAssignment.Known>()
            .mapNotNull { k -> k.passenger.restIntervalMin?.let { it to k.passenger.name } }
            .minByOrNull { it.first }
        val interval = custom?.first?.coerceAtMost(120) ?: 120
        val basis = custom?.second?.let { "$it 기준" } ?: "운전자 기준"
        val elapsed = ((SystemClock.elapsedRealtime() - restBaseMs) / 60_000L).toInt() + _clockOffsetMin.value
        _restDue.value = RestDue(interval - elapsed, interval, basis)
    }

    /** 정차해서 쉬었을 때 운전자가 누른다 */
    fun markRested() {
        restBaseMs = SystemClock.elapsedRealtime()
        _clockOffsetMin.value = 0
        _nextStop.value = null
        updateRestDue()
        gateway.note("휴식함 · 권장 휴식 시간 다시 시작")
    }

    /** 시연용: 시간을 30분 앞으로 */
    fun advanceClock() {
        _clockOffsetMin.update { it + 30 }
        updateRestDue()
        gateway.note("[시뮬레이션] 시간 +30분 → 권장 휴식까지 ${_restDue.value?.remainingMin}분")
    }

    /** #수면중: 이번 운행에서 재우기 모드를 썼는지 → 하차 항목 "깨우지 않고 옮기기" */
    private val _sleptThisTrip = MutableStateFlow(false)
    val sleptThisTrip: StateFlow<Boolean> = _sleptThisTrip.asStateFlow()

    fun startTrip() {
        restBaseMs = SystemClock.elapsedRealtime()
        _clockOffsetMin.value = 0
        _sleptThisTrip.value = false
        _tripActive.value = true
        updateRestDue()
        refreshEnv()
        gateway.note("운행 시작")
    }

    fun endTrip() {
        _tripActive.value = false
        _restDue.value = null
        dismissVoice()
        gateway.restoreScene()
        _arrived.value = false
        _confirmedOut.value = emptySet()
        _nextStop.value = null
        if (cabinLightByApp) {
            cabinLightByApp = false
            gateway.setCabinLight(false)
        }
        gateway.note("운행 종료")
    }

    fun simulateDrive(state: DriveState?) = gateway.simulateDrive(state)

    /** 탑승 확정 → 세팅 일괄 적용 → 출발 전 확인 시작 */
    fun startPreDeparture() {
        _manualChecked.value = emptySet()
        val planned = CabinPlanner.actions(_assignments.value)
        _actions.value = planned.map { it to ActionStatus.PENDING }
        fun mark(action: CabinAction, status: ActionStatus) = _actions.update { list ->
            list.map { if (it.first == action) action to status else it }
        }
        planned.forEach { action ->
            val report: (Boolean, String?) -> Unit = { ok, _ -> mark(action, if (ok) ActionStatus.DONE else ActionStatus.MANUAL) }
            when (action) {
                is CabinAction.LockWindow -> gateway.lockWindow(action.position, report)
                is CabinAction.SeatHeatOff -> gateway.setSeatHeat(action.position, 0, report)
                is CabinAction.SetTemperature -> Unit // 아래에서 존 단위로 한 번에
            }
        }
        // E07: 같은 공조 존이면 더 취약한 동승자 기준
        val temps = planned.filterIsInstance<CabinAction.SetTemperature>().sortedBy { it.priority }
        if (temps.isNotEmpty()) {
            gateway.setZoneTemperatures(temps.map { it.position to it.celsius }) { pos, ok, _ ->
                val action = temps.first { it.position == pos }
                mark(action, when (ok) { true -> ActionStatus.DONE; false -> ActionStatus.MANUAL; null -> ActionStatus.SHARED })
            }
        }
    }

    fun toggleManual(id: String) {
        _manualChecked.update { if (id in it) it - id else it + id }
    }

    fun toggleSimulatedBelt(position: SeatPosition) {
        val current = seats.value[position]?.belted == true
        gateway.simulateBelt(position, !current)
    }

    fun savePassenger(p: Passenger) = store.save(p)
    fun deletePassenger(id: String) = store.delete(id)
    fun newPassengerId() = store.newId()

    fun confirmSeat(position: SeatPosition, assignment: SeatAssignment) {
        _assignments.update { it + (position to assignment) }
    }

    fun clearSeat(position: SeatPosition) {
        _assignments.update { it - position }
    }

    fun resetBoarding() {
        _assignments.value = emptyMap()
        gateway.clearBeltSimulation()
        gateway.refreshOccupancy()
    }

    fun refreshSeats() = gateway.refreshOccupancy()

    fun setInvertOccupancy(enabled: Boolean) = gateway.setInvertOccupancy(enabled)

    fun simulateBelt(position: SeatPosition, belted: Boolean) =
        gateway.simulateBelt(position, belted)

    override fun onCleared() {
        navi.release()
        alerter.release()
        gateway.release()
    }

    // 아래 init은 클래스 끝에 둔다: 위에서 선언한 모든 상태가 초기화된 뒤에 실행되어야 한다
    init {
        viewModelScope.launch { while (true) { updateRestDue(); navi.refresh(); delay(30_000) } }
        viewModelScope.launch {
            navi.events.collect { e ->
                when (e) {
                    // 기획서 M8: 도착 이벤트 → 하차 확인 자동 시작 (A3/A2 구분에도 쓰인다)
                    is NaviGateway.Event.Arrived -> when {
                        // 쉬어갈 곳으로 안내받아 도착한 경우는 휴식이지 하차가 아니다
                        _nextStop.value?.area?.displayName == e.name -> markRested()
                        _tripActive.value && !_arrived.value -> { arrive(); _autoArrive.tryEmit(e.name) }
                    }
                    // 고른 휴식지(경유지)에 도착하면 휴식 시간을 다시 시작
                    is NaviGateway.Event.WaypointArrived -> if (_nextStop.value != null) markRested()
                    is NaviGateway.Event.Error -> Unit
                }
            }
        }
        // 장면에 따라 소리 규칙을 바꾼다: 재우기 = 경고음 작게, 조용히 = 응답 음성 끔 (경보는 항상 울림)
        viewModelScope.launch {
            gateway.scene.collect { sc ->
                alerter.softMode = sc == CareScene.SLEEP
                alerter.repliesMuted = sc == CareScene.QUIET
            }
        }
    }
}
