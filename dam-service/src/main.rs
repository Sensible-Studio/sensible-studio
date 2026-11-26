use studio_dam::{server, Config};

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "studio_dam=info".into()),
        )
        .init();

    let cfg = match Config::from_args(std::env::args().skip(1)) {
        Ok(cfg) => cfg,
        Err(msg) => {
            eprintln!("{msg}");
            std::process::exit(2);
        }
    };
    for dir in [&cfg.datastore, &cfg.output, &cfg.cache] {
        if let Err(e) = std::fs::create_dir_all(dir) {
            eprintln!("cannot create {}: {e}", dir.display());
            std::process::exit(1);
        }
    }

    let listener = match tokio::net::TcpListener::bind(cfg.bind).await {
        Ok(l) => l,
        Err(e) => {
            eprintln!("cannot bind {}: {e}", cfg.bind);
            std::process::exit(1);
        }
    };
    tracing::info!(
        bind = %cfg.bind,
        datastore = %cfg.datastore.display(),
        output = %cfg.output.display(),
        workers = cfg.workers,
        "studio-dam listening"
    );

    let app = server::app(cfg);
    if let Err(e) = axum::serve(listener, app).with_graceful_shutdown(shutdown_signal()).await {
        tracing::error!(error = %e, "server error");
        std::process::exit(1);
    }
    tracing::info!("studio-dam stopped");
}

/// Sensible Studio stops us with SIGTERM; Ctrl-C works when run by hand.
async fn shutdown_signal() {
    let ctrl_c = async {
        let _ = tokio::signal::ctrl_c().await;
    };
    #[cfg(unix)]
    let terminate = async {
        match tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate()) {
            Ok(mut s) => {
                s.recv().await;
            }
            Err(_) => std::future::pending::<()>().await,
        }
    };
    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();
    tokio::select! {
        _ = ctrl_c => {},
        _ = terminate => {},
    }
}
