package vn.minh_nguyen.vkey.liquid.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * **Đã gom vào [LiquidLayout]** (chế độ `blur` / `lens` + `lgv_pressScale`); giữ lại để các layout cũ chạy tiếp.
 *
 * Nút / khung "liquid glass" đầy đủ (kiểu iOS): kính làm mờ + bẻ cong của [GlassLensLayout] cộng thêm phần "liquid" vẽ trên mặt kính.
 * Khác [LiquidLayout] (liquid không blur, dùng được mọi nơi): view này làm mờ video / ảnh phủ màn nằm sau nó nên hợp với nút nổi trên
 * trình phát video. Lớp kính gồm, từ dưới lên:
 * 0. nội dung phía sau làm mờ ([blurEnabled]) và bẻ cong ở mép ([refraction]) — phần của [GlassLayout] / [GlassLensLayout];
 * 1. nền màu [glassColor] (mặc định #171717 30 %);
 * 2. ánh sáng trượt trên mặt kính: sáng nhẹ ở phía nguồn sáng, tắt dần về giữa, hắt lại rất nhẹ ở mép đối diện;
 * 3. viền kính dày [rimWidth]: sáng nhất ở phía nguồn sáng ([lightAngle], mặc định trên trái), sáng vừa ở mép đối diện (ánh khúc xạ), gần
 *    như tắt ở hai bên (rim light).
 * Bo góc theo [cornerRadius]; âm (mặc định) = bo tròn hai đầu theo nửa cạnh ngắn (hình tròn khi vuông). Con được cắt theo hình kính.
 * Bấm được thì lún nhẹ còn [pressScale] khi giữ, thả ra nảy về (không dùng ripple).
 *
 * XML: `app:lgl_color`, `app:lgl_cornerRadius`, `app:lgl_rimWidth`, `app:lgl_rimColor`, `app:lgl_rimAlpha`, `app:lgl_lightAngle`,
 * `app:lgl_sheenAlpha`, `app:lgl_pressScale`, `app:lgl_blur`, `app:lgl_refraction`.
 */
@Suppress("DEPRECATION")
@Deprecated(
    "Đã gom vào LiquidLayout: dùng LiquidLayout với app:lgv_mode=\"blur\" (hoặc \"lens\") và app:lgv_pressScale=\"0.92\"; " +
        "viền sáng là phần vệt sáng sẵn có của LiquidLayout."
)
class LiquidGlassLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : GlassLensLayout(context, attrs, defStyleAttr) {

    /** Độ dày viền kính (px). */
    var rimWidth: Float = resources.displayMetrics.density * DEFAULT_RIM_DP
        set(value) {
            field = value
            rimPaint.strokeWidth = value
            invalidate()
        }

    /** Màu viền sáng và độ sáng lớn nhất của viền (0..1) ở phía nguồn sáng. */
    @ColorInt
    var rimColor: Int = Color.WHITE
        set(value) {
            field = value
            invalidateShaders()
        }
    var rimAlpha: Float = DEFAULT_RIM_ALPHA
        set(value) {
            field = value
            invalidateShaders()
        }

    /** Hướng nguồn sáng tính từ tâm, độ, 0 = bên phải, tăng theo chiều kim đồng hồ (225 = trên trái). */
    var lightAngle: Float = DEFAULT_LIGHT_ANGLE
        set(value) {
            field = value
            invalidateShaders()
        }

    /** Độ sáng lớn nhất của ánh trượt trên mặt kính (0..1); 0 = tắt. */
    var sheenAlpha: Float = DEFAULT_SHEEN_ALPHA
        set(value) {
            field = value
            invalidateShaders()
        }

    /** Tỉ lệ thu nhỏ khi đang giữ (1 = không lún). */
    var pressScale: Float = DEFAULT_PRESS_SCALE

    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private var shadersDirty = true

    init {
        if (attrs != null) {
            val values = context.obtainStyledAttributes(attrs, R.styleable.LiquidGlassLayout)
            // Thuộc tính lgl_* của phần kính ghi đè giá trị lớp cha đã đọc (gl_* / gll_*) khi có khai
            glassColor = values.getColor(R.styleable.LiquidGlassLayout_lgl_color, glassColor)
            cornerRadius = values.getDimension(R.styleable.LiquidGlassLayout_lgl_cornerRadius, cornerRadius)
            blurEnabled = values.getBoolean(R.styleable.LiquidGlassLayout_lgl_blur, blurEnabled)
            refraction = values.getFloat(R.styleable.LiquidGlassLayout_lgl_refraction, refraction)
            rimWidth = values.getDimension(R.styleable.LiquidGlassLayout_lgl_rimWidth, rimWidth)
            rimColor = values.getColor(R.styleable.LiquidGlassLayout_lgl_rimColor, rimColor)
            rimAlpha = values.getFloat(R.styleable.LiquidGlassLayout_lgl_rimAlpha, rimAlpha)
            lightAngle = values.getFloat(R.styleable.LiquidGlassLayout_lgl_lightAngle, lightAngle)
            sheenAlpha = values.getFloat(R.styleable.LiquidGlassLayout_lgl_sheenAlpha, sheenAlpha)
            pressScale = values.getFloat(R.styleable.LiquidGlassLayout_lgl_pressScale, pressScale)
            values.recycle()
        }
        rimPaint.strokeWidth = rimWidth
    }

    private fun invalidateShaders() {
        shadersDirty = true
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shadersDirty = true
    }

    /** Dựng lại gradient ánh trượt và viền theo kích thước, hướng sáng hiện tại. */
    private fun buildShaders(width: Float, height: Float) {
        shadersDirty = false
        val centerX = width / 2f
        val centerY = height / 2f
        val radians = Math.toRadians(lightAngle.toDouble())
        val dirX = cos(radians).toFloat()
        val dirY = sin(radians).toFloat()

        // Ánh trượt: từ mép phía nguồn sáng tới mép đối diện
        sheenPaint.shader = if (sheenAlpha <= 0f) null else LinearGradient(
            centerX + dirX * width / 2f, centerY + dirY * height / 2f,
            centerX - dirX * width / 2f, centerY - dirY * height / 2f,
            intArrayOf(
                withAlpha(rimColor, sheenAlpha),
                withAlpha(rimColor, 0f),
                withAlpha(rimColor, 0f),
                withAlpha(rimColor, sheenAlpha * SHEEN_BACK_RATIO)
            ),
            floatArrayOf(0f, 0.5f, 0.78f, 1f),
            Shader.TileMode.CLAMP
        )

        // Viền: sáng nhất hướng nguồn sáng, sáng vừa hướng đối diện, mờ ở hai bên. SweepGradient chạy từ 0° (bên phải) theo chiều kim đồng hồ
        val colors = IntArray(RIM_STOPS + 1)
        val positions = FloatArray(RIM_STOPS + 1)
        for (i in 0..RIM_STOPS) {
            val angle = 360f * i / RIM_STOPS
            val delta = Math.toRadians((angle - lightAngle).toDouble())
            val facing = cos(delta).toFloat()
            val lit = if (facing > 0f) facing.pow(RIM_FOCUS) else 0f
            val back = if (facing < 0f) (-facing).pow(RIM_FOCUS) * RIM_BACK_RATIO else 0f
            val strength = RIM_SIDE_RATIO + (1f - RIM_SIDE_RATIO) * (lit + back)
            colors[i] = withAlpha(rimColor, rimAlpha * strength.coerceIn(0f, 1f))
            positions[i] = i.toFloat() / RIM_STOPS
        }
        rimPaint.shader = SweepGradient(centerX, centerY, colors, positions)
    }

    private fun withAlpha(@ColorInt color: Int, alpha: Float) =
        ColorUtils.setAlphaComponent(color, (alpha.coerceIn(0f, 1f) * 255).toInt())

    /** Phần "liquid" trên mặt kính: ánh trượt rồi viền sáng (viền nằm trong mép kính). */
    override fun onDrawGlassOverlay(canvas: Canvas, width: Float, height: Float, radius: Float) {
        if (shadersDirty) buildShaders(width, height)
        rect.set(0f, 0f, width, height)
        if (sheenPaint.shader != null) canvas.drawRoundRect(rect, radius, radius, sheenPaint)
        if (rimWidth > 0f && rimAlpha > 0f) {
            val inset = rimWidth / 2f
            rect.set(inset, inset, width - inset, height - inset)
            val rimRadius = (radius - inset).coerceAtLeast(0f)
            canvas.drawRoundRect(rect, rimRadius, rimRadius, rimPaint)
        }
    }

    /** Giữ: lún nhẹ; thả: nảy về đúng cỡ. Chỉ khi view bấm được. */
    override fun setPressed(pressed: Boolean) {
        val changed = pressed != isPressed
        super.setPressed(pressed)
        if (!changed || !isClickable || pressScale >= 1f) return
        animate().cancel()
        if (pressed) {
            animate().scaleX(pressScale).scaleY(pressScale).setDuration(PRESS_MS).setInterpolator(DecelerateInterpolator()).start()
        } else {
            animate().scaleX(1f).scaleY(1f).setDuration(RELEASE_MS).setInterpolator(OvershootInterpolator(2.5f)).start()
        }
    }

    private companion object {
        const val DEFAULT_RIM_DP = 1f
        const val DEFAULT_RIM_ALPHA = 0.55f
        const val DEFAULT_LIGHT_ANGLE = 225f
        const val DEFAULT_SHEEN_ALPHA = 0.16f
        const val DEFAULT_PRESS_SCALE = 0.92f

        /** Viền: số mốc màu quanh chu vi, độ tụ của vùng sáng, độ sáng mép đối diện và hai bên so với phía nguồn sáng. */
        const val RIM_STOPS = 24
        const val RIM_FOCUS = 2.2f
        const val RIM_BACK_RATIO = 0.55f
        const val RIM_SIDE_RATIO = 0.14f

        /** Ánh hắt lại ở mép đối diện so với ánh trượt chính. */
        const val SHEEN_BACK_RATIO = 0.3f

        const val PRESS_MS = 110L
        const val RELEASE_MS = 320L
    }
}
