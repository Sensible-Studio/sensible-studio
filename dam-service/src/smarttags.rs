//! Smart Tags: which tags of the taxonomy fit an image, without training.
//!
//! [SigLIP](https://huggingface.co/google/siglip-base-patch16-224) (Google, Apache-2.0) puts
//! images and short texts in the same vector space; a tag fits an image when the image is close
//! to "This is a photo of <tag title>.". It runs on the CPU with
//! [candle](https://github.com/huggingface/candle) (Apache-2.0/MIT), a few hundred ms per image.
//!
//! The model is not part of the binary: Sensible Studio downloads it once (Tools › Smart Tags) and uploads
//! the files here (`PUT /smart-tags/model/{file}`); each file must match the size and SHA-256
//! pinned below, so nothing else can be loaded as the model.

use std::collections::HashMap;
use std::fs::{self, File};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

use candle_core::{DType, Device, Tensor, D};
use candle_nn::VarBuilder;
use candle_transformers::models::siglip;
use image::DynamicImage;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::DamError;

pub const MODEL: &str = "siglip-base-patch16-224";
/// The Hugging Face repository and the commit the hashes below belong to.
pub const REPOSITORY: &str = "google/siglip-base-patch16-224";
pub const REVISION: &str = "7fd15f0689c79d79e38b1c2e2e2370a7bf2761ed";

pub struct ModelFile {
    pub name: &'static str,
    pub size: u64,
    pub sha256: &'static str,
}

pub const FILES: [ModelFile; 3] = [
    ModelFile { name: "config.json", size: 432, sha256: "cd85b3d28829722820bcb89a2cfbb4160e55fd359249a3044da724166a8d9688" },
    ModelFile { name: "tokenizer.json", size: 2_399_357, sha256: "c6e405cb7c670d56636a9402c81023a55bc6c3c53d89cf02b92f5c5005bfe920" },
    ModelFile {
        name: "model.safetensors",
        size: 812_672_320,
        sha256: "2c63cb7d1f2e95ba501893cbb8faeb4ea9a3af295498d35097126228659c2af8",
    },
];

/// A SigLIP model this service can run: where it comes from, the files pinned by size and SHA-256,
/// and how it reads pictures and words.
pub struct Spec {
    pub model: &'static str,
    pub repository: &'static str,
    pub revision: &'static str,
    pub files: &'static [ModelFile],
    /// the side of the square picture the vision tower takes
    pub image_size: u32,
    /// English-only models compare a picture with "This is a photo of …"; the multilingual one
    /// with the words as written (lower case, as its tokenizer was trained)
    pub english_prompt: bool,
    /// what the logs and errors call it
    pub purpose: &'static str,
}

/// Smart Tags, focus crops, "find similar" and English picture search.
pub static TAGS: Spec = Spec {
    model: MODEL,
    repository: REPOSITORY,
    revision: REVISION,
    files: &FILES,
    image_size: 224,
    english_prompt: true,
    purpose: "smart tags",
};

/// Picture search in any language (about 100 languages; Apache-2.0). Optional: 1.5 GB.
pub static MULTILINGUAL: Spec = Spec {
    model: "siglip-base-patch16-256-multilingual",
    repository: "google/siglip-base-patch16-256-multilingual",
    revision: "8952a4eafcde3cb7ab46b1dd629b33f8784ca9c6",
    files: &[
        ModelFile { name: "config.json", size: 348, sha256: "af92473ce89269ba92b2fb33466179aef6ceeb62d3c2f4db55da9a3380e67320" },
        ModelFile { name: "tokenizer.json", size: 16_350_281, sha256: "babbf9fed5f505ac6b28fd820b5108ca6882332ce6b921c8d33f8ff69bf841d9" },
        ModelFile {
            name: "model.safetensors",
            size: 1_482_553_200,
            sha256: "00df88d823790b34a4d2d0047803ffabd47497ae724fd6659b1533dd793255be",
        },
    ],
    image_size: 256,
    english_prompt: false,
    purpose: "picture search",
};

/// The text length SigLIP was trained with (padded).
const TEXT_LEN: usize = 64;
const PAD_ID: u32 = 1;
const TEXT_BATCH: usize = 32;
/// Text embeddings kept in memory (one per label text); cleared when full.
const TEXT_CACHE: usize = 20_000;
pub const MAX_LABELS: usize = 5_000;
pub const MAX_LABEL_CHARS: usize = 200;

#[derive(Clone, Debug, Deserialize)]
pub struct Label {
    /// returned as is (a tag id such as `wknd-shared:activity/surfing`)
    pub id: String,
    /// what the tag means, in a few words ("Surfing")
    pub text: String,
}

#[derive(Clone, Debug, Serialize, PartialEq)]
pub struct Prediction {
    pub id: String,
    /// share of this label among all the labels (softmax), 0..1: what the console shows
    pub confidence: f32,
    /// how well the label fits on its own (sigmoid), 0..1
    pub score: f32,
}

struct Loaded {
    model: siglip::Model,
    scale: f32,
    bias: f32,
    tokenizer: tokenizers::Tokenizer,
    /// the vision tower without its pooling head: one token per patch
    patches: siglip::VisionModel,
    /// the pooling head's probe and q/k projections, to read where it looks
    probe: Tensor,
    wq: Tensor,
    bq: Tensor,
    wk: Tensor,
    bk: Tensor,
    heads: usize,
    image_size: u32,
    english_prompt: bool,
}

pub struct SmartTagger {
    spec: &'static Spec,
    dir: PathBuf,
    loaded: Mutex<Option<Arc<Loaded>>>,
    texts: Mutex<HashMap<String, Arc<Vec<f32>>>>,
    /// files being uploaded, so two uploads of one file do not interleave
    installing: Mutex<()>,
    /// when the model was last used, to give its ~800 MB back after a quiet while
    last_used: Mutex<std::time::Instant>,
}

fn internal(e: impl std::fmt::Display) -> DamError {
    DamError::Internal(format!("smart tags: {e}"))
}

impl SmartTagger {
    /// `models` is the directory of all models; this one lives in `<models>/<MODEL>`.
    pub fn new(models: &Path) -> Self {
        Self::of(&TAGS, models)
    }

    /// The model `spec`, kept in `<models>/<spec.model>`.
    pub fn of(spec: &'static Spec, models: &Path) -> Self {
        SmartTagger {
            spec,
            dir: models.join(spec.model),
            loaded: Mutex::new(None),
            texts: Mutex::new(HashMap::new()),
            installing: Mutex::new(()),
            last_used: Mutex::new(std::time::Instant::now()),
        }
    }

    fn file(&self, f: &ModelFile) -> PathBuf {
        self.dir.join(f.name)
    }

    /// Every file present with its expected size (contents were checked when uploaded).
    pub fn installed(&self) -> bool {
        self.spec.files.iter().all(|f| fs::metadata(self.file(f)).map(|m| m.len() == f.size).unwrap_or(false))
    }

    pub fn status(&self) -> serde_json::Value {
        let spec = self.spec;
        let files: Vec<_> = spec
            .files
            .iter()
            .map(|f| {
                let present = fs::metadata(self.file(f)).map(|m| m.len()).unwrap_or(0);
                serde_json::json!({ "name": f.name, "size": f.size, "present": present == f.size,
                    "url": format!("https://huggingface.co/{}/resolve/{}/{}", spec.repository, spec.revision, f.name), "sha256": f.sha256 })
            })
            .collect();
        serde_json::json!({
            "model": spec.model,
            "repository": spec.repository,
            "revision": spec.revision,
            "license": "Apache-2.0",
            "installed": self.installed(),
            "loaded": self.loaded.lock().map(|l| l.is_some()).unwrap_or(false),
            "files": files,
        })
    }

    /// Stores one model file from `chunks`, checking size and SHA-256 before it replaces anything.
    pub fn install<I>(&self, name: &str, chunks: I) -> Result<(), DamError>
    where
        I: IntoIterator<Item = Result<Vec<u8>, DamError>>,
    {
        let f = self.spec.files.iter().find(|f| f.name == name).ok_or(DamError::NotFound)?;
        let _one = self.installing.lock().map_err(internal)?;
        fs::create_dir_all(&self.dir)?;
        let tmp = self.dir.join(crate::server::unique_tmp_name(f.name));
        let result = (|| {
            let mut out = File::create(&tmp)?;
            let mut hash = Sha256::new();
            let mut size = 0u64;
            for chunk in chunks {
                let chunk = chunk?;
                size += chunk.len() as u64;
                if size > f.size {
                    return Err(DamError::BadRequest(format!("{} is larger than {} bytes", f.name, f.size)));
                }
                hash.update(&chunk);
                out.write_all(&chunk)?;
            }
            out.sync_all()?;
            if size != f.size || hex::encode(hash.finalize()) != f.sha256 {
                return Err(DamError::BadRequest(format!("{} does not match the pinned size and SHA-256", f.name)));
            }
            Ok(())
        })();
        match result {
            Ok(()) => {
                fs::rename(&tmp, self.file(f))?;
                *self.loaded.lock().map_err(internal)? = None;
                Ok(())
            }
            Err(e) => {
                let _ = fs::remove_file(&tmp);
                Err(e)
            }
        }
    }

    /// Drops the loaded model when it has not been used for `idle`; the next prediction loads it
    /// again (about a second). Returns whether it was dropped. Predictions running now keep their
    /// own reference, so they finish normally.
    pub fn unload_if_idle(&self, idle: std::time::Duration) -> bool {
        let quiet = self.last_used.lock().map(|t| t.elapsed() >= idle).unwrap_or(false);
        if !quiet {
            return false;
        }
        match self.loaded.lock() {
            Ok(mut slot) if slot.is_some() => {
                *slot = None;
                if let Ok(mut t) = self.texts.lock() {
                    t.clear();
                }
                tracing::info!("{}: unloaded {} after {}s unused", self.spec.purpose, self.spec.model, idle.as_secs());
                true
            }
            _ => false,
        }
    }

    pub fn is_loaded(&self) -> bool {
        self.loaded.lock().map(|l| l.is_some()).unwrap_or(false)
    }

    fn load(&self) -> Result<Arc<Loaded>, DamError> {
        if let Ok(mut t) = self.last_used.lock() {
            *t = std::time::Instant::now();
        }
        let mut slot = self.loaded.lock().map_err(internal)?;
        if let Some(l) = slot.as_ref() {
            return Ok(l.clone());
        }
        if !self.installed() {
            return Err(DamError::NotReady(format!("the {} model is not installed", self.spec.purpose)));
        }
        let dev = Device::Cpu;
        // SAFETY: the file is ours, complete (size checked) and not modified while mapped:
        // a new upload goes to a temporary file and replaces it with a rename.
        let vb = unsafe { VarBuilder::from_mmaped_safetensors(&[self.dir.join("model.safetensors")], DType::F32, &dev) }
            .map_err(internal)?;
        let cfg = self.config()?;
        let model = siglip::Model::new(&cfg, vb.clone()).map_err(internal)?;
        let scale = vb.get(1, "logit_scale").and_then(|t| t.exp()).and_then(|t| t.to_vec1::<f32>()).map_err(internal)?[0];
        let bias = vb.get(1, "logit_bias").and_then(|t| t.to_vec1::<f32>()).map_err(internal)?[0];
        let tokenizer = tokenizers::Tokenizer::from_file(self.dir.join("tokenizer.json")).map_err(internal)?;
        let patches = siglip::VisionModel::new(&cfg.vision_config, false, vb.pp("vision_model")).map_err(internal)?;
        let h = cfg.vision_config.hidden_size;
        let head = vb.pp("vision_model.head");
        let probe = head.get((1, 1, h), "probe").map_err(internal)?;
        let w = head.get((3 * h, h), "attention.in_proj_weight").and_then(|t| t.chunk(3, 0)).map_err(internal)?;
        let b = head.get(3 * h, "attention.in_proj_bias").and_then(|t| t.chunk(3, 0)).map_err(internal)?;
        let l = Arc::new(Loaded {
            model,
            scale,
            bias,
            tokenizer,
            patches,
            probe,
            wq: w[0].clone(),
            bq: b[0].clone(),
            wk: w[1].clone(),
            bk: b[1].clone(),
            heads: cfg.vision_config.num_attention_heads,
            image_size: self.spec.image_size,
            english_prompt: self.spec.english_prompt,
        });
        *slot = Some(l.clone());
        tracing::info!("{}: loaded {}", self.spec.purpose, self.spec.model);
        Ok(l)
    }

    /// The model's shape: the base model's is built in, others come from their pinned config.json.
    fn config(&self) -> Result<siglip::Config, DamError> {
        if std::ptr::eq(self.spec, &TAGS) {
            return Ok(siglip::Config::base_patch16_224());
        }
        let text = fs::read_to_string(self.dir.join("config.json"))?;
        serde_json::from_str(&text).map_err(internal)
    }

    pub fn spec(&self) -> &'static Spec {
        self.spec
    }

    /// Normalised embeddings of `texts`, computed in batches for those not cached yet.
    fn text_embeddings(&self, m: &Loaded, texts: &[String]) -> Result<Vec<Arc<Vec<f32>>>, DamError> {
        let missing: Vec<&String> = {
            let cache = self.texts.lock().map_err(internal)?;
            let mut seen = std::collections::HashSet::new();
            texts.iter().filter(|t| !cache.contains_key(*t) && seen.insert(*t)).collect()
        };
        for batch in missing.chunks(TEXT_BATCH) {
            let mut ids = Vec::with_capacity(batch.len() * TEXT_LEN);
            for t in batch {
                let enc = m.tokenizer.encode(t.as_str(), true).map_err(internal)?;
                let mut v = enc.get_ids().to_vec();
                v.truncate(TEXT_LEN);
                v.resize(TEXT_LEN, PAD_ID);
                ids.extend(v);
            }
            let input = Tensor::from_vec(ids, (batch.len(), TEXT_LEN), &Device::Cpu).map_err(internal)?;
            let rows = normalize(&m.model.get_text_features(&input).map_err(internal)?)?.to_vec2::<f32>().map_err(internal)?;
            let mut cache = self.texts.lock().map_err(internal)?;
            if cache.len() + rows.len() > TEXT_CACHE {
                cache.clear();
            }
            for (t, row) in batch.iter().zip(rows) {
                cache.insert((*t).clone(), Arc::new(row));
            }
        }
        let cache = self.texts.lock().map_err(internal)?;
        texts.iter().map(|t| cache.get(t).cloned().ok_or_else(|| internal("text cache"))).collect()
    }

    /// Where the model looks: the pooling head's attention over the patches of `image`, as a
    /// square map (side × side, row-major, 0..=1). `None` when the model is not installed, so
    /// callers can fall back to something cheaper.
    pub fn attention_map(&self, image: &DynamicImage) -> Result<Option<(usize, Vec<f32>)>, DamError> {
        if !self.installed() {
            return Ok(None);
        }
        let m = self.load()?;
        attention_map(&m, image).map(Some)
    }

    /// The normalised embedding of an image (also the basis of "find similar").
    pub fn image_embedding(&self, image: &DynamicImage) -> Result<Vec<f32>, DamError> {
        let m = self.load()?;
        image_features(&m, image)
    }

    /// The embedding of a phrase, in the space of [`Self::image_embedding`], with the model's
    /// scale and bias: `sigmoid(scale * dot(text, image) + bias)` is how well a picture fits it.
    /// The basis of searching pictures by words.
    pub fn text_embedding(&self, text: &str) -> Result<(Vec<f32>, f32, f32), DamError> {
        let m = self.load()?;
        let phrase = if m.english_prompt { prompt(text) } else { plain(text) };
        let mut emb = self.text_embeddings(&m, &[phrase])?;
        let e = emb.pop().ok_or_else(|| internal("no text embedding"))?;
        Ok((e.as_ref().clone(), m.scale, m.bias))
    }

    /// The labels that fit `image` best: at most `max`, each with at least `min_confidence`
    /// of the softmax over all labels and a sigmoid score of at least `min_score`.
    pub fn predict(
        &self,
        image: &DynamicImage,
        labels: &[Label],
        max: usize,
        min_confidence: f32,
        min_score: f32,
    ) -> Result<Vec<Prediction>, DamError> {
        if labels.is_empty() {
            return Ok(vec![]);
        }
        let m = self.load()?;
        let img = image_features(&m, image)?;
        let texts: Vec<String> = labels.iter().map(|l| prompt(&l.text)).collect();
        let emb = self.text_embeddings(&m, &texts)?;
        let logits: Vec<f32> = emb.iter().map(|t| m.scale * dot(t, &img) + m.bias).collect();
        Ok(select(labels, &logits, max, min_confidence, min_score))
    }
}

/// The words as the multilingual model reads them: lower case, at most the label length.
pub fn plain(text: &str) -> String {
    text.trim().chars().take(MAX_LABEL_CHARS).collect::<String>().to_lowercase()
}

/// The sentence SigLIP compares an image with.
pub fn prompt(text: &str) -> String {
    let t: String = text.trim().chars().take(MAX_LABEL_CHARS).collect();
    format!("This is a photo of {t}.")
}

fn dot(a: &[f32], b: &[f32]) -> f32 {
    a.iter().zip(b).map(|(x, y)| x * y).sum()
}

fn normalize(t: &Tensor) -> Result<Tensor, DamError> {
    t.broadcast_div(&t.sqr().and_then(|s| s.sum_keepdim(D::Minus1)).and_then(|s| s.sqrt()).map_err(internal)?)
        .map_err(internal)
}

/// SigLIP's input: 224 x 224 RGB, values scaled to -1..1 (mean 0.5, std 0.5).
pub fn pixels(image: &DynamicImage) -> Result<Tensor, DamError> {
    pixels_of(image, 224)
}

/// [`pixels`] for a vision tower that takes `size` x `size`.
pub fn pixels_of(image: &DynamicImage, size: u32) -> Result<Tensor, DamError> {
    let rgb = DynamicImage::ImageRgb8(image.to_rgb8());
    let small = crate::process::resize(&rgb, size, size)?.to_rgb8();
    let data: Vec<f32> = small.into_raw().into_iter().map(|p| p as f32 / 127.5 - 1.0).collect();
    Tensor::from_vec(data, (size as usize, size as usize, 3), &Device::Cpu)
        .and_then(|t| t.permute((2, 0, 1)))
        .and_then(|t| t.unsqueeze(0))
        .map_err(internal)
}

/// The head's attention weights, averaged over its heads, as a side×side map scaled to 0..=1.
fn attention_map(m: &Loaded, image: &DynamicImage) -> Result<(usize, Vec<f32>), DamError> {
    use candle_core::Module;
    let tokens = m.patches.forward(&pixels_of(image, m.image_size)?).map_err(internal)?; // (1, n, h)
    let (_, n, h) = tokens.dims3().map_err(internal)?;
    let d = h / m.heads;
    let q = m.probe.squeeze(0).and_then(|p| p.matmul(&m.wq.t()?)).and_then(|t| t.broadcast_add(&m.bq)).map_err(internal)?; // (1, h)
    let k = tokens.squeeze(0).and_then(|t| t.matmul(&m.wk.t()?)).and_then(|t| t.broadcast_add(&m.bk)).map_err(internal)?; // (n, h)
    let q = q.reshape((m.heads, 1, d)).map_err(internal)?;
    let k = k.reshape((n, m.heads, d)).and_then(|t| t.transpose(0, 1)).and_then(|t| t.contiguous()).map_err(internal)?; // (heads, n, d)
    let attn = q.matmul(&k.t().map_err(internal)?).and_then(|t| t / (d as f64).sqrt()).map_err(internal)?; // (heads, 1, n)
    let attn = candle_nn::ops::softmax_last_dim(&attn).and_then(|t| t.mean(0)).and_then(|t| t.squeeze(0)).map_err(internal)?; // (n)
    let mut v = attn.to_vec1::<f32>().map_err(internal)?;
    let side = (n as f64).sqrt().round() as usize;
    if side * side != n {
        return Err(internal(format!("{n} patches do not form a square")));
    }
    let max = v.iter().cloned().fold(0f32, f32::max).max(1e-9);
    for x in &mut v {
        *x /= max;
    }
    Ok((side, v))
}

fn image_features(m: &Loaded, image: &DynamicImage) -> Result<Vec<f32>, DamError> {
    let f = m.model.get_image_features(&pixels_of(image, m.image_size)?).map_err(internal)?;
    normalize(&f)?.squeeze(0).and_then(|t| t.to_vec1::<f32>()).map_err(internal)
}

/// Best labels first; see [`SmartTagger::predict`].
pub fn select(labels: &[Label], logits: &[f32], max: usize, min_confidence: f32, min_score: f32) -> Vec<Prediction> {
    let top = logits.iter().cloned().fold(f32::NEG_INFINITY, f32::max);
    let sum: f32 = logits.iter().map(|l| (l - top).exp()).sum();
    let mut order: Vec<usize> = (0..labels.len()).collect();
    order.sort_by(|a, b| logits[*b].total_cmp(&logits[*a]));
    let mut out = Vec::new();
    for i in order {
        let confidence = (logits[i] - top).exp() / sum;
        let score = 1.0 / (1.0 + (-logits[i]).exp());
        if out.len() >= max || confidence < min_confidence || score < min_score || !confidence.is_finite() {
            break;
        }
        if out.iter().any(|p: &Prediction| p.id == labels[i].id) {
            continue;
        }
        out.push(Prediction { id: labels[i].id.clone(), confidence, score });
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn labels(ids: &[&str]) -> Vec<Label> {
        ids.iter().map(|i| Label { id: i.to_string(), text: i.to_string() }).collect()
    }

    #[test]
    fn selects_the_labels_that_stand_out() {
        let l = labels(&["skiing", "snow", "beach", "city"]);
        // sigmoid ≈ 0.19, 0.012, 0.0001, 0.00001
        let p = select(&l, &[-1.46, -4.4, -9.2, -11.5], 5, 0.05, 1e-4);
        assert_eq!(p.iter().map(|p| p.id.as_str()).collect::<Vec<_>>(), ["skiing", "snow"]);
        assert!(p[0].confidence > 0.9 && (p[0].score - 0.188).abs() < 0.01);
        assert_eq!(select(&l, &[-1.46, -4.4, -9.2, -11.5], 1, 0.0, 0.0).len(), 1);
        // nothing fits: every score is tiny even if one is the "best"
        assert!(select(&l, &[-12.0, -13.0, -14.0, -15.0], 5, 0.05, 1e-4).is_empty());
        assert!(select(&[], &[], 5, 0.05, 1e-4).is_empty());
    }

    #[test]
    fn prompts_are_short_sentences() {
        assert_eq!(prompt("  Surfing "), "This is a photo of Surfing.");
        assert_eq!(prompt(&"x".repeat(500)).len(), "This is a photo of .".len() + MAX_LABEL_CHARS);
    }

    #[test]
    fn pixels_are_scaled_to_minus_one_one() {
        let img = DynamicImage::ImageRgb8(image::RgbImage::from_pixel(300, 100, image::Rgb([255, 0, 128])));
        let t = pixels(&img).unwrap();
        assert_eq!(t.dims(), &[1, 3, 224, 224]);
        let v: Vec<f32> = t.flatten_all().unwrap().to_vec1().unwrap();
        assert!((v[0] - 1.0).abs() < 1e-3); // red channel
        assert!((v[224 * 224] + 1.0).abs() < 1e-3); // green
        assert!(v[2 * 224 * 224].abs() < 0.01); // blue 128
    }

    #[test]
    fn installs_only_the_pinned_files() {
        let dir = tempfile::tempdir().unwrap();
        let t = SmartTagger::new(dir.path());
        assert!(!t.installed());
        assert!(matches!(t.install("evil.bin", vec![Ok(vec![1])]), Err(DamError::NotFound)));
        // right size, wrong content: refused and nothing left behind
        let r = t.install("config.json", vec![Ok(vec![b' '; 432])]);
        assert!(matches!(r, Err(DamError::BadRequest(_))), "{r:?}");
        assert!(t.install("config.json", vec![Ok(vec![0; 500])]).is_err());
        assert_eq!(fs::read_dir(dir.path().join(MODEL)).unwrap().count(), 0);
        assert!(matches!(t.predict(&DynamicImage::new_rgb8(4, 4), &labels(&["a"]), 3, 0.0, 0.0), Err(DamError::NotReady(_))));
        assert_eq!(t.status()["installed"], false);
    }

    /// With the real model (STUDIO_TEST_SIGLIP=<dir with the three files>): a skier is tagged skiing.
    #[test]
    fn tags_a_photo_with_the_real_model() {
        let Ok(dir) = std::env::var("STUDIO_TEST_SIGLIP") else { return };
        let models = tempfile::tempdir().unwrap();
        let t = SmartTagger::new(models.path());
        for f in FILES.iter() {
            let bytes = fs::read(Path::new(&dir).join(f.name)).unwrap();
            t.install(f.name, bytes.chunks(1 << 20).map(|c| Ok(c.to_vec()))).unwrap();
        }
        assert!(t.installed());
        let img = image::load_from_memory(&fs::read("tests/fixtures/wknd/freeride.jpg").unwrap()).unwrap();
        let l = labels(&["skiing", "surfing", "food", "city", "beach", "snow"]);
        let p = t.predict(&img, &l, 3, 0.05, 1e-4).unwrap();
        assert_eq!(p[0].id, "skiing", "{p:?}");
        assert_eq!(t.image_embedding(&img).unwrap().len(), 768);
    }

    /// With the real multilingual model (STUDIO_TEST_SIGLIP_ML=<dir with its three files>): the
    /// same phrase in four languages finds the skier rather than the surfer.
    #[test]
    fn finds_a_photo_in_any_language_with_the_real_model() {
        let Ok(dir) = std::env::var("STUDIO_TEST_SIGLIP_ML") else { return };
        let models = tempfile::tempdir().unwrap();
        let t = SmartTagger::of(&MULTILINGUAL, models.path());
        for f in MULTILINGUAL.files.iter() {
            let bytes = fs::read(Path::new(&dir).join(f.name)).unwrap();
            t.install(f.name, bytes.chunks(1 << 20).map(|c| Ok(c.to_vec()))).unwrap();
        }
        assert!(t.installed());
        let fixtures = Path::new("tests/fixtures/wknd");
        let mut photos = vec![];
        for e in fs::read_dir(fixtures).unwrap() {
            let p = e.unwrap().path();
            if p.extension().map(|x| x == "jpg").unwrap_or(false) {
                let img = image::load_from_memory(&fs::read(&p).unwrap()).unwrap();
                photos.push((p.file_name().unwrap().to_string_lossy().to_string(), t.image_embedding(&img).unwrap()));
            }
        }
        for phrase in ["a person skiing in the snow", "o persoană care schiază pe zăpadă", "eine Person fährt Ski im Schnee", "una persona che scia sulla neve"] {
            let (e, scale, bias) = t.text_embedding(phrase).unwrap();
            let mut scored: Vec<(f32, &str)> = photos.iter().map(|(n, p)| (scale * dot(&e, p) + bias, n.as_str())).collect();
            scored.sort_by(|a, b| b.0.total_cmp(&a.0));
            eprintln!("{phrase}: {:?}", &scored[..scored.len().min(3)]);
            assert_eq!(scored[0].1, "freeride.jpg", "{phrase}: {scored:?}");
        }
    }
}
