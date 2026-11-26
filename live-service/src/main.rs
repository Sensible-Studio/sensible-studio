use std::path::PathBuf;

use studio_live::config::Config;
use studio_live::server::{run, App};

const USAGE: &str = "Usage: studio-live [--config live.toml] [--check]
  --config FILE   the configuration (default: live.toml)
  --check         read the configuration, say what it means, and exit
  --import-dispatcher FILE [--var NAME=VALUE]... [--vars FILE]...
                  read a dispatcher configuration (dispatcher.any and what it includes) and
                  print it as a live.toml, with what has no equivalent as comments; ${NAME} is
                  replaced by --var, then Define lines of --vars files, then the project's
                  conf.d/variables/*.vars, then the environment
STUDIO_LIVE_FLUSH_SECRET in the environment overrides [flush] secret.";

#[tokio::main]
async fn main() {
    let mut config_path = PathBuf::from("live.toml");
    let mut check = false;
    let mut import: Option<PathBuf> = None;
    let mut given: Vec<(String, String)> = Vec::new();
    let mut var_files: Vec<PathBuf> = Vec::new();
    let mut args = std::env::args().skip(1);
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--config" => match args.next() {
                Some(path) => config_path = PathBuf::from(path),
                None => fail("--config needs a file"),
            },
            "--check" => check = true,
            "--import-dispatcher" => match args.next() {
                Some(file) => import = Some(PathBuf::from(file)),
                None => fail("--import-dispatcher needs a file"),
            },
            "--var" => match args.next().as_deref().and_then(|v| v.split_once('=')) {
                Some((name, value)) if !name.is_empty() => given.push((name.to_owned(), value.to_owned())),
                _ => fail("--var needs NAME=VALUE"),
            },
            "--vars" => match args.next() {
                Some(file) => var_files.push(PathBuf::from(file)),
                None => fail("--vars needs a file"),
            },
            "--help" | "-h" => {
                println!("{USAGE}");
                return;
            }
            other => fail(&format!("unknown argument {other}\n{USAGE}")),
        }
    }

    if let Some(file) = import {
        let text = studio_live::dispatcher::Variables::gather(&file, &given, &var_files)
            .and_then(|variables| studio_live::dispatcher::read_with(&file, &variables))
            .and_then(|root| studio_live::dispatcher::to_toml(&root, &file));
        match text {
            Ok(text) => {
                print!("{text}");
                return;
            }
            Err(e) => fail(&e),
        }
    }
    if !given.is_empty() || !var_files.is_empty() {
        fail("--var and --vars are for --import-dispatcher");
    }

    tracing_subscriber::fmt()
        .with_ansi(std::io::IsTerminal::is_terminal(&std::io::stdout()))
        .with_env_filter(tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()))
        .init();

    let config = Config::load(&config_path).unwrap_or_else(|e| fail(&e));
    if config.filter.is_empty() {
        tracing::warn!("no [[filter]] rules: every request will be refused");
    }
    if config.cdn.is_some() && !config.after_invalidation.is_set() {
        tracing::warn!("[cdn] without [after_invalidation]: answers are tagged, but nothing tells the CDN what to purge");
    }
    if config.flush.secret.is_none() {
        tracing::warn!("no flush secret: only this machine can invalidate the cache");
    }
    if check {
        println!(
            "{}: OK\n  listen   {}\n  docroot  {}\n  publish  {}\n  sites    {}\n  filter   {} rules\n  flush    {}",
            config_path.display(),
            config.listen,
            config.docroot.display(),
            config.publish.join(", "),
            if config.site.is_empty() {
                "none named: one set of rules for every host name".to_owned()
            } else {
                config
                    .site
                    .iter()
                    .map(|site| format!("{} ({} at {})", site.name, site.hosts.join(", "), site.roots.join(", ")))
                    .collect::<Vec<_>>()
                    .join("; ")
            },
            config.filter.len(),
            if config.flush.secret.is_some() { "with the secret" } else { "from this machine only" },
        );
        return;
    }

    let listen = config.listen;
    let app = App::new(config).unwrap_or_else(|e| fail(&e));
    let listener = tokio::net::TcpListener::bind(listen).await.unwrap_or_else(|e| fail(&format!("cannot listen on {listen}: {e}")));
    tracing::info!(%listen, docroot = %app.cache().docroot().display(), publish = ?app.config().publish, "Live cache ready");
    notify_ready();
    if let Err(e) = run(app, listener).await {
        fail(&e.to_string());
    }
}

/// Under systemd (`Type=notify`): says the service is ready, once the port is bound and the files
/// Apache reads at its start (the redirect maps) exist, so that a web server ordered after it does
/// not start before them. Nothing without `NOTIFY_SOCKET`.
fn notify_ready() {
    #[cfg(unix)]
    {
        use std::os::unix::ffi::OsStrExt;
        use std::os::unix::net::UnixDatagram;
        let Some(path) = std::env::var_os("NOTIFY_SOCKET") else { return };
        let sent = UnixDatagram::unbound().and_then(|socket| {
            let bytes = path.as_bytes();
            if let Some(name) = bytes.strip_prefix(b"@") {
                #[cfg(target_os = "linux")]
                {
                    use std::os::linux::net::SocketAddrExt;
                    let address = std::os::unix::net::SocketAddr::from_abstract_name(name)?;
                    return socket.send_to_addr(b"READY=1", &address);
                }
                #[cfg(not(target_os = "linux"))]
                {
                    let _ = name;
                    return Err(std::io::Error::other("abstract socket"));
                }
            }
            socket.send_to(b"READY=1", &path)
        });
        if let Err(error) = sent {
            tracing::warn!(%error, "systemd could not be told the service is ready");
        }
    }
}

fn fail(message: &str) -> ! {
    eprintln!("studio-live: {message}");
    std::process::exit(2);
}
