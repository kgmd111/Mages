use crate::FfiError;
use robius_location::{Handler, Location};
use std::sync::{Arc, Mutex, MutexGuard};
use uniffi::{Object, Record, export};

#[cfg(any(target_os = "macos", target_os = "linux"))]
use robius_location::{Access, Accuracy, Manager};

#[derive(Clone, Debug, Record)]
pub struct DesktopLocationUpdate {
    pub latitude: f64,
    pub longitude: f64,
    pub altitude: Option<f64>,
    pub speed: Option<f64>,
    pub bearing: Option<f64>,
}

#[export(callback_interface)]
pub trait DesktopLocationObserver: Send + Sync {
    fn on_location(&self, location: DesktopLocationUpdate);
    fn on_error(&self, message: String);
}

struct ObserverSlot {
    observer: Mutex<Option<Arc<dyn DesktopLocationObserver>>>,
}

impl ObserverSlot {
    fn set(&self, observer: Arc<dyn DesktopLocationObserver>) {
        *lock(&self.observer) = Some(observer);
    }

    fn clear(&self) {
        *lock(&self.observer) = None;
    }

    fn location(&self, update: DesktopLocationUpdate) {
        let observer = lock(&self.observer).clone();
        if let Some(observer) = observer {
            observer.on_location(update);
        }
    }

    fn error(&self, message: String) {
        let observer = lock(&self.observer).clone();
        if let Some(observer) = observer {
            observer.on_error(message);
        }
    }
}

struct DesktopLocationHandler {
    slot: Arc<ObserverSlot>,
}

impl Handler for DesktopLocationHandler {
    fn handle(&self, location: Location<'_>) {
        let coordinates = match location.coordinates() {
            Ok(coordinates) => coordinates,
            Err(error) => {
                self.slot
                    .error(format!("Could not read location coordinates: {error:?}"));
                return;
            }
        };
        self.slot.location(DesktopLocationUpdate {
            latitude: coordinates.latitude,
            longitude: coordinates.longitude,
            altitude: location.altitude().ok(),
            speed: location.speed().ok(),
            bearing: location.bearing().ok(),
        });
    }

    fn error(&self, error: robius_location::Error) {
        self.slot.error(location_error_message(error));
    }
}

fn location_error(error: robius_location::Error) -> FfiError {
    match error {
        robius_location::Error::AuthorizationDenied => FfiError::LocationPermissionDenied,
        error => FfiError::Msg(format!("{error:?}")),
    }
}

fn location_error_message(error: robius_location::Error) -> String {
    match error {
        robius_location::Error::AuthorizationDenied => "Location permission denied".to_string(),
        robius_location::Error::PermanentlyUnavailable => {
            "Location services are unavailable".to_string()
        }
        robius_location::Error::TemporarilyUnavailable => {
            "Location is temporarily unavailable".to_string()
        }
        error => format!("Location provider error: {error:?}"),
    }
}

#[derive(Object)]
pub struct DesktopLocationSource {
    slot: Arc<ObserverSlot>,
    backend: Backend,
}

#[uniffi::export]
impl DesktopLocationSource {
    #[uniffi::constructor]
    pub fn new() -> Self {
        Self {
            slot: Arc::new(ObserverSlot {
                observer: Mutex::new(None),
            }),
            backend: Backend::new(),
        }
    }

    pub fn start(&self, observer: Box<dyn DesktopLocationObserver>) -> Result<(), FfiError> {
        self.slot.set(Arc::from(observer));
        if let Err(error) = self.backend.start(self.slot.clone()) {
            self.slot.clear();
            return Err(error);
        }
        Ok(())
    }

    pub fn stop(&self) {
        self.backend.stop();
        self.slot.clear();
    }
}

impl Drop for DesktopLocationSource {
    fn drop(&mut self) {
        self.backend.stop();
        self.slot.clear();
    }
}

// macOS: robius's Manager is main-thread-only (!Send + !Sync), so it lives in
// a MainThreadBound and is only touched inside run_on_main.
#[cfg(target_os = "macos")]
struct Backend {
    manager: Mutex<Option<dispatch2::MainThreadBound<Manager>>>,
}

#[cfg(target_os = "macos")]
impl Backend {
    fn new() -> Self {
        Self {
            manager: Mutex::new(None),
        }
    }

    fn start(&self, slot: Arc<ObserverSlot>) -> Result<(), FfiError> {
        dispatch2::run_on_main(|mtm| {
            let mut manager = lock(&self.manager);
            if manager.is_none() {
                let created = Manager::new(DesktopLocationHandler { slot })?;
                *manager = Some(dispatch2::MainThreadBound::new(created, mtm));
            }
            let inner = manager
                .as_mut()
                .ok_or(robius_location::Error::PermanentlyUnavailable)?;
            let inner = inner.get_mut(mtm);
            inner.request_authorization(Access::Background, Accuracy::Precise)?;
            inner.start_updates()
        })
        .map_err(location_error)
    }

    fn stop(&self) {
        dispatch2::run_on_main(|mtm| {
            let mut manager = lock(&self.manager);
            if let Some(bound) = manager.as_mut() {
                let _ = bound.get_mut(mtm).stop_updates();
            }
        });
    }
}

// Windows: the Manager holds non-Send COM handlers and there is no main-thread
// dispatcher, so a dedicated thread owns it and calls go through an mpsc channel.
#[cfg(target_os = "windows")]
struct Backend {
    worker: Mutex<Option<windows_worker::Worker>>,
}

#[cfg(target_os = "windows")]
impl Backend {
    fn new() -> Self {
        Self {
            worker: Mutex::new(None),
        }
    }

    fn start(&self, slot: Arc<ObserverSlot>) -> Result<(), FfiError> {
        if lock(&self.worker).is_none() {
            *lock(&self.worker) = Some(windows_worker::Worker::spawn());
        }
        let worker = lock(&self.worker);
        match worker.as_ref() {
            Some(worker) => worker.start(slot),
            None => Err(FfiError::Msg("location worker is unavailable".into())),
        }
    }

    fn stop(&self) {
        if let Some(worker) = lock(&self.worker).as_ref() {
            worker.stop();
        }
    }
}

#[cfg(target_os = "windows")]
mod windows_worker {
    use super::{DesktopLocationHandler, ObserverSlot, location_error};
    use crate::FfiError;
    use robius_location::{Access, Accuracy, Error, Manager};
    use std::sync::{
        Arc,
        mpsc::{self, Sender},
    };
    use std::thread;

    enum Command {
        Start {
            slot: Arc<ObserverSlot>,
            reply: Sender<Result<(), Error>>,
        },
        Stop,
    }

    pub struct Worker {
        tx: Sender<Command>,
    }

    impl Worker {
        pub fn spawn() -> Self {
            let (tx, rx) = mpsc::channel::<Command>();
            thread::spawn(move || {
                let mut manager: Option<Manager> = None;
                let mut manager_slot: Option<Arc<ObserverSlot>> = None;
                for command in rx {
                    match command {
                        Command::Start { slot, reply } => {
                            let needs_recreate = match (&manager, &manager_slot) {
                                (Some(_), Some(current)) => !Arc::ptr_eq(current, &slot),
                                _ => true,
                            };
                            if needs_recreate {
                                if let Some(previous) = manager.as_mut() {
                                    let _ = previous.stop_updates();
                                }
                                manager = None;
                                manager_slot = None;
                            }
                            if manager.is_none() {
                                match Manager::new(DesktopLocationHandler { slot: slot.clone() }) {
                                    Ok(created) => {
                                        manager = Some(created);
                                        manager_slot = Some(slot);
                                    }
                                    Err(error) => {
                                        let _ = reply.send(Err(error));
                                        continue;
                                    }
                                }
                            }
                            let result = (|| {
                                let inner =
                                    manager.as_mut().ok_or(Error::PermanentlyUnavailable)?;
                                inner
                                    .request_authorization(Access::Background, Accuracy::Precise)?;
                                inner.start_updates()
                            })();
                            let _ = reply.send(result);
                        }
                        Command::Stop => {
                            if let Some(inner) = manager.as_mut() {
                                let _ = inner.stop_updates();
                            }
                        }
                    }
                }
                if let Some(inner) = manager.as_mut() {
                    let _ = inner.stop_updates();
                }
            });
            Self { tx }
        }

        pub fn start(&self, slot: Arc<ObserverSlot>) -> Result<(), FfiError> {
            let (reply_tx, reply_rx) = mpsc::channel();
            self.tx
                .send(Command::Start {
                    slot,
                    reply: reply_tx,
                })
                .map_err(|_| FfiError::Msg("location worker stopped".into()))?;
            reply_rx
                .recv()
                .map_err(|_| FfiError::Msg("location worker stopped".into()))?
                .map_err(location_error)
        }

        pub fn stop(&self) {
            let _ = self.tx.send(Command::Stop);
        }
    }
}

// Linux allows Manager use from any thread, so the direct Mutex still holds.
#[cfg(target_os = "linux")]
struct Backend {
    manager: Mutex<Option<Manager>>,
}

#[cfg(target_os = "linux")]
impl Backend {
    fn new() -> Self {
        Self {
            manager: Mutex::new(None),
        }
    }

    fn start(&self, slot: Arc<ObserverSlot>) -> Result<(), FfiError> {
        let mut manager = lock(&self.manager);
        if manager.is_none() {
            let created = Manager::new(DesktopLocationHandler { slot }).map_err(location_error)?;
            *manager = Some(created);
        }
        let inner = manager
            .as_mut()
            .ok_or_else(|| location_error(robius_location::Error::PermanentlyUnavailable))?;
        inner
            .request_authorization(Access::Background, Accuracy::Precise)
            .map_err(location_error)?;
        inner.start_updates().map_err(location_error)
    }

    fn stop(&self) {
        let mut manager = lock(&self.manager);
        if let Some(inner) = manager.as_mut() {
            let _ = inner.stop_updates();
        }
    }
}

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}
