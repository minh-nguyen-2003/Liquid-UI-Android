package vn.minh_nguyen.vkey.liquid.ui

import android.content.Context
import android.util.AttributeSet

/**
 * Khung kính làm mờ + bẻ cong ("glass lens", không có viền sáng / ánh trượt kiểu liquid): như [GlassLayout] — làm mờ thật nội dung phía
 * sau, kể cả video — và bẻ cong nền ở mép như thấu kính (khúc xạ theo đúng hình tròn / pill / chữ nhật bo góc), nền phóng nhẹ 12 % và
 * đậm màu hơn 40 %.
 *
 * XML: `app:gll_refraction` (mặc định 0.8; 0 = tắt bẻ cong) cùng các thuộc tính của [GlassLayout] (`app:gl_color`, `app:gl_cornerRadius`,
 * `app:gl_blur`).
 *
 * Lớp con [LiquidGlassLayout] thêm viền sáng + ánh trượt kiểu liquid.
 */
open class GlassLensLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : GlassLayout(context, attrs, defStyleAttr) {

    init {
        backdropZoom = LENS_ZOOM
        backdropSaturation = LENS_SATURATION
        refraction = DEFAULT_REFRACTION
        if (attrs != null) {
            val values = context.obtainStyledAttributes(attrs, R.styleable.GlassLensLayout)
            refraction = values.getFloat(R.styleable.GlassLensLayout_gll_refraction, refraction)
            values.recycle()
        }
    }

    private companion object {
        /** Mép kính với tới vành nền ngoài view bằng 80 % nửa cạnh ngắn. */
        const val DEFAULT_REFRACTION = 0.8f

        /** Nền sau thấu kính: phóng to 12 % quanh tâm, đậm màu hơn 40 %. */
        const val LENS_ZOOM = 1.12f
        const val LENS_SATURATION = 1.4f
    }
}
