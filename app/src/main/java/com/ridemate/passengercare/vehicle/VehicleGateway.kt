package com.ridemate.passengercare.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.Door
import ai.pleos.playground.vehicle.api.Gear
import ai.pleos.playground.vehicle.api.HVAC
import ai.pleos.playground.vehicle.api.Light
import ai.pleos.playground.vehicle.api.Seat
import ai.pleos.playground.vehicle.api.Window
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 앱이 감시하는 좌석. code는 Pleos SDK의 SeatValue 이름과 같아야 한다. */
enum class SeatPosition(val code: String, val label: String) {
    FRONT_LEFT("ROW_1_LEFT", "운전석"),
    FRONT_RIGHT("ROW_1_RIGHT", "조수석"),
    REAR_LEFT("ROW_2_LEFT", "뒷좌석 왼쪽"),
    REAR_CENTER("ROW_2_CENTER", "뒷좌석 가운데"),
    REAR_RIGHT("ROW_2_RIGHT", "뒷좌석 오른쪽");

    fun toSdkArea(): SeatArea = SeatArea(SeatValue.valueOf(code))

    val isRear: Boolean get() = code.startsWith("ROW_2")

    /** 같은 위치의 창문. 가운데 좌석은 창문이 없다. */
    val windowCode: String? get() = if (code.endsWith("CENTER")) null else code

    /** 같은 위치의 문. 가운데 좌석은 문이 없다. */
    val doorCode: String? get() = windowCode
}

/**
 * 이 차량에서 실제로 쓰는 좌석 목록. 모든 화면은 이 목록에 있는 좌석만 그린다.
 * 지금은 Pleos 에뮬레이터 차량(앞 2 + 뒤 2)에 맞춘 값.
 * TODO: Seat capability 확인 결과로 차종마다 자동 결정 (5인승 뒷좌석 가운데, 7인승 3열 등)
 */
object VehicleSeats {
    val active: List<SeatPosition> = listOf(
        SeatPosition.FRONT_LEFT,
        SeatPosition.FRONT_RIGHT,
        SeatPosition.REAR_LEFT,
        SeatPosition.REAR_RIGHT
    )
    val passengerSeats: List<SeatPosition> get() = active.filter { it != SeatPosition.FRONT_LEFT }
    val rear: List<SeatPosition> get() = active.filter { it.isRear }
}

/** M6 장면. 사용자가 불렀을 때만 적용하고, "원래대로"로 직전 상태를 되돌린다. */
enum class CareScene(val label: String) {
    SLEEP("재우기"),
    MOTION("멀미"),
    QUIET("조용히")
}

/** 차가 움직일 수 있는 상태인지. 기어 값으로 판단한다. */
enum class DriveState(val label: String, val moving: Boolean) {
    PARKED("정차", false),
    DRIVING("주행 중", true),
    REVERSE("후진 중", true),
    NEUTRAL("중립", true),
    UNKNOWN("확인 중", false)
}

/** 좌석 하나의 현재 상태. null = 아직 모름(읽기 전 또는 미지원). */
data class SeatState(
    val occupied: Boolean? = null,
    val belted: Boolean? = null,
    /** 이 좌석 쪽 문이 열려 있는지 */
    val doorOpen: Boolean? = null
)

/**
 * Pleos Vehicle SDK와 앱 사이의 유일한 창구.
 * - 앱의 다른 코드는 SDK를 직접 부르지 않고 이 클래스만 쓴다.
 * - 리스너는 좌석마다 한 번만 등록하고, release()에서 모두 해제한다.
 *   (같은 리스너를 두 번 등록하면 "Listener is already registered" 오류가 난다)
 */
class VehicleGateway(context: Context) {

    private val vehicle = Vehicle(context.applicationContext)
    private val seat: Seat by lazy { vehicle.getSeat() }
    private val window: Window by lazy { vehicle.getWindow() }
    private val hvac: HVAC by lazy { vehicle.getHVAC() }
    private val gear: Gear by lazy { vehicle.getGear() }
    private val door: Door by lazy { vehicle.getDoor() }
    private val light: Light by lazy { vehicle.getLight() }

    private val _sensorDrive = MutableStateFlow(DriveState.UNKNOWN)
    private val _driveOverride = MutableStateFlow<DriveState?>(null)
    private val _drive = MutableStateFlow(DriveState.UNKNOWN)
    /** 기어 센서 값. 시연용 덮어쓰기가 있으면 그 값. */
    val drive: StateFlow<DriveState> = _drive.asStateFlow()
    val driveSimulated: StateFlow<DriveState?> = _driveOverride.asStateFlow()

    /** 시연용 벨트 값. 차량 센서 값보다 우선한다(재확인 때 덮어써지지 않게). */
    private val beltOverride = mutableMapOf<SeatPosition, Boolean>()
    private val _beltSimulated = MutableStateFlow<Set<SeatPosition>>(emptySet())
    val beltSimulated: StateFlow<Set<SeatPosition>> = _beltSimulated.asStateFlow()

    private val _seats = MutableStateFlow(VehicleSeats.active.associateWith { SeatState() })
    val seats: StateFlow<Map<SeatPosition, SeatState>> = _seats.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    /**
     * 착석 값 보정.
     * Vehicle SDK 2.3.2 SeatImpl.getCurrentSeatOccupancy는 `value == 1`일 때 true를 돌려준다.
     * 안드로이드 VehicleSeatOccupancyState에서 1은 VACANT(비어 있음), 2가 OCCUPIED라서
     * SDK 값이 반대로 나온다(에뮬레이터 실험으로도 4개 좌석 모두 확인, 2026-11-12).
     * 주최 측 확인 전까지 기본으로 보정을 켜 둔다.
     */
    private val _invertOccupancy = MutableStateFlow(true)
    val invertOccupancy: StateFlow<Boolean> = _invertOccupancy.asStateFlow()

    fun setInvertOccupancy(enabled: Boolean) {
        _invertOccupancy.value = enabled
        log("착석 값 반전 보정 → ${if (enabled) "켬" else "끔"}")
        refreshOccupancy()
    }

    /** 공조 제어 지원 여부(풍량 capability 확인 결과). null = 아직 확인 중 */
    private val _hvacReady = MutableStateFlow<Boolean?>(null)
    val hvacReady: StateFlow<Boolean?> = _hvacReady.asStateFlow()

    private var initialized = false
    private val occupancyListeners = mutableMapOf<SeatPosition, SeatOccupancyListener>()
    private val beltListeners = mutableMapOf<SeatPosition, SeatBeltBuckledStateListener>()
    private var gearListener: GearSelectionListener? = null
    private val doorListeners = mutableMapOf<SeatPosition, DoorPositionListener>()

    fun start() {
        if (initialized) return
        try {
            vehicle.initialize()
            initialized = true
            log("SDK 초기화 성공")
        } catch (e: Exception) {
            log("SDK 초기화 실패: ${e.message}")
            return
        }
        VehicleSeats.active.forEach { position ->
            readOccupancy(position)
            readBelt(position)
            watchOccupancy(position)
            watchBelt(position)
        }
        VehicleSeats.active.filter { it.doorCode != null }.forEach { position ->
            readDoor(position)
            watchDoor(position)
        }
        readGear()
        watchGear()
        probeHvac()
    }

    /** 원칙 P3: 차량 기능을 쓰기 전에 지원 여부를 먼저 확인한다(홈 화면 "지원 기능 확인"에 표시) */
    private fun probeHvac() = sdkCall("공조 지원 확인") {
        hvac.checkHvacFanSpeedCapabilities(
            onComplete = { _, bounds ->
                _hvacReady.value = bounds.isNotEmpty()
                log("공조 지원 확인 · 풍량 조절 존 ${bounds.size}개")
            },
            onFailed = { e ->
                _hvacReady.value = false
                log("공조 지원 확인 실패: ${e.message}")
            }
        )
    }

    fun release() {
        if (!initialized) return
        occupancyListeners.forEach { (position, listener) ->
            runCatching { seat.unregisterSeatOccupancy(position.toSdkArea(), listener) }
        }
        beltListeners.forEach { (position, listener) ->
            runCatching { seat.unregisterSeatBeltBuckledState(position.toSdkArea(), listener) }
        }
        gearListener?.let { l -> runCatching { @Suppress("DEPRECATION") gear.unregisterGearSelection(l) } }
        gearListener = null
        doorListeners.forEach { (position, listener) ->
            position.doorArea()?.let { area -> runCatching { door.unregisterDoorPosition(area, listener) } }
        }
        doorListeners.clear()
        occupancyListeners.clear()
        beltListeners.clear()
        runCatching { vehicle.release() }
        initialized = false
    }

    // ---- 착석 ----

    /** 모든 좌석의 착석 값을 좌석별로 다시 읽는다. 화면 진입 시와 변화 알림 시 호출. */
    fun refreshOccupancy() {
        if (!initialized) return
        VehicleSeats.active.forEach { readOccupancy(it) }
    }

    private fun readOccupancy(position: SeatPosition) = sdkCall("착석 읽기 ${position.label}") {
        seat.getCurrentSeatOccupancy(
            area = position.toSdkArea(),
            onComplete = { value ->
                val raw = value.asBool()
                val occupied = if (_invertOccupancy.value) raw?.not() else raw
                updateSeat(position) { it.copy(occupied = occupied) }
                log("읽기 · ${position.label} 착석 원래값=$raw → 적용=$occupied")
            },
            onFailed = { e -> log("착석 읽기 실패 ${position.label}: ${e.message}") }
        )
    }

    private fun watchOccupancy(position: SeatPosition) = sdkCall("착석 구독 ${position.label}") {
        val listener = object : SeatOccupancyListener {
            override fun onSeatOccupancyUpdated(value: Boolean?) {
                // 알림 값은 어느 좌석의 변화인지 보장되지 않을 수 있어서(에뮬레이터 확인 결과)
                // 값을 그대로 쓰지 않고, 좌석을 지정해 현재 값을 다시 읽는다.
                log("알림 · ${position.label} 리스너 → $value (좌석별 재확인)")
                refreshOccupancy()
            }

            override fun onFailed(e: Exception) {
                log("착석 구독 오류 ${position.label}: ${e.message}")
            }
        }
        seat.registerSeatOccupancy(position.toSdkArea(), listener)
        occupancyListeners[position] = listener
    }

    // ---- 안전벨트 ----

    private fun readBelt(position: SeatPosition) = sdkCall("벨트 읽기 ${position.label}") {
        seat.getCurrentSeatBeltBuckledState(
            area = position.toSdkArea(),
            onComplete = { value ->
                val belted = beltOverride[position] ?: value.asBool()
                updateSeat(position) { it.copy(belted = belted) }
            },
            onFailed = { e -> log("벨트 읽기 실패 ${position.label}: ${e.message}") }
        )
    }

    private fun watchBelt(position: SeatPosition) = sdkCall("벨트 구독 ${position.label}") {
        val listener = object : SeatBeltBuckledStateListener {
            override fun onSeatBeltBuckledStateUpdated(value: Boolean?) {
                log("알림 · ${position.label} 벨트 리스너 → $value (좌석별 재확인)")
                VehicleSeats.active.forEach { readBelt(it) }
            }

            override fun onFailed(e: Exception) {
                log("벨트 구독 오류 ${position.label}: ${e.message}")
            }
        }
        seat.registerSeatBeltBuckledState(position.toSdkArea(), listener)
        beltListeners[position] = listener
    }

    // ---- 문 ----

    private fun SeatPosition.doorArea(): DoorArea? = doorCode?.let { DoorArea(DoorValue.valueOf(it)) }

    private fun readDoor(position: SeatPosition) = sdkCall("문 읽기 ${position.label}") {
        val area = position.doorArea() ?: return@sdkCall
        door.getCurrentDoorPosition(
            area = area,
            onComplete = { pos ->
                val open = pos?.let { it > 0 }
                val before = _seats.value[position]?.doorOpen
                updateSeat(position) { it.copy(doorOpen = open) }
                if (before != open) log("읽기 · ${position.label} 문 $pos → ${if (open == true) "열림" else if (open == false) "닫힘" else "모름"}")
            },
            onFailed = { e -> log("문 읽기 실패 ${position.label}: ${e.message}") }
        )
    }

    private fun watchDoor(position: SeatPosition) = sdkCall("문 구독 ${position.label}") {
        val area = position.doorArea() ?: return@sdkCall
        val listener = object : DoorPositionListener {
            override fun onPositionUpdated(pos: Int?) {
                // 착석 리스너와 같은 이유로 값을 믿지 않고 모든 문을 다시 읽는다.
                VehicleSeats.active.filter { it.doorCode != null }.forEach { readDoor(it) }
            }

            override fun onFailed(e: Exception) {
                log("문 구독 오류 ${position.label}: ${e.message}")
            }
        }
        door.registerDoorPosition(area, listener)
        doorListeners[position] = listener
    }

    /** 실내등 켜기/끄기 (A2 차내 잔류 경보 때 켠다) */
    fun setCabinLight(on: Boolean, onResult: (Boolean) -> Unit = {}) = sdkCall("실내등") {
        light.setCabinLightSwitch(
            value = if (on) LightSwitch.ON else LightSwitch.OFF,
            onComplete = { v -> log("실내등 → $v"); onResult(true) },
            onFailed = { e -> log("실내등 실패: ${e.message}"); onResult(false) }
        )
    }

    // ---- M6 장면 (재우기 · 멀미) ----
    // 바꾸기 전 값을 읽어 되돌리기 목록에 넣고 나서 바꾼다. "원래대로"는 그 목록을 거꾸로 실행한다.

    private val sceneRestore = mutableListOf<() -> Unit>()
    private val _scene = MutableStateFlow<CareScene?>(null)
    val scene: StateFlow<CareScene?> = _scene.asStateFlow()

    fun applyScene(scene: CareScene) {
        if (_scene.value != null) restoreScene()
        _scene.value = scene
        log("장면 · ${scene.label} 적용")
        when (scene) {
            CareScene.SLEEP -> {
                cabinLightOffWithBackup()
                setFanAllZones { b -> maxOf(b.minValue, 1) }
            }
            CareScene.MOTION -> {
                outsideAirWithBackup()
                setFanAllZones { b -> b.minValue + (b.maxValue - b.minValue) * 2 / 3 }
            }
            // 조용히: 바람 소리를 줄인다. 앱 응답 음성은 ViewModel에서 끈다(경보는 그대로)
            CareScene.QUIET -> setFanAllZones { b -> maxOf(b.minValue, 1) + 1 }
        }
    }

    fun restoreScene() {
        val prev = _scene.value ?: return
        sceneRestore.asReversed().forEach { undo -> runCatching { undo() } }
        sceneRestore.clear()
        _scene.value = null
        log("장면 · ${prev.label} 해제 → 직전 상태로")
    }

    private fun cabinLightOffWithBackup() = sdkCall("장면 실내등") {
        light.getCabinLightSwitch(
            onComplete = { prev ->
                if (prev != null) sceneRestore += { light.setCabinLightSwitch(prev) }
                light.setCabinLightSwitch(
                    LightSwitch.OFF,
                    onComplete = { v -> log("장면 · 실내등 → $v") },
                    onFailed = { e -> log("장면 · 실내등 실패: ${e.message}") }
                )
            },
            onFailed = { e -> log("장면 · 실내등 읽기 실패: ${e.message}") }
        )
    }

    private fun setFanAllZones(target: (AreaBounds<Int>) -> Int) = sdkCall("장면 풍량") {
        hvac.checkHvacFanSpeedCapabilities(
            onComplete = { _, bounds ->
                if (bounds.isEmpty()) log("장면 · 풍량 조절 존 없음")
                bounds.forEach { (zone, b) ->
                    hvac.getHvacFanSpeed(
                        area = zone,
                        onComplete = { prev ->
                            if (prev != null) sceneRestore += { hvac.setHvacFanSpeed(zone, prev) }
                            val value = target(b).coerceIn(b.minValue, b.maxValue)
                            hvac.setHvacFanSpeed(
                                area = zone,
                                value = value,
                                onComplete = { _, v -> log("장면 · 풍량 ${zone.values.joinToString()} $prev → $v") },
                                onFailed = { e -> log("장면 · 풍량 실패: ${e.message}") }
                            )
                        },
                        onFailed = { e -> log("장면 · 풍량 읽기 실패: ${e.message}") }
                    )
                }
            },
            onFailed = { e -> log("장면 · 풍량 지원 확인 실패: ${e.message}") }
        )
    }

    private fun outsideAirWithBackup() = sdkCall("장면 외기") {
        hvac.checkHvacRecirculationCapabilities(
            onComplete = { _, zones ->
                if (zones.isEmpty()) log("장면 · 내·외기 조절 존 없음")
                zones.forEach { zone ->
                    hvac.getHvacRecirculationState(
                        area = zone,
                        onComplete = { prev ->
                            if (prev != null) sceneRestore += { hvac.setHvacRecirculation(zone, prev) }
                            hvac.setHvacRecirculation(
                                area = zone,
                                value = false,
                                onComplete = { _, v -> log("장면 · 내기순환 ${zone.values.joinToString()} → $v (외기)") },
                                onFailed = { e -> log("장면 · 외기 전환 실패: ${e.message}") }
                            )
                        },
                        onFailed = { e -> log("장면 · 내·외기 읽기 실패: ${e.message}") }
                    )
                }
            },
            onFailed = { e -> log("장면 · 내·외기 지원 확인 실패: ${e.message}") }
        )
    }

    // ---- 기어(주행 상태) ----
    // Pleos Gear API는 2.3.2에서 deprecated(구글 GEAR_SELECTION 권장)지만 에뮬레이터에서 동작하고,
    // 구글 Car API를 따로 붙이지 않아도 되어서 이걸 쓴다.

    @Suppress("DEPRECATION")
    private fun readGear() = sdkCall("기어 읽기") {
        gear.getCurrentGearSelectionState(
            onComplete = { g -> applyGear(g, "읽기") },
            onFailed = { e -> log("기어 읽기 실패: ${e.message}") }
        )
    }

    @Suppress("DEPRECATION")
    private fun watchGear() = sdkCall("기어 구독") {
        val listener = object : GearSelectionListener {
            override fun onSelected(gear: VehicleGear?) {
                log("알림 · 기어 리스너 → $gear (재확인)")
                readGear()
            }

            override fun onFailed(e: Exception) {
                log("기어 구독 오류: ${e.message}")
            }
        }
        gear.registerGearSelection(listener)
        gearListener = listener
    }

    private fun applyGear(g: VehicleGear?, from: String) {
        val state = when (g) {
            VehicleGear.GEAR_PARK -> DriveState.PARKED
            VehicleGear.GEAR_REVERSE -> DriveState.REVERSE
            VehicleGear.GEAR_NEUTRAL -> DriveState.NEUTRAL
            null, VehicleGear.GEAR_UNKNOWN -> DriveState.UNKNOWN
            else -> DriveState.DRIVING
        }
        _sensorDrive.value = state
        _drive.value = _driveOverride.value ?: state
        log("$from · 기어 $g → ${state.label}")
    }

    /** 시연용: 기어를 바꾸지 않고 주행/정차를 흉내 낸다. null이면 센서 값으로 돌아간다. */
    fun simulateDrive(state: DriveState?) {
        _driveOverride.value = state
        _drive.value = state ?: _sensorDrive.value
        log("[시뮬레이션] 주행 상태 → ${state?.label ?: "센서 값 사용"}")
    }

    // ---- 실내 세팅 적용 (창문 잠금 · 공조 온도 · 좌석 열선) ----

    /**
     * 세팅 하나를 적용하고 결과를 onResult로 알려준다.
     * 실패하면 화면에 "직접 해 주세요"로 보여준다(예외 E13).
     */
    fun lockWindow(position: SeatPosition, onResult: (Boolean, String?) -> Unit) {
        val code = position.windowCode ?: return onResult(false, "창문 없음")
        sdkCall("창문 잠금 ${position.label}") {
            window.setWindowLock(
                area = WindowArea(WindowValue.valueOf(code)),
                value = true,
                onComplete = { _, v -> log("창문 잠금 ${position.label} → $v"); onResult(true, null) },
                onFailed = { e -> log("창문 잠금 실패 ${position.label}: ${e.message}"); onResult(false, e.message) }
            )
        }
    }

    fun setSeatHeat(position: SeatPosition, level: Int, onResult: (Boolean, String?) -> Unit) =
        sdkCall("좌석 열선 ${position.label}") {
            seat.setHvacSeatTemperature(
                area = position.toSdkArea(),
                value = level,
                onComplete = { _, v -> log("좌석 열선 ${position.label} → $v"); onResult(true, null) },
                onFailed = { e -> log("좌석 열선 실패 ${position.label}: ${e.message}"); onResult(false, e.message) }
            )
        }

    /**
     * 좌석이 속한 공조 존을 찾아 온도를 맞춘다.
     * 존 구성은 차마다 달라서(예: 앞 2존만 있는 차) capability 결과에서 그 좌석을 포함한 존을 고른다.
     */
    fun setZoneTemperature(position: SeatPosition, celsius: Int, onResult: (Boolean, String?) -> Unit) =
        sdkCall("공조 온도 ${position.label}") {
            hvac.checkHvacTemperatureCapabilities(
                onComplete = { _, bounds, _ ->
                    val seatValue = SeatValue.valueOf(position.code)
                    val zone = bounds.keys.firstOrNull { seatValue in it.values }
                    if (zone == null) {
                        log("공조 존 없음 ${position.label} (지원 존: ${bounds.keys.map { k -> k.values.joinToString() }})")
                        onResult(false, "이 좌석의 공조 존이 없어요")
                        return@checkHvacTemperatureCapabilities
                    }
                    val range = bounds.getValue(zone)
                    val value = celsius.toFloat().coerceIn(range.minValue, range.maxValue)
                    hvac.setHvacTemperature(
                        area = zone,
                        value = value,
                        unit = TemperatureUnit.CELSIUS,
                        onComplete = { _, v -> log("공조 온도 ${position.label} 존 → $v℃"); onResult(true, null) },
                        onFailed = { e -> log("공조 온도 실패 ${position.label}: ${e.message}"); onResult(false, e.message) }
                    )
                },
                onFailed = { e -> log("공조 지원 확인 실패: ${e.message}"); onResult(false, e.message) }
            )
        }

    /**
     * 여러 좌석의 선호 온도를 한 번에 적용한다(예외 E07).
     * requests는 우선순위 순(더 취약한 동승자 먼저). 같은 존에 여러 요청이 있으면 첫 요청만 적용하고
     * 나머지는 onResult(position, null, 기준 좌석)으로 알린다.
     * @param onResult (좌석, 성공 여부 또는 null=다른 동승자 기준, 기준이 된 좌석)
     */
    fun setZoneTemperatures(
        requests: List<Pair<SeatPosition, Int>>,
        onResult: (SeatPosition, Boolean?, SeatPosition?) -> Unit
    ) = sdkCall("공조 온도 일괄") {
        hvac.checkHvacTemperatureCapabilities(
            onComplete = { _, bounds, _ ->
                val owner = mutableMapOf<SeatArea, SeatPosition>()
                requests.forEach { (position, celsius) ->
                    val seatValue = SeatValue.valueOf(position.code)
                    val zone = bounds.keys.firstOrNull { seatValue in it.values }
                    if (zone == null) {
                        log("공조 존 없음 ${position.label}")
                        onResult(position, false, null); return@forEach
                    }
                    owner[zone]?.let { first ->
                        log("공조 ${position.label}: ${first.label}와 같은 존 → ${first.label} 기준")
                        onResult(position, null, first); return@forEach
                    }
                    owner[zone] = position
                    val range = bounds.getValue(zone)
                    hvac.setHvacTemperature(
                        area = zone,
                        value = celsius.toFloat().coerceIn(range.minValue, range.maxValue),
                        unit = TemperatureUnit.CELSIUS,
                        onComplete = { _, v -> log("공조 온도 ${position.label} 존 → $v℃"); onResult(position, true, null) },
                        onFailed = { e -> log("공조 온도 실패 ${position.label}: ${e.message}"); onResult(position, false, null) }
                    )
                }
            },
            onFailed = { e ->
                log("공조 지원 확인 실패: ${e.message}")
                requests.forEach { onResult(it.first, false, null) }
            }
        )
    }

    // ---- 시연용: 버추얼 콘솔에 없는 벨트 값을 앱에서 바꿔 본다 ----

    fun simulateBelt(position: SeatPosition, belted: Boolean) {
        beltOverride[position] = belted
        _beltSimulated.value = beltOverride.keys.toSet()
        updateSeat(position) { it.copy(belted = belted) }
        log("[시뮬레이션] ${position.label} 벨트 → $belted")
    }

    /** 새 탑승 확인을 시작할 때 이전 시연 값을 지운다. */
    fun clearBeltSimulation() {
        if (beltOverride.isEmpty()) return
        beltOverride.clear()
        _beltSimulated.value = emptySet()
        log("[시뮬레이션] 벨트 값 초기화 → 센서 값 사용")
        VehicleSeats.active.forEach { readBelt(it) }
    }

    // ---- 내부 도우미 ----

    private fun updateSeat(position: SeatPosition, change: (SeatState) -> SeatState) {
        _seats.update { current -> current + (position to change(current.getValue(position))) }
    }

    /** 앱의 다른 부분(경보 등)이 같은 개발자 로그에 남길 때. */
    fun note(message: String) = log(message)

    private fun log(message: String) {
        Log.d(TAG, message)
        _log.update { (listOf(message) + it).take(30) }
    }

    private inline fun sdkCall(name: String, action: () -> Unit) {
        if (!initialized) {
            log("$name 건너뜀: SDK 미초기화")
            return
        }
        try {
            action()
        } catch (e: Exception) {
            log("$name 예외: ${e.message}")
        }
    }

    /** SDK가 Boolean 또는 Boolean?을 돌려줘도 같은 방식으로 처리한다. */
    private fun Any?.asBool(): Boolean? = this as? Boolean

    private companion object {
        const val TAG = "VehicleGateway"

        fun isEmulator(): Boolean =
            Build.HARDWARE.contains("ranchu") ||
                Build.HARDWARE.contains("goldfish") ||
                Build.PRODUCT.contains("emulator") ||
                Build.PRODUCT.contains("sdk") ||
                Build.FINGERPRINT.contains("generic")
    }
}
