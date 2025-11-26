// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

use serde::{Deserialize, Serialize};

/// Messages sent from client to server
#[derive(Debug, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum ClientMessage {
    /// Start the container
    StartContainer,
    /// Get server status
    GetStatus,
    /// Touch event
    TouchEvent {
        action: i32,  // 0=DOWN, 1=UP, 2=MOVE, 3=CANCEL
        x: f32,
        y: f32,
        pointer_id: i32,
        pressure: f32,
    },
    /// Key event
    KeyEvent {
        keycode: i32,
        pressed: bool,
    },
    /// Ping for connection keep-alive
    Ping,
}

/// Messages sent from server to client
#[derive(Debug, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum ServerMessage {
    /// Container started successfully
    ContainerStarted,
    /// Server status
    Status {
        container_running: bool,
        rootfs_path: String,
        width: i32,
        height: i32,
    },
    /// Generic OK response
    Ok,
    /// Pong response to ping
    Pong,
    /// Error message
    Error {
        message: String,
    },
}
