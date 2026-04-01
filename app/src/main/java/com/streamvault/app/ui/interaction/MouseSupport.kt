package com.streamvault.app.ui.interaction

import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.composed
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.focus.FocusRequester

fun Modifier.mouseClickable(
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
): Modifier = composed {
    var pressedFromMouse by remember { mutableStateOf(false) }

    pointerInteropFilter { event ->
        if (!enabled || !event.isMouseLikePrimaryPress()) {
            return@pointerInteropFilter false
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_BUTTON_PRESS -> {
                pressedFromMouse = true
                focusRequester?.requestFocus()
                true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_BUTTON_RELEASE -> {
                val shouldClick = pressedFromMouse
                pressedFromMouse = false
                if (shouldClick) {
                    onClick()
                }
                shouldClick
            }

            MotionEvent.ACTION_CANCEL -> {
                pressedFromMouse = false
                false
            }

            else -> false
        }
    }
}

private fun MotionEvent.isMouseLikePrimaryPress(): Boolean {
    val isPointerSource = isFromSource(InputDevice.SOURCE_MOUSE) ||
        isFromSource(InputDevice.SOURCE_TOUCHPAD) ||
        isFromSource(InputDevice.SOURCE_STYLUS)

    if (!isPointerSource) {
        return false
    }

    return when (actionMasked) {
        MotionEvent.ACTION_DOWN,
        MotionEvent.ACTION_UP -> buttonState == 0 || buttonState and MotionEvent.BUTTON_PRIMARY != 0
        MotionEvent.ACTION_BUTTON_PRESS,
        MotionEvent.ACTION_BUTTON_RELEASE -> actionButton == MotionEvent.BUTTON_PRIMARY
        else -> false
    }
}
