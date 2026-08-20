package vn.minh_nguyen.vkey.liquid.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidGlassGeometryTest {

    // --- Bán kính -------------------------------------------------------------------------

    @Test
    fun `pill on square view gives circle radius`() {
        val r = LiquidGlassGeometry.resolveRadius(pill = true, cornerRadius = 4f, 40f, 40f)
        assertEquals(20f, r, 0f)
    }

    @Test
    fun `pill on wide view uses shorter side`() {
        val r = LiquidGlassGeometry.resolveRadius(pill = true, cornerRadius = 4f, 220f, 60f)
        assertEquals(30f, r, 0f)
    }

    @Test
    fun `pill wins over cornerRadius`() {
        val r = LiquidGlassGeometry.resolveRadius(pill = true, cornerRadius = 2f, 100f, 100f)
        assertEquals(50f, r, 0f)
    }

    @Test
    fun `cornerRadius is clamped to half of shorter side`() {
        val r = LiquidGlassGeometry.resolveRadius(pill = false, cornerRadius = 999f, 200f, 60f)
        assertEquals(30f, r, 0f)
    }

    @Test
    fun `negative cornerRadius falls back to zero`() {
        val r = LiquidGlassGeometry.resolveRadius(pill = false, cornerRadius = -8f, 100f, 100f)
        assertEquals(0f, r, 0f)
    }

    @Test
    fun `zero size gives zero radius`() {
        assertEquals(0f, LiquidGlassGeometry.resolveRadius(true, 12f, 0f, 0f), 0f)
        assertEquals(0f, LiquidGlassGeometry.resolveRadius(false, 12f, 40f, 0f), 0f)
    }

    // --- Stroke đồng tâm ------------------------------------------------------------------

    @Test
    fun `stroke inset is half stroke width`() {
        assertEquals(2f, LiquidGlassGeometry.strokeInset(4f), 0f)
    }

    @Test
    fun `inset radius keeps circles concentric`() {
        // view 40x40 bo tròn (r=20), stroke dày 4 → rect thụt 2 mỗi bên (36x36) nên r phải là 18
        val inset = LiquidGlassGeometry.strokeInset(4f)
        assertEquals(18f, LiquidGlassGeometry.insetRadius(20f, inset), 0f)
    }

    @Test
    fun `inset radius never goes negative`() {
        assertEquals(0f, LiquidGlassGeometry.insetRadius(2f, 10f), 0f)
    }

    @Test
    fun `stroke width is clamped to half of shorter side`() {
        assertEquals(20f, LiquidGlassGeometry.clampStrokeWidth(80f, 200f, 40f), 0f)
        assertEquals(0f, LiquidGlassGeometry.clampStrokeWidth(-3f, 200f, 40f), 0f)
        assertEquals(3f, LiquidGlassGeometry.clampStrokeWidth(3f, 200f, 40f), 0f)
    }

    // --- Quầng sáng -----------------------------------------------------------------------

    @Test
    fun `glow rings get wider as they go out`() {
        var previous = 2f // độ dày vệt sáng nét
        for (i in 0 until LiquidGlassGeometry.GLOW_LAYERS) {
            val width = LiquidGlassGeometry.glowStrokeWidth(i, 2f)
            assertTrue("vòng $i phải dày hơn vòng trước", width > previous)
            previous = width
        }
    }

    @Test
    fun `glow rings fade as they go out`() {
        var previous = 1f // vệt sáng nét = 100%
        for (i in 0 until LiquidGlassGeometry.GLOW_LAYERS) {
            val alpha = LiquidGlassGeometry.glowAlphaRatio(i)
            assertTrue("vòng $i phải mờ hơn vòng trước", alpha < previous)
            assertTrue("alpha phải nằm trong 0..1", alpha in 0f..1f)
            previous = alpha
        }
    }

    // --- Màu / alpha ----------------------------------------------------------------------

    @Test
    fun `alpha is clamped to unit range`() {
        assertEquals(0f, LiquidGlassGeometry.clampAlpha(-1f), 0f)
        assertEquals(1f, LiquidGlassGeometry.clampAlpha(9f), 0f)
        assertEquals(0.4f, LiquidGlassGeometry.clampAlpha(0.4f), 0f)
    }

    @Test
    fun `alpha byte covers full range`() {
        assertEquals(0, LiquidGlassGeometry.toAlphaByte(0f))
        assertEquals(255, LiquidGlassGeometry.toAlphaByte(1f))
        assertEquals(255, LiquidGlassGeometry.toAlphaByte(2f))
        assertEquals(51, LiquidGlassGeometry.toAlphaByte(0.2f)) // 0x33 — nền đen 20%
    }

    @Test
    fun `withAlpha keeps rgb and replaces alpha`() {
        val white = 0xFFFFFFFF.toInt()
        assertEquals(0x80FFFFFF.toInt(), LiquidGlassGeometry.withAlpha(white, 0.502f))
        // stop trong suốt vẫn giữ RGB trắng, không rơi về 0 (đen trong suốt gây ám xám)
        assertEquals(0x00FFFFFF, LiquidGlassGeometry.withAlpha(white, 0f))
    }

    // --- Vệt sáng theo góc (SweepGradient) ------------------------------------------------

    @Test
    fun `light source angle maps each orientation to its corner or edge`() {
        assertEquals(
            180f,
            LiquidGlassGeometry.lightSourceAngle(LiquidGlassOrientation.LEFT_RIGHT), 0f
        )
        assertEquals(
            270f,
            LiquidGlassGeometry.lightSourceAngle(LiquidGlassOrientation.TOP_BOTTOM), 0f
        )
        assertEquals(
            225f,
            LiquidGlassGeometry.lightSourceAngle(LiquidGlassOrientation.TOP_LEFT_BOTTOM_RIGHT), 0f
        )
        assertEquals(
            315f,
            LiquidGlassGeometry.lightSourceAngle(LiquidGlassOrientation.TOP_RIGHT_BOTTOM_LEFT), 0f
        )
        assertEquals(180f, LiquidGlassGeometry.lightSourceAngle(99), 0f) // lạ → LEFT_RIGHT
    }

    @Test
    fun `angular distance takes the short way around`() {
        assertEquals(0f, LiquidGlassGeometry.angularDistance(225f, 225f), 0f)
        assertEquals(180f, LiquidGlassGeometry.angularDistance(225f, 45f), 0f)
        assertEquals(90f, LiquidGlassGeometry.angularDistance(350f, 80f), 1e-4f)
        assertEquals(20f, LiquidGlassGeometry.angularDistance(10f, 350f), 1e-4f) // vắt qua 0°
    }

    @Test
    fun `sweep has white lobes on the light axis and dark lobes on the flanks`() {
        val light = 225f
        val atSource = LiquidGlassGeometry.sweepNetAt(225f, light, 0.55f)
        val flank = LiquidGlassGeometry.sweepNetAt(315f, light, 0.55f)     // trên-phải
        val opposite = LiquidGlassGeometry.sweepNetAt(45f, light, 0.55f)   // dưới-phải

        assertEquals(0.55f, atSource, 1e-4f)                               // đỉnh sáng = peakAlpha
        assertTrue("phía vuông góc là vệt TỐI (âm)", flank < 0f)
        assertTrue("bóng rõ chứ không chỉ nhạt đi", flank < -0.55f * 0.3f)
        assertTrue("phản quang đối diện là vệt sáng (~45% đỉnh)", opposite > 0.55f * 0.35f)
        assertTrue("nhưng vẫn mờ hơn vệt chính", opposite < atSource)
        // đối xứng qua trục nguồn sáng: 2 vệt tối sâu bằng nhau
        assertEquals(LiquidGlassGeometry.sweepNetAt(135f, light, 0.55f), flank, 1e-4f)
        // bóng tắt hẳn trên trục sáng
        assertTrue(LiquidGlassGeometry.sweepNetAt(45f, light, 0.55f) > 0f)
    }

    // --- Mốc sweep chiếu theo pháp tuyến --------------------------------------------------

    private val white = 0xFFFFFFFF.toInt()
    private val alphaOf = { color: Int -> (color ushr 24) and 0xFF }
    private val rgbOf = { color: Int -> color and 0x00FFFFFF }

    @Test
    fun `normal mapped stops are valid sweep input and close the loop`() {
        val (positions, colors) =
            LiquidGlassGeometry.normalMappedStops(225f, 0.55f, white, 560f, 96f, 48f)

        assertEquals(positions.size, colors.size)
        assertEquals(0f, positions.first(), 0f)
        assertEquals(1f, positions.last(), 0f)
        for (i in 1 until positions.size) {
            assertTrue("mốc $i phải tăng dần", positions[i] > positions[i - 1])
        }
        // mốc 0 và mốc 1 là cùng một điểm trên viền — màu phải trùng để không có đường nối
        assertEquals(colors.first(), colors.last())
    }

    @Test
    fun `on a circle normal mapping degenerates to position angle`() {
        val (positions, colors) =
            LiquidGlassGeometry.normalMappedStops(225f, 0.55f, white, 96f, 96f, 48f)

        // θ = φ: từng mốc phải mang đúng màu của sweepNetAt tại góc đó
        for (i in positions.indices) {
            val angle = positions[i] * 360f
            val expected = LiquidGlassGeometry.netToColor(
                LiquidGlassGeometry.sweepNetAt(angle, 225f, 0.55f), white
            )
            assertEquals("mốc $i (góc $angle)", expected, colors[i])
        }
    }

    @Test
    fun `on a flat pill the peak sits on the left cap, not mid-top`() {
        val (positions, colors) =
            LiquidGlassGeometry.normalMappedStops(225f, 0.55f, white, 560f, 96f, 48f)

        val brightest = colors.indices.maxBy { alphaOf(colors[it]) }
        assertEquals(0x00FFFFFF, rgbOf(colors[brightest]))
        assertEquals(LiquidGlassGeometry.toAlphaByte(0.55f), alphaOf(colors[brightest]))
        // nắp trái: θ quanh 180° (0.5) — đỉnh sáng phải nằm sát đó, không phải giữa cạnh trên (~0.75)
        assertEquals(0.52f, positions[brightest], 0.03f)
    }

    @Test
    fun `on a flat pill the whole top edge is lit evenly`() {
        val (positions, colors) =
            LiquidGlassGeometry.normalMappedStops(225f, 0.55f, white, 560f, 96f, 48f)

        // Cạnh trên (pháp tuyến 270°) nằm giữa 2 mốc chặn — mọi θ giữa chúng nội suy
        // từ cùng một màu ở 2 đầu ⇒ chỉ cần 2 mốc chặn cùng màu trắng dương.
        val topColor = LiquidGlassGeometry.netToColor(
            LiquidGlassGeometry.sweepNetAt(270f, 225f, 0.55f), white
        )
        assertTrue("cạnh trên phải sáng", alphaOf(topColor) > 0 && rgbOf(topColor) == 0x00FFFFFF)
        // tìm cặp mốc liên tiếp kẹp θ = 270° (0.75) — cả 2 phải mang màu cạnh trên
        val i = positions.indices.last { positions[it] < 0.75f }
        assertEquals(topColor, colors[i])
        assertEquals(topColor, colors[i + 1])
        // và khoảng cách giữa 2 mốc phải rộng (cả cạnh thẳng), không phải mốc dày đặc
        assertTrue(
            "cạnh thẳng phải là một khoảng nội suy dài",
            positions[i + 1] - positions[i] > 0.2f
        )
    }

    @Test
    fun `on a flat pill the dark bevel hugs the caps not the top edge`() {
        val (positions, colors) =
            LiquidGlassGeometry.normalMappedStops(225f, 0.55f, white, 560f, 96f, 48f)

        // vệt đen (pháp tuyến 315°) phải nằm trong vùng nắp phải (θ sát 0/1),
        // không rơi vào giữa cạnh trên (θ ~ 0.7-0.8) như bản mốc-đều-theo-θ
        for (i in positions.indices) {
            if (rgbOf(colors[i]) == 0x000000 && alphaOf(colors[i]) > 60) {
                val theta = positions[i]
                val onLeftCap = theta > 0.42f && theta < 0.58f
                val onRightCap = theta < 0.08f || theta > 0.92f
                assertTrue(
                    "vệt đen đậm tại θ=$theta phải nằm trên nắp trái/phải",
                    onLeftCap || onRightCap
                )
            }
        }
    }
}
