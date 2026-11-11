package com.dynedge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Follows the user's music session: title, artist, art, playback state, position and transport.
 * Listeners exist only between start() and stop(). All onChange callbacks arrive on the main thread.
 *
 * Only a session that is allowed by [Prefs.mediaBlocked] and carries metadata can become the source.
 * There is deliberately no "first active session" fallback: idle widgets (Nothing's Essential News
 * publishes a `PODCAST_SESSION`) must never appear here.
 */
class MediaBridge(private val ctx: Context, private val artPx: Int, private val onChange: () -> Unit) {

    private val app: Context = ctx.applicationContext ?: ctx
    private val main = Handler(Looper.getMainLooper())
    private val msm: MediaSessionManager? =
        app.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
    private val listenerComponent = ComponentName(app, MediaListener::class.java)

    private var started = false
    private var controller: MediaController? = null
    private var playback: PlaybackState? = null
    private var sourceArt: Bitmap? = null

    var title: String? = null
        private set
    var artist: String? = null
        private set
    var art: Bitmap? = null
        private set
    var durationMs: Long = 0L
        private set

    val hasSession: Boolean get() = controller != null
    val isPlaying: Boolean get() = playback?.state == PlaybackState.STATE_PLAYING

    private fun fire() = onChange()

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list ->
            if (!started) return@OnActiveSessionsChangedListener
            val l = list ?: emptyList()
            watch(l)
            select(l)
            fire()
        }

    // Lightweight callbacks on every allowed (not blocked, not Nothing podcast) session, so a player that
    // registers its session before it sets metadata, or starts playing later, is picked without waiting
    // for a session-list change. The selected controller itself is handled by [callback].
    private val watched = ArrayList<MediaController>()
    private val watchCbs = ArrayList<MediaController.Callback>()

    private inner class Watch(private val c: MediaController) : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = repick()

        override fun onPlaybackStateChanged(state: PlaybackState?) = repick()

        override fun onSessionDestroyed() {
            val i = watchCbs.indexOf(this)
            if (i < 0) return
            try {
                watched[i].unregisterCallback(this)
            } catch (e: Exception) {
            }
            watched.removeAt(i)
            watchCbs.removeAt(i)
        }

        private fun repick() {
            if (!started) return
            val cur = controller
            if (cur != null && sameSession(cur, c)) return
            val best = pick(watched) ?: return
            if (cur != null && sameSession(cur, best)) return
            select(ArrayList(watched))
            fire()
        }
    }

    private fun sameSession(a: MediaController, b: MediaController): Boolean = try {
        a.sessionToken == b.sessionToken
    } catch (e: Exception) {
        false
    }

    private fun watch(list: List<MediaController>) {
        unwatch()
        if (!started) return
        val blocked = blockedSet(app)
        for (c in list) {
            if (!isAllowedSource(c, blocked)) continue
            val cb = Watch(c)
            try {
                c.registerCallback(cb, main)
                watched.add(c)
                watchCbs.add(cb)
            } catch (e: Exception) {
            }
        }
    }

    private fun unwatch() {
        for (i in watched.indices) {
            try {
                watched[i].unregisterCallback(watchCbs[i])
            } catch (e: Exception) {
            }
        }
        watched.clear()
        watchCbs.clear()
    }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            val c = controller
            if (c != null && state?.state == PlaybackState.STATE_PLAYING) remember(c)
            refresh()
            fire()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            refresh()
            fire()
        }

        override fun onSessionDestroyed() {
            if (!started) return
            val l = querySessions()
            watch(l)
            select(l)
            fire()
        }
    }

    fun start() {
        if (started) return
        started = true
        val m = msm
        if (m != null) {
            try {
                m.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, main)
            } catch (e: Exception) {
            }
        }
        val l = querySessions()
        watch(l)
        select(l)
    }

    fun stop() {
        if (!started) return
        started = false
        val m = msm
        if (m != null) {
            try {
                m.removeOnActiveSessionsChangedListener(sessionsListener)
            } catch (e: Exception) {
            }
        }
        unwatch()
        controller?.let {
            try {
                it.unregisterCallback(callback)
            } catch (e: Exception) {
            }
        }
        controller = null
        playback = null
        sourceArt = null
        art = null
        title = null
        artist = null
        durationMs = 0L
    }

    fun positionMs(): Long {
        val s = playback ?: return 0L
        var p = s.position
        if (s.state == PlaybackState.STATE_PLAYING) {
            val elapsed = SystemClock.elapsedRealtime() - s.lastPositionUpdateTime
            p += (elapsed * s.playbackSpeed).toLong()
        }
        if (p < 0L) p = 0L
        if (durationMs > 0L && p > durationMs) p = durationMs
        return p
    }

    fun playPause() {
        // No allowed session = nothing to resume. Never dispatch a global media key here: that could
        // start a blocked background session (e.g. the Essential News podcast).
        val c = controller ?: return
        remember(c)
        try {
            if (isPlaying) c.transportControls.pause() else c.transportControls.play()
        } catch (e: Exception) {
        }
    }

    fun next() {
        val c = controller ?: return
        remember(c)
        try {
            c.transportControls.skipToNext()
        } catch (e: Exception) {
        }
    }

    fun prev() {
        val c = controller ?: return
        remember(c)
        try {
            c.transportControls.skipToPrevious()
        } catch (e: Exception) {
        }
    }

    /** True when the active session supports seeking and the duration is known. */
    val canSeek: Boolean
        get() {
            val c = controller ?: return false
            val actions = c.playbackState?.actions ?: return false
            return durationMs > 0L && (actions and android.media.session.PlaybackState.ACTION_SEEK_TO) != 0L
        }

    fun seekTo(ms: Long) {
        try {
            controller?.transportControls?.seekTo(ms.coerceIn(0L, if (durationMs > 0L) durationMs else ms))
        } catch (e: Exception) {
        }
    }

    fun openPlayer(): Boolean {
        val c = controller ?: return false
        try {
            val pi = c.sessionActivity
            if (pi != null) {
                pi.send()
                return true
            }
        } catch (e: Exception) {
        }
        return try {
            val i = app.packageManager.getLaunchIntentForPackage(c.packageName) ?: return false
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---- internals --------------------------------------------------------------------

    private fun querySessions(): List<MediaController> {
        val m = msm ?: return emptyList()
        return try {
            m.getActiveSessions(listenerComponent)
        } catch (e: SecurityException) {
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Picks the media source and switches callbacks if it changed.
     *
     * Rule, in order (there is NO first-session fallback):
     *  1. an allowed session that is playing (the remembered one wins a tie),
     *  2. the remembered session (last one the user played) if it is still active,
     *  3. an allowed paused session with metadata that was active within [RECENT_MS],
     *  4. none - the window shows its empty state.
     *
     * A session is allowed only when its package is not blocked, it is not a Nothing podcast
     * session, and it carries at least a title or an artist.
     */
    private fun select(list: List<MediaController>) {
        val best = pick(list)
        val current = controller
        val same = best != null && current != null && best.sessionToken == current.sessionToken
        if (!same) {
            if (current != null) {
                try {
                    current.unregisterCallback(callback)
                } catch (e: Exception) {
                }
            }
            controller = best
            if (best != null) {
                try {
                    best.registerCallback(callback, main)
                } catch (e: Exception) {
                }
            }
        }
        if (best != null && stateOf(best) == PlaybackState.STATE_PLAYING) remember(best)
        refresh()
    }

    /** The selection rule above. Reads every entry point through the block filter. */
    private fun pick(list: List<MediaController>): MediaController? {
        if (list.isEmpty()) return null
        val blocked = blockedSet(app)
        val now = SystemClock.elapsedRealtime()
        var playing: MediaController? = null
        var rememberedBest: MediaController? = null
        var recent: MediaController? = null
        var recentAt = 0L
        for (c in list) {
            if (!isCandidate(c, blocked)) continue
            val st = stateOf(c)
            val rank = rememberRank(c)
            if (st == PlaybackState.STATE_PLAYING && (playing == null || rank > rememberRank(playing))) playing = c
            if (rank > 0 && (rememberedBest == null || rank > rememberRank(rememberedBest))) rememberedBest = c
            if (st == PlaybackState.STATE_PAUSED) {
                val at = activeAt(c, now)
                if (at > 0L && now - at <= RECENT_MS && at > recentAt) {
                    recentAt = at
                    recent = c
                }
            }
        }
        return playing ?: rememberedBest ?: recent
    }

    private fun activeAt(c: MediaController, now: Long): Long {
        val at = try {
            c.playbackState?.lastPositionUpdateTime ?: 0L
        } catch (e: Exception) {
            0L
        }
        if (at <= 0L) return 0L
        return if (at > now) now else at
    }

    /**
     * 0 = not the remembered player, 1 = same package (player restarted its session),
     * 2 = same package and the exact session token the user last played.
     */
    private fun rememberRank(c: MediaController): Int {
        val pkg = lastPkg ?: return 0
        if (pkgOf(c) != pkg) return 0
        val token = lastToken ?: return 1
        val t = try {
            c.sessionToken
        } catch (e: Exception) {
            null
        }
        return if (t != null && t == token) 2 else 1
    }

    private fun remember(c: MediaController) {
        val pkg = pkgOf(c) ?: return
        lastPkg = pkg
        lastToken = try {
            c.sessionToken
        } catch (e: Exception) {
            null
        }
    }

    private fun refresh() {
        val c = controller
        if (c == null) {
            playback = null
            title = null
            artist = null
            durationMs = 0L
            sourceArt = null
            art = null
            return
        }
        playback = try {
            c.playbackState
        } catch (e: Exception) {
            null
        }
        val meta: MediaMetadata? = try {
            c.metadata
        } catch (e: Exception) {
            null
        }
        title = if (meta == null) null else firstText(
            meta,
            MediaMetadata.METADATA_KEY_TITLE,
            MediaMetadata.METADATA_KEY_DISPLAY_TITLE
        )
        artist = if (meta == null) null else firstText(
            meta,
            MediaMetadata.METADATA_KEY_ARTIST,
            MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
            MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE
        )
        durationMs = if (meta == null) 0L else maxOf(0L, meta.getLong(MediaMetadata.METADATA_KEY_DURATION))
        updateArt(meta)
    }

    private fun firstText(meta: MediaMetadata, vararg keys: String): String? {
        for (k in keys) {
            val v = meta.getString(k)
            if (!v.isNullOrBlank()) return v
        }
        return null
    }

    private fun updateArt(meta: MediaMetadata?) {
        val src: Bitmap? = if (meta == null) null else (
            meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            )
        if (src === sourceArt) return
        sourceArt = src
        // The previous scaled bitmap is not recycled: the view may still be drawing it. GC reclaims it.
        art = if (src == null) null else centerCrop(src)
    }

    private fun centerCrop(src: Bitmap): Bitmap? {
        if (artPx <= 0 || src.width <= 0 || src.height <= 0) return null
        return try {
            val side = minOf(src.width, src.height)
            val left = (src.width - side) / 2
            val top = (src.height - side) / 2
            val out = Bitmap.createBitmap(artPx, artPx, Bitmap.Config.ARGB_8888)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            Canvas(out).drawBitmap(src, Rect(left, top, left + side, top + side), Rect(0, 0, artPx, artPx), paint)
            out
        } catch (e: Exception) {
            null
        }
    }

    companion object {

        /** A paused session older than this is not "recently active" and is not picked on its own. */
        private const val RECENT_MS = 30 * 60 * 1000L

        // The last session the user actually played. Kept across stop()/start() so closing and
        // reopening the edge returns to the user's player instead of another app's idle session.
        private var lastPkg: String? = null
        private var lastToken: MediaSession.Token? = null

        /** The packages the media window / visualizer must never follow (media sessions only, not notifications). */
        internal fun blockedSet(ctx: Context): Set<String> = Prefs.mediaBlocked(ctx)

        internal fun isBlocked(ctx: Context, pkg: String): Boolean = pkg in blockedSet(ctx)

        internal fun pkgOf(c: MediaController): String? = try {
            c.packageName
        } catch (e: Exception) {
            null
        }

        internal fun stateOf(c: MediaController): Int? = try {
            c.playbackState?.state
        } catch (e: Exception) {
            null
        }

        /** Nothing's widgets tag their news widget `PODCAST_SESSION`; it is never a music source. */
        internal fun isPodcastTag(tag: String?): Boolean = tag != null && tag.contains("PODCAST", true)

        internal fun tagOf(c: MediaController): String? = try {
            c.tag
        } catch (e: Exception) {
            null
        }

        /** At least a title or an artist: nameless sessions are widgets / previews, not music. */
        internal fun hasIdentity(c: MediaController): Boolean {
            val meta = try {
                c.metadata
            } catch (e: Exception) {
                null
            } ?: return false
            val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            if (!title.isNullOrBlank()) return true
            val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            return !artist.isNullOrBlank()
        }

        /**
         * True when this controller may become / stay the media source. Blocked packages are rejected
         * first, then Nothing podcast sessions, then sessions without any title or artist. Real podcast
         * players from other packages are untouched.
         */
        internal fun isCandidate(c: MediaController, blocked: Set<String>): Boolean =
            isAllowedSource(c, blocked) && hasIdentity(c)

        /**
         * The static part of [isCandidate]: package not blocked and not a Nothing podcast session.
         * Does not look at metadata, which a player may set only after it registers its session.
         */
        internal fun isAllowedSource(c: MediaController, blocked: Set<String>): Boolean {
            val pkg = pkgOf(c) ?: return false
            if (pkg in blocked) return false
            if (pkg.startsWith("com.nothing.") && isPodcastTag(tagOf(c))) return false
            return true
        }
    }
}
