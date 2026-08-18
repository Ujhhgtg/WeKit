package dev.ujhhgtg.wekit.features.core

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class Feature(
    val id: String,
    val nameRes: String,
    val categoryIds: Array<String>,
    val descriptionRes: String = "",
)
