package org.cocos2dx.lib;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.view.Choreographer;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGL11;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;
import javax.microedition.khronos.opengles.GL;
import javax.microedition.khronos.opengles.GL10;

public class GLSurfaceView extends SurfaceView implements SurfaceHolder.Callback2 {
    
    public GLSurfaceView(Context context) {
        super(context);
        SurfaceHolder holder = getHolder();
        holder.addCallback(this);
    }

    public void setRenderer(Renderer renderer) {
        if (mGLThread != null) {
            throw new IllegalStateException("setRenderer has already been called for this instance.");
        }
        mRenderer = renderer;
        mGLThread = new GLThread(mThisWeakRef, mRenderer);
        mGLThread.start();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        mGLThread.surfaceCreated();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        mGLThread.surfaceDestroyed();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        mGLThread.onWindowResize();
    }

    @Override
    public void surfaceRedrawNeededAsync(SurfaceHolder holder, Runnable finishDrawing) {
        mGLThread.queueEvent(finishDrawing);
    }

    @Override
    public void surfaceRedrawNeeded(SurfaceHolder holder) {}


    public void onPause() {
        mGLThread.onPause();
    }

    public void onResume() {
        mGLThread.onResume();
    }

    public void queueEvent(Runnable r) {
        mGLThread.queueEvent(r);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (mDetached && (mRenderer != null)) {
            mGLThread = new GLThread(mThisWeakRef, mRenderer);
            mGLThread.start();
        }
        mDetached = false;
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mGLThread != null) {
            mGLThread.close();
            mGLThread = null;
        }
        
        mDetached = true;
        super.onDetachedFromWindow();
    }

    interface Renderer {
        void onSurfaceCreated();

        void onSurfaceChanged();

        void onDrawFrame();

        void handleOnPause();

        void handleOnResume();
    }

    private Renderer mRenderer;
    private GLThread mGLThread;
    private final WeakReference<GLSurfaceView> mThisWeakRef = new WeakReference<GLSurfaceView>(this);

    private boolean mDetached = false;

    static class GLThread extends HandlerThread implements Choreographer.FrameCallback {
        public GLThread(WeakReference<GLSurfaceView> glSurfaceViewWeakRef, Renderer renderer) {
            super("GLThread", Process.THREAD_PRIORITY_URGENT_DISPLAY*2);
            mRenderer = renderer;
            mGLSurfaceViewWeakRef = glSurfaceViewWeakRef;
            mEGLHelper = new EGLHelper(glSurfaceViewWeakRef);
        }

        @Override
        protected void onLooperPrepared() {
            mHandler = Handler.createAsync(getLooper());
            mChoreographer = Choreographer.getInstance();
            try {
                mEGLHelper.start(); 
            } finally {}
            mExited = false;
        }

        @Override
        public void doFrame(long frameTimeInNanos) {
            if (!mHasSurface || mPaused || mExited) { 
                return;
            }
            
            mEventQueue.processEvents();
            mRenderer.onDrawFrame();

            int swapError = mEGLHelper.swap();
            switch (swapError) {
                case EGL10.EGL_SUCCESS:
                    mChoreographer.postFrameCallback(this);
                    break;
                case EGL11.EGL_CONTEXT_LOST:
                    break;
                default:
                    break;
            }
        }

        public void surfaceCreated() {
            synchronized(GLThreadManager) {
                GLThread callback = this;
                if (!mHasSurface && mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!mHasSurface) {
                            mHasSurface = mEGLHelper.createSurface();
                            if (mHasSurface) {
                                mRenderer.onSurfaceCreated();
                                mRenderer.onSurfaceChanged();
                                mChoreographer.postFrameCallback(callback);
                            }

                            synchronized(GLThreadManager) {
                                GLThreadManager.notifyAll();
                            }
                        }
                    }
                })) {
                    while (!mHasSurface) {
                        try {
                            GLThreadManager.wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }
        }

        public void surfaceDestroyed() {
            synchronized(GLThreadManager) {
                GLThread callback = this;
                if (mHasSurface && mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mHasSurface) {
                            mChoreographer.removeFrameCallback(callback);
                            mEGLHelper.destroySurface();
                            mHasSurface = false;
                        }
                        
                        synchronized(GLThreadManager) {
                            GLThreadManager.notifyAll();
                        }
                    }
                })) {
                    while (mHasSurface) {
                        try {
                            GLThreadManager.wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }
        }

        public void onPause() {
            synchronized(GLThreadManager) {
                GLThread callback = this;    
                if (!mPaused && mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!mPaused) {
                            mRenderer.handleOnPause();
                            mPaused = true;
                        }
                        if (mHasSurface) mChoreographer.removeFrameCallback(callback);
                        
                        synchronized(GLThreadManager) {
                            GLThreadManager.notifyAll();
                        }
                    }
                })) {
                    while (!mPaused) {
                        try {
                            GLThreadManager.wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }
        }

        public void onResume() {
            synchronized(GLThreadManager){
                GLThread callback = this;
                if (mPaused && mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mPaused) {
                            mRenderer.handleOnResume();
                            mPaused = false;
                        }
                        if (mHasSurface) mChoreographer.postFrameCallback(callback);
                            
                        synchronized(GLThreadManager) {
                            GLThreadManager.notifyAll();
                        }
                    }
                })) {
                    while (mPaused) {
                        try {
                            GLThreadManager.wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }          
            }
        }

        public void onWindowResize() {
            synchronized(GLThreadManager) {    
                if (mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mRenderer.onSurfaceChanged();

                        synchronized(GLThreadManager) {
                            mSizeChanged = true;
                            GLThreadManager.notifyAll();
                        }
                    }
                })) {
                    
                    while (!mSizeChanged) {
                        try {
                            GLThreadManager.wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    mSizeChanged = false;
                }
            }
        }

        public void queueEvent(Runnable r) {
            if (r != null) {
                mEventQueue.add(r);
            }
        }

        public void close() {
            synchronized(GLThreadManager) {    
                GLThread callback = this;
                if (!mExited && mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mChoreographer.removeFrameCallback(callback);
                        if (!mPaused) {
                            mRenderer.handleOnPause();
                        }
                        if (mHasSurface) {
                            mEGLHelper.destroySurface();
                        }
                        mEGLHelper.finish();


                        synchronized(GLThreadManager) {
                            mPaused = true;
                            mHasSurface = false;
                            mExited = true;
                            GLThreadManager.notifyAll();
                        }
                    }
                })) {  
                    while (!mExited) {
                        try {
                            GLThreadManager.wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }      
                }

                super.quitSafely();
                try {
                    this.join();
                } catch (InterruptedException e) {}
            }
        }

        private boolean mHasSurface = false;
        private boolean mPaused = true;
        private boolean mSizeChanged = false;
        private boolean mExited = true;
        
        private Choreographer mChoreographer;
        private Handler mHandler;
        private WeakReference<GLSurfaceView> mGLSurfaceViewWeakRef;
        private Object GLThreadManager = new Object();
        private EventQueue mEventQueue = new EventQueue();
        private EGLHelper mEGLHelper;
        private Renderer mRenderer;

        private class EventQueue {
            public void add(Runnable r) {
                synchronized(lock) {
                    front.add(r);
                }
            }

            public void processEvents() {
                synchronized(lock) {
                    temp = front;
                    front = back;
                    back = temp;
                }
                

                while (!back.isEmpty()) {
                    back.poll().run();
                }
            }

            private ArrayDeque<Runnable> front = new ArrayDeque<Runnable>();
            private ArrayDeque<Runnable> back = new ArrayDeque<Runnable>();
            private ArrayDeque<Runnable> temp;
            private Object lock = new Object();
        }

        private static class EGLHelper {
            public EGLHelper(WeakReference<GLSurfaceView> glSurfaceViewWeakRef) {
                mGLSurfaceViewWeakRef = glSurfaceViewWeakRef;
            }

            public void start() {
                    mEGL = (EGL10) EGLContext.getEGL();
                    mEGLDisplay = mEGL.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
                    if (mEGLDisplay == EGL10.EGL_NO_DISPLAY) {
                        throw new IllegalStateException("eglGetDisplay failed");
                    }

                    int[] version = new int[2];
                    if (!mEGL.eglInitialize(mEGLDisplay, version)) {
                        throw new RuntimeException("eglInitialize failed");
                    }

                    mEGLConfig = EGLConfigChooser.chooseConfig(mEGL, mEGLDisplay);

                    int[] attrib_list = {0x3098, 2, EGL10.EGL_NONE};
                    mEGLContext = mEGL.eglCreateContext(mEGLDisplay, mEGLConfig, EGL10.EGL_NO_CONTEXT, attrib_list);

                    if (mEGLContext == null || mEGLContext == EGL10.EGL_NO_CONTEXT) {
                        mEGLContext = null;
                        throw new IllegalStateException("EGLContext has not been created");
                    }
            }

            public boolean createSurface() {
                destroySurface();

                GLSurfaceView view = mGLSurfaceViewWeakRef.get();
                if (view != null && mEGLSurface == null) {
                    mEGLSurface = mEGL.eglCreateWindowSurface(mEGLDisplay, mEGLConfig, view.getHolder(), null);
                }

                if (mEGLSurface == null || mEGLSurface == EGL10.EGL_NO_SURFACE) {
                    return false;
                }

                if (!mEGL.eglMakeCurrent(mEGLDisplay, mEGLSurface, mEGLSurface, mEGLContext)) {
                    mEGLSurface = null;
                    return false;
                }

                return true;
            }

            public int swap() {
                if (!mEGL.eglSwapBuffers(mEGLDisplay, mEGLSurface)) {
                    return mEGL.eglGetError();
                }
                return EGL10.EGL_SUCCESS;
            }

            public void destroySurface() {
                if (mEGLSurface != null) {
                    mEGL.eglMakeCurrent(mEGLDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);

                    mEGL.eglDestroySurface(mEGLDisplay, mEGLSurface);
                    mEGLSurface = null;
                }
            }

            public void finish() {
                if (mEGLContext != null) {
                    if (mEGL.eglDestroyContext(mEGLDisplay, mEGLContext)) {
                        mEGL.eglTerminate(mEGLDisplay);
                        mEGLContext = null;
                    }
                }
            }

            private WeakReference<GLSurfaceView> mGLSurfaceViewWeakRef;
            private EGLDisplay mEGLDisplay;
            private EGLSurface mEGLSurface;
            private EGLConfig mEGLConfig;
            private EGLContext mEGLContext;
            private EGL10 mEGL;
        }
    }
}