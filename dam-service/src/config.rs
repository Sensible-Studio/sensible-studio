use std::net::SocketAddr;
use std::path::PathBuf;

/// Runtime configuration. Secrets come from the environment, never from argv
/// (argv is visible to every user through `ps`).
#[derive(Clone, Debug)]
pub struct Config {
    /// Listen address. Defaults to loopback: in embedded mode only Sensible Studio talks to us.
    pub bind: SocketAddr,
    /// Where original binaries are read from (Oak FileDataStore layout).
    pub datastore: PathBuf,
    /// Where rendition binaries are written. Same layout; may equal `datastore`
    /// so Oak deduplicates when the Java side imports them.
    pub output: PathBuf,
    /// Cache directory for on-the-fly delivery.
    pub cache: PathBuf,
    /// The delivery cache is trimmed to this size (least recently used first)...
    pub cache_max_bytes: u64,
    /// ...and entries not used for this long are dropped.
    pub cache_max_age: std::time::Duration,
    /// The Smart Tags model leaves memory after this long unused.
    pub model_idle: std::time::Duration,
    /// Shared secret: bearer token for the API and HMAC key for delivery URLs.
    pub token: String,
    /// Largest width/height accepted for on-the-fly delivery.
    pub max_delivery_dimension: u32,
    /// Largest source image accepted, in pixels (decompression-bomb guard).
    pub max_source_pixels: u64,
    /// Largest upload accepted through `PUT /blobs`, in bytes.
    pub max_upload_bytes: usize,
    /// Concurrent CPU-bound jobs.
    pub workers: usize,
    /// Machine-learning models (Smart Tags), downloaded by Sensible Studio on request.
    pub models: PathBuf,
}

impl Config {
    pub fn with_dirs(root: impl Into<PathBuf>, token: impl Into<String>) -> Self {
        let root = root.into();
        Config {
            bind: "127.0.0.1:4510".parse().unwrap(),
            datastore: root.join("datastore"),
            output: root.join("datastore"),
            cache: root.join("cache"),
            cache_max_bytes: 2 * 1024 * 1024 * 1024,
            cache_max_age: std::time::Duration::from_secs(30 * 86_400),
            model_idle: std::time::Duration::from_secs(600),
            models: root.join("models"),
            token: token.into(),
            max_delivery_dimension: 4096,
            max_source_pixels: 150_000_000,
            max_upload_bytes: 512 * 1024 * 1024,
            workers: std::thread::available_parallelism().map(|n| n.get()).unwrap_or(2),
        }
    }

    /// Parse `--flag value` pairs plus `STUDIO_DAM_TOKEN` from the environment.
    pub fn from_args(args: impl IntoIterator<Item = String>) -> Result<Self, String> {
        let token = std::env::var("STUDIO_DAM_TOKEN")
            .map_err(|_| "STUDIO_DAM_TOKEN must be set (shared secret with Sensible Studio)".to_string())?;
        if token.len() < 16 {
            return Err("STUDIO_DAM_TOKEN must be at least 16 characters".into());
        }
        let mut cfg = Config::with_dirs("./instances/author/dam", token);
        let mut datastore_set = false;
        let mut output_set = false;
        let mut it = args.into_iter();
        while let Some(flag) = it.next() {
            let mut value = || it.next().ok_or_else(|| format!("missing value for {flag}"));
            match flag.as_str() {
                "--bind" => cfg.bind = value()?.parse().map_err(|e| format!("--bind: {e}"))?,
                "--datastore" => {
                    cfg.datastore = value()?.into();
                    datastore_set = true;
                }
                "--output" => {
                    cfg.output = value()?.into();
                    output_set = true;
                }
                "--cache" => cfg.cache = value()?.into(),
                "--cache-max-mb" => {
                    cfg.cache_max_bytes = value()?.parse::<u64>().map_err(|e| format!("{flag}: {e}"))? * 1024 * 1024
                }
                "--cache-max-days" => {
                    cfg.cache_max_age =
                        std::time::Duration::from_secs(value()?.parse::<u64>().map_err(|e| format!("{flag}: {e}"))? * 86_400)
                }
                "--model-idle-minutes" => {
                    cfg.model_idle = std::time::Duration::from_secs(value()?.parse::<u64>().map_err(|e| format!("{flag}: {e}"))? * 60)
                }
                "--models" => cfg.models = value()?.into(),
                "--workers" => cfg.workers = value()?.parse().map_err(|e| format!("--workers: {e}"))?,
                "--max-delivery-dimension" => {
                    cfg.max_delivery_dimension = value()?.parse().map_err(|e| format!("{flag}: {e}"))?
                }
                "--max-source-pixels" => {
                    cfg.max_source_pixels = value()?.parse().map_err(|e| format!("{flag}: {e}"))?
                }
                "-h" | "--help" => return Err(usage()),
                other => return Err(format!("unknown argument {other}\n\n{}", usage())),
            }
        }
        if datastore_set && !output_set {
            cfg.output = cfg.datastore.clone();
        }
        cfg.workers = cfg.workers.max(1);
        Ok(cfg)
    }
}

pub fn usage() -> String {
    "studio-dam: Sensible Studio asset processing service

USAGE: STUDIO_DAM_TOKEN=<secret> studio-dam [options]

  --bind <addr>                    listen address (default 127.0.0.1:4510)
  --datastore <dir>                Oak FileDataStore with originals
  --output <dir>                   where renditions are written (default: --datastore)
  --cache <dir>                    delivery cache (default ./instances/author/dam/cache)
  --cache-max-mb <n>               delivery cache size limit (default 2048)
  --cache-max-days <n>             drop cache entries unused this long (default 30)
  --model-idle-minutes <n>         unload the Smart Tags model after this (default 10)
  --models <dir>                   Smart Tags model (default ./instances/author/dam/models)
  --workers <n>                    concurrent image jobs (default: CPU count)
  --max-delivery-dimension <px>    default 4096
  --max-source-pixels <n>          default 150000000"
        .to_string()
}
