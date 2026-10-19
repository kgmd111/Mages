use crate::{CoreClient, FfiError};
use futures_util::StreamExt;
use matrix_sdk::encryption::verification::{
    SasState as SdkSasState, Verification, VerificationRequest, VerificationRequestState,
};
use matrix_sdk::ruma::{OwnedDeviceId, OwnedUserId, UserId};
use matrix_sdk::sleep::sleep;
use matrix_sdk::RoomMemberships;
use serde::{Deserialize, Serialize};
use std::time::Duration;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "phase")]
pub enum VerifEvent {
    Requested {
        flow_id: String,
    },
    Ready,
    SasStarted,
    KeysExchanged {
        emojis: Vec<EmojiEntry>,
        other_user: String,
        other_device: String,
    },
    Confirmed,
    Done,
    Cancelled {
        reason: String,
    },
    Error {
        message: String,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EmojiEntry {
    pub symbol: String,
    pub description: String,
}

impl CoreClient {
    pub async fn start_device_verification(
        &self,
        device_id: String,
    ) -> Result<(String, VerificationRequest), FfiError> {
        let me = self
            .sdk
            .user_id()
            .ok_or_else(|| FfiError::Msg("No user session".into()))?;
        let device_id = OwnedDeviceId::from(device_id);
        let device = self
            .sdk
            .encryption()
            .get_device(me, &device_id)
            .await
            .map_err(|e| FfiError::Msg(format!("Failed to get device: {e}")))?
            .ok_or_else(|| FfiError::Msg("Device not found".into()))?;
        let request = device
            .request_verification()
            .await
            .map_err(|e| FfiError::Msg(format!("Request verification failed: {e}")))?;
        let flow_id = request.flow_id().to_owned();
        Ok((flow_id, request))
    }

    pub async fn start_user_verification(
        &self,
        user_id: String,
    ) -> Result<(String, VerificationRequest), FfiError> {
        let uid: OwnedUserId = user_id
            .parse()
            .map_err(|_| FfiError::Msg("Invalid user ID".into()))?;
        let identity = self
            .sdk
            .encryption()
            .get_user_identity(&uid)
            .await
            .map_err(|e| FfiError::Msg(format!("Failed to get user identity: {e}")))?
            .ok_or_else(|| FfiError::Msg("User identity not found".into()))?;
        let request = identity
            .request_verification()
            .await
            .map_err(|e| FfiError::Msg(format!("Request verification failed: {e}")))?;
        let flow_id = request.flow_id().to_owned();
        Ok((flow_id, request))
    }

    pub async fn observation_request(
        &self,
        flow_id: String,
        other_user_id: String,
    ) -> Result<VerificationRequest, FfiError> {
        let uid: OwnedUserId = other_user_id.parse().map_err(|e| {
            tracing::warn!(
                "accept_and_observe_verification with invalid user id {other_user_id}: {e:?}"
            );
            FfiError::Msg("Invalid user ID".into())
        })?;
        self.sdk
            .encryption()
            .get_verification_request(&uid, &flow_id)
            .await
            .ok_or_else(|| FfiError::Msg("Verification request not found".into()))
    }

    fn verif_uid(&self, other_user_id: Option<&str>) -> Option<OwnedUserId> {
        match other_user_id {
            Some(u) => match u.parse::<OwnedUserId>() {
                Ok(uid) => Some(uid),
                Err(e) => {
                    tracing::warn!("verification with invalid user id {u}: {e:?}");
                    None
                }
            },
            None => self.sdk.user_id().map(|u| u.to_owned()),
        }
    }

    pub async fn cancel_verification(
        &self,
        flow_id: String,
        other_user_id: Option<String>,
    ) -> bool {
        let Some(uid) = self.verif_uid(other_user_id.as_deref()) else {
            return false;
        };
        if let Some(v) = self.sdk.encryption().get_verification(&uid, &flow_id).await {
            match v {
                Verification::SasV1(sas) => sas.cancel().await.is_ok(),
                _ => false,
            }
        } else if let Some(req) = self
            .sdk
            .encryption()
            .get_verification_request(&uid, &flow_id)
            .await
        {
            req.cancel().await.is_ok()
        } else {
            false
        }
    }

    pub async fn confirm_sas(&self, flow_id: String, other_user_id: Option<String>) -> bool {
        let Some(uid) = self.verif_uid(other_user_id.as_deref()) else {
            return false;
        };
        if let Some(Verification::SasV1(sas)) =
            self.sdk.encryption().get_verification(&uid, &flow_id).await
        {
            sas.confirm().await.is_ok()
        } else {
            false
        }
    }

    pub async fn accept_verification_request(
        &self,
        flow_id: String,
        other_user_id: Option<String>,
    ) -> bool {
        let Some(uid) = self.verif_uid(other_user_id.as_deref()) else {
            return false;
        };
        for _ in 0..30 {
            if let Some(req) = self
                .sdk
                .encryption()
                .get_verification_request(&uid, &flow_id)
                .await
            {
                return req.accept().await.is_ok();
            }
            sleep(Duration::from_millis(200)).await;
        }
        false
    }

    pub async fn accept_sas(&self, flow_id: String, other_user_id: Option<String>) -> bool {
        let Some(uid) = self.verif_uid(other_user_id.as_deref()) else {
            return false;
        };
        for _ in 0..30 {
            if let Some(verification) = self.sdk.encryption().get_verification(&uid, &flow_id).await
            {
                if let Some(sas) = verification.sas() {
                    return sas.accept().await.is_ok();
                }
            }
            sleep(Duration::from_millis(200)).await;
        }
        false
    }

    pub async fn is_user_verified(&self, user_id: String) -> bool {
        let Ok(uid) = user_id.parse::<OwnedUserId>() else {
            return false;
        };
        self.user_identity_verified(&uid).await
    }

    async fn user_identity_verified(&self, user_id: &UserId) -> bool {
        match self.sdk.encryption().get_user_identity(user_id).await {
            Ok(Some(identity)) => identity.is_verified(),
            _ => false,
        }
    }

    /// True when every active member of the room has a verified identity, the
    /// rule FluffyChat gates its badge on: invited members count, and our own
    /// identity has to be verified too. Members and identities come from the
    /// store, no fetch, so a member list that is not fully synced yet counts
    /// as unverified rather than claiming a partial list is verified.
    pub async fn all_users_verified(&self, room_id: String) -> bool {
        let Ok(room) = self.require_room(&room_id) else {
            return false;
        };
        let Ok(members) = room.members_no_sync(RoomMemberships::ACTIVE).await else {
            return false;
        };
        if members.is_empty() {
            return false;
        }
        if members.len() as u64 < room.active_members_count() {
            return false;
        }
        for member in members {
            if !self.user_identity_verified(member.user_id()).await {
                return false;
            }
        }
        true
    }
}

pub fn error_json(message: impl Into<String>) -> String {
    serde_json::to_string(&VerifEvent::Error {
        message: message.into(),
    })
    .unwrap_or_default()
}

pub async fn drive_and_emit<S, F>(stream: S, emit: F)
where
    S: futures_util::Stream<Item = VerifEvent>,
    F: Fn(&str),
{
    futures_util::pin_mut!(stream);
    while let Some(event) = stream.next().await {
        let json = serde_json::to_string(&event).unwrap_or_default();
        emit(&json);
        if matches!(event, VerifEvent::Done) || matches!(event, VerifEvent::Cancelled { .. }) {
            break;
        }
    }
}

pub async fn drive_verification_request(
    request: VerificationRequest,
    we_start_sas: bool,
) -> impl futures_util::Stream<Item = VerifEvent> {
    let flow_id = request.flow_id().to_owned();
    async_stream::stream! {
        yield VerifEvent::Requested { flow_id };

        let mut req_changes = request.changes();
        let sas = loop {
            let Some(state) = req_changes.next().await else {
                yield VerifEvent::Error { message: "Request stream ended".into() };
                return;
            };
            match state {
                VerificationRequestState::Ready { .. } => {
                    yield VerifEvent::Ready;
                    if we_start_sas {
                        match request.start_sas().await {
                            Ok(Some(sas)) => break sas,
                            Ok(None) => {
                                yield VerifEvent::Error { message: "start_sas returned None".into() };
                                return;
                            }
                            Err(e) => {
                                yield VerifEvent::Error { message: format!("start_sas failed: {e}") };
                                return;
                            }
                        }
                    }
                }
                VerificationRequestState::Transitioned { verification } => {
                    if let Verification::SasV1(sas) = verification { break sas; }
                    yield VerifEvent::Error { message: "Non-SAS method".into() };
                    return;
                }
                VerificationRequestState::Cancelled(info) => {
                    yield VerifEvent::Cancelled { reason: info.reason().to_owned() };
                    return;
                }
                VerificationRequestState::Done => { yield VerifEvent::Done; return; }
                _ => {}
            }
        };

        yield VerifEvent::SasStarted;

        // No-op when we sent `start` ourselves, sends the accept when their `start`
        // won the race and we are the receiving side.
        let mut sas_changes = sas.changes();
        if let Err(e) = sas.accept().await {
            yield VerifEvent::Error { message: format!("SAS accept failed: {e}") };
            return;
        }
        while let Some(state) = sas_changes.next().await {
            match state {
                SdkSasState::KeysExchanged { emojis, .. } => {
                    yield VerifEvent::KeysExchanged {
                        emojis: emojis.map(|e| e.emojis.iter().map(|em| EmojiEntry {
                            symbol: em.symbol.to_string(),
                            description: em.description.to_string(),
                        }).collect()).unwrap_or_default(),
                        other_user: sas.other_user_id().to_string(),
                        other_device: sas.other_device().device_id().to_string(),
                    };
                }
                SdkSasState::Confirmed => { yield VerifEvent::Confirmed; }
                SdkSasState::Done { .. } => { yield VerifEvent::Done; return; }
                SdkSasState::Cancelled(info) => {
                    yield VerifEvent::Cancelled { reason: info.reason().to_owned() };
                    return;
                }
                _ => {}
            }
        }
    }
}

pub async fn drive_incoming_verification(
    request: VerificationRequest,
) -> impl futures_util::Stream<Item = VerifEvent> {
    async_stream::stream! {
        // Subscribe BEFORE accept so we never miss the Transitioned event
        let mut req_changes = request.changes();

        if let Err(e) = request.accept().await {
            yield VerifEvent::Error { message: format!("Accept failed: {e}") };
            return;
        }
        yield VerifEvent::Ready;

        // Wait for other side to start SAS -> Transitioned
        let sas = loop {
            let Some(state) = req_changes.next().await else {
                yield VerifEvent::Error { message: "Stream ended waiting for SAS".into() };
                return;
            };
            match state {
                VerificationRequestState::Ready { .. } => { /* already emitted */ }
                VerificationRequestState::Transitioned { verification } => {
                    if let Verification::SasV1(sas) = verification { break sas; }
                    yield VerifEvent::Error { message: "Non-SAS method".into() };
                    return;
                }
                VerificationRequestState::Cancelled(info) => {
                    yield VerifEvent::Cancelled { reason: info.reason().to_owned() };
                    return;
                }
                VerificationRequestState::Done => { yield VerifEvent::Done; return; }
                _ => {}
            }
        };

        yield VerifEvent::SasStarted;
        let mut sas_changes = sas.changes();

        // We received their `start`, so we are the side that has to send the accept.
        if let Err(e) = sas.accept().await {
            yield VerifEvent::Error { message: format!("SAS accept failed: {e}") };
            return;
        }
        while let Some(state) = sas_changes.next().await {
            match state {
                SdkSasState::KeysExchanged { emojis, .. } => {
                    yield VerifEvent::KeysExchanged {
                        emojis: emojis.map(|e| e.emojis.iter().map(|em| EmojiEntry {
                            symbol: em.symbol.to_string(),
                            description: em.description.to_string(),
                        }).collect()).unwrap_or_default(),
                        other_user: sas.other_user_id().to_string(),
                        other_device: sas.other_device().device_id().to_string(),
                    };
                }
                SdkSasState::Confirmed => { yield VerifEvent::Confirmed; }
                SdkSasState::Done { .. } => { yield VerifEvent::Done; return; }
                SdkSasState::Cancelled(info) => {
                    yield VerifEvent::Cancelled { reason: info.reason().to_owned() };
                    return;
                }
                _ => {}
            }
        }
    }
}
