package me.rerere.asr

import android.media.audiofx.AcousticEchoCanceler

/** VOICE_COMMUNICATION supplies the platform path; enable its AEC effect where available. */
internal fun enableAcousticEchoCancellation(sessionId: Int): AcousticEchoCanceler? =
    runCatching {
        if (!AcousticEchoCanceler.isAvailable()) null
        else AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
    }.getOrNull()
