"""Selective O-MVLL policy for the native bootstrap loader.

The loader is deliberately kept small, so the policy targets every owned
function and printable string in this translation unit.  The file is a build
input only; it is never packaged into the APK.
"""

import os
from functools import lru_cache

import omvll


class WeKitLoaderConfig(omvll.ObfuscationConfig):
    _protected = {
        "runtime_guard", "archive_is_signed_wekit", "valid_apk_path", "append_body",
        "verify_pins", "loader_create", "loader_seal", "loader_verify_bootstrap", "loader_request",
        "loader_decrypt_payload", "loader_configuration",
    }

    def flatten_cfg(self, module, function):
        selected = function.name in self._protected
        if selected:
            print("WEKIT_OMVLL_SELECTED flatten_cfg " + function.name, flush=True)
        return selected

    def obfuscate_arithmetic(self, module, function):
        selected = function.name in self._protected
        if selected:
            print("WEKIT_OMVLL_SELECTED arithmetic " + function.name, flush=True)
        return omvll.ArithmeticOpt(2) if selected else False

    def obfuscate_string(self, module, function, value):
        if not value or len(value) > 4096 or any(
            c not in (9, 10, 13) and not 32 <= c < 127 for c in value
        ):
            return False
        print("WEKIT_OMVLL_SELECTED strings wekit_loader.c", flush=True)
        return omvll.StringEncOptLocal()

    def anti_hooking(self, module, function):
        return False


@lru_cache(maxsize=1)
def omvll_get_config():
    return WeKitLoaderConfig()
