package com.wemade.teslamacro.service

/** 이미 연결된 차량 BLE가 있으면 서버 대신 기존 직접 명령 경로를 사용한다. */
internal fun shouldUseFleetForQuickAction(fleetEnabled: Boolean, bleConnected: Boolean): Boolean =
    fleetEnabled && !bleConnected
