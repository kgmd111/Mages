package org.mlm.mages.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.browser.document
import org.w3c.dom.HTMLTextAreaElement

@Composable
actual fun rememberClipboardCopy(): (text: String, sensitive: Boolean) -> Unit = remember {
    { text, _ ->
        if (windowIsSecureContext() && navigatorClipboardWriteTextSupported()) {
            navigatorClipboardWriteText(text)
        } else {
            val textarea = document.createElement("textarea") as HTMLTextAreaElement
            textarea.value = text
            textarea.setAttribute(
                "style",
                "position:fixed;left:-9999px;top:0;opacity:0;pointer-events:none;"
            )
            document.body?.appendChild(textarea)
            textareaSelect(textarea)
            documentExecCopy()
            document.body?.removeChild(textarea)
        }
    }
}
