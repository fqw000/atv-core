//! JNI bindings for Android TV: runs AtvServer and dispatches callbacks into Kotlin/Java.

use std::io;
use std::net::Ipv4Addr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
use jni::sys::{jboolean, jint, JNI_FALSE, JNI_TRUE};
use jni::{JNIEnv, JavaVM};
use once_cell::sync::Lazy;
use tokio::runtime::Runtime;
use tracing::{error, info, warn};

use atv_core::{AtvConfig, AtvDelegate, AtvServer, EventKind, TouchPhase};

// ---------------------------------------------------------------------------
// Android logcat bridge for `tracing`.
//
// The atv-core / atv-android crates log everything through `tracing`, but the
// library previously installed no subscriber, so those events were silently
// dropped — mDNS registration results, bind IPs and startup errors never
// showed up in logcat. This routes every tracing event into Android logcat via
// liblog's `__android_log_write`, which exists since API 1 and is therefore
// safe on API 23. Tag: FakeAtvNative.
// ---------------------------------------------------------------------------
mod android_log {
    use std::ffi::CStr;
    use std::io::{self, Write};
    use std::sync::Once;

    const TAG: &[u8] = b"FakeAtvNative\0";
    const ANDROID_LOG_VERBOSE: i32 = 2;
    const ANDROID_LOG_DEBUG: i32 = 3;
    const ANDROID_LOG_INFO: i32 = 4;
    const ANDROID_LOG_WARN: i32 = 5;
    const ANDROID_LOG_ERROR: i32 = 6;

    #[link(name = "log")]
    extern "C" {
        fn __android_log_write(prio: i32, tag: *const i8, text: *const i8) -> i32;
    }

    fn write_log(prio: i32, msg: &str) {
        let tag = CStr::from_bytes_with_nul(TAG).unwrap();
        let text = std::ffi::CString::new(msg).unwrap_or_else(|_| {
            std::ffi::CString::new("<invalid log bytes>").unwrap()
        });
        unsafe {
            __android_log_write(prio, tag.as_ptr() as *const i8, text.as_ptr() as *const i8);
        }
    }

    /// A line-buffered writer that emits each complete line to logcat.
    struct LogcatWriter {
        level: i32,
        buf: Vec<u8>,
    }

    impl Write for LogcatWriter {
        fn write(&mut self, data: &[u8]) -> io::Result<usize> {
            self.buf.extend_from_slice(data);
            // tracing-subscriber fmt writes whole formatted events (with a
            // trailing newline) per write call. Flush each complete line.
            while let Some(pos) = self.buf.iter().position(|&b| b == b'\n') {
                let line: Vec<u8> = self.buf.drain(..=pos).collect();
                let line = &line[..line.len() - 1];
                if !line.is_empty() {
                    if let Ok(s) = std::str::from_utf8(line) {
                        write_log(self.level, s);
                    }
                }
            }
            Ok(data.len())
        }

        fn flush(&mut self) -> io::Result<()> {
            if !self.buf.is_empty() {
                let line = std::mem::take(&mut self.buf);
                if let Ok(s) = std::str::from_utf8(&line) {
                    write_log(self.level, s);
                }
            }
            Ok(())
        }
    }

    /// MakeWriter that maps tracing levels to Android log priorities.
    #[derive(Clone)]
    struct LogcatMakeWriter;

    impl<'a> tracing_subscriber::fmt::MakeWriter<'a> for LogcatMakeWriter {
        type Writer = LogcatWriter;
        fn make_writer(&'a self) -> Self::Writer {
            LogcatWriter { level: ANDROID_LOG_INFO, buf: Vec::new() }
        }
        fn make_writer_for(&'a self, meta: &tracing::Metadata<'_>) -> Self::Writer {
            let level = match *meta.level() {
                tracing::Level::ERROR => ANDROID_LOG_ERROR,
                tracing::Level::WARN => ANDROID_LOG_WARN,
                tracing::Level::INFO => ANDROID_LOG_INFO,
                tracing::Level::DEBUG => ANDROID_LOG_DEBUG,
                tracing::Level::TRACE => ANDROID_LOG_VERBOSE,
            };
            LogcatWriter { level, buf: Vec::new() }
        }
    }

    static INIT: Once = Once::new();

    pub fn init() {
        INIT.call_once(|| {
            use tracing_subscriber::fmt::format::FmtSpan;
            let _ = tracing_subscriber::fmt()
                .with_writer(LogcatMakeWriter)
                .with_ansi(false)
                .with_target(true)
                .with_max_level(tracing::Level::TRACE)
                .with_span_events(FmtSpan::NONE)
                .try_init();
            tracing::info!("FakeAtvNative logcat bridge installed");
        });
    }
}

struct JniDelegate {
    jvm: JavaVM,
    callback: GlobalRef,
}

impl JniDelegate {
    fn new(jvm: JavaVM, callback: GlobalRef) -> Self {
        Self { jvm, callback }
    }

    fn call_void_string(&self, method: &str, arg: &str) {
        if let Ok(mut env) = self.jvm.attach_current_thread() {
            let jstr = match env.new_string(arg) {
                Ok(s) => s,
                Err(e) => {
                    warn!("failed to create JString: {e}");
                    return;
                }
            };
            let obj = self.callback.as_obj();
            let _ = env.call_method(
                obj,
                method,
                "(Ljava/lang/String;)V",
                &[JValue::Object(&jstr)],
            );
        }
    }
}

impl AtvDelegate for JniDelegate {
    fn on_button(&self, name: &str) {
        info!("[Android JNI] on_button: {name}");
        self.call_void_string("onButton", name);
    }

    fn on_touch(&self, dx: f64, dy: f64, phase: TouchPhase) {
        if let Ok(mut env) = self.jvm.attach_current_thread() {
            let phase_int = match phase {
                TouchPhase::Began => 1,
                TouchPhase::Moved => 2,
                TouchPhase::Ended => 4,
            };
            let obj = self.callback.as_obj();
            let _ = env.call_method(
                obj,
                "onTouch",
                "(DDI)V",
                &[
                    JValue::Double(dx),
                    JValue::Double(dy),
                    JValue::Int(phase_int),
                ],
            );
        }
    }

    fn on_audio(&self, volume: f64, muted: bool) {
        if let Ok(mut env) = self.jvm.attach_current_thread() {
            let obj = self.callback.as_obj();
            let _ = env.call_method(
                obj,
                "onAudio",
                "(DZ)V",
                &[JValue::Double(volume), JValue::Bool(muted as u8)],
            );
        }
    }

    fn on_event(&self, kind: EventKind, detail: &str) {
        if let Ok(mut env) = self.jvm.attach_current_thread() {
            let kind_str = match env.new_string(format!("{kind:?}")) {
                Ok(s) => s,
                Err(_) => return,
            };
            let detail_str = match env.new_string(detail) {
                Ok(s) => s,
                Err(_) => return,
            };
            let obj = self.callback.as_obj();
            let _ = env.call_method(
                obj,
                "onEvent",
                "(Ljava/lang/String;Ljava/lang/String;)V",
                &[JValue::Object(&kind_str), JValue::Object(&detail_str)],
            );
        }
    }
}

struct RunningServer {
    runtime: Runtime,
    server: Option<AtvServer>,
}

static SERVER_STATE: Lazy<Mutex<Option<RunningServer>>> = Lazy::new(|| Mutex::new(None));
static IS_RUNNING: AtomicBool = AtomicBool::new(false);

/// Called by the Android runtime when libatv_android.so is loaded. Installs the
/// tracing -> logcat bridge so all native logs become visible immediately.
#[no_mangle]
pub extern "system" fn JNI_OnLoad(_vm: JavaVM, _reserved: *mut std::ffi::c_void) -> jint {
    android_log::init();
    jni::sys::JNI_VERSION_1_6
}

#[no_mangle]
pub extern "system" fn Java_com_corvofeng_fakeatv_AtvNative_nativeStartServer(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
    pin: jint,
    device_id: JString,
    server_id: JString,
    local_ip: JString,
    callback: JObject,
) -> jboolean {
    // Defensive: ensure the logcat bridge is installed even if JNI_OnLoad was
    // not called by the runtime.
    android_log::init();
    info!("nativeStartServer called");

    let mut state_guard = match SERVER_STATE.lock() {
        Ok(guard) => guard,
        Err(_) => return JNI_FALSE,
    };

    if state_guard.is_some() {
        warn!("server is already running");
        return JNI_TRUE;
    }

    let device_name: String = match env.get_string(&name) {
        Ok(s) => s.into(),
        Err(_) => "Android TV".to_string(),
    };

    let device_id_str: Option<String> = env
        .get_string(&device_id)
        .ok()
        .map(|s| s.into())
        .filter(|s: &String| !s.is_empty());

    let server_id_str: Option<String> = env
        .get_string(&server_id)
        .ok()
        .map(|s| s.into())
        .filter(|s: &String| !s.is_empty());

    let local_ip_str: Option<String> = env
        .get_string(&local_ip)
        .ok()
        .map(|s| s.into())
        .filter(|s: &String| !s.is_empty());

    let jvm = match env.get_java_vm() {
        Ok(vm) => vm,
        Err(e) => {
            error!("failed to get JavaVM: {e}");
            return JNI_FALSE;
        }
    };

    let global_callback = match env.new_global_ref(callback) {
        Ok(g) => g,
        Err(e) => {
            error!("failed to create GlobalRef for callback: {e}");
            return JNI_FALSE;
        }
    };

    let runtime = match Runtime::new() {
        Ok(rt) => rt,
        Err(e) => {
            error!("failed to create Tokio runtime: {e}");
            return JNI_FALSE;
        }
    };

    let delegate = Arc::new(JniDelegate::new(jvm, global_callback));
    let mut config = AtvConfig::default();
    config.name = device_name;
    config.pin = pin as u32;
    config.device_id = device_id_str;

    // mitv4a v1.5.0: derive a STABLE signing key from the persistent server
    // identifier that Kotlin passes in (derived from the device MAC and stored
    // in device-protected prefs). AtvConfig::default() generates a random UUID
    // on Android (no $HOME) and derives private_key from it, which changed on
    // every app restart and made the iPhone reject the saved pairing key
    // (pair-verify failure -> "cannot connect"). Overriding server_identifier
    // alone was not enough because private_key was already Some(random).
    if let Some(ref sid) = server_id_str {
        config.private_key = Some(atv_core::identity::derive_private_key(sid));
        let fp = &config.private_key.as_ref().unwrap()[..4];
        info!("stable signing key derived from server id {} (fingerprint {:02X}{:02X}{:02X}{:02X})",
            sid, fp[0], fp[1], fp[2], fp[3]);
    }
    config.server_identifier = server_id_str;
    if let Some(ref s) = local_ip_str {
        if let Ok(ip) = s.parse::<Ipv4Addr>() {
            info!("using explicit local IP from Android: {ip}");
            config.ip = Some(ip);
        }
    }

    let server_res = runtime.block_on(async {
        AtvServer::start(config, delegate).await
    });

    match server_res {
        Ok(server) => {
            *state_guard = Some(RunningServer {
                runtime,
                server: Some(server),
            });
            IS_RUNNING.store(true, Ordering::SeqCst);
            info!("AtvServer successfully started on Android TV");
            JNI_TRUE
        }
        Err(e) => {
            error!("failed to start AtvServer: {e}");
            JNI_FALSE
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_corvofeng_fakeatv_AtvNative_nativeStopServer(
    _env: JNIEnv,
    _class: JClass,
) {
    let mut state_guard = match SERVER_STATE.lock() {
        Ok(guard) => guard,
        Err(_) => return,
    };

    if let Some(mut running) = state_guard.take() {
        if let Some(server) = running.server.take() {
            running.runtime.block_on(async {
                server.stop().await;
            });
        }
        IS_RUNNING.store(false, Ordering::SeqCst);
        info!("AtvServer stopped on Android TV");
    }
}

#[no_mangle]
pub extern "system" fn Java_com_corvofeng_fakeatv_AtvNative_nativeIsRunning(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    if IS_RUNNING.load(Ordering::SeqCst) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}
