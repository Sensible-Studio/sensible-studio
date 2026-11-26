//! Sensible Studio Live cache.
//!
//! Apache httpd is the web server: virtual hosts, rewrites, TLS, compression, and it serves every
//! cached page straight from its document root. This service is what Apache proxies to when the
//! file is not there: it filters the request, fetches the page from a publish instance, writes it
//! into the document root, and removes files again when the publish says content changed.

pub mod cache;
pub mod config;
pub mod dispatcher;
pub mod filter;
pub mod server;
pub mod tags;
pub mod url;
