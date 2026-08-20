package vn.minh_nguyen.vkey.liquid.ui

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Phần tính toán của [LiquidGlassView] — thuần Kotlin (không import `android.*`) để unit test
 * được trên JVM.
 *
 * Quy ước hình học: mọi lớp stroke vẽ ĐỒNG TÂM và canh mép ngoài trùng mép view — stroke dày `w`
 * thì rect thụt vào `w/2` và bán kính giảm đúng `w/2` ([insetRadius]). Nhờ vậy hình tròn / pill
 * vẫn tròn đều, và quầng sáng loang vào TRONG nên không bị `clipToOutline` cắt cụt.
 */
object LiquidGlassGeometry {

    /** Số vòng stroke dùng để giả lập quầng sáng mềm (xem [glowStrokeWidth]). */
    const val GLOW_LAYERS = 3

    /** Mỗi vòng glow dày thêm bấy nhiêu lần độ dày vệt sáng gốc. */
    private const val GLOW_WIDTH_STEP = 1.5f

    /** Vòng glow trong cùng sáng bằng bấy nhiêu phần so với vệt sáng nét. */
    private const val GLOW_PEAK_RATIO = 0.35f

    /** Bước lấy mẫu pháp tuyến trên mỗi cung góc bo (độ) — đủ mịn mà mảng mốc vẫn nhỏ. */
    private const val ARC_STEP_DEG = 15

    /** Vệt sáng đối diện nguồn sáng (phản quang dưới-phải trong ảnh mẫu) = 45% vệt chính. */
    private const val SWEEP_SECONDARY = 0.45f

    /** Số mũ raised-cosine: càng lớn thì 2 vệt sáng càng gọn. */
    private const val SWEEP_LOBE_POWER = 4.0

    /** Vệt TỐI ở 2 phía vuông góc (bóng bevel trong ảnh mẫu) — đỉnh = 60% alpha vệt sáng chính. */
    private const val SWEEP_SHADOW_RATIO = 0.6f

    /** Số mũ của thuỳ tối `sin^k`: càng lớn thì vệt đen càng gọn quanh 2 hông. */
    private const val SWEEP_SHADOW_POWER = 4.0

    /** RGB của vệt tối — bóng là "thiếu sáng" nên luôn đen, không theo `highlightColor`. */
    private const val SHADOW_RGB = 0x000000

    fun clampAlpha(alpha: Float): Float = alpha.coerceIn(0f, 1f)

    /** Alpha 0..1 → kênh alpha 0..255. */
    fun toAlphaByte(alpha: Float): Int = (clampAlpha(alpha) * 255f + 0.5f).toInt()

    /**
     * Ghép alpha vào màu nhưng GIỮ NGUYÊN RGB — stop trong suốt vẫn là "trắng alpha 0",
     * không phải màu 0 (đen trong suốt) vốn làm gradient bị ám xám khi nội suy.
     */
    fun withAlpha(color: Int, alpha: Float): Int =
        (toAlphaByte(alpha) shl 24) or (color and 0x00FFFFFF)

    /**
     * Bán kính thực dùng: `pill` = true thì tự lấy nửa cạnh ngắn (w == h ra hình tròn,
     * w > h ra pill) và bỏ qua [cornerRadius]; ngược lại clamp [cornerRadius] để không vỡ hình.
     */
    fun resolveRadius(pill: Boolean, cornerRadius: Float, width: Float, height: Float): Float {
        val maxRadius = minOf(width, height) / 2f
        if (maxRadius <= 0f) return 0f
        return if (pill) maxRadius else cornerRadius.coerceIn(0f, maxRadius)
    }

    /** Bán kính của rect đã thụt vào [inset] để vẫn đồng tâm với đường bao ngoài. */
    fun insetRadius(radius: Float, inset: Float): Float = (radius - inset).coerceAtLeast(0f)

    /**
     * Stroke vẽ giữa đường path nên rect phải thụt nửa độ dày, không thì nửa ngoài của viền
     * bị cắt mất ở mép view.
     */
    fun strokeInset(strokeWidth: Float): Float = strokeWidth / 2f

    /** Vệt sáng dày quá nửa cạnh ngắn thì rect thụt vào sẽ âm → clamp lại. */
    fun clampStrokeWidth(strokeWidth: Float, width: Float, height: Float): Float =
        strokeWidth.coerceIn(0f, minOf(width, height) / 2f)

    /**
     * Góc (độ, 0° = hướng +x, tăng theo chiều kim đồng hồ như hệ toạ độ Canvas) của NGUỒN SÁNG
     * theo orientation — giá trị 0..3 khớp [LiquidGlassOrientation] và `attrs.xml`.
     *
     * Vệt sáng dùng SweepGradient chứ không phải LinearGradient: trên hình dẹt (search bar)
     * gradient tuyến tính corner-to-corner gần như nằm ngang → sáng cả 2 đầu trái/phải,
     * mất cảm giác "nguồn sáng trên-trái". Quét theo GÓC thì cạnh hướng về nguồn sáng luôn
     * sáng đúng, bất kể tỉ lệ view.
     */
    fun lightSourceAngle(orientation: Int): Float = when (orientation) {
        LiquidGlassOrientation.TOP_BOTTOM -> 270f            // sáng từ cạnh trên
        LiquidGlassOrientation.TOP_LEFT_BOTTOM_RIGHT -> 225f // sáng từ góc trên-trái
        LiquidGlassOrientation.TOP_RIGHT_BOTTOM_LEFT -> 315f // sáng từ góc trên-phải
        else -> 180f                                         // LEFT_RIGHT: sáng từ cạnh trái
    }

    /** Chênh lệch góc ngắn nhất |a-b| trên vòng tròn, kết quả 0..180. */
    fun angularDistance(a: Float, b: Float): Float {
        val d = ((a - b) % 360f + 360f) % 360f
        return if (d > 180f) 360f - d else d
    }

    /**
     * Cường độ CÓ DẤU của viền tại góc [angle] khi nguồn sáng đặt ở [lightAngle] — mô phỏng
     * bevel kính theo ảnh mẫu: dương = vệt SÁNG, âm = vệt TỐI.
     * - Vệt sáng chính đỉnh [peakAlpha] phía nguồn sáng (raised-cosine `((1+cos d)/2)^k`).
     * - Vệt sáng phụ ~45% ở phía đối diện — ánh phản quang dưới-phải.
     * - Vệt TỐI `sin^k` đỉnh 60% ở 2 phía vuông góc (trên-phải / dưới-trái) — bóng bevel,
     *   không chỉ là "bớt sáng".
     * Các thuỳ đều mượt và giao nhau ở cường độ ~0 nên đổi màu trắng↔đen không có điểm gãy.
     */
    fun sweepNetAt(angle: Float, lightAngle: Float, peakAlpha: Float): Float {
        val peak = clampAlpha(peakAlpha)
        val d = angularDistance(angle, lightAngle) * PI / 180.0
        val cos = cos(d)
        val primary = ((1.0 + cos) / 2.0).pow(SWEEP_LOBE_POWER)
        val secondary = ((1.0 - cos) / 2.0).pow(SWEEP_LOBE_POWER)
        // primary + SECONDARY*secondary đạt max = 1 tại d=0 → đỉnh sáng ra đúng peakAlpha
        val light = peak * (primary + SWEEP_SECONDARY * secondary)
        val shadow = peak * SWEEP_SHADOW_RATIO * sin(d).pow(SWEEP_SHADOW_POWER)
        return (light - shadow).toFloat().coerceIn(-1f, 1f)
    }

    /** Màu của một mốc từ cường độ có dấu — trắng ([color]) khi dương, đen khi âm. */
    fun netToColor(net: Float, color: Int): Int =
        if (net >= 0f) withAlpha(color, net) else withAlpha(SHADOW_RGB, -net)

    /**
     * Bộ mốc SweepGradient CHIẾU THEO PHÁP TUYẾN cho rounded-rect [width]×[height] bo [radius]:
     * mốc đặt tại góc-vị-trí θ (quanh tâm) của từng điểm mép, màu tính theo góc PHÁP TUYẾN φ
     * của điểm đó qua [sweepNetAt].
     *
     * Vì sao không dùng mốc đều theo θ: bevel kính sáng theo HƯỚNG MÉP — cả cạnh trên phải sáng
     * đều vì cùng hướng lên, đỉnh sáng nằm trên NẮP TRÁI nơi pháp tuyến trùng nguồn sáng. Trên
     * hình dẹt (search bar) mốc đều theo θ dồn đỉnh sáng vào giữa cạnh trên và cắm vệt đen ngay
     * cạnh đó — sai design. Chiếu theo pháp tuyến thì: 2 mốc chặn một cạnh thẳng mang cùng màu
     * (pháp tuyến không đổi) ⇒ cạnh tô phẳng; các cung bo được lấy mẫu mỗi [ARC_STEP_DEG]°.
     * Hình tròn suy biến về θ = φ — giữ nguyên kết quả đang đúng ở nút icon.
     *
     * Trả về (positions, colors) cho SweepGradient — positions tăng dần, mốc 0 = mốc 1 nên
     * không có đường nối trên viền.
     */
    fun normalMappedStops(
        lightAngle: Float, peakAlpha: Float, color: Int,
        width: Float, height: Float, radius: Float
    ): Pair<FloatArray, IntArray> {
        val a = width / 2.0
        val b = height / 2.0
        val r = radius.toDouble().coerceIn(0.0, minOf(a, b))
        // Tâm 4 cung bo (toạ độ so với tâm view; y hướng xuống như Canvas)
        val ox = a - r
        val oy = b - r

        val positions = ArrayList<Float>()
        val colors = ArrayList<Int>()

        // Mốc 0°: điểm giữa cạnh phải (θ = φ = 0) — thêm tay để phủ kín [0,1]
        positions.add(0f)
        colors.add(netToColor(sweepNetAt(0f, lightAngle, peakAlpha), color))

        // 4 cung theo thứ tự pháp tuyến φ tăng dần ⇒ θ cũng tăng dần (hình lồi):
        // dưới-phải [0,90] → dưới-trái [90,180] → trên-trái [180,270] → trên-phải [270,360]
        val corners = arrayOf(
            doubleArrayOf(ox, oy, 0.0, 90.0),
            doubleArrayOf(-ox, oy, 90.0, 180.0),
            doubleArrayOf(-ox, -oy, 180.0, 270.0),
            doubleArrayOf(ox, -oy, 270.0, 360.0)
        )
        for (corner in corners) {
            val (cxr, cyr) = corner
            var phi = corner[2]
            while (phi <= corner[3] + 1e-6) {
                val rad = phi * PI / 180.0
                val px = cxr + r * cos(rad)
                val py = cyr + r * sin(rad)
                var theta = atan2(py, px) * 180.0 / PI
                if (theta < 0) theta += 360.0
                // atan2 của mốc cuối (φ=360°) trả 0 → hiểu là 360 để giữ thứ tự tăng dần
                if (phi > 180.0 && theta < 1e-6) theta = 360.0
                val position = (theta / 360.0).toFloat().coerceIn(0f, 1f)
                if (position > positions.last() + 1e-5f) {
                    positions.add(position)
                    colors.add(netToColor(sweepNetAt(phi.toFloat(), lightAngle, peakAlpha), color))
                }
                phi += ARC_STEP_DEG
            }
        }

        // Mốc 1: cùng điểm với mốc 0 — màu phải trùng để viền không có đường nối
        if (positions.last() < 1f) {
            positions.add(1f)
            colors.add(colors.first())
        } else {
            colors[colors.size - 1] = colors.first()
        }

        return Pair(positions.toFloatArray(), colors.toIntArray())
    }

    /**
     * Quầng sáng mềm = nhiều vòng stroke đồng tâm, càng ra ngoài càng dày và càng mờ — "blur giả".
     * Không dùng `BlurMaskFilter` vì filter đó không chạy trên canvas hardware-accelerated,
     * muốn có nó phải bật software layer (tốn hiệu năng, nhất là trong list cuộn).
     */
    fun glowStrokeWidth(index: Int, highlightWidth: Float): Float =
        highlightWidth * (1f + GLOW_WIDTH_STEP * (index + 1))

    /** Độ sáng của vòng glow thứ [index] tính theo vệt sáng nét — giảm theo bình phương. */
    fun glowAlphaRatio(index: Int): Float {
        val step = index + 1
        return clampAlpha(GLOW_PEAK_RATIO / (step * step))
    }
}
