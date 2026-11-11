package com.dynedge

import android.graphics.Region
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Restricts the window's touchable area to an arbitrary Region through the hidden
 * ViewTreeObserver.OnComputeInternalInsetsListener (TOUCHABLE_INSETS_REGION).
 */
class TouchRegion private constructor(
    private val view: View,
    private val setTouchableInsets: Method,
    private val touchableRegion: Field,
    private val addMethod: Method,
    private val removeMethod: Method
) {
    private val region = Region()
    private var listener: Any? = null
    private var observer: ViewTreeObserver? = null

    private fun attach(): Boolean {
        val handler = InvocationHandler { p, m, args ->
            when (m.name) {
                "onComputeInternalInsets" -> {
                    val info = args?.getOrNull(0)
                    if (info != null) {
                        try {
                            setTouchableInsets.invoke(info, TOUCHABLE_INSETS_REGION)
                            (touchableRegion.get(info) as? Region)?.set(region)
                        } catch (t: Throwable) {
                            logOnce(t)
                        }
                    }
                    null
                }
                "equals" -> p === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(p)
                "toString" -> "TouchRegion@" + Integer.toHexString(System.identityHashCode(p))
                else -> null
            }
        }
        val cls = addMethod.parameterTypes[0]
        val l = Proxy.newProxyInstance(cls.classLoader, arrayOf(cls), handler)
        val vto = view.viewTreeObserver
        addMethod.invoke(vto, l)
        listener = l
        observer = vto
        return true
    }

    /** Copies [r] and triggers a traversal so the insets are re-reported. */
    fun set(r: Region) {
        region.set(r)
        view.invalidate()
    }

    fun uninstall() {
        val l = listener ?: return
        listener = null
        try {
            val cur = view.viewTreeObserver
            if (cur.isAlive) removeMethod.invoke(cur, l)
            val old = observer
            if (old != null && old !== cur && old.isAlive) removeMethod.invoke(old, l)
        } catch (t: Throwable) {
            logOnce(t)
        }
        observer = null
    }

    companion object {
        private const val TAG = "DynamicEdge"
        private const val TOUCHABLE_INSETS_REGION = 3
        private var logged = false

        private var cached = false
        private var mSetInsets: Method? = null
        private var fRegion: Field? = null
        private var mAdd: Method? = null
        private var mRemove: Method? = null

        private fun logOnce(t: Throwable) {
            if (!logged) {
                logged = true
                Log.w(TAG, "TouchRegion reflection failed, falling back to full window", t)
            }
        }

        private fun lookup(): Boolean {
            if (cached) return mSetInsets != null
            cached = true
            try {
                val listenerCls = Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
                val infoCls = Class.forName("android.view.ViewTreeObserver\$InternalInsetsInfo")
                val set = infoCls.getMethod("setTouchableInsets", Int::class.javaPrimitiveType)
                val field = infoCls.getField("touchableRegion")
                val add = ViewTreeObserver::class.java.getMethod("addOnComputeInternalInsetsListener", listenerCls)
                val remove = ViewTreeObserver::class.java.getMethod("removeOnComputeInternalInsetsListener", listenerCls)
                mSetInsets = set
                fRegion = field
                mAdd = add
                mRemove = remove
                return true
            } catch (t: Throwable) {
                logOnce(t)
                return false
            }
        }

        fun install(view: View): TouchRegion? {
            if (!lookup()) return null
            return try {
                val tr = TouchRegion(view, mSetInsets!!, fRegion!!, mAdd!!, mRemove!!)
                tr.attach()
                tr
            } catch (t: Throwable) {
                logOnce(t)
                null
            }
        }
    }
}
