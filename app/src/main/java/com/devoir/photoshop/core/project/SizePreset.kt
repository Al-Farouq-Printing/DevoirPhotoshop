package com.devoir.photoshop.core.project

import com.devoir.photoshop.model.SizeUnit

enum class SizePreset(val label: String, val width: Double, val height: Double, val unit: SizeUnit) {
    Custom("Custom", 0.0, 0.0, SizeUnit.Pixels),
    Phone("1080 × 1920", 1080.0, 1920.0, SizeUnit.Pixels),
    Hd("1920 × 1080", 1920.0, 1080.0, SizeUnit.Pixels),
    Square("1080 × 1080", 1080.0, 1080.0, SizeUnit.Pixels),
    A4("A4", 210.0, 297.0, SizeUnit.Millimeters),
    A5("A5", 148.0, 210.0, SizeUnit.Millimeters),
    InstagramPost("Instagram post", 1080.0, 1350.0, SizeUnit.Pixels),
    YouTubeThumb("YouTube thumbnail", 1280.0, 720.0, SizeUnit.Pixels),
    FacebookCover("Facebook cover", 820.0, 312.0, SizeUnit.Pixels),
    XHeader("X header", 1500.0, 500.0, SizeUnit.Pixels),
    PinterestPin("Pinterest pin", 1000.0, 1500.0, SizeUnit.Pixels),
}
