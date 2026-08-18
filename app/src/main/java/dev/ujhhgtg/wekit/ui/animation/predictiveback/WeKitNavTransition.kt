package dev.ujhhgtg.wekit.ui.animation.predictiveback

import dev.ujhhgtg.wekit.ui.utils.theme.PageTransitionAnimation
import top.yukonga.miuix.kmp.nav.transition.NavTransition

fun weKitNavTransition(animation: PageTransitionAnimation): NavTransition = when (animation) {
    PageTransitionAnimation.AOSP -> AospNavTransition
    PageTransitionAnimation.MIUIX -> top.yukonga.miuix.kmp.nav.transition.NavTransitions.MiuixDefault
}
