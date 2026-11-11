package com.dynedge

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper

/**
 * Light "is anything playing" watch used while the widget is closed: active-session changes plus a
 * playback-state / metadata callback per allowed session. No art work. Registered only while the screen is on.
 */
class IdleMediaWatch(ctx: Context, private val onChange: (Boolean) -> Unit) {

    private val app: Context = ctx.applicationContext ?: ctx
    private val main = Handler(Looper.getMainLooper())
    private val msm = app.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
    private val component = ComponentName(app, MediaListener::class.java)
    private var started = false
    private val controllers = ArrayList<MediaController>()
    private val callbacks = ArrayList<MediaController.Callback>()

    var playing = false
        private set

    /** Called with the new track title when the metadata of a session changes (track change). */
    var onTrack: ((String?) -> Unit)? = null

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        track(list ?: emptyList())
    }

    private inner class Cb(private val c: MediaController) : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            recompute()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            // A player may set its metadata after its session is listed: re-check every time.
            recompute()
            // Nameless (widget / preview) metadata never reaches the track-change feed.
            if (started && MediaBridge.hasIdentity(c)) {
                onTrack?.invoke(metadata?.getString(MediaMetadata.METADATA_KEY_TITLE))
            }
        }

        override fun onSessionDestroyed() {
            drop(this)
            recompute()
        }
    }

    fun start() {
        if (started) return
        started = true
        try {
            msm?.addOnActiveSessionsChangedListener(sessionsListener, component, main)
        } catch (e: Exception) {
        }
        val list = try {
            msm?.getActiveSessions(component) ?: emptyList()
        } catch (e: Exception) {
            emptyList<MediaController>()
        }
        track(list)
    }

    fun stop() {
        if (!started) return
        started = false
        try {
            msm?.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (e: Exception) {
        }
        untrack()
        playing = false
    }

    private fun untrack() {
        for (i in controllers.indices) {
            try {
                controllers[i].unregisterCallback(callbacks[i])
            } catch (e: Exception) {
            }
        }
        controllers.clear()
        callbacks.clear()
    }

    /** Unregisters and forgets one destroyed session's callback (the session list change follows later). */
    private fun drop(cb: MediaController.Callback) {
        val i = callbacks.indexOf(cb)
        if (i < 0) return
        try {
            controllers[i].unregisterCallback(cb)
        } catch (e: Exception) {
        }
        controllers.removeAt(i)
        callbacks.removeAt(i)
    }

    private fun track(list: List<MediaController>) {
        untrack()
        if (!started) return
        // Same source rule as the media window: blocked packages (Nothing's Essential News widget) and
        // Nothing podcast sessions are never tracked. The title / artist rule is checked in recompute(),
        // because a player may register its session before it sets metadata.
        val blocked = MediaBridge.blockedSet(app)
        for (c in list) {
            if (!MediaBridge.isAllowedSource(c, blocked)) continue
            val cb = Cb(c)
            try {
                c.registerCallback(cb, main)
                controllers.add(c)
                callbacks.add(cb)
            } catch (e: Exception) {
            }
        }
        recompute()
    }

    private fun recompute() {
        var p = false
        for (c in controllers) {
            // nameless widget sessions never drive the visualizer
            if (!MediaBridge.hasIdentity(c)) continue
            val st = try {
                c.playbackState?.state
            } catch (e: Exception) {
                null
            }
            if (st == PlaybackState.STATE_PLAYING) {
                p = true
                break
            }
        }
        if (p != playing) {
            playing = p
            if (started) onChange(p)
        }
    }
}
