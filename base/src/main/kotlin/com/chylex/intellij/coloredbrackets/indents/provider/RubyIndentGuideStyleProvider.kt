package com.chylex.intellij.coloredbrackets.indents.provider

import com.chylex.intellij.coloredbrackets.indents.provider.IndentGuideStyle.Precedence.OVERRIDE_BRACKET_COLOR
import com.intellij.openapi.progress.ProgressManager

class RubyIndentGuideStyleProvider : IndentGuideStyleProvider {
	override fun getStyles(
		context: IndentGuideStyleContext,
		candidates: List<IndentGuideCandidate>,
	): Map<IndentGuideCandidate, IndentGuideStyle> {
		val boundaryLines = calculateBlockBoundaryLines(context)
		return candidates.mapNotNull { candidate ->
			ProgressManager.checkCanceled()
			val endLine = context.document.getLineNumber(
				candidate.range.endOffset.coerceIn(0, context.document.textLength)
			)
			if (!boundaryLines[endLine]) return@mapNotNull null

			candidate to IndentGuideStyle(
				level = (candidate.descriptor.indentLevel / context.indentSize - 1).coerceAtLeast(0),
				precedence = OVERRIDE_BRACKET_COLOR,
			)
		}.toMap()
	}

	private fun calculateBlockBoundaryLines(context: IndentGuideStyleContext): BooleanArray {
		val document = context.document
		val result = BooleanArray(document.lineCount)
		var effectiveLineIsBoundary = false

		for (lineNumber in result.indices) {
			ProgressManager.checkCanceled()
			val lineStart = document.getLineStartOffset(lineNumber)
			val lineEnd = document.getLineEndOffset(lineNumber)
			val line = document.charsSequence.subSequence(lineStart, lineEnd).trimStart()

			if (line.isNotEmpty() && line.first() != '#') {
				val boundary = line.takeWhile { it.isLetter() }.toString()
				effectiveLineIsBoundary = boundary in BLOCK_BOUNDARIES
			}
			result[lineNumber] = effectiveLineIsBoundary
		}

		return result
	}

	companion object {
		private val BLOCK_BOUNDARIES = setOf("end", "else", "elsif", "when", "rescue", "ensure")
	}
}
