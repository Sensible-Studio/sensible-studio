use axum::body::Body;
use axum::http::{header, Request, StatusCode};
use axum::Router;
use http_body_util::BodyExt;
use serde_json::{json, Value};
use tower::ServiceExt;

const TOKEN: &str = "test-token-0123456789";

fn setup() -> (tempfile::TempDir, Router) {
    let dir = tempfile::tempdir().unwrap();
    let cfg = studio_dam::Config::with_dirs(dir.path(), TOKEN);
    (dir, studio_dam::server::app(cfg))
}

fn fixture(name: &str) -> Vec<u8> {
    std::fs::read(format!("{}/tests/fixtures/wknd/{name}", env!("CARGO_MANIFEST_DIR"))).unwrap()
}

async fn send(app: &Router, req: Request<Body>) -> (StatusCode, axum::http::HeaderMap, Vec<u8>) {
    let res = app.clone().oneshot(req).await.unwrap();
    let status = res.status();
    let headers = res.headers().clone();
    let body = res.into_body().collect().await.unwrap().to_bytes().to_vec();
    (status, headers, body)
}

fn bearer() -> String {
    format!("Bearer {TOKEN}")
}

async fn upload(app: &Router, bytes: Vec<u8>) -> String {
    let req = Request::put("/blobs").header(header::AUTHORIZATION, bearer()).body(Body::from(bytes)).unwrap();
    let (status, _, body) = send(app, req).await;
    assert_eq!(status, StatusCode::CREATED);
    serde_json::from_slice::<Value>(&body).unwrap()["blobId"].as_str().unwrap().to_string()
}

#[tokio::test]
async fn health_is_public() {
    let (_d, app) = setup();
    let (status, _, body) = send(&app, Request::get("/health").body(Body::empty()).unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(serde_json::from_slice::<Value>(&body).unwrap()["status"], "ok");
}

#[tokio::test]
async fn api_requires_the_token() {
    let (_d, app) = setup();
    let req = Request::put("/blobs").body(Body::from("x")).unwrap();
    assert_eq!(send(&app, req).await.0, StatusCode::UNAUTHORIZED);
    let req = Request::post("/process")
        .header(header::AUTHORIZATION, "Bearer wrong-token-0123456789")
        .header(header::CONTENT_TYPE, "application/json")
        .body(Body::from(json!({"blobId": "0".repeat(64)}).to_string()))
        .unwrap();
    assert_eq!(send(&app, req).await.0, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn upload_process_and_fetch_renditions() {
    let (dir, app) = setup();
    let id = upload(&app, fixture("adobestock-231698835.jpg")).await;

    let req = Request::post("/process")
        .header(header::AUTHORIZATION, bearer())
        .header(header::CONTENT_TYPE, "application/json")
        .body(Body::from(json!({ "blobId": id }).to_string()))
        .unwrap();
    let (status, _, body) = send(&app, req).await;
    assert_eq!(status, StatusCode::OK, "{}", String::from_utf8_lossy(&body));
    let res: Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(res["mimeType"], "image/jpeg");
    assert_eq!(res["metadata"]["dam:sha1"], "660c6383c9417dddfa0f58dbe4954abb05b8f09e");
    let renditions = res["renditions"].as_array().unwrap();
    assert_eq!(renditions.len(), 5);

    let web = renditions.iter().find(|r| r["name"] == "cq5dam.web.1280.1280.jpeg").unwrap();
    assert_eq!((web["width"].as_u64(), web["height"].as_u64()), (Some(1280), Some(709)));
    let blob = web["blobId"].as_str().unwrap();
    // Written in Oak FileDataStore layout so Sensible Studio can import it.
    assert!(dir.path().join("datastore").join(&blob[0..2]).join(&blob[2..4]).join(&blob[4..6]).join(blob).is_file());

    let req = Request::get(format!("/blobs/{blob}")).header(header::AUTHORIZATION, bearer()).body(Body::empty()).unwrap();
    let (status, _, bytes) = send(&app, req).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(bytes.len() as u64, web["size"].as_u64().unwrap());
}

#[tokio::test]
async fn process_unknown_blob_is_404() {
    let (_d, app) = setup();
    let req = Request::post("/process")
        .header(header::AUTHORIZATION, bearer())
        .header(header::CONTENT_TYPE, "application/json")
        .body(Body::from(json!({ "blobId": "a".repeat(64) }).to_string()))
        .unwrap();
    assert_eq!(send(&app, req).await.0, StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn delivery_needs_a_valid_signature_and_caches() {
    let (_d, app) = setup();
    let id = upload(&app, fixture("ayo-ogunseinde-237739.jpg")).await;

    let unsigned = format!("/deliver/{id}/400x400.webp");
    assert_eq!(send(&app, Request::get(&unsigned).body(Body::empty()).unwrap()).await.0, StatusCode::FORBIDDEN);
    let forged = format!("{unsigned}?sig={}", "00".repeat(32));
    assert_eq!(send(&app, Request::get(&forged).body(Body::empty()).unwrap()).await.0, StatusCode::FORBIDDEN);

    let req = studio_dam::delivery::DeliveryRequest::parse(&id, "400x400.webp", None, 4096).unwrap();
    let sig = studio_dam::delivery::sign(TOKEN, &req.canonical());
    let url = format!("{unsigned}?sig={sig}");

    let (status, headers, body) = send(&app, Request::get(&url).body(Body::empty()).unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(headers[header::CONTENT_TYPE], "image/webp");
    assert_eq!(headers["x-studio-cache"], "miss");
    let img = image::load_from_memory_with_format(&body, image::ImageFormat::WebP).unwrap();
    assert_eq!((img.width(), img.height()), (307, 400));

    let (_, headers, again) = send(&app, Request::get(&url).body(Body::empty()).unwrap()).await;
    assert_eq!(headers["x-studio-cache"], "hit");
    assert_eq!(again, body);
}

#[tokio::test]
async fn rejects_path_traversal_ids() {
    let (_d, app) = setup();
    let req = Request::get("/blobs/..%2F..%2Fetc%2Fpasswd").header(header::AUTHORIZATION, bearer()).body(Body::empty()).unwrap();
    assert_eq!(send(&app, req).await.0, StatusCode::BAD_REQUEST);
}

fn cache_files(root: &std::path::Path) -> usize {
    fn walk(p: &std::path::Path, n: &mut usize) {
        if let Ok(rd) = std::fs::read_dir(p) {
            for e in rd.flatten() {
                let path = e.path();
                if path.is_dir() {
                    walk(&path, n);
                } else if !e.file_name().to_string_lossy().starts_with('.') {
                    *n += 1;
                }
            }
        }
    }
    let mut n = 0;
    walk(root, &mut n);
    n
}

fn signed(id: &str, spec: &str) -> String {
    let req = studio_dam::delivery::DeliveryRequest::parse(id, spec, None, 4096).unwrap();
    format!("/deliver/{id}/{spec}?sig={}", studio_dam::delivery::sign(TOKEN, &req.canonical()))
}

#[tokio::test]
async fn concurrent_misses_for_one_url_agree() {
    let (dir, app) = setup();
    let id = upload(&app, fixture("ayo-ogunseinde-237739.jpg")).await;
    let url = signed(&id, "300x300.jpeg");
    let mut tasks = Vec::new();
    for _ in 0..8 {
        let app = app.clone();
        let url = url.clone();
        tasks.push(tokio::spawn(async move { send(&app, Request::get(&url).body(Body::empty()).unwrap()).await }));
    }
    let mut bodies = Vec::new();
    let mut misses = 0;
    for t in tasks {
        let (status, headers, body) = t.await.unwrap();
        assert_eq!(status, StatusCode::OK);
        if headers["x-studio-cache"] == "miss" {
            misses += 1;
        }
        bodies.push(body);
    }
    assert_eq!(misses, 1, "generated once, the others waited for the cache");
    assert!(bodies.windows(2).all(|w| w[0] == w[1]));
    assert_eq!(cache_files(&dir.path().join("cache")), 1);
}

#[tokio::test]
async fn widths_beyond_the_source_share_one_cache_entry() {
    let (dir, app) = setup();
    // 720x1080: any box of 1080 or more gives the original size.
    let id = upload(&app, fixture("freeride.jpg")).await;
    for spec in ["1080x1080.webp", "2000x2000.webp", "4096x4096.webp"] {
        let (status, _, body) = send(&app, Request::get(signed(&id, spec)).body(Body::empty()).unwrap()).await;
        assert_eq!(status, StatusCode::OK);
        let img = image::load_from_memory(&body).unwrap();
        assert_eq!((img.width(), img.height()), (720, 1080));
    }
    assert_eq!(cache_files(&dir.path().join("cache")), 1);
}

#[tokio::test]
async fn identical_concurrent_uploads_both_succeed() {
    let (_d, app) = setup();
    let bytes = fixture("freeride.jpg");
    let a = { let app = app.clone(); let b = bytes.clone(); tokio::spawn(async move { upload(&app, b).await }) };
    let b = { let app = app.clone(); tokio::spawn(async move { upload(&app, bytes).await }) };
    assert_eq!(a.await.unwrap(), b.await.unwrap());
}

#[tokio::test]
async fn health_proves_the_token_without_revealing_it() {
    let (_d, app) = setup();
    let (status, _, body) = send(&app, Request::get("/health?challenge=00ff").body(Body::empty()).unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    let proof = serde_json::from_slice::<Value>(&body).unwrap()["proof"].as_str().unwrap().to_string();
    assert_eq!(proof, studio_dam::delivery::sign(TOKEN, "health:00ff"));
    assert!(!String::from_utf8_lossy(&body).contains(TOKEN));
    let (status, _, _) = send(&app, Request::get("/health?challenge=zz").body(Body::empty()).unwrap()).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn edit_crops_and_rotates_into_a_new_original() {
    let (_d, app) = setup();
    let id = upload(&app, fixture("adobestock-231698835.jpg")).await;
    let ops = json!([
        { "op": "crop", "x": 0.0, "y": 0.0, "width": 0.5, "height": 0.5 },
        { "op": "rotate", "degrees": 90 }
    ]);
    let req = Request::post("/edit")
        .header(header::AUTHORIZATION, bearer())
        .header(header::CONTENT_TYPE, "application/json")
        .body(Body::from(json!({ "blobId": id, "operations": ops }).to_string()))
        .unwrap();
    let (status, _, body) = send(&app, req).await;
    assert_eq!(status, StatusCode::OK, "{}", String::from_utf8_lossy(&body));
    let res: Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(res["mimeType"], "image/jpeg");
    // 1600x886 cropped to its top-left quarter (800x443), then turned on its side.
    assert_eq!((res["width"].as_u64(), res["height"].as_u64()), (Some(443), Some(800)));
    let edited = res["blobId"].as_str().unwrap().to_string();
    assert_ne!(edited, id);

    // The new original can be processed like any upload.
    let req = Request::post("/process")
        .header(header::AUTHORIZATION, bearer())
        .header(header::CONTENT_TYPE, "application/json")
        .body(Body::from(json!({ "blobId": edited }).to_string()))
        .unwrap();
    let (status, _, body) = send(&app, req).await;
    assert_eq!(status, StatusCode::OK);
    let res: Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(res["metadata"]["tiff:ImageWidth"], 443);
}

#[tokio::test]
async fn edit_refuses_bad_operations() {
    let (_d, app) = setup();
    let id = upload(&app, fixture("adobestock-231698835.jpg")).await;
    for ops in [json!([]), json!([{ "op": "rotate", "degrees": 30 }]), json!([{ "op": "warp" }])] {
        let req = Request::post("/edit")
            .header(header::AUTHORIZATION, bearer())
            .header(header::CONTENT_TYPE, "application/json")
            .body(Body::from(json!({ "blobId": id, "operations": ops }).to_string()))
            .unwrap();
        let status = send(&app, req).await.0;
        assert!(status == StatusCode::BAD_REQUEST || status == StatusCode::UNPROCESSABLE_ENTITY, "{ops}: {status}");
    }
    let req = Request::post("/edit")
        .header(header::CONTENT_TYPE, "application/json")
        .body(Body::from(json!({ "blobId": id, "operations": [{ "op": "rotate", "degrees": 90 }] }).to_string()))
        .unwrap();
    assert_eq!(send(&app, req).await.0, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn delivery_applies_a_signed_transform_before_resizing() {
    let (_d, app) = setup();
    // 1600x886.
    let id = upload(&app, fixture("adobestock-231698835.jpg")).await;
    let t = "c0,0,800,443-r90-h";
    let req = studio_dam::delivery::DeliveryRequest::parse(&id, "400x4096.jpeg", Some(60), 4096)
        .unwrap()
        .with_transform(Some(t))
        .unwrap();
    let sig = studio_dam::delivery::sign(TOKEN, &req.canonical());
    let url = format!("/deliver/{id}/400x4096.jpeg?q=60&t={t}&sig={sig}");
    let (status, _, body) = send(&app, Request::get(&url).body(Body::empty()).unwrap()).await;
    assert_eq!(status, StatusCode::OK, "{}", String::from_utf8_lossy(&body));
    let img = image::load_from_memory(&body).unwrap();
    // 800x443 crop, turned on its side (443x800), then 400 wide.
    assert_eq!((img.width(), img.height()), (400, 722));

    // The signature covers the transform.
    let tampered = format!("/deliver/{id}/400x4096.jpeg?q=60&t=c0,0,1600,886&sig={sig}");
    assert_eq!(send(&app, Request::get(&tampered).body(Body::empty()).unwrap()).await.0, StatusCode::FORBIDDEN);

    // No upscaling past the cropped size.
    let req = studio_dam::delivery::DeliveryRequest::parse(&id, "1600x4096.jpeg", Some(60), 4096)
        .unwrap()
        .with_transform(Some("c100,100,300,200"))
        .unwrap();
    let url = format!("/deliver/{id}/1600x4096.jpeg?q=60&t=c100,100,300,200&sig={}", studio_dam::delivery::sign(TOKEN, &req.canonical()));
    let (status, _, body) = send(&app, Request::get(&url).body(Body::empty()).unwrap()).await;
    assert_eq!(status, StatusCode::OK);
    let img = image::load_from_memory(&body).unwrap();
    assert_eq!((img.width(), img.height()), (300, 200));
}

#[tokio::test]
async fn smart_tags_need_the_model_and_take_only_pinned_files() {
    let (_d, app) = setup();
    let req = Request::get("/smart-tags/status").header(header::AUTHORIZATION, bearer()).body(Body::empty()).unwrap();
    let (status, _, body) = send(&app, req).await;
    assert_eq!(status, StatusCode::OK);
    let v: Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(v["installed"], false);
    assert_eq!(v["files"].as_array().unwrap().len(), 3);

    // without a token, an unknown file, or the wrong bytes: refused
    let req = Request::put("/smart-tags/model/config.json").body(Body::from(vec![b' '; 432])).unwrap();
    assert_eq!(send(&app, req).await.0, StatusCode::UNAUTHORIZED);
    let req = Request::put("/smart-tags/model/..%2Fevil").header(header::AUTHORIZATION, bearer()).body(Body::from("x")).unwrap();
    assert_eq!(send(&app, req).await.0, StatusCode::NOT_FOUND);
    let req = Request::put("/smart-tags/model/config.json").header(header::AUTHORIZATION, bearer()).body(Body::from(vec![b' '; 432])).unwrap();
    assert_eq!(send(&app, req).await.0, StatusCode::BAD_REQUEST);

    // predicting before the model is there: 409, so Sensible Studio knows to wait
    let blob = upload(&app, fixture("freeride.jpg")).await;
    let body = json!({ "blobId": blob, "labels": [{ "id": "a", "text": "skiing" }] });
    let req = Request::post("/smart-tags/predict")
        .header(header::AUTHORIZATION, bearer())
        .header(header::CONTENT_TYPE, "application/json")
        .body(Body::from(body.to_string()))
        .unwrap();
    assert_eq!(send(&app, req).await.0, StatusCode::CONFLICT);
}

#[tokio::test]
async fn the_search_model_is_separate_and_optional() {
    let (_d, app) = setup();
    let req = Request::get("/search-model/status").header(header::AUTHORIZATION, bearer()).body(Body::empty()).unwrap();
    let (status, _, body) = send(&app, req).await;
    assert_eq!(status, StatusCode::OK);
    let v: Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(v["model"], "siglip-base-patch16-256-multilingual");
    assert_eq!(v["installed"], false);
    assert!(v["files"][2]["url"].as_str().unwrap().starts_with("https://huggingface.co/google/siglip-base-patch16-256-multilingual/resolve/"));

    let req = Request::get("/search-model/status").body(Body::empty()).unwrap();
    assert_eq!(send(&app, req).await.0, StatusCode::UNAUTHORIZED);
    // the smart tags model's files are not this model's
    let req = Request::put("/search-model/model/config.json").header(header::AUTHORIZATION, bearer()).body(Body::from(vec![b' '; 432])).unwrap();
    assert_eq!(send(&app, req).await.0, StatusCode::BAD_REQUEST);
    let req = Request::put("/search-model/model/spiece.model").header(header::AUTHORIZATION, bearer()).body(Body::from("x")).unwrap();
    assert_eq!(send(&app, req).await.0, StatusCode::NOT_FOUND);

    // not installed: 409 for words and pictures
    let req = Request::post("/search-model/embed-text")
        .header(header::AUTHORIZATION, bearer())
        .header(header::CONTENT_TYPE, "application/json")
        .body(Body::from(json!({ "text": "un cort roșu" }).to_string()))
        .unwrap();
    assert_eq!(send(&app, req).await.0, StatusCode::CONFLICT);
    let blob = upload(&app, fixture("freeride.jpg")).await;
    let req = Request::post("/search-model/embed")
        .header(header::AUTHORIZATION, bearer())
        .header(header::CONTENT_TYPE, "application/json")
        .body(Body::from(json!({ "blobId": blob }).to_string()))
        .unwrap();
    assert_eq!(send(&app, req).await.0, StatusCode::CONFLICT);
}

#[tokio::test]
async fn blob_gc_needs_the_token_and_a_keep_list() {
    let (_d, app) = setup();
    let blob = upload(&app, fixture("freeride.jpg")).await;
    let gc = |body: Value, auth: bool| {
        let mut r = Request::post("/blobs/gc").header(header::CONTENT_TYPE, "application/json");
        if auth {
            r = r.header(header::AUTHORIZATION, bearer());
        }
        r.body(Body::from(body.to_string())).unwrap()
    };
    assert_eq!(send(&app, gc(json!({ "keep": [blob] }), false)).await.0, StatusCode::UNAUTHORIZED);
    assert_eq!(send(&app, gc(json!({ "keep": [] }), true)).await.0, StatusCode::BAD_REQUEST);
    assert_eq!(send(&app, gc(json!({ "keep": ["../x"] }), true)).await.0, StatusCode::BAD_REQUEST);
    // the upload is recent: kept even though it is not on the list
    let other = "0".repeat(64);
    let (status, _, body) = send(&app, gc(json!({ "keep": [other] }), true)).await;
    assert_eq!(status, StatusCode::OK);
    let v: Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(v["blobs"]["removed"], 0);
    assert!(v["blobs"]["files"].as_u64().unwrap() >= 1);
    let req = Request::get(format!("/blobs/{blob}")).header(header::AUTHORIZATION, bearer()).body(Body::empty()).unwrap();
    assert_eq!(send(&app, req).await.0, StatusCode::OK);
}

#[tokio::test]
async fn smart_crop_returns_a_window_per_ratio() {
    let (_d, app) = setup();
    let blob = upload(&app, fixture("freeride.jpg")).await;
    let body = json!({ "blobId": blob, "ratios": [{ "name": "square", "width": 1, "height": 1 }, { "name": "banner", "width": 3, "height": 1 }] });
    let req = Request::post("/smart-crop")
        .header(header::AUTHORIZATION, bearer())
        .header(header::CONTENT_TYPE, "application/json")
        .body(Body::from(body.to_string()))
        .unwrap();
    let (status, _, out) = send(&app, req).await;
    assert_eq!(status, StatusCode::OK);
    let v: Value = serde_json::from_slice(&out).unwrap();
    let w = v["width"].as_u64().unwrap();
    let h = v["height"].as_u64().unwrap();
    let crops = v["crops"].as_array().unwrap();
    assert_eq!(crops.len(), 2);
    let side = w.min(h);
    assert_eq!(crops[0]["width"], side);
    assert_eq!(crops[0]["height"], side);
    assert!(crops[0]["x"].as_u64().unwrap() + side <= w);
    assert_eq!(crops[1]["name"], "banner");
    // no token, no ratios: refused
    let req = Request::post("/smart-crop").header(header::CONTENT_TYPE, "application/json").body(Body::from(body.to_string())).unwrap();
    assert_eq!(send(&app, req).await.0, StatusCode::UNAUTHORIZED);
    let req = Request::post("/smart-crop")
        .header(header::AUTHORIZATION, bearer())
        .header(header::CONTENT_TYPE, "application/json")
        .body(Body::from(json!({ "blobId": blob, "ratios": [] }).to_string()))
        .unwrap();
    assert_eq!(send(&app, req).await.0, StatusCode::BAD_REQUEST);
}
