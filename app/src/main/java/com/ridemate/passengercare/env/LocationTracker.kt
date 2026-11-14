package com.ridemate.passengercare.env

/*
 * (사용하지 않음) 안드로이드 위치 권한으로 실제 위치를 받던 시도.
 *
 * Pleos 에뮬레이터 실험(2026-11-12): ACCESS_FINE_LOCATION 같은 사용자 허용 권한을 허용하면
 * distractionOptimized를 선언해도 주행 중 앱 전체가 차단됐다. 그래서 위치는
 * Pleos NaviHelper(보호 수준 Normal)의 getCurrentLocationInfo로 받는다 → vehicle/NaviGateway.kt
 */
