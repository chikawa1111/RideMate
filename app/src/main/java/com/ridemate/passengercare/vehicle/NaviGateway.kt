package com.ridemate.passengercare.vehicle

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import com.ridemate.passengercare.data.SavedPlace
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pleos NaviHelper(길 안내 앱)와 앱 사이의 창구. 권한은 모두 Normal 등급이라
 * 사용자 허용 대화상자가 없고, 주행 중 차단에도 걸리지 않는다(위치·마이크 권한과 다름).
 *
 * 쓰는 것
 *  - 현재 위치·진행 방위 (getCurrentLocationInfo) → 쉬어갈 곳·날씨
 *  - 목적지 남은 시간·거리 (getDestinationInfo) → "다 왔어?"
 *  - 경유지 추가 (addWaypoint) / 경로 요청 (requestRoute) → 쉬어갈 곳 선택 시
 *  - 경로 옵션 변경 (changeRouteOption COMFORTABLE) → "편안한 길"
 *  - 도착·경유지 도착 이벤트 → 하차 확인 자동 시작, 휴식 시간 초기화
 *  - 즐겨찾기·최근 목적지 (getBookmarkInfo / getRecentDestinationInfo) → 긴급 병원 등록
 *  - 긴급 병원으로 바로 안내 (requestRoute) / 되돌리기 (직전 목적지로 다시 requestRoute, 없으면 cancelRoute)
 */
class NaviGateway(context: Context, private val log: (String) -> Unit) {

    data class NaviLocation(val lat: Double, val lng: Double, val heading: Double, val address: String)
    data class Destination(val name: String, val remainMin: Int, val remainKm: Double)

    sealed interface Event {
        data class Arrived(val name: String) : Event
        data class WaypointArrived(val name: String) : Event
        data class Error(val code: String) : Event
    }

    private val navi = NaviHelper(context.applicationContext)
    private var connected = false

    private val _connectedFlow = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _connectedFlow.asStateFlow()

    private val _location = MutableStateFlow<NaviLocation?>(null)
    val location: StateFlow<NaviLocation?> = _location.asStateFlow()

    private val _destination = MutableStateFlow<Destination?>(null)
    val destination: StateFlow<Destination?> = _destination.asStateFlow()

    /** 직전 목적지(긴급 병원으로 바꾼 뒤 되돌릴 때 쓴다) */
    private var lastDestinationPlace: SavedPlace? = null

    private val _bookmarks = MutableStateFlow<List<SavedPlace>>(emptyList())
    private val _recents = MutableStateFlow<List<SavedPlace>>(emptyList())
    val bookmarks: StateFlow<List<SavedPlace>> = _bookmarks.asStateFlow()
    val recents: StateFlow<List<SavedPlace>> = _recents.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private val listener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            _location.value = NaviLocation(
                currentLocationInfo.latitude, currentLocationInfo.longitude,
                currentLocationInfo.heading, currentLocationInfo.address
            )
        }

        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            // 안내 중이 아니면 빈 목적지(이름 없음, 거리 0)가 온다 → 목적지 없음으로 본다
            if (destinationInfo.name.isBlank() && destinationInfo.remainDistance <= 0) {
                _destination.value = null
                return
            }
            lastDestinationPlace = SavedPlace(
                destinationInfo.name, destinationInfo.latitude, destinationInfo.longitude,
                destinationInfo.address, destinationInfo.poiId, destinationInfo.poiSubId ?: ""
            )
            _destination.value = Destination(
                destinationInfo.name,
                (destinationInfo.remainTime / 60.0).toInt().coerceAtLeast(0),
                destinationInfo.remainDistance / 1000.0
            )
        }

        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            log("내비 · 안내 시작 → ${routeStartInfo.name}")
            _destination.value = Destination(routeStartInfo.name, routeStartInfo.duration / 60, routeStartInfo.distance / 1000.0)
        }

        override fun onRouteEnded() {
            log("내비 · 안내 종료")
            _destination.value = null
        }

        override fun onRouteCancelled() {
            log("내비 · 안내 취소")
            _destination.value = null
        }

        override fun onRouteOptionChanged(routeOption: RouteOption) {
            log("내비 · 경로 옵션 → $routeOption")
        }

        override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) {
            log("내비 · 경유지 ${waypointInfoList.map { it.name }}")
        }

        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            log("내비 · 목적지 도착 ${destinationArrivedInfo.name}")
            _destination.value = null
            _events.tryEmit(Event.Arrived(destinationArrivedInfo.name))
        }

        override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) {
            log("내비 · 경유지 도착 ${waypointArrivedInfo.name}")
            _events.tryEmit(Event.WaypointArrived(waypointArrivedInfo.name))
        }

        override fun onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>) {
            // 집·회사 슬롯이 비어 있으면 이름 없는 항목이 온다 → 뺀다
            _bookmarks.value = bookmarkInfoList.filter { it.name.isNotBlank() && it.latitude != 0.0 }.map {
                SavedPlace(it.name, it.latitude, it.longitude, it.address, it.poiId, it.poiSubId ?: "")
            }
            log("내비 · 즐겨찾기 ${bookmarkInfoList.size}곳")
        }

        override fun onRecentDestinationInfo(recentDestinationInfoList: List<RecentDestinationInfo>) {
            _recents.value = recentDestinationInfoList.filter { it.name.isNotBlank() && it.latitude != 0.0 }.map {
                SavedPlace(it.name, it.latitude, it.longitude, it.address, it.poiId, it.poiSubId ?: "")
            }
            log("내비 · 최근 목적지 ${recentDestinationInfoList.size}곳")
        }

        override fun onNavigationStatus(status: NavigationStatus) {
            _status.value = status.name
        }

        override fun onError(errorCode: NaviErrorCode) {
            log("내비 · 오류 $errorCode")
            _events.tryEmit(Event.Error(errorCode.toString()))
        }
    }

    fun start() {
        if (connected) return
        runCatching {
            navi.addListener(listener)
            navi.initialize(
                onConnected = {
                    connected = true
                    _connectedFlow.value = true
                    log("내비 연결 성공")
                    refresh()
                },
                onFailed = { e -> log("내비 연결 실패: ${e.message}") }
            )
        }.onFailure { log("내비 초기화 예외: ${it.message}") }
    }

    /** 위치·목적지를 다시 묻는다. 결과는 리스너로 온다 */
    fun refresh() {
        if (!connected) return
        call("위치 조회") { navi.getCurrentLocationInfo() }
        call("목적지 조회") { navi.getDestinationInfo() }
    }

    /** 쉬어갈 곳을 경유지로. 안내 중이 아니면 그곳으로 안내를 시작한다 */
    fun goVia(lat: Double, lng: Double, name: String, id: String, routing: Boolean): Boolean = call("경유지 추가") {
        if (routing) {
            navi.addWaypoint(RequestWaypointInfo(lat, lng, name, WaypointIndex.FIRST, id, "", ""))
            log("내비 · 경유지 추가 요청 → $name")
        } else {
            navi.requestRoute(RouteInfo(latitude = lat, longitude = lng, poiName = name, poiId = id, address = "", poiSubId = ""))
            log("내비 · 안내 요청 → $name")
        }
    }

    /** 즐겨찾기·최근 목적지를 다시 묻는다(긴급 병원 고르기). 결과는 bookmarks / recents로 온다 */
    fun requestPlaces() {
        call("즐겨찾기 조회") { navi.getBookmarkInfo() }
        call("최근 목적지 조회") { navi.getRecentDestinationInfo() }
    }

    /** 지금 목적지를 버리고 그곳으로 바로 안내한다. @return 되돌릴 때 쓸 직전 목적지(없으면 null) */
    fun goTo(place: SavedPlace): Pair<Boolean, SavedPlace?> {
        val previous = if (_destination.value != null) lastDestinationPlace else null
        val ok = call("바로 안내") {
            navi.requestRoute(
                RouteInfo(latitude = place.lat, longitude = place.lng, poiName = place.name,
                    poiId = place.poiId, address = place.address, poiSubId = place.poiSubId)
            )
            log("내비 · 바로 안내 → ${place.name}")
        }
        return ok to previous
    }

    /** 긴급 안내를 되돌린다: 직전 목적지가 있으면 그곳으로, 없으면 안내 종료 */
    fun undoGoTo(previous: SavedPlace?): Boolean =
        if (previous != null) goTo(previous).first
        else call("안내 취소") { navi.cancelRoute(); log("내비 · 안내 취소 요청") }

    fun comfortableRoute(): Boolean = call("편안한 경로") {
        navi.changeRouteOption(RouteOption.COMFORTABLE)
        log("내비 · 편안한 경로 요청")
    }

    fun release() {
        runCatching { navi.removeListener(listener) }
        runCatching { navi.release() }
        connected = false
        _connectedFlow.value = false
    }

    private inline fun call(name: String, action: () -> Unit): Boolean {
        if (!connected) {
            log("내비 $name 건너뜀: 연결 안 됨")
            return false
        }
        return runCatching { action() }.onFailure { log("내비 $name 실패: ${it.message}") }.isSuccess
    }
}
