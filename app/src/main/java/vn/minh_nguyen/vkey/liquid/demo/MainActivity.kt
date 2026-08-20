package vn.minh_nguyen.vkey.liquid.demo

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import vn.minh_nguyen.vkey.liquid.demo.databinding.ActivityMainBinding
import vn.minh_nguyen.vkey.liquid.ui.LiquidGlassOrientation
import vn.minh_nguyen.vkey.liquid.ui.LiquidGlassView

/**
 * Demo: layout khai toàn bộ bằng XML, Activity chỉ chỉnh vài thuộc tính lúc chạy để thấy
 * API code-side (`highlightAlpha`, `isGlowEnabled`, `highlightOrientation`).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** Mọi LiquidGlassView trong màn — gom 1 lần để 3 control bên dưới chỉnh đồng loạt. */
    private val glassViews by lazy { binding.glassRoot.collectGlassViews() }

    private var orientationIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        showOrientation()
        binding.btnOrientation.setOnClickListener {
            orientationIndex = (orientationIndex + 1) % ORIENTATIONS.size
            glassViews.forEach { it.highlightOrientation = ORIENTATIONS[orientationIndex].second }
            showOrientation()
        }

        binding.seekAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                glassViews.forEach { it.highlightAlpha = progress / 100f }
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })

        binding.switchGlow.setOnCheckedChangeListener { _, checked ->
            glassViews.forEach { it.isGlowEnabled = checked }
        }
    }

    private fun showOrientation() {
        binding.tvOrientation.text =
            getString(R.string.btn_orientation, ORIENTATIONS[orientationIndex].first)
    }

    private fun View.collectGlassViews(): List<LiquidGlassView> {
        val found = mutableListOf<LiquidGlassView>()
        if (this is LiquidGlassView) found += this
        if (this is ViewGroup) {
            for (i in 0 until childCount) found += getChildAt(i).collectGlassViews()
        }
        return found
    }

    companion object {
        private val ORIENTATIONS = listOf(
            "top_left" to LiquidGlassOrientation.TOP_LEFT_BOTTOM_RIGHT,
            "top" to LiquidGlassOrientation.TOP_BOTTOM,
            "top_right" to LiquidGlassOrientation.TOP_RIGHT_BOTTOM_LEFT,
            "left" to LiquidGlassOrientation.LEFT_RIGHT
        )
    }
}
