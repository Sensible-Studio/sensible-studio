use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::Json;
use serde_json::json;

#[derive(Debug, thiserror::Error)]
pub enum DamError {
    #[error("not found")]
    NotFound,
    #[error("bad request: {0}")]
    BadRequest(String),
    #[error("unauthorized")]
    Unauthorized,
    #[error("forbidden")]
    Forbidden,
    #[error("payload too large")]
    TooLarge,
    #[error("busy, retry later")]
    Busy,
    #[error("{0}")]
    NotReady(String),
    #[error("unsupported format: {0}")]
    Unsupported(String),
    #[error("image error: {0}")]
    Image(#[from] image::ImageError),
    #[error("io error: {0}")]
    Io(#[from] std::io::Error),
    #[error("internal error: {0}")]
    Internal(String),
}

impl DamError {
    pub fn status(&self) -> StatusCode {
        match self {
            DamError::NotFound => StatusCode::NOT_FOUND,
            DamError::BadRequest(_) => StatusCode::BAD_REQUEST,
            DamError::Unauthorized => StatusCode::UNAUTHORIZED,
            DamError::Forbidden => StatusCode::FORBIDDEN,
            DamError::TooLarge => StatusCode::PAYLOAD_TOO_LARGE,
            DamError::Busy => StatusCode::SERVICE_UNAVAILABLE,
            DamError::NotReady(_) => StatusCode::CONFLICT,
            DamError::Unsupported(_) => StatusCode::UNPROCESSABLE_ENTITY,
            DamError::Image(e) => match e {
                image::ImageError::Limits(_) => StatusCode::PAYLOAD_TOO_LARGE,
                image::ImageError::Unsupported(_) => StatusCode::UNPROCESSABLE_ENTITY,
                image::ImageError::Decoding(_) => StatusCode::UNPROCESSABLE_ENTITY,
                _ => StatusCode::INTERNAL_SERVER_ERROR,
            },
            DamError::Io(e) if e.kind() == std::io::ErrorKind::NotFound => StatusCode::NOT_FOUND,
            DamError::Io(_) | DamError::Internal(_) => StatusCode::INTERNAL_SERVER_ERROR,
        }
    }
}

impl IntoResponse for DamError {
    fn into_response(self) -> Response {
        let status = self.status();
        if status.is_server_error() {
            tracing::error!(error = %self, "request failed");
        }
        // Do not leak filesystem paths from io errors to clients.
        let message = match &self {
            DamError::Io(_) | DamError::Internal(_) => "internal error".to_string(),
            other => other.to_string(),
        };
        let mut res = (status, Json(json!({ "error": message }))).into_response();
        if matches!(self, DamError::Busy) {
            res.headers_mut().insert(axum::http::header::RETRY_AFTER, axum::http::HeaderValue::from_static("2"));
        }
        res
    }
}
