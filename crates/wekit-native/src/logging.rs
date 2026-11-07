pub use wekit_native_common as common;

pub const LOGGER: common::logging::Logger =
    common::logging::Logger::new("NativeLib: ", "NativeLib: ");

#[macro_export]
macro_rules! logi {
    ($($arguments:tt)*) => {
        $crate::logging::common::logi!($crate::logging::LOGGER; $($arguments)*)
    };
}

#[macro_export]
macro_rules! logw {
    ($($arguments:tt)*) => {
        $crate::logging::common::logw!($crate::logging::LOGGER; $($arguments)*)
    };
}

#[macro_export]
macro_rules! loge {
    ($($arguments:tt)*) => {
        $crate::logging::common::loge!($crate::logging::LOGGER; $($arguments)*)
    };
}
