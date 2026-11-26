//! HTTP API.
//!
//! | Route | Auth | Purpose |
//! |---|---|---|
//! | `GET /health` | none | liveness for the Sensible Studio process manager |
//! | `PUT /blobs` | bearer | store a binary, returns its blob id |
//! | `GET /blobs/{id}` | bearer | fetch a rendition (or original) binary |
//! | `POST /process` | bearer | renditions + metadata for an original |
//! | `POST /edit` | bearer | crop / rotate / flip an original into a new original |
//! | `GET /deliver/{id}/{W}x{H}.{ext}` | signed URL | on-the-fly resize, cached |
//! | `GET /smart-tags/status` | bearer | whether the Smart Tags model is installed |
//! | `PUT /smart-tags/model/{file}` | bearer | one model file (size and SHA-256 pinned) |
//! | `POST /smart-tags/predict` | bearer | which labels fit an image |
//! | `POST /smart-tags/embed` | bearer | the image's embedding, for "find similar" |
//! | `POST /smart-tags/embed-text` | bearer | a phrase's embedding, for finding pictures by words |
//! | `POST /smart-crop` | bearer | the crop of each aspect ratio that keeps the subject (see `smartcrop`) |
//! | `POST /blobs/gc` | bearer | remove blobs Sensible Studio no longer refers to (see `housekeeping`) |

use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Instant;

use axum::body::{Body, Bytes};
use axum::extract::{DefaultBodyLimit, Path, Query, State};
use axum::http::{header, HeaderMap, HeaderValue, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post, put};
use axum::{Json, Router};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use tokio::sync::Semaphore;

use crate::blobstore::{BlobId, BlobStore};
use crate::delivery::{self, DeliveryRequest};
use crate::edit::{self, Operation};
use crate::process::{self, FailedRendition};
use crate::{Config, DamError};

pub struct AppState {
    pub cfg: Config,
    pub originals: BlobStore,
    pub outputs: BlobStore,
    pub permits: Arc<Semaphore>,
    pub tagger: Arc<crate::smarttags::SmartTagger>,
    /// the optional multilingual model: picture search in any language
    pub searcher: Arc<crate::smarttags::SmartTagger>,
    /// Requests waiting for a worker; beyond `workers * QUEUE_FACTOR` we answer 503 at once.
    waiting: AtomicUsize,
    /// One lock per delivery cache key, so N concurrent misses for one URL decode once.
    inflight: Mutex<HashMap<String, Arc<tokio::sync::Mutex<()>>>>,
}

/// How many requests may wait per worker before new ones are turned away.
const QUEUE_FACTOR: usize = 8;

static TMP_COUNTER: AtomicU64 = AtomicU64::new(0);

/// A temp-file name no other request or process uses.
pub(crate) fn unique_tmp_name(prefix: &str) -> String {
    format!(".{prefix}.{}.{}.tmp", std::process::id(), TMP_COUNTER.fetch_add(1, Ordering::Relaxed))
}

pub type Shared = Arc<AppState>;

/// Every few minutes: trim the delivery cache and give back the Smart Tags model's memory when
/// it has not been used. Stops when the app is dropped.
fn spawn_housekeeping(state: &Shared) {
    if tokio::runtime::Handle::try_current().is_err() {
        return;
    }
    let weak = Arc::downgrade(state);
    tokio::spawn(async move {
        let mut tick = tokio::time::interval(std::time::Duration::from_secs(600));
        tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
        loop {
            tick.tick().await;
            let Some(st) = weak.upgrade() else { return };
            let s = st.clone();
            let swept = tokio::task::spawn_blocking(move || {
                s.tagger.unload_if_idle(s.cfg.model_idle);
                s.searcher.unload_if_idle(s.cfg.model_idle);
                crate::housekeeping::sweep_cache(&s.cfg.cache, s.cfg.cache_max_bytes, s.cfg.cache_max_age)
            })
            .await;
            if let Ok(sw) = swept {
                if sw.removed + sw.temp_removed > 0 {
                    tracing::info!(removed = sw.removed, freed = sw.freed, files = sw.files, "delivery cache trimmed");
                }
            }
        }
    });
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct GcRequest {
    /// every blob id Sensible Studio still refers to
    keep: Vec<String>,
    /// only blobs older than this go (default: 3 days)
    grace_seconds: Option<u64>,
    #[serde(default)]
    dry_run: bool,
}

async fn gc_blobs(State(state): State<Shared>, headers: HeaderMap, Json(req): Json<GcRequest>) -> Result<Json<Value>, DamError> {
    check_bearer(&state, &headers)?;
    // An empty list would mean "nothing is used": far more likely a bug on the caller's side.
    if req.keep.is_empty() {
        return Err(DamError::BadRequest("keep must list the blobs in use".into()));
    }
    let keep = req.keep.iter().map(|k| BlobId::parse(k).map(|id| id.to_string())).collect::<Result<_, _>>()?;
    let grace = std::time::Duration::from_secs(req.grace_seconds.unwrap_or(3 * 86_400).max(3600));
    let st = state.clone();
    let sweep = tokio::task::spawn_blocking(move || {
        crate::housekeeping::gc_blobs(&[st.cfg.datastore.as_path(), st.cfg.output.as_path()], &keep, grace, req.dry_run)
    })
    .await
    .map_err(|e| DamError::Internal(e.to_string()))?;
    tracing::info!(removed = sweep.removed, freed = sweep.freed, files = sweep.files, dry_run = req.dry_run, "blob gc");
    Ok(Json(json!({ "blobs": sweep, "dryRun": req.dry_run })))
}

pub fn app(cfg: Config) -> Router {
    let state = Arc::new(AppState {
        originals: BlobStore::new(&cfg.datastore),
        outputs: BlobStore::new(&cfg.output),
        permits: Arc::new(Semaphore::new(cfg.workers)),
        tagger: Arc::new(crate::smarttags::SmartTagger::new(&cfg.models)),
        searcher: Arc::new(crate::smarttags::SmartTagger::of(&crate::smarttags::MULTILINGUAL, &cfg.models)),
        waiting: AtomicUsize::new(0),
        inflight: Mutex::new(HashMap::new()),
        cfg,
    });
    spawn_housekeeping(&state);
    let upload_limit = state.cfg.max_upload_bytes;
    Router::new()
        .route("/health", get(health))
        .route("/blobs", put(put_blob).layer(DefaultBodyLimit::max(upload_limit)))
        .route("/blobs/{id}", get(get_blob))
        .route("/blobs/gc", post(gc_blobs).layer(DefaultBodyLimit::max(256 * 1024 * 1024)))
        .route("/process", post(process_asset))
        .route("/edit", post(edit_asset))
        .route("/deliver/{id}/{spec}", get(deliver))
        .route("/smart-tags/status", get(smart_tags_status))
        .route("/smart-tags/model/{file}", put(smart_tags_install).layer(DefaultBodyLimit::disable()))
        .route("/smart-tags/predict", post(smart_tags_predict))
        .route("/smart-tags/embed", post(smart_tags_embed))
        .route("/smart-tags/embed-text", post(smart_tags_embed_text))
        .route("/search-model/status", get(search_model_status))
        .route("/search-model/model/{file}", put(search_model_install).layer(DefaultBodyLimit::disable()))
        .route("/search-model/embed", post(search_model_embed))
        .route("/search-model/embed-text", post(search_model_embed_text))
        .route("/smart-crop", post(smart_crop))
        .route("/video/poster", post(video_poster))
        .route("/video/preview", post(video_preview))
        .with_state(state)
}

#[derive(Deserialize)]
struct HealthQuery {
    challenge: Option<String>,
}

/// Liveness. With `?challenge=<hex>`, also proves knowledge of the token
/// (`proof = HMAC(token, "health:" + challenge)`), so Sensible Studio can tell its own process from
/// anything else that happens to listen on the port — without ever sending the token.
async fn health(State(state): State<Shared>, Query(q): Query<HealthQuery>) -> Result<Json<Value>, DamError> {
    let mut body = json!({ "status": "ok", "service": "studio-dam", "version": env!("CARGO_PKG_VERSION"), "ffmpeg": crate::ffmpeg::path().is_some(), "office": crate::office::path().is_some() });
    if let Some(c) = q.challenge {
        if c.is_empty() || c.len() > 128 || !c.bytes().all(|b| b.is_ascii_hexdigit()) {
            return Err(DamError::BadRequest("challenge must be hex".into()));
        }
        body["proof"] = json!(delivery::sign(&state.cfg.token, &format!("health:{c}")));
    }
    Ok(Json(body))
}

fn check_bearer(state: &AppState, headers: &HeaderMap) -> Result<(), DamError> {
    let given = headers
        .get(header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "))
        .ok_or(DamError::Unauthorized)?;
    if constant_time_eq(given.as_bytes(), state.cfg.token.as_bytes()) {
        Ok(())
    } else {
        Err(DamError::Unauthorized)
    }
}

fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    a.len() == b.len() && a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

/// Decrements the waiting counter however the wait ends (including a dropped request).
struct Waiting<'a>(&'a AtomicUsize);

impl Drop for Waiting<'_> {
    fn drop(&mut self) {
        self.0.fetch_sub(1, Ordering::Relaxed);
    }
}

/// Run CPU-bound work on the blocking pool, at most `workers` at a time. The permit moves into
/// the blocking task, so work keeps counting against the limit even if the client hangs up.
/// When too many requests are already waiting, fail fast with 503 instead of queueing forever.
async fn cpu<T, F>(state: &Shared, f: F) -> Result<T, DamError>
where
    F: FnOnce() -> Result<T, DamError> + Send + 'static,
    T: Send + 'static,
{
    let limit = state.cfg.workers * QUEUE_FACTOR;
    if state.waiting.fetch_add(1, Ordering::Relaxed) >= limit {
        state.waiting.fetch_sub(1, Ordering::Relaxed);
        return Err(DamError::Busy);
    }
    let permit = {
        let _w = Waiting(&state.waiting);
        state.permits.clone().acquire_owned().await.map_err(|e| DamError::Internal(e.to_string()))?
    };
    tokio::task::spawn_blocking(move || {
        let _permit = permit;
        f()
    })
    .await
    .map_err(|e| DamError::Internal(format!("worker: {e}")))?
}

async fn put_blob(State(state): State<Shared>, headers: HeaderMap, body: Bytes) -> Result<Response, DamError> {
    check_bearer(&state, &headers)?;
    if body.is_empty() {
        return Err(DamError::BadRequest("empty body".into()));
    }
    let store = state.originals.clone();
    let id = cpu(&state, move || store.put(&body)).await?;
    Ok((StatusCode::CREATED, Json(json!({ "blobId": id.to_string() }))).into_response())
}

async fn get_blob(State(state): State<Shared>, headers: HeaderMap, Path(id): Path<String>) -> Result<Response, DamError> {
    check_bearer(&state, &headers)?;
    let id = BlobId::parse(&id)?;
    let store = if state.outputs.exists(&id) { state.outputs.clone() } else { state.originals.clone() };
    let max = state.cfg.max_upload_bytes as u64;
    let bytes = tokio::task::spawn_blocking(move || store.read(&id, max))
        .await
        .map_err(|e| DamError::Internal(e.to_string()))??;
    Ok(([(header::CONTENT_TYPE, "application/octet-stream")], bytes).into_response())
}

async fn smart_tags_status(State(state): State<Shared>, headers: HeaderMap) -> Result<Json<Value>, DamError> {
    check_bearer(&state, &headers)?;
    Ok(Json(state.tagger.status()))
}

async fn search_model_status(State(state): State<Shared>, headers: HeaderMap) -> Result<Json<Value>, DamError> {
    check_bearer(&state, &headers)?;
    Ok(Json(state.searcher.status()))
}

/// Streams the body into the model directory; the file is checked before it replaces anything.
async fn smart_tags_install(
    State(state): State<Shared>,
    headers: HeaderMap,
    Path(file): Path<String>,
    body: Body,
) -> Result<Json<Value>, DamError> {
    check_bearer(&state, &headers)?;
    let tagger = state.tagger.clone();
    install_model(tagger, file, body).await
}

async fn search_model_install(
    State(state): State<Shared>,
    headers: HeaderMap,
    Path(file): Path<String>,
    body: Body,
) -> Result<Json<Value>, DamError> {
    check_bearer(&state, &headers)?;
    let searcher = state.searcher.clone();
    install_model(searcher, file, body).await
}

async fn install_model(tagger: Arc<crate::smarttags::SmartTagger>, file: String, body: Body) -> Result<Json<Value>, DamError> {
    use futures_util::StreamExt;
    if !tagger.spec().files.iter().any(|f| f.name == file) {
        return Err(DamError::NotFound);
    }
    let (tx, mut rx) = tokio::sync::mpsc::channel::<Result<Vec<u8>, DamError>>(8);
    let writing = tagger.clone();
    let name = file.clone();
    let writer = tokio::task::spawn_blocking(move || writing.install(&name, std::iter::from_fn(move || rx.blocking_recv())));
    let mut stream = body.into_data_stream();
    while let Some(chunk) = stream.next().await {
        let item = chunk.map(|b| b.to_vec()).map_err(|e| DamError::BadRequest(format!("upload: {e}")));
        let failed = item.is_err();
        if tx.send(item).await.is_err() || failed {
            break; // the writer stopped (bad size) or the client went away
        }
    }
    drop(tx);
    writer.await.map_err(|e| DamError::Internal(e.to_string()))??;
    Ok(Json(json!({ "installed": file, "model": tagger.installed() })))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct SmartCropRequest {
    /// an image: the original, or a rendition of the same proportions
    blob_id: String,
    ratios: Vec<crate::smartcrop::Ratio>,
}

/// At most this many ratios per request.
const MAX_RATIOS: usize = 20;

async fn smart_crop(State(state): State<Shared>, headers: HeaderMap, Json(req): Json<SmartCropRequest>) -> Result<Json<Value>, DamError> {
    check_bearer(&state, &headers)?;
    let id = BlobId::parse(&req.blob_id)?;
    if req.ratios.is_empty() || req.ratios.len() > MAX_RATIOS {
        return Err(DamError::BadRequest(format!("between 1 and {MAX_RATIOS} ratios")));
    }
    let started = Instant::now();
    let st = state.clone();
    let (w, h, crops) = cpu(&state, move || {
        let store = if st.outputs.exists(&id) { &st.outputs } else { &st.originals };
        let bytes = store.read_mapped(&id, st.cfg.max_upload_bytes as u64)?;
        let decoded = process::decode(&bytes, st.cfg.max_source_pixels)?;
        // the Smart Tags model, when installed, says where the subject is; without it the cheap map decides
        let model = match st.tagger.attention_map(&decoded.image) {
            Ok(m) => m,
            Err(e) => {
                tracing::info!(error = %e, "smart crop: without the model's attention");
                None
            }
        };
        let crops = crate::smartcrop::crops(&decoded.image, &req.ratios, model.as_ref())?;
        Ok((decoded.image.width(), decoded.image.height(), crops))
    })
    .await?;
    Ok(Json(json!({ "width": w, "height": h, "crops": crops, "model": state.tagger.installed(), "elapsedMs": started.elapsed().as_millis() })))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct PredictRequest {
    /// an image: a rendition (web) or an original
    blob_id: String,
    labels: Vec<crate::smarttags::Label>,
    max: Option<usize>,
    min_confidence: Option<f32>,
    min_score: Option<f32>,
}

async fn smart_tags_predict(
    State(state): State<Shared>,
    headers: HeaderMap,
    Json(req): Json<PredictRequest>,
) -> Result<Json<Value>, DamError> {
    use crate::smarttags::{MAX_LABELS, MAX_LABEL_CHARS};
    check_bearer(&state, &headers)?;
    let id = BlobId::parse(&req.blob_id)?;
    if req.labels.len() > MAX_LABELS || req.labels.iter().any(|l| l.text.chars().count() > MAX_LABEL_CHARS || l.id.len() > 512) {
        return Err(DamError::BadRequest(format!("at most {MAX_LABELS} labels of {MAX_LABEL_CHARS} characters")));
    }
    let max = req.max.unwrap_or(5).clamp(1, 20);
    let min_confidence = req.min_confidence.unwrap_or(0.05).clamp(0.0, 1.0);
    let min_score = req.min_score.unwrap_or(1e-4).clamp(0.0, 1.0);
    let started = Instant::now();
    let st = state.clone();
    let tags = cpu(&state, move || {
        let store = if st.outputs.exists(&id) { &st.outputs } else { &st.originals };
        let bytes = store.read_mapped(&id, st.cfg.max_upload_bytes as u64)?;
        let decoded = process::decode(&bytes, st.cfg.max_source_pixels)?;
        st.tagger.predict(&decoded.image, &req.labels, max, min_confidence, min_score)
    })
    .await?;
    Ok(Json(json!({ "model": crate::smarttags::MODEL, "tags": tags, "elapsedMs": started.elapsed().as_millis() })))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct EmbedRequest {
    blob_id: String,
}

/// The normalised embedding of an image (768 numbers): two images are alike when the dot product
/// of their embeddings is close to 1. Sensible Studio keeps it with the asset and compares there.
async fn smart_tags_embed(
    State(state): State<Shared>,
    headers: HeaderMap,
    Json(req): Json<EmbedRequest>,
) -> Result<Json<Value>, DamError> {
    check_bearer(&state, &headers)?;
    let tagger = state.tagger.clone();
    embed_with(state, tagger, req).await
}

/// [`smart_tags_embed`] with the multilingual model: the space picture search in any language compares in.
async fn search_model_embed(
    State(state): State<Shared>,
    headers: HeaderMap,
    Json(req): Json<EmbedRequest>,
) -> Result<Json<Value>, DamError> {
    check_bearer(&state, &headers)?;
    let searcher = state.searcher.clone();
    embed_with(state, searcher, req).await
}

async fn embed_with(state: Shared, tagger: Arc<crate::smarttags::SmartTagger>, req: EmbedRequest) -> Result<Json<Value>, DamError> {
    let id = BlobId::parse(&req.blob_id)?;
    let started = Instant::now();
    let st = state.clone();
    let model = tagger.spec().model;
    let embedding = cpu(&state, move || {
        let store = if st.outputs.exists(&id) { &st.outputs } else { &st.originals };
        let bytes = store.read_mapped(&id, st.cfg.max_upload_bytes as u64)?;
        let decoded = process::decode(&bytes, st.cfg.max_source_pixels)?;
        tagger.image_embedding(&decoded.image)
    })
    .await?;
    Ok(Json(json!({ "model": model, "embedding": embedding, "elapsedMs": started.elapsed().as_millis() })))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct VideoRequest {
    blob_id: String,
    /// seconds into the video (poster); a tenth of the way in when absent
    at: Option<f64>,
    /// `/video/preview`: `mp4` (default) or `webm`
    format: Option<String>,
    /// `/video/preview`: the most lines (720 when absent)
    height: Option<u32>,
}

/// The container of an uploaded video, as this service reads it (never as the upload says).
fn video_kind(state: &AppState, id: &BlobId) -> Result<Option<&'static str>, DamError> {
    use std::io::Read;
    let mut head = vec![0u8; 64 * 1024];
    let mut f = std::fs::File::open(state.originals.path_of(id)).map_err(|_| DamError::NotFound)?;
    let n = f.read(&mut head)?;
    head.truncate(n);
    Ok(crate::video::sniff(&head))
}

/// A frame of an uploaded video as a JPEG (409 without ffmpeg, 415 for what is not a video this
/// service knows). Sensible Studio keeps it as the video's poster and makes the thumbnails from it.
async fn video_poster(State(state): State<Shared>, headers: HeaderMap, Json(req): Json<VideoRequest>) -> Result<Response, DamError> {
    check_bearer(&state, &headers)?;
    let id = BlobId::parse(&req.blob_id)?;
    if let Some(at) = req.at {
        if !at.is_finite() || at < 0.0 {
            return Err(DamError::BadRequest("at must be seconds from the start".into()));
        }
    }
    let st = state.clone();
    let jpeg = cpu(&state, move || {
        if !st.originals.exists(&id) {
            return Err(DamError::NotFound);
        }
        let kind = video_kind(&st, &id)?;
        let at = match req.at {
            Some(at) => at,
            None => {
                // a tenth of the way in, where a film has usually left its black opening frames
                let bytes = st.originals.read_mapped(&id, st.cfg.max_upload_bytes as u64)?;
                let md = crate::video::metadata(&bytes);
                md.get("dam:duration").and_then(|d| d.as_f64()).map(|d| (d / 10.0).min(30.0)).unwrap_or(1.0)
            }
        };
        crate::ffmpeg::poster(&st.originals.path_of(&id), kind, at)
    })
    .await?;
    Ok(([(header::CONTENT_TYPE, "image/jpeg")], jpeg).into_response())
}

/// An uploaded video as an MP4 any browser plays; the result is a blob like a rendition.
async fn video_preview(State(state): State<Shared>, headers: HeaderMap, Json(req): Json<VideoRequest>) -> Result<Json<Value>, DamError> {
    check_bearer(&state, &headers)?;
    let id = BlobId::parse(&req.blob_id)?;
    let format = match req.format.as_deref() {
        None => crate::ffmpeg::Format::Mp4,
        Some(f) => crate::ffmpeg::Format::parse(f).ok_or_else(|| DamError::BadRequest(format!("format is mp4 or webm, not {f}")))?,
    };
    let height = req.height.unwrap_or(720);
    if !(crate::ffmpeg::MIN_HEIGHT..=crate::ffmpeg::MAX_HEIGHT).contains(&height) {
        return Err(DamError::BadRequest(format!("height of {} to {}", crate::ffmpeg::MIN_HEIGHT, crate::ffmpeg::MAX_HEIGHT)));
    }
    let started = Instant::now();
    let st = state.clone();
    let (blob, size) = cpu(&state, move || {
        if !st.originals.exists(&id) {
            return Err(DamError::NotFound);
        }
        let kind = video_kind(&st, &id)?;
        // written beside the blobs (same disk), under a name no blob has, and removed whatever happens
        struct Scratch(std::path::PathBuf);
        impl Drop for Scratch {
            fn drop(&mut self) {
                let _ = std::fs::remove_file(&self.0);
            }
        }
        let nanos = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_nanos()).unwrap_or(0);
        let tmp = Scratch(st.outputs.root().join(format!(".preview-{}-{nanos}.{}", std::process::id(), format.extension())));
        crate::ffmpeg::encode(&st.originals.path_of(&id), kind, &tmp.0, format, height)?;
        let bytes = std::fs::read(&tmp.0)?;
        if bytes.is_empty() {
            return Err(DamError::Unsupported("the preview came out empty".into()));
        }
        let blob = st.outputs.put(&bytes)?;
        Ok((blob, bytes.len()))
    })
    .await?;
    Ok(Json(json!({ "blobId": blob.as_str(), "size": size, "mimeType": format.mime(), "elapsedMs": started.elapsed().as_millis() })))
}

#[derive(Deserialize)]
struct EmbedTextRequest {
    text: String,
}

/// The embedding of a phrase ("a red tent on a mountain") in the space of the image embeddings,
/// with the model's scale and bias: a picture fits the phrase as `sigmoid(scale * dot + bias)`.
async fn smart_tags_embed_text(
    State(state): State<Shared>,
    headers: HeaderMap,
    Json(req): Json<EmbedTextRequest>,
) -> Result<Json<Value>, DamError> {
    check_bearer(&state, &headers)?;
    let tagger = state.tagger.clone();
    embed_text_with(state, tagger, req).await
}

/// [`smart_tags_embed_text`] with the multilingual model: the phrase may be in any language.
async fn search_model_embed_text(
    State(state): State<Shared>,
    headers: HeaderMap,
    Json(req): Json<EmbedTextRequest>,
) -> Result<Json<Value>, DamError> {
    check_bearer(&state, &headers)?;
    let searcher = state.searcher.clone();
    embed_text_with(state, searcher, req).await
}

async fn embed_text_with(state: Shared, tagger: Arc<crate::smarttags::SmartTagger>, req: EmbedTextRequest) -> Result<Json<Value>, DamError> {
    let text = req.text.trim().to_string();
    if text.is_empty() || text.chars().count() > crate::smarttags::MAX_LABEL_CHARS {
        return Err(DamError::BadRequest(format!("text of 1 to {} characters", crate::smarttags::MAX_LABEL_CHARS)));
    }
    let model = tagger.spec().model;
    let (embedding, scale, bias) = cpu(&state, move || tagger.text_embedding(&text)).await?;
    Ok(Json(json!({ "model": model, "embedding": embedding, "scale": scale, "bias": bias })))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ProcessRequest {
    blob_id: String,
    mime_type: Option<String>,
    /// renditions of the folder's processing profile
    #[serde(default)]
    extra: Vec<ExtraRendition>,
    /// drawn on the `extra` renditions that ask for it
    watermark: Option<WatermarkRequest>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct WatermarkRequest {
    blob_id: String,
    position: Option<String>,
    opacity: Option<u32>,
    size: Option<u32>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ExtraRendition {
    name: String,
    width: u32,
    height: u32,
    /// jpeg (default), png or webp
    format: Option<String>,
    quality: Option<u8>,
    #[serde(default)]
    watermark: bool,
}

fn extra_specs(extra: &[ExtraRendition]) -> Result<Vec<crate::contract::RenditionSpec>, DamError> {
    use crate::contract::{OutFormat, RenditionSpec, MAX_CUSTOM_RENDITIONS};
    if extra.len() > MAX_CUSTOM_RENDITIONS {
        return Err(DamError::BadRequest(format!("at most {MAX_CUSTOM_RENDITIONS} extra renditions")));
    }
    let mut names = std::collections::HashSet::new();
    extra
        .iter()
        .map(|e| {
            let q = e.quality.unwrap_or(crate::contract::WEB_QUALITY).clamp(1, 100);
            let format = match e.format.as_deref().unwrap_or("jpeg") {
                "jpeg" | "jpg" => OutFormat::Jpeg { quality: q },
                "png" => OutFormat::Png,
                "webp" => OutFormat::WebP { quality: q },
                other => return Err(DamError::BadRequest(format!("unknown format {other:?}"))),
            };
            let mut spec = RenditionSpec::custom(&e.name, e.width, e.height, format).map_err(DamError::BadRequest)?;
            spec.watermark = e.watermark;
            if !names.insert(spec.name()) {
                return Err(DamError::BadRequest(format!("rendition {} listed twice", spec.name())));
            }
            Ok(spec)
        })
        .collect()
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct RenditionJson {
    name: String,
    blob_id: String,
    mime_type: &'static str,
    size: usize,
    sha1: String,
    width: u32,
    height: u32,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ProcessResponse {
    blob_id: String,
    mime_type: String,
    metadata: std::collections::BTreeMap<String, Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    xmp_packet: Option<String>,
    renditions: Vec<RenditionJson>,
    failed: Vec<FailedRendition>,
    elapsed_ms: u128,
}

async fn process_asset(
    State(state): State<Shared>,
    headers: HeaderMap,
    Json(req): Json<ProcessRequest>,
) -> Result<Json<ProcessResponse>, DamError> {
    check_bearer(&state, &headers)?;
    let id = BlobId::parse(&req.blob_id)?;
    let extra = extra_specs(&req.extra)?;
    let mark_req = match &req.watermark {
        Some(w) => Some((
            BlobId::parse(&w.blob_id)?,
            crate::watermark::Position::parse(w.position.as_deref().unwrap_or("center"))?,
            w.opacity.unwrap_or(50),
            w.size.unwrap_or(25),
        )),
        None => None,
    };
    let started = Instant::now();
    let st = state.clone();
    let response = cpu(&state, move || {
        let bytes = st.originals.read_mapped(&id, st.cfg.max_upload_bytes as u64)?;
        let mark = match mark_req {
            Some((mid, position, opacity, size)) => {
                let mb = st.originals.read_mapped(&mid, st.cfg.max_upload_bytes as u64)?;
                let decoded = process::decode(&mb, st.cfg.max_source_pixels)?;
                Some(crate::watermark::Watermark::new(decoded.image, position, opacity, size))
            }
            None => None,
        };
        let out = process::process_marked(&bytes, req.mime_type.as_deref(), st.cfg.max_source_pixels, &extra, mark.as_ref())?;
        let mut renditions = Vec::with_capacity(out.renditions.len());
        for r in out.renditions {
            let blob = st.outputs.put(&r.bytes)?;
            renditions.push(RenditionJson {
                name: r.name,
                blob_id: blob.to_string(),
                mime_type: r.mime,
                size: r.bytes.len(),
                sha1: r.sha1,
                width: r.width,
                height: r.height,
            });
        }
        Ok(ProcessResponse {
            blob_id: id.to_string(),
            mime_type: out.mime,
            metadata: out.metadata,
            xmp_packet: out.xmp_packet,
            renditions,
            failed: out.failed,
            elapsed_ms: 0,
        })
    })
    .await?;
    let elapsed_ms = started.elapsed().as_millis();
    tracing::info!(blob = %response.blob_id, renditions = response.renditions.len(), elapsed_ms, "processed");
    Ok(Json(ProcessResponse { elapsed_ms, ..response }))
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct EditRequest {
    blob_id: String,
    operations: Vec<Operation>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct EditResponse {
    /// The edited original, stored next to the other originals.
    blob_id: String,
    mime_type: &'static str,
    width: u32,
    height: u32,
    size: usize,
}

async fn edit_asset(
    State(state): State<Shared>,
    headers: HeaderMap,
    Json(req): Json<EditRequest>,
) -> Result<Json<EditResponse>, DamError> {
    check_bearer(&state, &headers)?;
    let id = BlobId::parse(&req.blob_id)?;
    let st = state.clone();
    let response = cpu(&state, move || {
        let bytes = st.originals.read_mapped(&id, st.cfg.max_upload_bytes as u64)?;
        let out = edit::edit(&bytes, &req.operations, st.cfg.max_source_pixels)?;
        let blob = st.originals.put(&out.bytes)?;
        Ok(EditResponse { blob_id: blob.to_string(), mime_type: out.mime, width: out.width, height: out.height, size: out.bytes.len() })
    })
    .await?;
    tracing::info!(from = %req.blob_id, to = %response.blob_id, "edited");
    Ok(Json(response))
}

#[derive(Deserialize)]
struct DeliverQuery {
    q: Option<u8>,
    /// Optional transform, see [`crate::delivery`].
    t: Option<String>,
    sig: Option<String>,
}

async fn deliver(
    State(state): State<Shared>,
    Path((id, spec)): Path<(String, String)>,
    Query(query): Query<DeliverQuery>,
) -> Result<Response, DamError> {
    let req = DeliveryRequest::parse(&id, &spec, query.q, state.cfg.max_delivery_dimension)?.with_transform(query.t.as_deref())?;
    let sig = query.sig.as_deref().ok_or(DamError::Forbidden)?;
    if !delivery::verify(&state.cfg.token, &req.canonical(), sig) {
        return Err(DamError::Forbidden);
    }

    // Never upscale: clamp the box to the source size before it becomes a cache key, so widths
    // above the original all share one cache entry. Only the header is read here.
    let source = state.originals.path_of(&req.blob);
    let dims = tokio::task::spawn_blocking(move || -> Result<(u32, u32), DamError> {
        match image::ImageReader::open(&source) {
            Ok(r) => Ok(r.with_guessed_format()?.into_dimensions()?),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Err(DamError::NotFound),
            Err(e) => Err(DamError::Io(e)),
        }
    })
    .await
    .map_err(|e| DamError::Internal(e.to_string()))??;
    // The header has the stored size; with an EXIF turn the displayed one is swapped, so take
    // whichever is larger (the clamp only has to be an upper bound).
    let longest = req.longest_side_after(dims.0, dims.1).max(req.longest_side_after(dims.1, dims.0));
    let req = if req.upscale() || req.fit_mode() != delivery::FitMode::Fit { req } else { req.clamped_to(longest.max(1)) };

    let path = req.cache_path(&state.cfg.cache);
    let mime = req.format.mime();
    if let Ok(bytes) = tokio::fs::read(&path).await {
        let p = path.clone();
        tokio::task::spawn_blocking(move || crate::housekeeping::touch(&p));
        return Ok(image_response(bytes, mime, "hit"));
    }

    // One generation per cache key; later arrivals wait and then read the cached file.
    let key = req.canonical();
    let lock = {
        let mut map = state.inflight.lock().expect("inflight lock");
        map.entry(key.clone()).or_default().clone()
    };
    let _guard = lock.lock().await;
    let result = async {
        if let Ok(bytes) = tokio::fs::read(&path).await {
            return Ok((bytes, "hit"));
        }
        let st = state.clone();
        let bytes = cpu(&state, move || {
            let original = st.originals.read_mapped(&req.blob, st.cfg.max_upload_bytes as u64)?;
            let out = process::derive_transformed(&original, &req, st.cfg.max_source_pixels)?;
            let dir = path.parent().expect("cache path has a parent");
            std::fs::create_dir_all(dir)?;
            let tmp = dir.join(unique_tmp_name("cache"));
            std::fs::write(&tmp, &out)?;
            std::fs::rename(&tmp, &path)?;
            Ok(out)
        })
        .await?;
        Ok::<_, DamError>((bytes, "miss"))
    }
    .await;
    {
        let mut map = state.inflight.lock().expect("inflight lock");
        if Arc::strong_count(&lock) <= 2 {
            map.remove(&key);
        }
    }
    let (bytes, cache) = result?;
    Ok(image_response(bytes, mime, cache))
}

fn image_response(bytes: Vec<u8>, mime: &'static str, cache: &'static str) -> Response {
    let mut res = Response::new(Body::from(bytes));
    let h = res.headers_mut();
    h.insert(header::CONTENT_TYPE, HeaderValue::from_static(mime));
    // The URL embeds the content hash and every parameter, so it never changes meaning.
    h.insert(header::CACHE_CONTROL, HeaderValue::from_static("public, max-age=31536000, immutable"));
    h.insert("x-studio-cache", HeaderValue::from_static(cache));
    res
}
