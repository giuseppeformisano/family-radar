package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.repository.FirebaseRepository
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

/**
 * Riceve i cambi di attivita' dal sistema: e' Android a svegliare il processo
 * quando l'utente entra o esce da uno stato, senza che l'app debba controllare.
 *
 * Il riconoscimento gira sul sensor hub (accelerometro e giroscopio a basso
 * consumo), non sul GPS: per questo puo' restare registrato sempre.
 */
class ActivityTransitionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        try {
            if (!ActivityTransitionResult.hasResult(intent)) return
            val result = ActivityTransitionResult.extractResult(intent) ?: return

            val repository = FirebaseRepository.getInstance(context)

            // Gli eventi arrivano in ordine cronologico: interessa l'ultimo stato
            // noto, quindi si applicano tutti in sequenza e vince quello finale.
            for (event in result.transitionEvents) {
                val isEnter = event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
                Log.d(
                    TAG,
                    "Transizione: ${labelFor(event.activityType)} ${if (isEnter) "ENTER" else "EXIT"}"
                )
                repository.onActivityTransition(event.activityType, isEnter)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Errore nella transizione di attivita': ${e.message}")
        }
    }

    private fun labelFor(activityType: Int): String = when (activityType) {
        DetectedActivity.IN_VEHICLE -> "IN_VEHICLE"
        DetectedActivity.ON_BICYCLE -> "ON_BICYCLE"
        DetectedActivity.WALKING -> "WALKING"
        DetectedActivity.RUNNING -> "RUNNING"
        DetectedActivity.STILL -> "STILL"
        else -> "ALTRO($activityType)"
    }

    companion object {
        private const val TAG = "ActivityTransition"
        const val ACTION_TRANSITION = "com.example.ACTION_ACTIVITY_TRANSITION"
    }
}
