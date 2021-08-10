package com.kylecorry.andromeda.signal

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.*
import androidx.core.content.getSystemService
import androidx.core.math.MathUtils
import com.kylecorry.andromeda.core.sensors.AbstractSensor
import com.kylecorry.andromeda.core.sensors.Quality
import com.kylecorry.andromeda.core.time.Timer
import com.kylecorry.andromeda.core.tryOrNothing
import com.kylecorry.andromeda.permissions.PermissionService
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors

class CellSignalSensor(private val context: Context, private val updateCellCache: Boolean) :
    AbstractSensor(), ICellSignalSensor {

    private val telephony by lazy { context.getSystemService<TelephonyManager>() }
    private val permissions by lazy { PermissionService(context) }

    override val hasValidReading: Boolean
        get() = hasReading

    override val signals: List<CellSignal>
        get() = _signals

    private var _signals = listOf<CellSignal>()
    private var oldSignals = listOf<RawCellSignal>()
    private var hasReading = false

    private val intervalometer = Timer {
        tryOrNothing {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && updateCellCache) {
                updateCellInfoCache()
            } else {
                loadFromCache()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateCellInfoCache() {
        tryOrNothing {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return@tryOrNothing
            }

            if (!permissions.canGetFineLocation()) {
                return@tryOrNothing
            }
            telephony?.requestCellInfoUpdate(
                Executors.newSingleThreadExecutor(),
                @SuppressLint("NewApi")
                object : TelephonyManager.CellInfoCallback() {
                    override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                        tryOrNothing {
                            Handler(Looper.getMainLooper()).post {
                                updateCellInfo(cellInfo)
                            }
                        }
                    }
                })
        }

    }

    @Suppress("DEPRECATION")
    private fun updateCellInfo(cells: List<CellInfo>, notify: Boolean = true) {
        synchronized(this) {
            hasReading = true
            val newSignals = cells.filter { it.isRegistered }.mapNotNull {
                when {
                    it is CellInfoWcdma -> {
                        RawCellSignal(
                            it.cellIdentity.cid.toString(),
                            Instant.ofEpochMilli(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) it.timestampMillis else (it.timeStamp / 1000000)),
                            it.cellSignalStrength.dbm,
                            it.cellSignalStrength.level,
                            CellNetwork.Wcdma
                        )
                    }
                    it is CellInfoGsm -> {
                        RawCellSignal(
                            it.cellIdentity.cid.toString(),
                            Instant.ofEpochMilli(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) it.timestampMillis else (it.timeStamp / 1000000)),
                            it.cellSignalStrength.dbm,
                            it.cellSignalStrength.level,
                            CellNetwork.Gsm
                        )
                    }
                    it is CellInfoLte -> {
                        RawCellSignal(
                            it.cellIdentity.ci.toString(),
                            Instant.ofEpochMilli(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) it.timestampMillis else (it.timeStamp / 1000000)),
                            it.cellSignalStrength.dbm,
                            it.cellSignalStrength.level,
                            CellNetwork.Lte
                        )
                    }
                    it is CellInfoCdma -> {
                        RawCellSignal(
                            it.cellIdentity.basestationId.toString(),
                            Instant.ofEpochMilli(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) it.timestampMillis else (it.timeStamp / 1000000)),
                            it.cellSignalStrength.dbm,
                            it.cellSignalStrength.level,
                            CellNetwork.Cdma
                        )
                    }
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && it is CellInfoTdscdma -> {
                        RawCellSignal(
                            it.cellIdentity.cid.toString(),
                            Instant.ofEpochMilli(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) it.timestampMillis else (it.timeStamp / 1000000)),
                            it.cellSignalStrength.dbm,
                            it.cellSignalStrength.level,
                            CellNetwork.Tdscdma
                        )
                    }
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && it is CellInfoNr -> {
                        RawCellSignal(
                            it.cellIdentity.operatorAlphaLong.toString(),
                            Instant.ofEpochMilli(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) it.timestampMillis else (it.timeStamp / 1000000)),
                            it.cellSignalStrength.dbm,
                            it.cellSignalStrength.level,
                            CellNetwork.Nr
                        )
                    }
                    else -> null
                }
            }

            val latestSignals = newSignals.map {
                val old = oldSignals.find { signal -> it.id == signal.id }
                if (old == null) {
                    it
                } else {
                    if (old.time > it.time) old else it
                }
            }

            oldSignals = latestSignals

            _signals = latestSignals.map {
                CellSignal(it.id, it.percent, it.dbm, it.quality, it.network)
            }

            if (notify) {
                notifyListeners()
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun startImpl() {
        if (!permissions.canGetFineLocation()) {
            _signals = listOf()
            notifyListeners()
            return
        }
        loadFromCache(false)
        intervalometer.interval(Duration.ofSeconds(5))
    }

    @SuppressLint("MissingPermission")
    private fun loadFromCache(notify: Boolean = true) {
        if (!permissions.canGetFineLocation()) return
        tryOrNothing {
            updateCellInfo(telephony?.allCellInfo ?: listOf(), notify)
        }
    }


    override fun stopImpl() {
        intervalometer.stop()
    }

    data class RawCellSignal(
        val id: String,
        val time: Instant,
        val dbm: Int,
        val level: Int,
        val network: CellNetwork
    ) {
        val percent: Float
            get() {
                return MathUtils.clamp(
                    100f * (dbm - network.minDbm) / (network.maxDbm - network.minDbm).toFloat(),
                    0f,
                    100f
                )
            }

        val quality: Quality
            get() = when (level) {
                3, 4 -> Quality.Good
                2 -> Quality.Moderate
                0 -> Quality.Unknown
                else -> Quality.Poor
            }
    }
}