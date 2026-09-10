package org.cocos2dx.lib

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.util.AttributeSet
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView

import java.lang.ref.WeakReference

import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGL11
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface
import javax.microedition.khronos.opengles.GL
import javax.microedition.khronos.opengles.GL10

import kotlinx.coroutines.*

open class GLSurfaceView : SurfaceView, SurfaceHolder.Callback2 {

    constructor(context: Context) : super(context) {
        val holder = getHolder()
        holder.addCallback(this)
        mThisWeakRef = WeakReference<GLSurfaceView>(this)
        mGLThread = GLThread(mThisWeakRef)
    }

    fun setRenderer(renderer: Renderer) {
        if (mGLThread == null) {
            mGLThread = GLThread(mThisWeakRef)
        }
        mRenderer = renderer
        mGLThread?.start()
        return        
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        mGLThread?.surfaceCreated()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        mGLThread?.surfaceDestroyed()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        mGLThread?.onWindowResize(width, height)
    }

    override fun surfaceRedrawNeededAsync(holder: SurfaceHolder, finishDrawing: Runnable) {
        mGLThread?.queueEvent(finishDrawing)
    }

    override fun surfaceRedrawNeeded(holder: SurfaceHolder) {}

    open fun onPause() {
        mGLThread?.onPause()
    }

    open fun onResume() {
        mGLThread?.onResume()
    }

    fun queueEvent(r: Runnable?) {
        mGLThread?.queueEvent(r)
    }

    override protected fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (mRenderer != null) {
            mGLThread = GLThread(mThisWeakRef)
            mGLThread?.start()
        }
    }

    override protected fun onDetachedFromWindow() {
        if (mGLThread != null) {
            mGLThread?.interrupt()
            mGLThread = null   
        }
        super.onDetachedFromWindow()    
    }

    interface Renderer {
        fun onSurfaceCreated(gl10: GL10?, eglConfig: EGLConfig?)

        fun onSurfaceChanged(gl10: GL10?, width: Int, height: Int)

        fun onDrawFrame(gl10: GL10?)
    }
    
    private var mRenderer: Renderer? = null

    private var mGLThread: GLThread? = null
    lateinit var mThisWeakRef: WeakReference<GLSurfaceView>

    class GLThread : HandlerThread, Choreographer.FrameCallback, Handler.Callback {

        constructor(glSurfaceViewWeakRef: WeakReference<GLSurfaceView>) : super("GLThread") {
            mGLSurfaceViewWeakRef = glSurfaceViewWeakRef
            EGLHelper.init(glSurfaceViewWeakRef)
        }

        fun surfaceCreated() {
            mHandler.post {
                EGLHelper.start()
                gl = EGLHelper.createGL()
            
                if (!EGLHelper.createSurface()) {
                    throw RuntimeException("")
                }

                val view = mGLSurfaceViewWeakRef.get()
                if (view != null) {
                    try {
                        view.mRenderer?.onSurfaceCreated(gl, EGLHelper.mEglConfig)
                    } finally {}
                }

                mChoreographer.postFrameCallback(this)

                synchronized(GLThreadManager) {
                    (GLThreadManager as java.lang.Object).notifyAll()
                }
            }

            synchronized(GLThreadManager) {
                (GLThreadManager as java.lang.Object).wait()
            }
        }

        fun surfaceDestroyed() {
            mHandler.post{
                mChoreographer.removeFrameCallback(this)
                EGLHelper.destroySurface()
                EGLHelper.finish()

                synchronized(GLThreadManager) {
                    (GLThreadManager as java.lang.Object).notifyAll()
                }
            }

            synchronized(GLThreadManager) {
                (GLThreadManager as java.lang.Object).wait()
            }
        }

        fun onPause() {
            mHandler.post{
                mChoreographer.removeFrameCallback(this)
                EGLHelper.destroySurface()

                synchronized(GLThreadManager) {
                    (GLThreadManager as java.lang.Object).notifyAll()
                }
            }
            
            synchronized(GLThreadManager) {
                (GLThreadManager as java.lang.Object).wait()
            }
        }

        fun onResume() {
            mHandler.post {
                if (!EGLHelper.createSurface()) {
                    throw RuntimeException()
                }

                mChoreographer.postFrameCallback(this)

                synchronized(GLThreadManager) {
                    (GLThreadManager as java.lang.Object).notifyAll()
                }
            }

            synchronized(GLThreadManager) {
                (GLThreadManager as java.lang.Object).wait()
            }
        }

        fun onWindowResize(width: Int, height: Int) {
            mHandler.post{
                val view = mGLSurfaceViewWeakRef.get()
                if (view != null) {
                    try {
                        view.mRenderer?.onSurfaceChanged(gl, width, height)
                    } finally {}
                    
                    synchronized(GLThreadManager) {
                        (GLThreadManager as java.lang.Object).notifyAll()
                    }
                }
            }
            synchronized(GLThreadManager) {
                (GLThreadManager as java.lang.Object).wait()
            }
        }

        fun queueEvent(r: Runnable?) {
            if (r != null) {
                mHandler.post(r)
            }
        }

        override fun doFrame(frameTimeInNanos: Long) {
            val view = mGLSurfaceViewWeakRef.get()
            if (view != null) {
                try {
                    view.mRenderer?.onDrawFrame(gl)
                } finally {}
            }
            
            val swapError = EGLHelper.swap()
            if (swapError != EGL10.EGL_SUCCESS) {
                throw RuntimeException("")
            }

            mChoreographer.postFrameCallback(this)
        }

        override protected fun onLooperPrepared() {
            mLooper = this.getLooper()
            mHandler = Handler.createAsync(mLooper, this)
            mChoreographer = Choreographer.getInstance()
        }

        override fun handleMessage(message: Message) : Boolean {
            message.getCallback().run()
            return true
        }
        
        lateinit var mLooper: Looper
        lateinit var mChoreographer: Choreographer
        lateinit var mHandler: Handler
        private var mGLSurfaceViewWeakRef: WeakReference<GLSurfaceView>
        lateinit var gl: GL10
        val GLThreadManager = Any()

        private object EGLHelper {

            fun init(glSurfaceViewWeakRef: WeakReference<GLSurfaceView>) {
                mGLSurfaceViewWeakRef = glSurfaceViewWeakRef
            }

            fun start() {
                mEgl = (EGLContext.getEGL() as EGL10)
                
                mEglDisplay = mEgl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
                if (mEglDisplay == EGL10.EGL_NO_DISPLAY) {
                    throw RuntimeException("")
                }

                var version = IntArray(2)
                if (!mEgl.eglInitialize(mEglDisplay, version)) {
                    throw RuntimeException()
                }

                mEglConfig = EGLConfigChooser.chooseConfig(mEgl, mEglDisplay)
                
                val attrib_list = intArrayOf(0x3098, 2, EGL10.EGL_NONE) 
                mEglContext = mEgl.eglCreateContext(mEglDisplay, mEglConfig, EGL10.EGL_NO_CONTEXT, attrib_list)
                
                if (mEglContext == null || mEglContext == EGL10.EGL_NO_CONTEXT) {
                    mEglContext = null
                }

                mGL = (mEglContext?.getGL() as GL10)
            }

            fun createSurface() : Boolean {
                
                destroySurface()

                val view = mGLSurfaceViewWeakRef.get()
                if (view != null) {
                    mEglSurface = mEgl.eglCreateWindowSurface(mEglDisplay, mEglConfig, view.getHolder(), null)
                } else {
                    mEglSurface = null
                }

                if (mEglSurface == null || mEglSurface == EGL10.EGL_NO_SURFACE) {
                    return false
                }

                if (!mEgl.eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface, mEglContext)) {
                    return false
                }

                return true
            }

            fun createGL() : GL10 {
                return mGL
            }

            fun swap() : Int {
                if (!mEgl.eglSwapBuffers(mEglDisplay, mEglSurface)) {
                    return mEgl.eglGetError()
                }
                return EGL10.EGL_SUCCESS
            }

            fun destroySurface() {
                if (mEglSurface != null && mEglSurface != EGL10.EGL_NO_SURFACE) {
                    mEgl.eglMakeCurrent(mEglDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
                    
                    mEgl.eglDestroySurface(mEglDisplay, mEglSurface)
                    mEglSurface = null;
                }
            }

            fun finish() {
                if (!mEgl.eglDestroyContext(mEglDisplay, mEglContext)) {
                    throw RuntimeException()
                }
                
                mEgl.eglTerminate(mEglDisplay);
            }

            lateinit var mGLSurfaceViewWeakRef: WeakReference<GLSurfaceView>
            lateinit var mEgl: EGL10
            lateinit var mEglDisplay: EGLDisplay
            var mEglSurface: EGLSurface? = null
            lateinit var mEglConfig: EGLConfig
            var mEglContext: EGLContext? = null
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