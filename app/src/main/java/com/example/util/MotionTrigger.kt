package com.example.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.util.Log

/**
 * Sveglia l'app quando il telefono si muove davvero, usando TYPE_SIGNIFICANT_MOTION.
 *
 * A differenza dell'accelerometro, questo sensore non produce un flusso di campioni
 * da filtrare: e' il sensor hub a decidere e a svegliare il processo una sola volta,
 * quindi resta armato a schermo spento senza consumare batteria. Il prezzo e' che
 * e' one-shot — dopo ogni scatto va riarmato a mano — e che non tutti i dispositivi
 * lo hanno, perche' nelle specifiche Android e' opzionale.
 *
 * Serve a togliere il polling da fermo: invece di guardare la posizione ogni minuto
 * nella speranza di cogliere una partenza, si aspetta che sia il sensore a dirlo.
 */
class MotionTrigger(private val context: Context) {

    private var sensorManager: SensorManager? = null
    private var sensor: Sensor? = null
    private var listener: TriggerEventListener? = null
    private var onMotion: (() -> Unit)? = null

    /** True se il dispositivo ha il sensore: se e' false il chiamante deve restare sul polling. */
    val isAvailable: Boolean
        get() = try {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            sm?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) != null
        } catch (e: Exception) {
            Log.w(TAG, "Sensore movimento non interrogabile: ${e.message}")
            false
        }

    /**
     * Arma il sensore. [callback] viene invocata una volta per ogni scatto; il
     * riarmo e' automatico, cosi' il chiamante non deve ricordarsene.
     */
    fun start(callback: () -> Unit): Boolean {
        return try {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return false
            val s = sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) ?: return false

            stop()

            sensorManager = sm
            sensor = s
            onMotion = callback

            val l = object : TriggerEventListener() {
                override fun onTrigger(event: TriggerEvent?) {
                    Log.d(TAG, "Movimento significativo rilevato")
                    onMotion?.invoke()
                    // One-shot: senza il riarmo il sensore scatta una volta sola
                    // per tutta la vita del processo.
                    try {
                        sensorManager?.requestTriggerSensor(this, sensor)
                    } catch (e: Exception) {
                        Log.w(TAG, "Riarmo sensore fallito: ${e.message}")
                    }
                }
            }
            listener = l

            val armed = sm.requestTriggerSensor(l, s)
            Log.d(TAG, "Sensore movimento armato: $armed")
            armed
        } catch (e: Exception) {
            Log.w(TAG, "Avvio sensore movimento fallito: ${e.message}")
            false
        }
    }

    fun stop() {
        try {
            val l = listener
            val s = sensor
            if (l != null && s != null) {
                sensorManager?.cancelTriggerSensor(l, s)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Stop sensore movimento fallito: ${e.message}")
        }
        listener = null
        sensor = null
        onMotion = null
        sensorManager = null
    }

    private companion object {
        const val TAG = "MotionTrigger"
    }
}
