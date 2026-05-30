//! サムネイル JPEG 生成。Java 版 `GazoVaultService.createThumbnailJpeg` に倣う。
//! - 最大辺を `max_edge` に収める縮小のみ（拡大はしない）。
//! - 透過は白背景に合成してから JPEG 化（JPEG はアルファ非対応）。

use image::{imageops::FilterType, ImageEncoder, ImageReader};
use std::io::Cursor;

/// 画像バイト列からサムネイル JPEG を生成する。デコード不能なら `None`。
pub fn make_thumbnail_jpeg(src_bytes: &[u8], max_edge: u32) -> Option<Vec<u8>> {
    let reader = ImageReader::new(Cursor::new(src_bytes))
        .with_guessed_format()
        .ok()?;
    let img = reader.decode().ok()?;

    let sw = img.width().max(1);
    let sh = img.height().max(1);
    let s = f64::min(max_edge as f64 / sw as f64, max_edge as f64 / sh as f64);
    let s = s.clamp(0.01, 1.0);
    let tw = ((sw as f64 * s).round() as u32).max(1);
    let th = ((sh as f64 * s).round() as u32).max(1);

    // 双線形相当（Triangle）で縮小。
    let resized = img.resize_exact(tw, th, FilterType::Triangle);

    // 白背景へ合成して RGB 化。
    let rgba = resized.to_rgba8();
    let mut rgb = image::RgbImage::new(tw, th);
    for (x, y, px) in rgba.enumerate_pixels() {
        let [r, g, b, a] = px.0;
        let a = a as u32;
        let blend = |c: u8| -> u8 {
            // c*a/255 + 255*(255-a)/255
            ((c as u32 * a + 255 * (255 - a)) / 255) as u8
        };
        rgb.put_pixel(x, y, image::Rgb([blend(r), blend(g), blend(b)]));
    }

    let mut out = Vec::new();
    {
        let mut cursor = Cursor::new(&mut out);
        let encoder = image::codecs::jpeg::JpegEncoder::new(&mut cursor);
        encoder
            .write_image(rgb.as_raw(), tw, th, image::ExtendedColorType::Rgb8)
            .ok()?;
    }
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn shrinks_large_image() {
        // 1000x500 の単色 PNG を作ってサムネ化。
        let mut img = image::RgbImage::new(1000, 500);
        for p in img.pixels_mut() {
            *p = image::Rgb([10, 20, 30]);
        }
        let mut png = Vec::new();
        image::DynamicImage::ImageRgb8(img)
            .write_to(&mut Cursor::new(&mut png), image::ImageFormat::Png)
            .unwrap();

        let thumb = make_thumbnail_jpeg(&png, 640).expect("thumbnail");
        let decoded = image::load_from_memory(&thumb).unwrap();
        assert_eq!(decoded.width(), 640);
        assert_eq!(decoded.height(), 320);
    }
}
