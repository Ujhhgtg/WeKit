package dev.ujhhgtg.wekit.features.core

abstract class ApiFeature : BaseFeature() {

    final override fun startup() {
        enable()
    }
}
