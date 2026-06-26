package com.immersive.ui.agent

import android.graphics.Rect

/**
 * Shared builders for JVM unit tests.
 *
 * android.graphics.Rect comes from the mockable android.jar in unit tests:
 * plain field reads/writes work, but Rect methods (width(), height(),
 * toString(), ...) throw "not mocked". Build Rect instances by assigning
 * fields directly and keep test assertions on fields or pure Kotlin types.
 */
internal fun testRect(l: Int, t: Int, r: Int, b: Int): Rect {
    val rect = Rect()
    rect.left = l
    rect.top = t
    rect.right = r
    rect.bottom = b
    return rect
}

internal fun testNode(
    index: Int,
    bounds: Rect,
    text: String = "",
    contentDesc: String = "",
    className: String = "android.widget.Button",
    packageName: String = "com.example.app",
    clickable: Boolean = false,
    scrollable: Boolean = false,
    editable: Boolean = false,
    resourceId: String = "",
    visibleToUser: Boolean = true,
    withinScreen: Boolean = true,
): UiNode {
    return UiNode(
        index = index,
        className = className,
        text = text,
        contentDesc = contentDesc,
        packageName = packageName,
        bounds = bounds,
        isClickable = clickable,
        isScrollable = scrollable,
        isEditable = editable,
        isFocused = false,
        isChecked = false,
        viewIdResourceName = resourceId,
        isVisibleToUser = visibleToUser,
        isWithinScreen = withinScreen,
    )
}
