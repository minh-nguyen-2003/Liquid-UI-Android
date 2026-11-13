package vn.minh_nguyen.vkey.liquid.ui

/**
 * Chế độ nền của [GlassLayout] và các lớp con ([GlassLensLayout], [LiquidGlassLayout]).
 *
 * Giá trị PHẢI khớp enum `gl_mode` trong `attrs.xml`.
 */
object GlassMode {

    /** Mặc định: nền phía sau làm mờ (và bẻ cong ở mép theo `refraction` với các lớp con có thấu kính). */
    const val BLUR = 0

    /**
     * Thấu kính thật, giống hệt [LiquidGlassMode.LENS] của [LiquidLayout]: không làm mờ, nội dung phía sau nét nguyên ở giữa, riêng vành
     * mép vát tròn bị bẻ cong theo đúng hình kính.
     */
    const val LENS = 1
}
