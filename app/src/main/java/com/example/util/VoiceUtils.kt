package com.example.util

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import android.util.Log
import java.io.File

/**
 * Note vocali: registrazione AAC/m4a mono, encode Base64 (salvato in Firestore
 * come le immagini) e file temporaneo per la riproduzione.
 *
 * La durata massima e' un parametro unico [MAX_DURATION_MS]: alzarlo qui basta.
 * A 5s in AAC ~24kbps il file e' di pochi KB, ben sotto il limite Firestore di 1 MB.
 */
object VoiceUtils {

    private const val TAG = "VoiceUtils"

    /** Durata massima di una nota vocale. Parametro unico, facile da cambiare. */
    const val MAX_DURATION_MS = 5_000L

    /** Bitrate/sampling volutamente bassi: e' voce, non musica. */
    private const val BITRATE = 24_000
    private const val SAMPLE_RATE = 44_100

    /**
     * Controller della singola registrazione. Incapsula MediaRecorder + file
     * temporaneo e l'istante di avvio (per calcolare la durata effettiva).
     */
    class Recording internal constructor(
        private val recorder: MediaRecorder,
        val file: File,
        val startedAt: Long
    ) {
        /** Ferma la registrazione. Ritorna la durata in ms, o null se troppo corta / errore. */
        fun stop(): Long? {
            return try {
                recorder.stop()
                recorder.release()
                System.currentTimeMillis() - startedAt
            } catch (e: Exception) {
                // stop() lancia se si ferma troppo presto (nessun frame audio).
                Log.w(TAG, "stop recording fallita: ${e.message}")
                runCatching { recorder.release() }
                runCatching { file.delete() }
                null
            }
        }

        /** Annulla e ripulisce senza produrre nulla. */
        fun cancel() {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            runCatching { file.delete() }
        }
    }

    /** Avvia una registrazione. Ritorna null se il microfono non e' disponibile. */
    fun startRecording(context: Context): Recording? {
        return try {
            val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
            @Suppress("DEPRECATION")
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                MediaRecorder()
            }
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioEncodingBitRate(BITRATE)
                setAudioSamplingRate(SAMPLE_RATE)
                setOutputFile(file.absolutePath)
                // Rete di sicurezza piu' lunga del cap UI: il cap vero a MAX_DURATION_MS
                // lo fa la UI, cosi' e' il nostro stop() a finalizzare (niente race con
                // l'auto-finalizzazione del recorder che farebbe fallire stop()).
                setMaxDuration((MAX_DURATION_MS + 2_000).toInt())
                prepare()
                start()
            }
            Recording(recorder, file, System.currentTimeMillis())
        } catch (e: Exception) {
            Log.e(TAG, "startRecording fallita: ${e.message}")
            null
        }
    }

    /** File audio -> Base64 (NO_WRAP, come le immagini). */
    fun fileToBase64(file: File): String? {
        return try {
            Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "fileToBase64 fallita: ${e.message}")
            null
        }
    }

    /**
     * Path del file audio in cache per un dato messageId, se gia' presente (e non
     * vuoto). Serve a NON rileggere l'audio da Firestore due volte: l'audio vive in
     * un documento separato `voiceNotes/{id}` e va scaricato una sola volta al play.
     */
    fun cachedPath(context: Context, messageId: String): String? {
        val f = File(context.cacheDir, "voice_$messageId.m4a")
        return if (f.exists() && f.length() > 0) f.absolutePath else null
    }

    /** Scrive in cache l'audio Base64 per messageId e ritorna il path (o null). */
    fun writeCacheFromBase64(context: Context, messageId: String, base64: String): String? {
        return try {
            val f = File(context.cacheDir, "voice_$messageId.m4a")
            if (!f.exists() || f.length() == 0L) {
                f.writeBytes(Base64.decode(base64, Base64.NO_WRAP))
            }
            f.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "writeCacheFromBase64 fallita: ${e.message}")
            null
        }
    }
}
