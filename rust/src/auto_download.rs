use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, RwLock};

use matrix_sdk::media::{MediaFormat, MediaRequestParameters};
use matrix_sdk::ruma::events::room::MediaSource;
use matrix_sdk::ruma::events::room::message::{
    MessageType, RoomMessageEventContent, SyncRoomMessageEvent,
};
use matrix_sdk::Client as SdkClient;

use crate::types::{AutoDownloadNetwork, MediaAutoDownloadRules};

#[derive(Clone, Copy, PartialEq, Eq)]
pub(crate) enum MediaKind {
    Photo,
    Audio,
    Video,
    Document,
}

struct Shared {
    rules: RwLock<MediaAutoDownloadRules>,
    metered: AtomicBool,
    roaming: AtomicBool,
}

struct Job {
    kind: MediaKind,
    source: MediaSource,
    size: Option<u64>,
}

#[derive(Clone)]
pub(crate) struct AutoDownload {
    shared: Arc<Shared>,
    tx: tokio::sync::mpsc::UnboundedSender<Job>,
}

#[cfg(not(target_family = "wasm"))]
fn spawn_detached<F: std::future::Future<Output = ()> + Send + 'static>(fut: F) {
    crate::RT.spawn(fut);
}

#[cfg(target_family = "wasm")]
fn spawn_detached<F: std::future::Future<Output = ()> + 'static>(fut: F) {
    wasm_bindgen_futures::spawn_local(fut);
}

fn allows(shared: &Shared, kind: MediaKind) -> bool {
    let rules = shared.rules.read().unwrap_or_else(|error| error.into_inner());
    let networks = match kind {
        MediaKind::Photo => &rules.photos,
        MediaKind::Audio => &rules.audio,
        MediaKind::Video => &rules.video,
        MediaKind::Document => &rules.documents,
    };
    let network = if !shared.metered.load(Ordering::Relaxed) {
        AutoDownloadNetwork::Wifi
    } else if shared.roaming.load(Ordering::Relaxed) {
        AutoDownloadNetwork::Roaming
    } else {
        AutoDownloadNetwork::Mobile
    };
    networks.contains(&network)
}

fn job_from(content: &RoomMessageEventContent) -> Option<(MediaKind, MediaSource, Option<u64>)> {
    match &content.msgtype {
        MessageType::Image(image) => {
            let size = image.info.as_deref().and_then(|info| info.size).map(u64::from);
            Some((MediaKind::Photo, image.source.clone(), size))
        }
        MessageType::Video(video) => {
            let size = video.info.as_deref().and_then(|info| info.size).map(u64::from);
            Some((MediaKind::Video, video.source.clone(), size))
        }
        MessageType::Audio(audio) => {
            let size = audio.info.as_deref().and_then(|info| info.size).map(u64::from);
            Some((MediaKind::Audio, audio.source.clone(), size))
        }
        MessageType::File(file) => {
            let size = file.info.as_deref().and_then(|info| info.size).map(u64::from);
            Some((MediaKind::Document, file.source.clone(), size))
        }
        _ => None,
    }
}

async fn run_worker(
    sdk: SdkClient,
    shared: Arc<Shared>,
    mut rx: tokio::sync::mpsc::UnboundedReceiver<Job>,
) {
    while let Some(job) = rx.recv().await {
        if !allows(&shared, job.kind) {
            continue;
        }
        let media = sdk.media();
        let cap = media
            .media_retention_policy()
            .await
            .ok()
            .and_then(|policy| policy.max_file_size);
        if let (Some(size), Some(cap)) = (job.size, cap) {
            if size > cap {
                continue;
            }
        }
        let request = MediaRequestParameters {
            source: job.source,
            format: MediaFormat::File,
        };
        let _ = media.get_media_content(&request, true).await;
    }
}

pub(crate) fn spawn(sdk: SdkClient) -> AutoDownload {
    let shared = Arc::new(Shared {
        rules: RwLock::new(MediaAutoDownloadRules::default()),
        metered: AtomicBool::new(false),
        roaming: AtomicBool::new(false),
    });
    let (tx, rx) = tokio::sync::mpsc::unbounded_channel::<Job>();
    let handle = AutoDownload {
        shared: shared.clone(),
        tx,
    };

    spawn_detached(run_worker(sdk.clone(), shared, rx));

    let state = handle.clone();
    sdk.add_event_handler(move |event: SyncRoomMessageEvent, client: SdkClient| {
        let state = state.clone();
        async move {
            let Some(original) = event.as_original() else {
                return;
            };
            if client
                .user_id()
                .is_some_and(|me| original.sender.as_str() == me.as_str())
            {
                return;
            }
            let Some((kind, source, size)) = job_from(&original.content) else {
                return;
            };
            if !allows(&state.shared, kind) {
                return;
            }
            let _ = state.tx.send(Job { kind, source, size });
        }
    });

    handle
}

impl AutoDownload {
    pub(crate) fn set_rules(&self, rules: MediaAutoDownloadRules) {
        *self.shared.rules.write().unwrap_or_else(|error| error.into_inner()) = rules;
    }

    pub(crate) fn set_network(&self, metered: bool, roaming: bool) {
        self.shared.metered.store(metered, Ordering::Relaxed);
        self.shared.roaming.store(metered && roaming, Ordering::Relaxed);
    }
}
