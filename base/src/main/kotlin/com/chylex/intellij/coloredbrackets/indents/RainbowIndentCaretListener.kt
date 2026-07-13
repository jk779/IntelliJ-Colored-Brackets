package com.chylex.intellij.coloredbrackets.indents

import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener

class RainbowIndentCaretListener : EditorFactoryListener {
	private val caretListener = object : CaretListener {
		override fun caretPositionChanged(event: CaretEvent) {
			RainbowIndentsPass.caretPositionChanged(event.editor)
		}
	}

	override fun editorCreated(event: EditorFactoryEvent) {
		event.editor.caretModel.addCaretListener(caretListener)
	}

	override fun editorReleased(event: EditorFactoryEvent) {
		event.editor.caretModel.removeCaretListener(caretListener)
	}
}
