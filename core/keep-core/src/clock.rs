//! Where "now" comes from. The standard clock everywhere, except in a browser's WebAssembly, which
//! has none: the host installs one with [`set_clock`] before it opens or edits a vault.

use std::sync::OnceLock;
use std::time::{Duration, Instant};

static CLOCK: OnceLock<fn() -> f64> = OnceLock::new();

/// Installs a clock that answers milliseconds since 1970-01-01 UTC. Only the first call counts.
pub fn set_clock(clock: fn() -> f64) {
    let _ = CLOCK.set(clock);
}

/// Milliseconds since 1970-01-01 UTC from the installed clock, if there is one.
pub(crate) fn installed_millis() -> Option<f64> {
    CLOCK.get().map(|clock| clock())
}

/// Whole seconds since 1970-01-01 UTC from the installed clock, if there is one.
pub(crate) fn installed_unix() -> Option<i64> {
    let millis = installed_millis()?;
    let seconds = Duration::try_from_secs_f64(millis / 1000.0).ok()?;
    i64::try_from(seconds.as_secs()).ok()
}

/// Measures a duration with the installed clock, or with the monotonic one when none is installed.
pub(crate) enum Stopwatch {
    Installed(f64),
    Monotonic(Instant),
}

impl Stopwatch {
    pub(crate) fn start() -> Self {
        installed_millis().map_or_else(|| Self::Monotonic(Instant::now()), Self::Installed)
    }

    pub(crate) fn elapsed_millis(&self) -> u128 {
        match self {
            Self::Installed(started) => {
                let now = installed_millis().unwrap_or(*started);
                Duration::try_from_secs_f64((now - *started) / 1000.0).map_or(0, |d| d.as_millis())
            }
            Self::Monotonic(started) => started.elapsed().as_millis(),
        }
    }
}
