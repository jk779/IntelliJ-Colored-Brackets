package com.chylex.intellij.coloredbrackets.indents.provider

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.yaml.psi.YAMLBlockScalar

class YamlIndentGuideStyleProvider : IndentGuideStyleProvider {
	override fun getStyles(
		context: IndentGuideStyleContext,
		candidates: List<IndentGuideCandidate>,
	): Map<IndentGuideCandidate, IndentGuideStyle> {
		val structuralCandidates = ReadAction.compute<List<IndentGuideCandidate>, Throwable> {
			candidates.filterNot { candidate ->
				ProgressManager.checkCanceled()
				isBlockScalarContent(context, candidate)
			}
		}

		val styles = HashMap<IndentGuideCandidate, IndentGuideStyle>()
		val ancestors = ArrayDeque<IndentGuideCandidate>()
		val sortedCandidates = structuralCandidates.sortedWith(
			compareBy<IndentGuideCandidate> { it.range.startOffset }
				.thenByDescending { it.range.endOffset }
				.thenBy { it.descriptor.indentLevel }
		)

		for (candidate in sortedCandidates) {
			ProgressManager.checkCanceled()
			while (ancestors.isNotEmpty() && !ancestors.last().range.contains(candidate.range)) {
				ancestors.removeLast()
			}

			styles[candidate] = IndentGuideStyle(level = ancestors.size)
			ancestors.addLast(candidate)
		}

		return styles
	}

	private fun isBlockScalarContent(
		context: IndentGuideStyleContext,
		candidate: IndentGuideCandidate,
	): Boolean {
		val document = context.document
		val firstLine = (candidate.descriptor.startLine + 1).coerceAtMost(document.lineCount - 1)
		val lastLine = candidate.descriptor.endLine.coerceAtMost(document.lineCount - 1)

		for (lineNumber in firstLine..lastLine) {
			val lineStart = document.getLineStartOffset(lineNumber)
			val lineEnd = document.getLineEndOffset(lineNumber)
			var contentOffset = lineStart
			while (contentOffset < lineEnd && document.charsSequence[contentOffset].isWhitespace()) {
				contentOffset++
			}
			if (contentOffset == lineEnd) continue

			val element = context.file.findElementAt(contentOffset) ?: continue
			return PsiTreeUtil.getParentOfType(element, YAMLBlockScalar::class.java, false) != null
		}

		return false
	}
}
