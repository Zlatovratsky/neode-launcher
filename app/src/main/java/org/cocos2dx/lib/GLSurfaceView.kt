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

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface

private const val DRAW_FRAME = 2

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

    private class GLThread : HandlerThread, Choreographer.FrameCallback, Handler.Callback {

        constructor(glSurfaceViewWeakRef: WeakReference<GLSurfaceView>, renderer: Renderer) : super("GLThread", Process.THREAD_PRIORITY_URGENT_DISPLAY*2) {
            mRenderer = renderer
            mGLSurfaceViewWeakRef = glSurfaceViewWeakRef
            mEGLHelper = EGLHelper(glSurfaceViewWeakRef)
        }

        override protected fun onLooperPrepared() {
            mHandler = Handler.createAsync(getLooper(), this)
            mChoreographer = Choreographer.getInstance()
            try {
                mEGLHelper.start()
            } finally {}
            mExited = false
        }

        override public fun doFrame(frameTimeInNanos: Long) {
            mHandler.sendEmptyMessage(DRAW_FRAME)
        }

        public fun drawFrame() {
            if (!mHasSurface || mPaused || mExited) {
                mChoreographer.postFrameCallback(this)
                return
            }             
            
            mRenderer.onDrawFrame()

            when (mEGLHelper.swap()) {
                EGL14.EGL_SUCCESS -> mChoreographer.postFrameCallback(this)
                EGL14.EGL_CONTEXT_LOST -> {
                    mEGLHelper.destroySurface()
                    mEGLHelper.finish()
                        
                    mEGLHelper.start()
                    synchronized(GLThreadManager) {
                        mHasSurface = mEGLHelper.createSurface()
                        if (mHasSurface) {
                            mRenderer.onSurfaceCreated()
                            mRenderer.onSurfaceChanged()
                            mChoreographer.postFrameCallback(this)
                        }
                    }
                }
                else -> throw IllegalStateException("Surface is bad")
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
                        mChoreographer.removeFrameCallback(this)
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
                    mHandler.post {
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
            mHandler.post(r)
        }

        public fun exit() {
            synchronized(GLThreadManager) {    
                if (!mExited && mHandler.post {
                    synchronized(GLThreadManager) {
                        mChoreographer.removeFrameCallback(this)
                        if (!mPaused) {
                            mRenderer.handleOnPause();
                            mPaused = true;
                        }
                        if (mHasSurface) {
                            mChoreographer.removeFrameCallback(this)
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

        override fun handleMessage(msg: Message) : Boolean {
            when (msg.what) {
                DRAW_FRAME -> {
                    drawFrame()
                    return true
                }
                else -> {
                    msg.callback.run()
                    return true
                }
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
        private lateinit var mEGLHelper: EGLHelper
        private lateinit var mRenderer: Renderer


        private class EGLHelper {
            constructor(glSurfaceViewWeakRef: WeakReference<GLSurfaceView>) {
                mGLSurfaceViewWeakRef = glSurfaceViewWeakRef
            }

            fun start() {
                
                mEGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                if (mEGLDisplay == EGL14.EGL_NO_DISPLAY) {
                    throw RuntimeException("eglGetDisplay failed")
                }

                val major = IntArray(1)
                val minor = IntArray(1)
                if (!EGL14.eglInitialize(mEGLDisplay, major, 0, minor, 0)) {
                    throw RuntimeException("eglInitialize failed")
                }

                mEGLConfig = EGLConfigChooser.chooseConfig(mEGLDisplay)
                
                val attrib_list = intArrayOf(0x3098, 2, EGL14.EGL_NONE) 
                mEGLContext = EGL14.eglCreateContext(mEGLDisplay, mEGLConfig, EGL14.EGL_NO_CONTEXT, attrib_list, 0)
                
                if (mEGLContext == EGL14.EGL_NO_CONTEXT) {
                    throw IllegalStateException("eglCreateContext failed")
                }
            }

            fun createSurface() : Boolean {

                val view = mGLSurfaceViewWeakRef.get()
                if (view != null) {
                    mEGLSurface = EGL14.eglCreateWindowSurface(mEGLDisplay, mEGLConfig, view.getHolder(), null, 0)
                }

                if (mEGLSurface == EGL14.EGL_NO_SURFACE) {
                    return false
                }

                if (!EGL14.eglMakeCurrent(mEGLDisplay, mEGLSurface, mEGLSurface, mEGLContext)) {
                    return false
                }
                
                // EGL14.eglSwapInterval(mEGLDisplay, 0)
                return true
            }

            fun swap() : Int {
                if (!EGL14.eglSwapBuffers(mEGLDisplay, mEGLSurface)) {
                    return EGL14.eglGetError()
                }
                return EGL14.EGL_SUCCESS
            }

            fun destroySurface() {
                EGL14.eglMakeCurrent(mEGLDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    
                EGL14.eglDestroySurface(mEGLDisplay, mEGLSurface)
            }

            fun finish() {
                if (!EGL14.eglDestroyContext(mEGLDisplay, mEGLContext)) {
                    throw RuntimeException()
                }
                
                EGL14.eglTerminate(mEGLDisplay);
            }

            lateinit var mGLSurfaceViewWeakRef: WeakReference<GLSurfaceView>
            lateinit var mEGLDisplay: EGLDisplay
            lateinit var mEGLSurface: EGLSurface
            lateinit var mEGLConfig: EGLConfig
            lateinit var mEGLContext: EGLContext


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
                
                private fun getAttribValue(display: EGLDisplay, config: EGLConfig, attrib: Int): Int {
                    val value = IntArray(1)
                    EGL14.eglGetConfigAttrib(display, config, attrib, value, 0)
                    return value[0]
                }
                
                private fun testConfig(display: EGLDisplay, attrib: ConfigValues): EGLConfig? {
                    val attribList = intArrayOf(
                        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                        EGL14.EGL_RED_SIZE, attrib.redSize,
                        EGL14.EGL_GREEN_SIZE, attrib.greenSize,
                        EGL14.EGL_BLUE_SIZE, attrib.blueSize,
                        EGL14.EGL_ALPHA_SIZE, attrib.alphaSize,
                        EGL14.EGL_DEPTH_SIZE, attrib.depthSize,
                        EGL14.EGL_STENCIL_SIZE, attrib.stencilSize,
                        EGL14.EGL_NONE
                    )

                    val configs = arrayOfNulls<EGLConfig>(1)
                    val numConfigs = IntArray(1)

                    val res = EGL14.eglChooseConfig(display, attribList, 0, configs, 0, configs.size, numConfigs, 0)
                    if (res && numConfigs[0] > 0 && configs[0] != null) {
                        return configs[0]
                    }

                    return null
                }

                private fun printConfig(display: EGLDisplay, config: EGLConfig) {
                    val id = getAttribValue(display, config, EGL14.EGL_CONFIG_ID)
                    val red = getAttribValue(display, config, EGL14.EGL_RED_SIZE)
                    val green = getAttribValue(display, config, EGL14.EGL_GREEN_SIZE)
                    val blue = getAttribValue(display, config, EGL14.EGL_BLUE_SIZE)
                    val alpha = getAttribValue(display, config, EGL14.EGL_ALPHA_SIZE)
                    val depth = getAttribValue(display, config, EGL14.EGL_DEPTH_SIZE)
                    val stencil = getAttribValue(display, config, EGL14.EGL_STENCIL_SIZE)
                    println("EGLConfig $id: (red = $red, green = $green, blue = $blue, alpha = $alpha, depth = $depth, stencil = $stencil)")
                }

                fun chooseConfig(display: EGLDisplay): EGLConfig {
                    val configs = listOf(
                        ConfigValues(8, 8, 8, 8, 16, 8),
                        ConfigValues(8, 8, 8, 0, 16, 8),
                        ConfigValues(5, 6, 5, 0, 16, 8),
                        ConfigValues(0, 0, 0, 0, 0, 0),
                    )

                    return configs.firstNotNullOf {
                        val config = testConfig(display, it)
                        if (config != null) {
                            printConfig(display, config)
                        }

                        config
                    }
                }
            }
        }
    }   
}