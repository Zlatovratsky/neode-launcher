package org.cocos2dx.lib

import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGL11
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface
import javax.microedition.khronos.opengles.GL
import javax.microedition.khronos.opengles.GL10

object EGLConfigChooser {
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

    @JvmStatic
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