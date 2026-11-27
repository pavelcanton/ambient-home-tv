package com.will.ambienthome.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.will.ambienthome.model.AmbientOverlayData

/**
 * Выделенный SurfaceView для статичных обоев (и Ambient-часов в простое) на отдельном
 * аппаратном слое HWC. Обои и минутные тики в Ambient рисуются напрямую
 * в буфер HWC-плоскости без лишних GLES-кадров на бюджетных TV GPU (0% GPU в простое, 0 jank).
 */
class WallpaperSurfaceView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    private companion object {
        const val TAG = "AmbientHome"
    }

    private val blitPaint = Paint()
    private val scalePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dstRect = Rect()
    private val clockRenderer = AmbientClockRenderer()

    private var currentBitmap: Bitmap? = null
    private var surfaceReady: Boolean = false

    private var ambientActive: Boolean = false
    private var ambientTime: String = ""
    private var ambientDate: String = ""
    private var ambientTick: Int = 0
    private var overlay: AmbientOverlayData = AmbientOverlayData()

    init {
        isFocusable = false
        isFocusableInTouchMode = false
        setZOrderOnTop(false)
        holder.setFormat(PixelFormat.RGBX_8888)
        holder.addCallback(this)
    }

    fun setWallpaper(bmp: Bitmap) {
        val prev = currentBitmap
        currentBitmap = bmp
        if (surfaceReady) {
            renderNow()
        }
        if (prev != null && prev !== bmp && !prev.isRecycled) {
            prev.recycle()
        }
    }

    fun release() {
        val prev = currentBitmap
        currentBitmap = null
        if (prev != null && !prev.isRecycled) {
            prev.recycle()
        }
    }

    fun setAmbientClock(
        active: Boolean,
        time: String,
        date: String,
        tickCount: Int,
        overlayData: AmbientOverlayData = overlay
    ) {
        val changed = ambientActive != active ||
            overlay != overlayData ||
            (active && (ambientTime != time || ambientDate != date || ambientTick != tickCount))
        ambientActive = active
        ambientTime = time
        ambientDate = date
        ambientTick = tickCount
        overlay = overlayData
        if (changed && surfaceReady) {
            renderNow()
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        renderNow()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceReady = true
        renderNow()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
    }

    private fun renderNow() {
        val h = holder
        if (!surfaceReady || !h.surface.isValid) return
        val canvas = try {
            h.lockCanvas()
        } catch (t: Throwable) {
            Log.w(TAG, "WallpaperSurfaceView.lockCanvas failed", t)
            null
        } ?: return
        try {
            drawBackground(canvas)
            if (ambientActive && ambientTime.isNotEmpty()) {
                clockRenderer.draw(canvas, ambientTime, ambientDate, ambientTick, overlay)
            }
        } finally {
            try {
                h.unlockCanvasAndPost(canvas)
            } catch (t: Throwable) {
                Log.w(TAG, "WallpaperSurfaceView.unlockCanvasAndPost failed", t)
            }
        }
    }

    private fun drawBackground(canvas: Canvas) {
        if (ambientActive && overlay.blackout) {
            canvas.drawColor(0xFF000000.toInt())
            return
        }
        val bmp = currentBitmap
        if (bmp != null && !bmp.isRecycled) {
            if (bmp.width == canvas.width && bmp.height == canvas.height) {
                canvas.drawBitmap(bmp, 0f, 0f, blitPaint)
            } else {
                dstRect.set(0, 0, canvas.width, canvas.height)
                canvas.drawBitmap(bmp, null, dstRect, scalePaint)
            }
        } else {
            canvas.drawColor(0xFF0A1018.toInt())
        }
    }
}
