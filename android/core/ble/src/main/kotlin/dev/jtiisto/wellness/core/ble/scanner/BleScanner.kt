package dev.jtiisto.wellness.core.ble.scanner

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import dev.jtiisto.wellness.core.ble.BleLog
import dev.jtiisto.wellness.core.ble.model.BleDevice
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * The two BLE scans this app runs, both as cold flows that stop the moment
 * their collector goes away.
 *
 * Device-only glue: everything here is a thin wrapper over
 * [android.bluetooth.le.BluetoothLeScanner], the decision it delegates lives in
 * [HrmAdvertisementFilter], and it is excluded from the coverage gate because a
 * unit test could only assert that the wrapper calls the API it obviously calls.
 *
 * Callers must hold `BLUETOOTH_SCAN`; `@SuppressLint("MissingPermission")` is
 * the acknowledgement, and the flow closes with the `SecurityException` if the
 * grant is missing or revoked rather than crashing whatever collected it.
 */
class BleScanner(
    private val context: Context,
    private val log: BleLog = BleLog {},
) {

    /**
     * Every heart-rate strap in range, repeating as long as it is collected.
     *
     * `SCAN_MODE_LOW_LATENCY` because this only ever runs while a human is
     * looking at a list waiting for their strap to appear — the battery cost is
     * bounded by how long that screen is open, and the alternative modes take
     * long enough that the list looks broken.
     *
     * The same device is emitted once per advertising packet. Deduplication
     * belongs to the collector, which is also the thing that knows whether it
     * wants the newest RSSI.
     */
    @SuppressLint("MissingPermission")
    fun scan(): Flow<DiscoveredDevice> = callbackFlow {
        val scanner = leScanner()
        if (scanner == null) {
            log.log("scan unavailable — no BLE adapter or it is switched off")
            close(IllegalStateException("Bluetooth LE scanner not available"))
            return@callbackFlow
        }

        // One line per device per scan, not per advertising packet: a strap
        // advertises several times a second and the log is a small ring buffer.
        val logged = mutableSetOf<String>()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName
                val serviceUuids = result.scanRecord?.serviceUuids?.map { it.uuid }
                if (!HrmAdvertisementFilter.matches(name, serviceUuids)) return
                val address = result.device.address
                if (logged.add(address)) {
                    // Address and name of a heart-rate strap: hardware
                    // identifiers, not health data. Nothing measured goes here.
                    log.log("scan found $name $address rssi=${result.rssi}")
                }
                trySend(DiscoveredDevice(BleDevice(address = address, name = name), result.rssi))
            }

            override fun onScanFailed(errorCode: Int) {
                log.log("scan FAILED errorCode=$errorCode")
                close(IllegalStateException("BLE scan failed with error code: $errorCode"))
            }
        }

        log.log("scan started")
        try {
            scanner.startScan(null, LOW_LATENCY, callback)
        } catch (e: SecurityException) {
            close(e)
            return@callbackFlow
        }

        awaitClose {
            log.log("scan stopped")
            try {
                scanner.stopScan(callback)
            } catch (_: SecurityException) {
                // The grant was revoked mid-scan; there is nothing left to stop.
            }
        }
    }

    /**
     * The RSSI of each advertisement heard from [address], and nothing else.
     *
     * This is the advertising probe: it runs alongside a connect attempt so a
     * watchdog abort can say *which* failure happened — a strap that is silent
     * (asleep, or held by a watch over ANT+) versus one that is advertising
     * happily and refusing or failing the GATT connection. Without it every
     * failure reads the same and the user has nothing to act on.
     *
     * Address-filtered here, unlike [scan], because the probe already knows
     * exactly which device it is asking about and the filter keeps the callback
     * quiet in a crowded room.
     *
     * Failure **closes the flow** rather than going silent, which is the whole
     * contract: a probe that emitted nothing because scanning was unavailable
     * must never be read as "the strap is not advertising".
     */
    @SuppressLint("MissingPermission")
    fun advertisements(address: String): Flow<Int> = callbackFlow {
        val scanner = leScanner()
        if (scanner == null) {
            close(IllegalStateException("Bluetooth LE scanner not available"))
            return@callbackFlow
        }

        val filter = ScanFilter.Builder().setDeviceAddress(address).build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(result.rssi)
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("probe scan failed with error code: $errorCode"))
            }
        }

        try {
            scanner.startScan(listOf(filter), LOW_LATENCY, callback)
        } catch (e: SecurityException) {
            close(e)
            return@callbackFlow
        }

        awaitClose {
            try {
                scanner.stopScan(callback)
            } catch (_: SecurityException) {
                // As above.
            }
        }
    }

    private fun leScanner() =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter
            ?.bluetoothLeScanner

    private companion object {
        val LOW_LATENCY: ScanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
    }
}
