use std::collections::{HashMap, VecDeque};
use std::fmt;
use std::sync::{Arc, PoisonError, RwLock, RwLockReadGuard, RwLockWriteGuard};

/// One open session, shared by handle. Reads run concurrently; edits take the write lock.
pub type SharedSession = Arc<RwLock<VaultSession>>;

use super::{SessionError, VaultSession};

const RECENTLY_CLOSED: usize = 256;

#[derive(Clone, Copy, PartialEq, Eq, Hash)]
pub struct SessionId([u8; 16]);

impl SessionId {
    fn random() -> Result<Self, SessionError> {
        let mut bytes = [0u8; 16];
        getrandom::fill(&mut bytes).map_err(|_| SessionError::RandomUnavailable)?;
        Ok(Self(bytes))
    }

    pub fn parse(text: &str) -> Result<Self, SessionError> {
        let bytes = text.as_bytes();
        if bytes.len() != 32 {
            return Err(SessionError::InvalidHandle);
        }
        let mut out = [0u8; 16];
        for (byte, pair) in out.iter_mut().zip(bytes.chunks_exact(2)) {
            let digit = |d: u8| match d {
                b'0'..=b'9' => Ok(d - b'0'),
                b'a'..=b'f' => Ok(d - b'a' + 10),
                _ => Err(SessionError::InvalidHandle),
            };
            *byte = digit(pair[0])? << 4 | digit(pair[1])?;
        }
        Ok(Self(out))
    }
}

impl fmt::Display for SessionId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        self.0.iter().try_for_each(|b| write!(f, "{b:02x}"))
    }
}

impl fmt::Debug for SessionId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("SessionId(..)")
    }
}

#[derive(Default)]
struct State {
    open: HashMap<SessionId, SharedSession>,
    closed: VecDeque<SessionId>,
}

pub struct VaultSessionStore {
    state: RwLock<State>,
    max_sessions: usize,
}

impl VaultSessionStore {
    pub const DEFAULT_MAX_SESSIONS: usize = 16;

    pub fn new(max_sessions: usize) -> Self {
        Self {
            state: RwLock::new(State::default()),
            max_sessions,
        }
    }

    pub fn has_capacity(&self) -> bool {
        self.state().open.len() < self.max_sessions
    }

    pub fn insert(&self, session: VaultSession) -> Result<SessionId, SessionError> {
        let mut state = self.state_mut();
        if state.open.len() >= self.max_sessions {
            return Err(SessionError::TooManySessions);
        }
        let id = loop {
            let candidate = SessionId::random()?;
            if !state.open.contains_key(&candidate) && !state.closed.contains(&candidate) {
                break candidate;
            }
        };
        state.open.insert(id, Arc::new(RwLock::new(session)));
        Ok(id)
    }

    pub fn read<R>(
        &self,
        id: SessionId,
        body: impl FnOnce(&VaultSession) -> R,
    ) -> Result<R, SessionError> {
        let session = self.get(id)?;
        let guard = session.read().unwrap_or_else(PoisonError::into_inner);
        Ok(body(&guard))
    }

    pub fn write<R>(
        &self,
        id: SessionId,
        body: impl FnOnce(&mut VaultSession) -> R,
    ) -> Result<R, SessionError> {
        let session = self.get(id)?;
        let mut guard = session.write().unwrap_or_else(PoisonError::into_inner);
        Ok(body(&mut guard))
    }

    pub fn get(&self, id: SessionId) -> Result<SharedSession, SessionError> {
        let state = self.state();
        match state.open.get(&id) {
            Some(session) => Ok(Arc::clone(session)),
            None if state.closed.contains(&id) => Err(SessionError::SessionClosed),
            None => Err(SessionError::SessionNotFound),
        }
    }

    pub fn close(&self, id: SessionId) -> bool {
        let removed = {
            let mut state = self.state_mut();
            let removed = state.open.remove(&id);
            if removed.is_some() {
                if state.closed.len() == RECENTLY_CLOSED {
                    state.closed.pop_front();
                }
                state.closed.push_back(id);
            }
            removed
        };
        removed.is_some()
    }

    pub fn close_all(&self) -> usize {
        let drained: Vec<_> = {
            let mut state = self.state_mut();
            let drained: Vec<_> = state.open.drain().collect();
            for (id, _) in &drained {
                if state.closed.len() == RECENTLY_CLOSED {
                    state.closed.pop_front();
                }
                state.closed.push_back(*id);
            }
            drained
        };
        drained.len()
    }

    pub fn len(&self) -> usize {
        self.state().open.len()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    fn state(&self) -> RwLockReadGuard<'_, State> {
        self.state.read().unwrap_or_else(PoisonError::into_inner)
    }

    fn state_mut(&self) -> RwLockWriteGuard<'_, State> {
        self.state.write().unwrap_or_else(PoisonError::into_inner)
    }
}

impl Default for VaultSessionStore {
    fn default() -> Self {
        Self::new(Self::DEFAULT_MAX_SESSIONS)
    }
}

impl fmt::Debug for VaultSessionStore {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("VaultSessionStore")
            .field("open", &self.len())
            .field("max_sessions", &self.max_sessions)
            .finish_non_exhaustive()
    }
}
