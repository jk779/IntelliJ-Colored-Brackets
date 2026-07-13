package com.chylex.intellij.coloredbrackets.indents.provider

import com.chylex.intellij.coloredbrackets.RainbowHighlighter
import com.intellij.lang.Language
import com.intellij.lang.LanguageExtension
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.IndentGuideDescriptor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

data class IndentGuideCandidate(
	val descriptor: IndentGuideDescriptor,
	val range: TextRange,
)

data class IndentGuideStyle(
	val palette: String = RainbowHighlighter.NAME_ROUND_BRACKETS,
	val level: Int,
	val precedence: Precedence = Precedence.FALLBACK,
) {
	enum class Precedence {
		FALLBACK,
		OVERRIDE_BRACKET_COLOR,
	}
}

data class IndentGuideStyleContext(
	val file: PsiFile,
	val document: Document,
	val indentSize: Int,
)

interface IndentGuideStyleProvider {
	fun getStyles(
		context: IndentGuideStyleContext,
		candidates: List<IndentGuideCandidate>,
	): Map<IndentGuideCandidate, IndentGuideStyle>
}

object IndentGuideStyleProviders {
	private val providers = LanguageExtension<IndentGuideStyleProvider>(
		"com.chylex.coloredbrackets.indentGuideStyleProvider"
	)

	fun allForLanguage(language: Language): List<IndentGuideStyleProvider> = providers.allForLanguage(language)
}
