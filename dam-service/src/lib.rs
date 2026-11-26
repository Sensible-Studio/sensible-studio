//! Sensible Studio DAM service: rendition generation, metadata extraction and on-the-fly
//! image delivery for the Sensible Studio.
//!
//! The Java side (OSGi, inside Sensible Studio) owns the JCR. This service never writes
//! to the repository: it reads binaries from a content-addressed blob store
//! (the Oak FileDataStore layout), writes rendition binaries to a blob store,
//! and returns JSON describing what the Java side should create.
//!
//! The rendition contract (names, sizes, formats) is documented in
//! `docs/dam-contract.md` and was observed from MIT-licensed WKND content.

pub mod blobstore;
pub mod config;
pub mod contract;
pub mod delivery;
pub mod document;
pub mod edit;
pub mod error;
pub mod housekeeping;
pub mod metadata;
pub mod office;
pub mod process;
pub mod server;
pub mod smartcrop;
pub mod smarttags;
pub mod ffmpeg;
pub mod video;
pub mod watermark;

pub use config::Config;
pub use error::DamError;
