use crate::models::{CropRect, PageEdits};
use chrono::Local;
use image::codecs::png::{CompressionType, FilterType as PngFilterType, PngEncoder};
use image::{
    imageops::FilterType, DynamicImage, GenericImageView, ImageBuffer, ImageEncoder, Luma, Rgb,
    RgbImage,
};
use imageproc::region_labelling::{connected_components, Connectivity};
use std::collections::HashMap;
use std::fs::File;
use std::io::BufWriter;
use std::path::{Path, PathBuf};

/// Estimates the scanner bed background color from the outer 3% border pixels.
fn estimate_background_rgb(img: &RgbImage) -> [f32; 3] {
    let (w, h) = img.dimensions();
    let bx = ((w as f32) * 0.03).max(2.0) as u32;
    let by = ((h as f32) * 0.03).max(2.0) as u32;
    let mut r_sum = 0f64;
    let mut g_sum = 0f64;
    let mut b_sum = 0f64;
    let mut count = 0u64;

    let step_x = (w / 120).max(1);
    let step_y = (h / 120).max(1);

    for y in (0..h).step_by(step_y as usize) {
        for x in (0..w).step_by(step_x as usize) {
            if x < bx || x >= w - bx || y < by || y >= h - by {
                let p = img.get_pixel(x, y);
                r_sum += p[0] as f64;
                g_sum += p[1] as f64;
                b_sum += p[2] as f64;
                count += 1;
            }
        }
    }

    if count == 0 {
        return [255.0, 255.0, 255.0];
    }

    [
        (r_sum / count as f64) as f32,
        (g_sum / count as f64) as f32,
        (b_sum / count as f64) as f32,
    ]
}

fn color_distance(p: &Rgb<u8>, bg: [f32; 3]) -> f32 {
    let dr = p[0] as f32 - bg[0];
    let dg = p[1] as f32 - bg[1];
    let db = p[2] as f32 - bg[2];
    (dr * dr + dg * dg + db * db).sqrt()
}

/// Detects foreground content bounds on a scanner bed and returns normalized (0..1) crop coordinates.
/// Returns None if content spans nearly the entire bed or no clear foreground region is found.
pub fn detect_edges_rect(img: &DynamicImage) -> Option<CropRect> {
    let (w, h) = img.dimensions();
    if w < 32 || h < 32 {
        return None;
    }

    // Downsample to <= 400px longest side for fast, noise-resistant edge detection
    let max_dim = w.max(h) as f32;
    let scale = (400.0 / max_dim).min(1.0);
    let sw = ((w as f32) * scale).round().max(16.0) as u32;
    let sh = ((h as f32) * scale).round().max(16.0) as u32;
    let small = img.resize_exact(sw, sh, FilterType::Triangle).to_rgb8();

    let bg = estimate_background_rgb(&small);
    let threshold = 24.0f32;

    let mut row_counts = vec![0u32; sh as usize];
    let mut col_counts = vec![0u32; sw as usize];
    let mut total_fg = 0u64;

    for y in 0..sh {
        for x in 0..sw {
            if color_distance(small.get_pixel(x, y), bg) > threshold {
                row_counts[y as usize] += 1;
                col_counts[x as usize] += 1;
                total_fg += 1;
            }
        }
    }

    let total_px = (sw as u64) * (sh as u64);
    // If very little foreground (< 1%) or almost full bed (> 92%), avoid accidental cropping
    if total_fg < total_px / 100 || total_fg > (total_px * 92) / 100 {
        return None;
    }

    let min_row_hits = (sw as f32 * 0.015).max(2.0) as u32;
    let min_col_hits = (sh as f32 * 0.015).max(2.0) as u32;

    let mut top = 0u32;
    while top < sh && row_counts[top as usize] < min_row_hits {
        top += 1;
    }
    let mut bottom = sh.saturating_sub(1);
    while bottom > top && row_counts[bottom as usize] < min_row_hits {
        bottom -= 1;
    }

    let mut left = 0u32;
    while left < sw && col_counts[left as usize] < min_col_hits {
        left += 1;
    }
    let mut right = sw.saturating_sub(1);
    while right > left && col_counts[right as usize] < min_col_hits {
        right -= 1;
    }

    if right <= left + 4 || bottom <= top + 4 {
        return None;
    }

    // Add 1.5% margin so edges of documents/photos aren't clipped
    let pad_x = ((sw as f32) * 0.015).round() as u32;
    let pad_y = ((sh as f32) * 0.015).round() as u32;

    let nx0 = (left.saturating_sub(pad_x) as f64 / sw as f64).clamp(0.0, 0.98);
    let ny0 = (top.saturating_sub(pad_y) as f64 / sh as f64).clamp(0.0, 0.98);
    let nx1 = ((right + pad_x + 1).min(sw) as f64 / sw as f64).clamp(nx0 + 0.02, 1.0);
    let ny1 = ((bottom + pad_y + 1).min(sh) as f64 / sh as f64).clamp(ny0 + 0.02, 1.0);

    let nw = (nx1 - nx0).clamp(0.02, 1.0);
    let nh = (ny1 - ny0).clamp(0.02, 1.0);

    // Only report a suggested crop if we trimmed at least 4% area overall
    if nw * nh > 0.96 {
        return None;
    }

    Some(CropRect {
        x: nx0,
        y: ny0,
        w: nw,
        h: nh,
    })
}

/// Detects foreground content bounds on a scanner bed and crops cleanly with a small margin.
/// Never over-crops if content spans the majority of the bed or has low border contrast.
pub fn detect_edges_and_crop(img: &DynamicImage) -> DynamicImage {
    let Some(r) = detect_edges_rect(img) else {
        return img.clone();
    };
    let (w, h) = img.dimensions();
    let x0 = (r.x * w as f64).floor() as u32;
    let y0 = (r.y * h as f64).floor() as u32;
    let cw = (r.w * w as f64).ceil().max(1.0) as u32;
    let ch = (r.h * h as f64).ceil().max(1.0) as u32;
    let cw = cw.min(w.saturating_sub(x0)).max(1);
    let ch = ch.min(h.saturating_sub(y0)).max(1);
    img.crop_imm(x0, y0, cw, ch)
}

/// Detects multiple separated items (e.g., receipts, photos, cards) on a scanner bed
/// and returns each cropped item individually. Falls back to `[detect_edges_and_crop(img)]`
/// if fewer than 2 distinct regions are detected.
pub fn split_multi_items(img: &DynamicImage) -> Vec<DynamicImage> {
    let (w, h) = img.dimensions();
    if w < 64 || h < 64 {
        return vec![img.clone()];
    }

    let max_dim = w.max(h) as f32;
    let scale = (360.0 / max_dim).min(1.0);
    let sw = ((w as f32) * scale).round().max(32.0) as u32;
    let sh = ((h as f32) * scale).round().max(32.0) as u32;
    let small = img.resize_exact(sw, sh, FilterType::Triangle).to_rgb8();
    let bg = estimate_background_rgb(&small);
    let threshold = 24.0f32;

    let mut raw_mask = vec![0u8; (sw * sh) as usize];
    for y in 0..sh {
        for x in 0..sw {
            if color_distance(small.get_pixel(x, y), bg) > threshold {
                raw_mask[(y * sw + x) as usize] = 255;
            }
        }
    }

    // Simple 3x3 dilation (radius=2) to merge nearby text/features within the same receipt or photo
    let mut dilated = ImageBuffer::<Luma<u8>, Vec<u8>>::new(sw, sh);
    let r = 3i32;
    for y in 0..sh as i32 {
        for x in 0..sw as i32 {
            let mut hit = false;
            'outer: for dy in -r..=r {
                let ny = y + dy;
                if ny < 0 || ny >= sh as i32 {
                    continue;
                }
                for dx in -r..=r {
                    let nx = x + dx;
                    if nx < 0 || nx >= sw as i32 {
                        continue;
                    }
                    if raw_mask[(ny as u32 * sw + nx as u32) as usize] > 0 {
                        hit = true;
                        break 'outer;
                    }
                }
            }
            dilated.put_pixel(x as u32, y as u32, Luma([if hit { 255 } else { 0 }]));
        }
    }

    let labels = connected_components(&dilated, Connectivity::Eight, Luma([0u8]));

    // Collect bounding boxes [min_x, min_y, max_x, max_y, count] per component label
    let mut boxes: HashMap<u32, (u32, u32, u32, u32, u64)> = HashMap::new();
    for y in 0..sh {
        for x in 0..sw {
            let lbl = labels.get_pixel(x, y)[0];
            if lbl == 0 {
                continue;
            }
            let entry = boxes.entry(lbl).or_insert((x, y, x, y, 0));
            entry.0 = entry.0.min(x);
            entry.1 = entry.1.min(y);
            entry.2 = entry.2.max(x);
            entry.3 = entry.3.max(y);
            entry.4 += 1;
        }
    }

    let min_area = ((sw as u64) * (sh as u64)) / 40; // ~2.5% of scan bed
    let mut regions: Vec<(u32, u32, u32, u32)> = boxes
        .into_values()
        .filter(|(x0, y0, x1, y1, count)| {
            let bw = x1 - x0 + 1;
            let bh = y1 - y0 + 1;
            *count >= min_area && bw >= 12 && bh >= 12
        })
        .map(|(x0, y0, x1, y1, _)| (x0, y0, x1, y1))
        .collect();

    if regions.len() < 2 {
        return vec![detect_edges_and_crop(img)];
    }

    // Sort top-to-bottom, left-to-right
    regions.sort_by_key(|&(x0, y0, _, _)| (y0 / 24, x0));

    let pad_x = ((sw as f32) * 0.01).round() as u32;
    let pad_y = ((sh as f32) * 0.01).round() as u32;

    regions
        .into_iter()
        .map(|(rx0, ry0, rx1, ry1)| {
            let x0 = ((rx0.saturating_sub(pad_x) as f32) / sw as f32 * w as f32).floor() as u32;
            let y0 = ((ry0.saturating_sub(pad_y) as f32) / sh as f32 * h as f32).floor() as u32;
            let x1 = (((rx1 + pad_x + 1).min(sw) as f32) / sw as f32 * w as f32).ceil() as u32;
            let y1 = (((ry1 + pad_y + 1).min(sh) as f32) / sh as f32 * h as f32).ceil() as u32;
            let cw = x1.saturating_sub(x0).max(1).min(w - x0);
            let ch = y1.saturating_sub(y0).max(1).min(h - y0);
            img.crop_imm(x0, y0, cw, ch)
        })
        .collect()
}

/// Splits a horizontal two-page book spread down the center gutter into left and right portrait pages.
pub fn split_book_spread(img: &DynamicImage) -> Vec<DynamicImage> {
    let (w, h) = img.dimensions();
    if w < 32 || h < 32 {
        return vec![img.clone()];
    }
    let half = w / 2;
    let gutter_trim = (w / 100).min(12);
    let left_w = half.saturating_sub(gutter_trim).max(1);
    let right_x = (half + gutter_trim).min(w.saturating_sub(1));
    let right_w = w.saturating_sub(right_x).max(1);

    let left = detect_edges_and_crop(&img.crop_imm(0, 0, left_w, h));
    let right = detect_edges_and_crop(&img.crop_imm(right_x, 0, right_w, h));
    vec![left, right]
}

/// Stitches front and back ID card scans neatly centered on an A4-proportioned white canvas.
pub fn stitch_id_card(front: &DynamicImage, back: &DynamicImage) -> DynamicImage {
    let f = detect_edges_and_crop(front);
    let b = detect_edges_and_crop(back);

    // Canvas width ~ 1654px (200 DPI A4 width) or scaled relative to card width
    let canvas_w = 1654u32;
    let canvas_h = 2338u32;
    let mut canvas = RgbImage::from_pixel(canvas_w, canvas_h, Rgb([255, 255, 255]));

    let target_card_w = (canvas_w as f32 * 0.62).round() as u32;
    let max_card_h = (canvas_h as f32 * 0.34).round() as u32;

    let place = |canvas: &mut RgbImage, card: &DynamicImage, center_y: u32| {
        let (cw, ch) = card.dimensions();
        let scale = (target_card_w as f32 / cw.max(1) as f32)
            .min(max_card_h as f32 / ch.max(1) as f32);
        let nw = ((cw as f32) * scale).round().max(1.0) as u32;
        let nh = ((ch as f32) * scale).round().max(1.0) as u32;
        let resized = card.resize_exact(nw, nh, FilterType::Lanczos3).to_rgb8();
        let ox = (canvas_w.saturating_sub(nw)) / 2;
        let oy = center_y.saturating_sub(nh / 2);

        for y in 0..nh {
            if oy + y >= canvas_h {
                break;
            }
            for x in 0..nw {
                if ox + x >= canvas_w {
                    break;
                }
                canvas.put_pixel(ox + x, oy + y, *resized.get_pixel(x, y));
            }
        }
    };

    place(&mut canvas, &f, (canvas_h as f32 * 0.31) as u32);
    place(&mut canvas, &b, (canvas_h as f32 * 0.69) as u32);

    DynamicImage::ImageRgb8(canvas)
}

/// Applies non-destructive edits (rotation -> crop -> brightness, contrast, saturation, and filter).
/// Rotating before cropping ensures the Crop tab operates in the rotated orientation the user sees.
pub fn apply_edits(img: &DynamicImage, edits: &PageEdits) -> DynamicImage {
    let mut out = img.clone();

    let deg = ((edits.rotate_deg % 360) + 360) % 360;
    out = match deg {
        90 => out.rotate90(),
        180 => out.rotate180(),
        270 => out.rotate270(),
        _ => out,
    };

    if let Some(c) = &edits.crop {
        let (w, h) = out.dimensions();
        let x = (c.x.clamp(0.0, 0.99) * w as f64).round() as u32;
        let y = (c.y.clamp(0.0, 0.99) * h as f64).round() as u32;
        let cw = (c.w.clamp(0.01, 1.0) * w as f64).round() as u32;
        let ch = (c.h.clamp(0.01, 1.0) * h as f64).round() as u32;
        let cw = cw.max(1).min(w.saturating_sub(x));
        let ch = ch.max(1).min(h.saturating_sub(y));
        out = out.crop_imm(x, y, cw, ch);
    }

    let mut rgb = out.to_rgb8();

    // Filter pass first or integrated with tone adjustments
    match edits.filter.as_str() {
        "auto_enhance" => {
            // Stretch white point slightly and boost micro-contrast to whiten gray paper backgrounds
            for p in rgb.pixels_mut() {
                for ch in 0..3 {
                    let v = p[ch] as f32 / 255.0;
                    // Gentle S-curve + white-point pull (0.86 -> 1.0)
                    let stretched = ((v - 0.04) / 0.84).clamp(0.0, 1.0);
                    let curved = stretched * stretched * (3.0 - 2.0 * stretched);
                    let blended = stretched * 0.65 + curved * 0.35;
                    p[ch] = (blended * 255.0).round().clamp(0.0, 255.0) as u8;
                }
            }
        }
        "grayscale" => {
            for p in rgb.pixels_mut() {
                let lum = (0.299 * p[0] as f32 + 0.587 * p[1] as f32 + 0.114 * p[2] as f32)
                    .round()
                    .clamp(0.0, 255.0) as u8;
                *p = Rgb([lum, lum, lum]);
            }
        }
        "bw_document" => {
            // Clean local/global adaptive threshold using Otsu-like histogram threshold
            let mut hist = [0u64; 256];
            let (w, h) = rgb.dimensions();
            let total = (w as u64) * (h as u64);
            for p in rgb.pixels() {
                let lum = (0.299 * p[0] as f32 + 0.587 * p[1] as f32 + 0.114 * p[2] as f32)
                    .round()
                    .clamp(0.0, 255.0) as usize;
                hist[lum] += 1;
            }
            let thresh = otsu_threshold(&hist, total).min(210).max(95) as f32;
            for p in rgb.pixels_mut() {
                let lum = 0.299 * p[0] as f32 + 0.587 * p[1] as f32 + 0.114 * p[2] as f32;
                let v = if lum >= thresh { 255 } else { 0 };
                *p = Rgb([v, v, v]);
            }
        }
        _ => {}
    }

    if edits.brightness != 0 || edits.contrast != 0 || edits.saturation != 0 {
        let b_shift = edits.brightness as f32 * 1.25;
        let c_val = (edits.contrast as f32 * 2.55).clamp(-254.0, 254.0);
        let c_factor = (259.0 * (c_val + 255.0)) / (255.0 * (259.0 - c_val));
        let sat_factor = 1.0 + (edits.saturation as f32 / 100.0);

        for p in rgb.pixels_mut() {
            let mut r = c_factor * (p[0] as f32 + b_shift - 128.0) + 128.0;
            let mut g = c_factor * (p[1] as f32 + b_shift - 128.0) + 128.0;
            let mut b = c_factor * (p[2] as f32 + b_shift - 128.0) + 128.0;

            if edits.saturation != 0 {
                let lum = 0.299 * r + 0.587 * g + 0.114 * b;
                r = lum + (r - lum) * sat_factor;
                g = lum + (g - lum) * sat_factor;
                b = lum + (b - lum) * sat_factor;
            }

            p[0] = r.round().clamp(0.0, 255.0) as u8;
            p[1] = g.round().clamp(0.0, 255.0) as u8;
            p[2] = b.round().clamp(0.0, 255.0) as u8;
        }
    }

    DynamicImage::ImageRgb8(rgb)
}

fn otsu_threshold(hist: &[u64; 256], total: u64) -> usize {
    if total == 0 {
        return 128;
    }
    let mut sum = 0f64;
    for (i, &c) in hist.iter().enumerate() {
        sum += (i as f64) * (c as f64);
    }
    let mut sum_b = 0f64;
    let mut w_b = 0u64;
    let mut max_var = 0f64;
    let mut thresh = 128usize;

    for (t, &c) in hist.iter().enumerate() {
        w_b += c;
        if w_b == 0 {
            continue;
        }
        let w_f = total - w_b;
        if w_f == 0 {
            break;
        }
        sum_b += (t as f64) * (c as f64);
        let m_b = sum_b / (w_b as f64);
        let m_f = (sum - sum_b) / (w_f as f64);
        let diff = m_b - m_f;
        let between = (w_b as f64) * (w_f as f64) * diff * diff;
        if between > max_var {
            max_var = between;
            thresh = t;
        }
    }
    thresh
}

pub fn save_png(img: &DynamicImage, path: &Path) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)
            .map_err(|e| format!("Failed to create directory {}: {e}", parent.display()))?;
    }
    let file = File::create(path)
        .map_err(|e| format!("Failed to create PNG {}: {e}", path.display()))?;
    let writer = BufWriter::new(file);
    let encoder = PngEncoder::new_with_quality(
        writer,
        CompressionType::Fast,
        PngFilterType::NoFilter,
    );
    let rgb = img.to_rgb8();
    encoder
        .write_image(rgb.as_raw(), rgb.width(), rgb.height(), image::ColorType::Rgb8.into())
        .map_err(|e| format!("Failed to save PNG {}: {e}", path.display()))
}

pub fn export_images(
    page_paths: &[String],
    output_dir: &str,
    format: &str,      // "jpg" | "png"
    compression: &str, // "low" | "medium" | "high"
    base_name: &str,
) -> Result<Vec<String>, String> {
    let dir = PathBuf::from(output_dir);
    std::fs::create_dir_all(&dir)
        .map_err(|e| format!("Failed to create output folder: {e}"))?;

    let quality = match compression {
        "high" => 60u8,
        "medium" => 78u8,
        _ => 92u8, // "low" compression = high image quality
    };

    let mut written = Vec::new();
    let multi = page_paths.len() > 1;
    let clean_base = sanitize_filename(base_name);

    for (idx, p) in page_paths.iter().enumerate() {
        let img = image::open(p)
            .map_err(|e| format!("Failed to open image {p}: {e}"))?;
        let suffix = if multi {
            format!("_{:02}", idx + 1)
        } else {
            String::new()
        };
        let ext = if format.eq_ignore_ascii_case("png") {
            "png"
        } else {
            "jpg"
        };
        let out_path = unique_path(&dir, &format!("{clean_base}{suffix}"), ext);

        if ext == "png" {
            img.save_with_format(&out_path, image::ImageFormat::Png)
                .map_err(|e| format!("Failed to save PNG: {e}"))?;
        } else {
            let rgb = img.to_rgb8();
            let file = File::create(&out_path)
                .map_err(|e| format!("Failed to create JPEG: {e}"))?;
            let writer = BufWriter::new(file);
            let encoder = image::codecs::jpeg::JpegEncoder::new_with_quality(writer, quality);
            encoder
                .write_image(rgb.as_raw(), rgb.width(), rgb.height(), image::ColorType::Rgb8.into())
                .map_err(|e| format!("Failed to encode JPEG: {e}"))?;
        }

        written.push(out_path.to_string_lossy().to_string());
    }

    Ok(written)
}

pub fn sanitize_filename(raw: &str) -> String {
    let cleaned: String = raw
        .trim()
        .chars()
        .map(|c| match c {
            '<' | '>' | ':' | '"' | '/' | '\\' | '|' | '?' | '*' => '_',
            c if c.is_control() => '_',
            c => c,
        })
        .collect();
    let trimmed = cleaned.trim_matches(|c| c == '.' || c == ' ' || c == '_');
    if trimmed.is_empty() {
        "Scan".to_string()
    } else {
        trimmed.chars().take(80).collect()
    }
}

pub fn unique_path(dir: &Path, stem: &str, ext: &str) -> PathBuf {
    let first = dir.join(format!("{stem}.{ext}"));
    if !first.exists() {
        return first;
    }
    for i in 2..1000 {
        let candidate = dir.join(format!("{stem}_{i}.{ext}"));
        if !candidate.exists() {
            return candidate;
        }
    }
    let ts = Local::now().format("%H%M%S");
    dir.join(format!("{stem}_{ts}.{ext}"))
}

/// Generates a clean diagnostic test page (CMYK color bars, alignment grid, printer info) for Print Test Page.
pub fn generate_test_page_png(printer_name: &str, output_path: &Path) -> Result<(), String> {
    let w = 1240u32;
    let h = 1754u32;
    let mut img = RgbImage::from_pixel(w, h, Rgb([255, 255, 255]));

    // Outer border margin (50px)
    for x in 45..w - 45 {
        for t in 0..4 {
            img.put_pixel(x, 45 + t, Rgb([30, 41, 59]));
            img.put_pixel(x, h - 49 + t, Rgb([30, 41, 59]));
        }
    }
    for y in 45..h - 45 {
        for t in 0..4 {
            img.put_pixel(45 + t, y, Rgb([30, 41, 59]));
            img.put_pixel(w - 49 + t, y, Rgb([30, 41, 59]));
        }
    }

    // Header band
    for y in 70..190 {
        for x in 70..w - 70 {
            img.put_pixel(x, y, Rgb([79, 70, 229]));
        }
    }

    // CMYK + RGB test swatches
    let swatches = [
        Rgb([0, 174, 239]),   // Cyan
        Rgb([236, 0, 140]),   // Magenta
        Rgb([255, 242, 0]),   // Yellow
        Rgb([20, 20, 20]),    // Black
        Rgb([239, 68, 68]),   // Red
        Rgb([34, 197, 94]),   // Green
        Rgb([59, 130, 246]),  // Blue
    ];
    let sw_w = (w - 180) / swatches.len() as u32;
    for (idx, color) in swatches.iter().enumerate() {
        let sx = 90 + (idx as u32) * sw_w;
        for y in 240..380 {
            for x in sx..sx + sw_w - 14 {
                img.put_pixel(x, y, *color);
            }
        }
    }

    // Grayscale gradient steps (10 blocks)
    let step_w = (w - 180) / 10;
    for i in 0..10u32 {
        let v = ((i * 255) / 9) as u8;
        let sx = 90 + i * step_w;
        for y in 420..510 {
            for x in sx..sx + step_w - 6 {
                img.put_pixel(x, y, Rgb([v, v, v]));
            }
        }
    }

    // Alignment / nozzle-check fine grid
    for y in (560..1460).step_by(60) {
        for x in 90..w - 90 {
            img.put_pixel(x, y, Rgb([148, 163, 184]));
        }
    }
    for x in (90..w - 90).step_by(60) {
        for y in 560..1460 {
            img.put_pixel(x, y, Rgb([148, 163, 184]));
        }
    }

    // Diagonal alignment lines inside grid
    for i in 0..900u32 {
        let gx = 90 + i;
        let gy = 560 + i;
        if gx < w - 90 && gy < 1460 {
            img.put_pixel(gx, gy, Rgb([15, 23, 42]));
            img.put_pixel(gx, gy + 1, Rgb([15, 23, 42]));
        }
    }

    let _ = printer_name; // Stamp kept lightweight without external font file dependency
    let dyn_img = DynamicImage::ImageRgb8(img);
    save_png(&dyn_img, output_path)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_detect_edges_crops_white_bed_around_document() {
        let mut bed = RgbImage::from_pixel(400, 500, Rgb([250, 250, 250]));
        for y in 120..360 {
            for x in 90..310 {
                bed.put_pixel(x, y, Rgb([40, 60, 110]));
            }
        }
        let cropped = detect_edges_and_crop(&DynamicImage::ImageRgb8(bed));
        let (cw, ch) = cropped.dimensions();
        assert!(cw < 280 && cw >= 210, "expected cropped width ~220..270, got {cw}");
        assert!(ch < 300 && ch >= 230, "expected cropped height ~240..290, got {ch}");
    }

    #[test]
    fn test_split_multi_items_separates_two_blocks() {
        let mut bed = RgbImage::from_pixel(600, 400, Rgb([252, 252, 252]));
        // Item 1 (top-left)
        for y in 50..180 {
            for x in 40..200 {
                bed.put_pixel(x, y, Rgb([30, 30, 30]));
            }
        }
        // Item 2 (bottom-right)
        for y in 210..360 {
            for x in 360..540 {
                bed.put_pixel(x, y, Rgb([20, 90, 40]));
            }
        }
        let items = split_multi_items(&DynamicImage::ImageRgb8(bed));
        assert_eq!(items.len(), 2, "expected 2 split items, got {}", items.len());
    }

    #[test]
    fn test_split_book_spread_produces_two_halves() {
        let spread = DynamicImage::ImageRgb8(RgbImage::from_pixel(800, 500, Rgb([50, 50, 50])));
        let pages = split_book_spread(&spread);
        assert_eq!(pages.len(), 2);
        assert!(pages[0].width() < 400 && pages[1].width() < 400);
    }

    #[test]
    fn test_stitch_id_card_produces_a4_canvas() {
        let front = DynamicImage::ImageRgb8(RgbImage::from_pixel(400, 250, Rgb([30, 80, 150])));
        let back = DynamicImage::ImageRgb8(RgbImage::from_pixel(400, 250, Rgb([150, 80, 30])));
        let out = stitch_id_card(&front, &back);
        assert_eq!(out.dimensions(), (1654, 2338));
    }

    #[test]
    fn test_apply_edits_rotate_crop_and_bw_filter() {
        let mut img = RgbImage::from_pixel(200, 100, Rgb([240, 240, 240]));
        for y in 20..80 {
            for x in 40..160 {
                img.put_pixel(x, y, Rgb([15, 15, 15]));
            }
        }
        let edits = PageEdits {
            crop: None,
            rotate_deg: 90,
            brightness: 0,
            contrast: 10,
            saturation: 0,
            filter: "bw_document".to_string(),
        };
        let out = apply_edits(&DynamicImage::ImageRgb8(img), &edits);
        assert_eq!(out.dimensions(), (100, 200));
        let p_dark = out.to_rgb8().get_pixel(50, 100)[0];
        assert!(p_dark == 0 || p_dark == 255);
    }

    #[test]
    fn test_detect_edges_rect_returns_normalized_bounds() {
        let mut bed = RgbImage::from_pixel(400, 500, Rgb([250, 250, 250]));
        for y in 100..400 {
            for x in 80..320 {
                bed.put_pixel(x, y, Rgb([30, 50, 90]));
            }
        }
        let rect = detect_edges_rect(&DynamicImage::ImageRgb8(bed)).expect("expected crop rect");
        assert!(rect.x > 0.15 && rect.x < 0.25, "x={}", rect.x);
        assert!(rect.y > 0.15 && rect.y < 0.25, "y={}", rect.y);
        assert!(rect.w > 0.55 && rect.w < 0.70, "w={}", rect.w);
        assert!(rect.h > 0.55 && rect.h < 0.70, "h={}", rect.h);
    }
}
