package vn.minh_nguyen.vkey.liquid.ui

/**
 * Chế độ nền của [LiquidLayout] — thuần Kotlin (không import `android.*`) để unit test được trên JVM.
 *
 * Giá trị PHẢI khớp enum `lgv_mode` trong `attrs.xml`; đổi số ở đây là hỏng mọi layout đang khai
 * bằng XML.
 */
object LiquidGlassMode {

    /** Mặc định: chỉ phủ lớp màu + vệt sáng, không đụng tới nội dung phía sau (nhẹ, dùng mọi nơi). */
    const val TINT = 0

    /**
     * Thấu kính thật: mặt kính phẳng, vành mép vát tròn. Nội dung phía sau hiện nét nguyên ở giữa,
     * riêng vành mép bị bẻ cong (khúc xạ) theo đúng hình kính.
     */
    const val LENS = 1

    /**
     * Kính mờ: làm mờ thật mọi thứ phía sau như kính iOS (giao diện thường lẫn video), lấy từ ảnh nền mờ dùng chung nên nhẹ, dùng được
     * cho nhiều view cùng lúc. Trước đây là `GlassLayout` / `LiquidGlassLayout`.
     */
    const val BLUR = 2
}
