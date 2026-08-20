package vn.minh_nguyen.vkey.liquid.ui

/**
 * Hướng nguồn sáng của [LiquidGlassView] — thuần Kotlin (không import `android.*`) để unit test
 * được trên JVM.
 *
 * Giá trị 0..3 PHẢI khớp enum `lgv_highlightOrientation` trong `attrs.xml`; đổi số ở đây là hỏng
 * mọi layout đang khai bằng XML.
 */
object LiquidGlassOrientation {

    /** Nguồn sáng ở cạnh trái. */
    const val LEFT_RIGHT = 0

    /** Nguồn sáng ở cạnh trên. */
    const val TOP_BOTTOM = 1

    /** Nguồn sáng ở góc trên-trái — mặc định, giống ảnh mẫu Liquid Glass của Apple. */
    const val TOP_LEFT_BOTTOM_RIGHT = 2

    /** Nguồn sáng ở góc trên-phải. */
    const val TOP_RIGHT_BOTTOM_LEFT = 3
}
