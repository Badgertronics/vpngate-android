package com.vpngate.client

import android.app.Application
import com.vpngate.client.data.PreferencesManager
import com.vpngate.client.data.ServerRepository
import com.vpngate.client.service.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class VpnGateApplication : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    lateinit var preferencesManager: PreferencesManager
        private set
    lateinit var serverRepository: ServerRepository
        private set

    override fun onCreate() {
        super.onCreate()
        preferencesManager = PreferencesManager(this)
        serverRepository = ServerRepository(this, preferencesManager)
        NotificationHelper.createNotificationChannel(this)

        applicationScope.launch(Dispatchers.IO) {
            serverRepository.loadInitialData()
            serverRepository.refreshServers()
        }
    }
}
