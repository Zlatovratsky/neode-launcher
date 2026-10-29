package org.cocos2dx.lib

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.Process
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView

import java.lang.ref.WeakReference
import kotlin.collections.ArrayList

import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGL11
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface
import javax.microedition.khronos.opengles.GL
import javax.microedition.khronos.opengles.GL10

open class GLSurfaceView : SurfaceView, SurfaceHolder.Callback2 {

    constructor(context: Context) : super(context) {
        val holder = getHolder()
        holder.addCallback(this)
    }

    fun setRenderer(renderer: Renderer) {
        if (!mHasRenderer) {
            mRenderer = renderer
            mGLThread = GLThread(mThisWeakRef, renderer)
            mGLThread.start()
            mHasRenderer = true
        }  
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        mGLThread.surfaceCreated()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        mGLThread.surfaceDestroyed()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        mGLThread.onWindowResize()
    }

    override fun surfaceRedrawNeededAsync(holder: SurfaceHolder, finishDrawing: Runnable) {
        mGLThread.queueEvent(finishDrawing)
    }

    override fun surfaceRedrawNeeded(holder: SurfaceHolder) {}

    open fun onPause() {
        mGLThread.onPause()
    }

    open fun onResume() {
        mGLThread.onResume()
    }

    fun queueEvent(r: Runnable) {
        mGLThread.queueEvent(r)
    }

    override protected fun onAttachedToWindow() {
        super.onAttachedToWindow()
        
        if (mDetached && mHasRenderer) {
            mGLThread = GLThread(mThisWeakRef, mRenderer)
            mGLThread.start()  
        }
        mDetached = false
    }

    override protected fun onDetachedFromWindow() {
        if (!mDetached) {
            mGLThread.exit()
            mDetached = true 
        }

        super.onDetachedFromWindow()    
    }

    interface Renderer {
        fun onSurfaceCreated()

        fun onSurfaceChanged()

        fun onDrawFrame()

        fun handleOnPause()

        fun handleOnResume()
    }
    
    private lateinit var mRenderer: Renderer
    private lateinit var mGLThread: GLThread
    private val mThisWeakRef = WeakReference<GLSurfaceView>(this)

    private var mDetached: Boolean = false
    private var mHasRenderer: Boolean = false

    private class GLThread : HandlerThread, Choreographer.FrameCallback {

        constructor(glSurfaceViewWeakRef: WeakReference<GLSurfaceView>, renderer: Renderer) : super("GLThread", Process.THREAD_PRIORITY_URGENT_DISPLAY*2) {
            mRenderer = renderer
            mGLSurfaceViewWeakRef = glSurfaceViewWeakRef
            mEGLHelper = EGLHelper(glSurfaceViewWeakRef)
        }

        override protected fun onLooperPrepared() {
            mHandler = Handler.createAsync(getLooper())
            mChoreographer = Choreographer.getInstance()
            try {
                mEGLHelper.start()
            } finally {}
            mExited = false
        }

        override public fun doFrame(frameTimeInNanos: Long) {
            mEventQueue.processEvents()

            if (!mHasSurface || mPaused || mExited) {
                mChoreographer.postFrameCallback(this)
                return
            }

            mRenderer.onDrawFrame()

            when (mEGLHelper.swap()) {
                EGL10.EGL_SUCCESS -> mChoreographer.postFrameCallback(this)
                else -> return
            }
        }

        public fun surfaceCreated() {
            synchronized (GLThreadManager) {
                if (!mHasSurface && mHandler.post {
                    synchronized (GLThreadManager) {
                        mHasSurface = mEGLHelper.createSurface()
                        if (mHasSurface) {
                            mRenderer.onSurfaceCreated()
                            mRenderer.onSurfaceChanged()
                            mChoreographer.postFrameCallback(this)
                        }
                        (GLThreadManager as java.lang.Object).notifyAll()
                    }
                }){
                    while (!mHasSurface) {
                        try {
                            (GLThreadManager as java.lang.Object).wait()
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                    }
                }
            }
        }

        public fun surfaceDestroyed() {
            synchronized(GLThreadManager) {
                if (mHasSurface && mHandler.post {
                    synchronized(GLThreadManager) {
                        mChoreographer.removeFrameCallback(this)
                        mEGLHelper.destroySurface()
                        mHasSurface = false
                        (GLThreadManager as java.lang.Object).notifyAll()
                    }
                }){
                    while (mHasSurface) {
                        try {
                            (GLThreadManager as java.lang.Object).wait()
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                    }
                }
            }
        }

        public fun onPause() {
            synchronized(GLThreadManager) {
                if (!mPaused) {
                    mHandler.post {
                        synchronized(GLThreadManager) {
                            mRenderer.handleOnPause()
                            mPaused = true
                            (GLThreadManager as java.lang.Object).notifyAll()
                        }
                    }
                    while (!mPaused) {
                        try {
                            (GLThreadManager as java.lang.Object).wait()
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                    }                    
                }
            }
        }

        public fun onResume() {
            synchronized(GLThreadManager) {
                if (mPaused) {
                    mEventQueue.add {
                        synchronized(GLThreadManager) {
                            mRenderer.handleOnResume()
                            mPaused = false
                            (GLThreadManager as java.lang.Object).notifyAll()
                        }
                    }
                }
            }
        }

        public fun onWindowResize() {
            synchronized(GLThreadManager) {
                mHandler.post {
                    synchronized(GLThreadManager) {
                        mRenderer.onSurfaceChanged()
                        mSizeChanged = true
                        (GLThreadManager as java.lang.Object).notifyAll()
                    }
                }
                while (!mSizeChanged) {
                    try {
                        (GLThreadManager as java.lang.Object).wait()
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
                mSizeChanged = false
            }
        }

        public fun queueEvent(r: Runnable) {
            mEventQueue.add(r)
        }

        public fun exit() {
            synchronized(GLThreadManager) {    
                if (!mExited && mHandler.post {
                    synchronized(GLThreadManager) {
                        mChoreographer.removeFrameCallback(this);
                        if (!mPaused) {
                            mRenderer.handleOnPause();
                            mPaused = true;
                        }
                        if (mHasSurface) {
                            mEGLHelper.destroySurface();
                            mHasSurface = false;
                        }
                        mEGLHelper.finish();                            
                        mExited = true;
                        (GLThreadManager as java.lang.Object).notifyAll()
                    }
                }) {
                    while (!mExited) {
                        try {
                            (GLThreadManager as java.lang.Object).wait()
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                    }
                }

                quitSafely();
                try {
                    this.join();
                } catch (e: InterruptedException) {}
            }
        }

        private var mHasSurface = false
        private var mPaused = true
        private var mSizeChanged = false
        private var mExited = true
        
        private lateinit var mChoreographer: Choreographer
        private lateinit var mHandler: Handler
        private lateinit var mGLSurfaceViewWeakRef: WeakReference<GLSurfaceView>
        private val GLThreadManager = Any()
        private val mEventQueue: EventQueue = EventQueue()
        private lateinit var mEGLHelper: EGLHelper
        private lateinit var mRenderer: Renderer

        private class EventQueue {
            private var front = ArrayList<Runnable>()
            private var back = ArrayList<Runnable>()
            private lateinit var buffer : ArrayList<Runnable>
            private val lock = Any()

            public fun add(r: Runnable) {
                synchronized(lock) {
                    front.add(r)
                }
            }

            public fun processEvents() {
                synchronized(lock) {
                    buffer = front
                    front = back
                    back = buffer                    
                }

                for (i in 0 until back.size) {
                    back[i].run()
                }
                back.clear()
            }
        }

        private class EGLHelper {
            constructor(glSurfaceViewWeakRef: WeakReference<GLSurfaceView>) {
                mGLSurfaceViewWeakRef = glSurfaceViewWeakRef
            }

            fun start() {
                mEGL = (EGLContext.getEGL() as EGL10)
                
                mEGLDisplay = mEGL.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
                if (mEGLDisplay == EGL10.EGL_NO_DISPLAY) {
                    throw RuntimeException("")
                }

                val version = IntArray(2)
                if (!mEGL.eglInitialize(mEGLDisplay, version)) {
                    throw RuntimeException("")
                }

                mEGLConfig = EGLConfigChooser.chooseConfig(mEGL, mEGLDisplay)
                
                val attrib_list = intArrayOf(0x3098, 2, EGL10.EGL_NONE) 
                mEGLContext = mEGL.eglCreateContext(mEGLDisplay, mEGLConfig, EGL10.EGL_NO_CONTEXT, attrib_list)
                
                if (mEGLContext == EGL10.EGL_NO_CONTEXT) {
                    throw IllegalStateException("")
                }
            }

            fun createSurface() : Boolean {

                val view = mGLSurfaceViewWeakRef.get()
                if (view != null) {
                    mEGLSurface = mEGL.eglCreateWindowSurface(mEGLDisplay, mEGLConfig, view.getHolder(), null)
                }

                if (mEGLSurface == EGL10.EGL_NO_SURFACE) {
                    return false
                }

                if (!mEGL.eglMakeCurrent(mEGLDisplay, mEGLSurface, mEGLSurface, mEGLContext)) {
                    return false
                }

                return true
            }

            fun swap() : Int {
                if (!mEGL.eglSwapBuffers(mEGLDisplay, mEGLSurface)) {
                    return mEGL.eglGetError()
                }
                return EGL10.EGL_SUCCESS
            }

            fun destroySurface() {
                mEGL.eglMakeCurrent(mEGLDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
                    
                mEGL.eglDestroySurface(mEGLDisplay, mEGLSurface)
            }

            fun finish() {
                if (!mEGL.eglDestroyContext(mEGLDisplay, mEGLContext)) {
                    throw RuntimeException()
                }
                
                mEGL.eglTerminate(mEGLDisplay);
            }

            lateinit var mGLSurfaceViewWeakRef: WeakReference<GLSurfaceView>
            lateinit var mEGL: EGL10
            lateinit var mEGLDisplay: EGLDisplay
            lateinit var mEGLSurface: EGLSurface
            lateinit var mEGLConfig: EGLConfig
            lateinit var mEGLContext: EGLContext
            lateinit var mGL: GL10


            private object EGLConfigChooser {
                // this comes from EGL14, but is unavailable on EGL10
                // also EGL14 is incompatible with EGL10. so whatever
                private var EGL_OPENGL_ES2_BIT: Int = 0x04
                
                private data class ConfigValues(
                    val redSize: Int,
                    val greenSize: Int,
                    val blueSize: Int,
                    val alphaSize: Int,
                    val depthSize: Int,
                    val stencilSize: Int,
                )
                
                private fun getAttribValue(egl: EGL10, display: EGLDisplay, config: EGLConfig, attrib: Int): Int {
                    val value = IntArray(1)
                    egl.eglGetConfigAttrib(display, config, attrib, value)
                    return value[0]
                }
                
                private fun testConfig(egl: EGL10, display: EGLDisplay, attrib: ConfigValues): EGLConfig? {
                    val attribList = intArrayOf(
                        EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                        EGL10.EGL_RED_SIZE, attrib.redSize,
                        EGL10.EGL_GREEN_SIZE, attrib.greenSize,
                        EGL10.EGL_BLUE_SIZE, attrib.blueSize,
                        EGL10.EGL_ALPHA_SIZE, attrib.alphaSize,
                        EGL10.EGL_DEPTH_SIZE, attrib.depthSize,
                        EGL10.EGL_STENCIL_SIZE, attrib.stencilSize,
                        EGL10.EGL_NONE
                    )

                    val configs = arrayOfNulls<EGLConfig>(1)
                    val numConfigs = IntArray(1)

                    val res = egl.eglChooseConfig(display, attribList, configs, configs.size, numConfigs)
                    if (res && numConfigs[0] > 0 && configs[0] != null) {
                        return configs[0]
                    }

                    return null
                }

                private fun printConfig(egl: EGL10, display: EGLDisplay, config: EGLConfig) {
                    val id = getAttribValue(egl, display, config, EGL10.EGL_CONFIG_ID)
                    val red = getAttribValue(egl, display, config, EGL10.EGL_RED_SIZE)
                    val green = getAttribValue(egl, display, config, EGL10.EGL_GREEN_SIZE)
                    val blue = getAttribValue(egl, display, config, EGL10.EGL_BLUE_SIZE)
                    val alpha = getAttribValue(egl, display, config, EGL10.EGL_ALPHA_SIZE)
                    val depth = getAttribValue(egl, display, config, EGL10.EGL_DEPTH_SIZE)
                    val stencil = getAttribValue(egl, display, config, EGL10.EGL_STENCIL_SIZE)
                    println("EGLConfig $id: (red = $red, green = $green, blue = $blue, alpha = $alpha, depth = $depth, stencil = $stencil)")
                }

                fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
                    val configs = listOf(
                        ConfigValues(8, 8, 8, 8, 16, 8),
                        ConfigValues(8, 8, 8, 0, 16, 8),
                        ConfigValues(5, 6, 5, 0, 16, 8),
                        ConfigValues(0, 0, 0, 0, 0, 0),
                    )

                    return configs.firstNotNullOf {
                        val config = testConfig(egl, display, it)
                        if (config != null) {
                            printConfig(egl, display, config)
                        }

                        config
                    }
                }
            }
        }
    }

    
}