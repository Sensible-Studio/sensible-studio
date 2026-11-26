//! Checks our output against what was observed in WKND content
//! (tests/fixtures/wknd/expected.json, built by tools/observe_wknd.py).

use std::collections::BTreeSet;
use std::path::PathBuf;

use serde_json::Value;

fn fixtures() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/wknd")
}

fn expected() -> Vec<Value> {
    let raw = std::fs::read_to_string(fixtures().join("expected.json")).unwrap();
    serde_json::from_str(&raw).unwrap()
}

/// `{Long}123` → 123, `{Decimal}1.5` → 1.5, plain strings unchanged.
fn jcr_value(raw: &str) -> Value {
    if let Some(n) = raw.strip_prefix("{Long}") {
        return Value::from(n.parse::<i64>().unwrap());
    }
    if let Some(n) = raw.strip_prefix("{Decimal}") {
        return Value::from(n.parse::<f64>().unwrap());
    }
    Value::from(raw)
}

#[test]
fn renditions_match_wknd() {
    for fx in expected() {
        let file = fx["file"].as_str().unwrap();
        let bytes = std::fs::read(fixtures().join(file)).unwrap();
        let out = studio_dam::process::process(&bytes, None, 150_000_000).unwrap();

        let want = fx["renditions"].as_object().unwrap();
        let got_names: BTreeSet<_> = out.renditions.iter().map(|r| r.name.clone()).collect();
        let want_names: BTreeSet<_> = want.keys().cloned().collect();
        assert_eq!(got_names, want_names, "{file}: rendition set");

        for r in &out.renditions {
            let w = &want[&r.name];
            let (ew, eh) = (w["w"].as_u64().unwrap() as u32, w["h"].as_u64().unwrap() as u32);
            // The box-limited side is exact; AEM thumbnails are sometimes ±1 on the other.
            let ok = |a: u32, b: u32| a.abs_diff(b) <= 1;
            assert!(ok(r.width, ew) && ok(r.height, eh), "{file} {}: got {}x{}, AEM {ew}x{eh}", r.name, r.width, r.height);
            assert!(r.width == ew || r.height == eh, "{file} {}: long side must be exact", r.name);

            let img = image::load_from_memory(&r.bytes).unwrap();
            assert_eq!((img.width(), img.height()), (r.width, r.height), "{file} {}: encoded size", r.name);
            let fmt = image::guess_format(&r.bytes).unwrap();
            let want_fmt = match w["fmt"].as_str().unwrap() {
                "PNG" => image::ImageFormat::Png,
                "JPEG" => image::ImageFormat::Jpeg,
                other => panic!("unexpected format {other}"),
            };
            assert_eq!(fmt, want_fmt, "{file} {}: format", r.name);
            let want_alpha = w["mode"].as_str() == Some("RGBA");
            assert_eq!(img.color().has_alpha(), want_alpha, "{file} {}: alpha", r.name);
        }
        assert!(out.failed.is_empty());
    }
}

#[test]
fn metadata_matches_wknd() {
    for fx in expected() {
        let file = fx["file"].as_str().unwrap();
        let bytes = std::fs::read(fixtures().join(file)).unwrap();
        let out = studio_dam::process::process(&bytes, None, 150_000_000).unwrap();
        for (key, raw) in fx["metadata"].as_object().unwrap() {
            let want = jcr_value(raw.as_str().unwrap());
            let got = out.metadata.get(key).unwrap_or_else(|| panic!("{file}: missing {key}"));
            assert_eq!(got, &want, "{file}: {key}");
        }
    }
}

#[test]
fn exif_fields_use_xmp_names() {
    let bytes = std::fs::read(fixtures().join("adobestock-231698835.jpg")).unwrap();
    let m = studio_dam::process::process(&bytes, None, 150_000_000).unwrap().metadata;
    // Same values as in the WKND metadata node of this asset.
    assert_eq!(m["exif:FNumber"], "8/1");
    assert_eq!(m["exif:ExposureTime"], "1/30");
    assert_eq!(m["exif:FocalLength"], "16/1");
    assert_eq!(m["exif:ISOSpeedRatings"], serde_json::json!([100]));
    assert_eq!(m["exifEX:LensModel"], "EF16-35mm f/4L IS USM");
    assert_eq!(m["exif:DateTimeOriginal"].as_str().unwrap()[..19], *"2016-09-07T04:13:42");
    let inches = m["dam:Physicalwidthininches"].as_f64().unwrap();
    assert!((inches - 22.2222).abs() < 0.001);
}

#[test]
fn xmp_packet_is_returned_raw() {
    let bytes = std::fs::read(fixtures().join("adobestock-231698835.jpg")).unwrap();
    let out = studio_dam::process::process(&bytes, None, 150_000_000).unwrap();
    let xmp = out.xmp_packet.expect("this stock photo carries XMP");
    assert!(xmp.contains("Half Dome"), "dc:description should be inside the packet");
}
