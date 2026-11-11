package dev.resticky.app

import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.companion.ObservingDevicePresenceRequest
import android.bluetooth.le.ScanFilter
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.ParcelUuid
import dev.resticky.BLE_SERVICE
import java.util.UUID

class Companion(private val context: Context) {
    private val cdm = context.getSystemService(CompanionDeviceManager::class.java)

    val associations: List<AssociationInfo> get() = cdm.myAssociations

    val association: AssociationInfo? get() = associations.firstOrNull()

    val address: String? get() = association?.deviceMacAddress?.toString()?.uppercase()

    fun associate(onPending: (IntentSender) -> Unit, onDone: () -> Unit) {
        val filter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID.fromString(BLE_SERVICE))).build())
            .build()
        val request = AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(true).build()
        cdm.associate(request, context.mainExecutor, object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(sender: IntentSender) = onPending(sender)

            override fun onAssociationCreated(info: AssociationInfo) {
                log("INFO companion associated id=${info.id} address=${info.deviceMacAddress}")
                observe(info)
                onDone()
            }

            override fun onFailure(error: CharSequence?) {
                log("WARN companion association failed: $error")
            }
        })
    }

    fun observe(info: AssociationInfo) =
        cdm.startObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(info.id).build())

    fun forget() {
        val info = association ?: return
        cdm.stopObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(info.id).build())
        cdm.disassociate(info.id)
    }
}

class StickyPresence : CompanionDeviceService() {
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        val app = application as HubApp
        log("INFO companion presence event=${event.event} association=${event.associationId}")
        if (event.event == DevicePresenceEvent.EVENT_BLE_APPEARED && app.hubOn) {
            startForegroundService(Intent(this, HubService::class.java))
        }
    }
}
