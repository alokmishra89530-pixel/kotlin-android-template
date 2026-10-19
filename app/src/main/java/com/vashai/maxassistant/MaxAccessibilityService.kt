package com.vashai.maxassistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors

class MaxAccessibilityService : AccessibilityService() {

    companion object {
        var instance: MaxAccessibilityService? = null
        var pendingWhatsAppAutoSend: Boolean = false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: return

        if (pendingWhatsAppAutoSend && (packageName == "com.whatsapp" || packageName == "com.whatsapp.w4b")) {
            val rootNode = rootInActiveWindow ?: return
            val sendIds = listOf("com.whatsapp:id/send", "com.whatsapp.w4b:id/send")
            for (id in sendIds) {
                val sendNodes = rootNode.findAccessibilityNodeInfosByViewId(id)
                if (!sendNodes.isNullOrEmpty()) {
                    val sendButton = sendNodes[0]
                    if (sendButton.isClickable) {
                        sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        pendingWhatsAppAutoSend = false
                        break
                    }
                }
            }
        }
    }

    fun clickTextOnScreen(targetText: String, onResult: (String) -> Unit) {
        val rootNode = rootInActiveWindow
        if (rootNode != null) {
            val matchedNodes = rootNode.findAccessibilityNodeInfosByText(targetText)
            if (!matchedNodes.isNullOrEmpty()) {
                for (node in matchedNodes) {
                    if (node.isClickable) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        onResult("Successfully clicked '$targetText' via UI Node.")
                        return
                    } else if (node.parent?.isClickable == true) {
                        node.parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        onResult("Successfully clicked parent of '$targetText'.")
                        return
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                Executors.newSingleThreadExecutor(),
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val hardwareBuffer = screenshot.hardwareBuffer
                        val colorSpace = screenshot.colorSpace
                        val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                        if (bitmap != null) {
                            val softBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                            hardwareBuffer.close()
                            val image = InputImage.fromBitmap(softBitmap, 0)
                            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                            recognizer.process(image)
                                .addOnSuccessListener { visionText ->
                                    for (block in visionText.textBlocks) {
                                        for (line in block.lines) {
                                            if (line.text.contains(targetText, ignoreCase = true)) {
                                                val box = line.boundingBox
                                                if (box != null) {
                                                    tapCoordinates(box.centerX().toFloat(), box.centerY().toFloat())
                                                    onResult("OCR clicked '$targetText'.")
                                                    return@addOnSuccessListener
                                                }
                                            }
                                        }
                                    }
                                    onResult("Target '$targetText' not found on screen.")
                                }
                        }
                    }
                    override fun onFailure(errorCode: Int) {
                        onResult("Screenshot failed with code $errorCode")
                    }
                }
            )
        }
    }

    fun extractScreenText(): String {
        val rootNode = rootInActiveWindow ?: return "Screen content unavailable."
        val builder = StringBuilder()
        traverseNodesForText(rootNode, builder)
        return builder.toString().ifBlank { "No readable text found on active window." }
    }

    private fun traverseNodesForText(node: AccessibilityNodeInfo?, builder: StringBuilder) {
        if (node == null) return
        if (!node.text.isNullOrBlank()) {
            builder.append(node.text).append(" | ")
        }
        for (i in 0 until node.childCount) {
            traverseNodesForText(node.getChild(i), builder)
        }
    }

    fun performSmartScroll(direction: String) {
        val path = Path()
        when (direction.uppercase()) {
            "DOWN" -> { path.moveTo(540f, 1600f); path.lineTo(540f, 400f) }
            "UP" -> { path.moveTo(540f, 400f); path.lineTo(540f, 1600f) }
            "LEFT" -> { path.moveTo(900f, 1000f); path.lineTo(200f, 1000f) }
            "RIGHT" -> { path.moveTo(200f, 1000f); path.lineTo(900f, 1000f) }
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 350)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    fun autoTypeIntoFocusedField(textToType: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode != null && focusedNode.isEditable) {
            val arguments = Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
            return focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }
        return false
    }

    fun tapCoordinates(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 80)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    override fun onInterrupt() {}
}
