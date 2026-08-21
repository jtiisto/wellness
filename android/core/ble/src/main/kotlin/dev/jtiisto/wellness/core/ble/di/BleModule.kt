package dev.jtiisto.wellness.core.ble.di

import dev.jtiisto.wellness.core.ble.buffer.IntervalBuffer
import dev.jtiisto.wellness.core.ble.capture.HrCaptureState
import dev.jtiisto.wellness.core.ble.device.KnownDevice
import dev.jtiisto.wellness.core.ble.device.KnownDeviceStorage
import dev.jtiisto.wellness.core.ble.device.KnownDeviceStore
import dev.jtiisto.wellness.core.ble.device.PrefsKnownDeviceStorage
import dev.jtiisto.wellness.core.ble.scanner.BleScanner
import dev.jtiisto.wellness.core.ble.trace.HrTraceRing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.Qualifier
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * The live capture state the service publishes and the UI collects.
 *
 * **Both flow singletons below MUST keep distinct qualifiers.** Koin keys a
 * definition by its raw class and generics are erased, so
 * `MutableStateFlow<HrCaptureState>` and `MutableStateFlow<List<KnownDevice>>`
 * are the *same key* to the container — registering them unqualified silently
 * gives one of them the other's value. Pulse-bridge learned this the hard way.
 */
val HrCaptureStateQualifier: Qualifier = named("hrCaptureState")

/** The remembered straps, published by `KnownDeviceStore`. See above. */
val KnownDeviceStateQualifier: Qualifier = named("hrKnownDeviceState")

/**
 * The scope the sample buffer's flush timer runs on.
 *
 * App-lived rather than service-lived on purpose: a capture service that is
 * killed mid-session leaves rows in the buffer, and this scope is what keeps
 * retrying to persist them afterwards. `SupervisorJob` so one failed flush does
 * not take the timer with it.
 */
val HrCaptureScope: Qualifier = named("hrCaptureScope")

val bleModule = module {
    single(HrCaptureStateQualifier) { MutableStateFlow(HrCaptureState()) }
    single(KnownDeviceStateQualifier) { MutableStateFlow(emptyList<KnownDevice>()) }

    single<CoroutineScope>(HrCaptureScope) { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    single { BleScanner(context = androidContext(), log = get()) }

    single<KnownDeviceStorage> { PrefsKnownDeviceStorage(androidContext()) }
    single { KnownDeviceStore(storage = get(), state = get(KnownDeviceStateQualifier)) }

    // The sink is `HrCaptureStore` in :core:data, bound there. This module never
    // sees it as anything but the interface, which is what keeps :core:ble a
    // leaf with Room on the other side of the seam.
    single { IntervalBuffer(sink = get(), scope = get(HrCaptureScope), log = get()) }

    // The rolling window the cardio guide draws, fed by the capture service and
    // read by whatever is on screen — so it outlives both, like the state flow
    // above. No qualifier needed and none wanted: it is registered as its own
    // class rather than as the flow it publishes, which is the erasure trap the
    // two `named` definitions above exist to work around. `HrTraceRing` is a
    // class Koin can key on; `MutableStateFlow<List<TraceSample>>` would not be.
    single { HrTraceRing() }
}
