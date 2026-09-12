package io.github.thibaultbee.streampack.app.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class AudioVisualizerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    
    // Configurable number of segments in the sound bar
    private val segmentCount = 20
    private var currentAmplitudeRatio = 0f
    
    // Smooth trailing animation
    private var displayedRatio = 0f

    fun setAmplitude(ratio: Float) {
        // Ratio should be 0.0 to 1.0
        currentAmplitudeRatio = ratio.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Smoothly approach the target ratio
        displayedRatio += (currentAmplitudeRatio - displayedRatio) * 0.2f
        
        val width = width.toFloat()
        val height = height.toFloat()
        
        val segmentWidth = width / segmentCount
        val gap = segmentWidth * 0.2f
        val actualSegmentWidth = segmentWidth - gap

        val activeSegments = (displayedRatio * segmentCount).toInt()

        for (i in 0 until segmentCount) {
            val left = i * segmentWidth
            val right = left + actualSegmentWidth
            val top = 0f
            val bottom = height
            
            val isActive = i < activeSegments
            
            if (isActive) {
                // Color grading: Green -> Yellow -> Red
                val ratio = i.toFloat() / segmentCount
                when {
                    ratio > 0.8f -> paint.color = Color.parseColor("#FF1744") // Red
                    ratio > 0.5f -> paint.color = Color.parseColor("#FFEA00") // Yellow
                    else -> paint.color = Color.parseColor("#00E676") // Cyan/Green
                }
                
                // Add a glow effect
                paint.setShadowLayer(8f, 0f, 0f, paint.color)
            } else {
                paint.color = Color.parseColor("#222222") // Dim background
                paint.clearShadowLayer()
            }
            
            canvas.drawRoundRect(left, top, right, bottom, 4f, 4f, paint)
        }
        
        // Keep animating if the displayed ratio hasn't settled
        if (Math.abs(currentAmplitudeRatio - displayedRatio) > 0.01f) {
            postInvalidateOnAnimation()
        } else {
            // Decay over time back to 0 if no new input
            currentAmplitudeRatio = Math.max(0f, currentAmplitudeRatio - 0.05f)
            postInvalidateOnAnimation()
        }
    }
}
