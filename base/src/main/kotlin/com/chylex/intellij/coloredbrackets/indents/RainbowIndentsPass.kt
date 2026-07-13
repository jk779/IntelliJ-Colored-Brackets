package com.chylex.intellij.coloredbrackets.indents

import com.chylex.intellij.coloredbrackets.indents.provider.IndentGuideCandidate
import com.chylex.intellij.coloredbrackets.indents.provider.IndentGuideStyle
import com.chylex.intellij.coloredbrackets.indents.provider.IndentGuideStyleContext
import com.chylex.intellij.coloredbrackets.indents.provider.IndentGuideStyleProviders
import com.chylex.intellij.coloredbrackets.settings.RainbowSettings
import com.intellij.codeHighlighting.TextEditorHighlightingPass
import com.intellij.codeInsight.highlighting.BraceMatchingUtil
import com.intellij.codeInsight.highlighting.CodeBlockSupportHandler
import com.intellij.ide.actions.ToggleZenModeAction
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.IndentGuideDescriptor
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.MarkupModel
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.Segment
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.TokenSet
import com.intellij.util.DocumentUtil
import com.intellij.util.text.CharArrayUtil
import it.unimi.dsi.fastutil.ints.IntArrayList
import java.lang.StrictMath.abs
import java.lang.StrictMath.min

/** From [com.intellij.codeInsight.daemon.impl.IndentsPass]
 * Commit history: https://sourcegraph.com/github.com/JetBrains/intellij-community/-/blob/platform/lang-impl/src/com/intellij/codeInsight/daemon/impl/IndentsPass.java#tab=history
 * mirror changes start from `Make it possible to ignore indent guides more granularly and do so for C#`
 * */
class RainbowIndentsPass internal constructor(
	project: Project,
	private val myEditor: Editor,
	private val myFile: PsiFile,
) : TextEditorHighlightingPass(project, myEditor.document, false), DumbAware {
	
	@Volatile
	private var myRanges = emptyList<StyledRange>()
	
	@Volatile
	private var myDescriptors = emptyList<IndentGuideDescriptor>()
	
	override fun doCollectInformation(progress: ProgressIndicator) {
		val stamp = myEditor.getUserData(LAST_TIME_INDENTS_BUILT)
		if (stamp != null && stamp == nowStamp()) return
		
		myDescriptors = buildDescriptors()
		
		val candidates = ArrayList<IndentGuideCandidate>()
		for (descriptor in myDescriptors) {
			ProgressManager.checkCanceled()
			val endOffset = if (descriptor.endLine < document.lineCount) {
				document.getLineStartOffset(descriptor.endLine)
			}
			else {
				document.textLength
			}
			candidates.add(IndentGuideCandidate(
				descriptor,
				TextRange(document.getLineStartOffset(descriptor.startLine), endOffset),
			))
		}

		val context = IndentGuideStyleContext(
			file = myFile,
			document = document,
			indentSize = EditorUtil.getTabSize(myEditor).coerceAtLeast(1),
		)
		val styles = HashMap<IndentGuideCandidate, IndentGuideStyle>()
		for (provider in IndentGuideStyleProviders.allForLanguage(myFile.language)) {
			ProgressManager.checkCanceled()
			for ((candidate, style) in provider.getStyles(context, candidates)) {
				val currentStyle = styles[candidate]
				if (currentStyle == null || style.precedence > currentStyle.precedence) {
					styles[candidate] = style
				}
			}
		}

		myRanges = candidates
			.map { candidate -> StyledRange(candidate.range, styles[candidate]) }
			.sortedWith { first, second -> Segment.BY_START_OFFSET_THEN_END_OFFSET.compare(first.range, second.range) }
	}
	
	private fun nowStamp(): Long = if (isRainbowIndentGuidesShown(this.myProject)) document.modificationStamp xor (EditorUtil.getTabSize(myEditor).toLong() shl 24) else -1
	
	override fun doApplyInformationToEditor() {
		val stamp = myEditor.getUserData(LAST_TIME_INDENTS_BUILT)
		val nowStamp = nowStamp()
		
		if (stamp == nowStamp) return
		
		myEditor.putUserData(LAST_TIME_INDENTS_BUILT, nowStamp)
		
		val oldHighlighters = myEditor.getUserData(INDENT_HIGHLIGHTERS_IN_EDITOR_KEY)
		if (nowStamp == -1L) {
			if (oldHighlighters != null) {
				for (oldHighlighter in oldHighlighters) {
					oldHighlighter.dispose()
				}
				oldHighlighters.clear()
			}
			return
		}
		
		val newHighlighters = ArrayList<RangeHighlighter>()
		val mm = myEditor.markupModel
		var curRange = 0
		
		if (oldHighlighters != null) {
			// after document change some range highlighters could have become invalid, or the order could have been broken
			oldHighlighters.sortWith(Comparator.comparing { h: RangeHighlighter -> !h.isValid }
				.thenComparing(Segment.BY_START_OFFSET_THEN_END_OFFSET))
			
			var curHighlight = 0
			while (curRange < myRanges.size && curHighlight < oldHighlighters.size) {
				val styledRange = myRanges[curRange]
				val range = styledRange.range
				val highlighter = oldHighlighters[curHighlight]
				if (!highlighter.isValid) break
				
				val cmp = compare(range, highlighter)
				when {
					cmp < 0 -> {
						newHighlighters.add(createHighlighter(mm, range, styledRange.style))
						curRange++
					}
					
					cmp > 0 -> {
						highlighter.dispose()
						curHighlight++
					}
					
					else    -> {
						highlighter.putUserData(INDENT_GUIDE_STYLE_KEY, styledRange.style)
						newHighlighters.add(highlighter)
						curHighlight++
						curRange++
					}
				}
			}
			
			while (curHighlight < oldHighlighters.size) {
				val highlighter = oldHighlighters[curHighlight]
				if (!highlighter.isValid) break
				highlighter.dispose()
				curHighlight++
			}
		}
		
		val startRangeIndex = curRange
		DocumentUtil.executeInBulk(document, myRanges.size > 10000) {
			for (i in startRangeIndex until myRanges.size) {
				val styledRange = myRanges[i]
				newHighlighters.add(createHighlighter(mm, styledRange.range, styledRange.style))
			}
		}
		
		myEditor.putUserData(INDENT_HIGHLIGHTERS_IN_EDITOR_KEY, newHighlighters)
		myEditor.putUserData(ACTIVE_GUIDE_CACHE_KEY, null)
		myEditor.indentsModel.assumeIndents(myDescriptors)
	}
	
	private fun buildDescriptors(): List<IndentGuideDescriptor> {
		if (!isRainbowIndentGuidesShown(this.myProject)) return emptyList()
		
		val calculator = IndentsCalculator()
		calculator.calculate()
		val lineIndents = calculator.lineIndents
		
		val lines = IntArrayList()
		val indents = IntArrayList()
		
		lines.push(0)
		indents.push(0)
		val descriptors = ArrayList<IndentGuideDescriptor>()
		for (line in 1 until lineIndents.size) {
			ProgressManager.checkCanceled()
			val curIndent = abs(lineIndents[line])
			
			while (!indents.isEmpty && curIndent <= indents.peekInt(0)) {
				ProgressManager.checkCanceled()
				val level = indents.popInt()
				val startLine = lines.popInt()
				if (level > 0) {
					for (i in startLine until line) {
						if (level != abs(lineIndents[i])) {
							descriptors.add(createDescriptor(level, startLine, line, lineIndents))
							break
						}
					}
				}
			}
			
			val prevLine = line - 1
			val prevIndent = abs(lineIndents[prevLine])
			
			if (curIndent - prevIndent > 1) {
				lines.push(prevLine)
				indents.push(prevIndent)
			}
		}
		
		while (!indents.isEmpty) {
			ProgressManager.checkCanceled()
			val level = indents.popInt()
			val startLine = lines.popInt()
			if (level > 0) {
				descriptors.add(createDescriptor(level, startLine, document.lineCount, lineIndents))
			}
		}
		return descriptors
	}
	
	private fun createDescriptor(
		level: Int,
		startLine: Int,
		endLine: Int,
		lineIndents: IntArray,
	): IndentGuideDescriptor {
		var sLine = startLine
		while (sLine > 0 && lineIndents[sLine] < 0) sLine--
		// int codeConstructStartLine = findCodeConstructStartLine(startLine);
		return IndentGuideDescriptor(level, sLine, endLine)
	}
	
	private inner class IndentsCalculator {
		val myComments: MutableMap<String, TokenSet> = HashMap()
		val lineIndents = IntArray(document.lineCount) // negative value means the line is empty (or contains a comment) and indent
		
		// (denoted by absolute value) was deduced from enclosing non-empty lines
		val myChars = document.charsSequence
		
		/**
		 * Calculates line indents for the [target document][.myDocument].
		 */
		fun calculate() {
			val fileType = myFile.fileType
			val tabSize = EditorUtil.getTabSize(myEditor)
			
			for (line in lineIndents.indices) {
				ProgressManager.checkCanceled()
				val lineStart = document.getLineStartOffset(line)
				val lineEnd = document.getLineEndOffset(line)
				var offset = lineStart
				var column = 0
				outer@ while (offset < lineEnd) {
					when (myChars[offset]) {
						' '  -> column++
						'\t' -> column = (column / tabSize + 1) * tabSize
						else -> break@outer
					}
					offset++
				}
				// treating commented lines in the same way as empty lines
				// Blank line marker
				lineIndents[line] = if (offset == lineEnd || isComment(offset)) -1 else column
			}
			
			var topIndent = 0
			var line = 0
			while (line < lineIndents.size) {
				ProgressManager.checkCanceled()
				if (lineIndents[line] >= 0) {
					topIndent = lineIndents[line]
				}
				else {
					val startLine = line
					while (line < lineIndents.size && lineIndents[line] < 0) {
						line++
					}
					
					val bottomIndent = if (line < lineIndents.size) lineIndents[line] else topIndent
					
					var indent = min(topIndent, bottomIndent)
					if (bottomIndent < topIndent) {
						val lineStart = document.getLineStartOffset(line)
						val lineEnd = document.getLineEndOffset(line)
						val nonWhitespaceOffset = CharArrayUtil.shiftForward(myChars, lineStart, lineEnd, " \t")
						val iterator = myEditor.highlighter.createIterator(nonWhitespaceOffset)
						val tokenType = iterator.tokenType
						if (BraceMatchingUtil.isRBraceToken(iterator, myChars, fileType) ||
							tokenType != null &&
							CodeBlockSupportHandler.findMarkersRanges(myFile, tokenType.language, nonWhitespaceOffset).isNotEmpty()
						) {
							indent = topIndent
						}
					}
					
					for (blankLine in startLine until line) {
						assert(lineIndents[blankLine] == -1)
						lineIndents[blankLine] = -min(topIndent, indent)
					}
					
					
					line-- // will be incremented back at the end of the loop;
				}
				line++
			}
		}
		
		private fun isComment(offset: Int): Boolean {
			val it = myEditor.highlighter.createIterator(offset)
			val tokenType = try {
				it.tokenType
			} catch (e: Throwable) {
				return false
			}
			val language = tokenType.language
			var comments: TokenSet? = myComments[language.id]
			if (comments == null) {
				val definition = LanguageParserDefinitions.INSTANCE.forLanguage(language)
				if (definition != null) {
					comments = definition.commentTokens
				}
				if (comments == null) {
					return false
				}
				else {
					myComments[language.id] = comments
				}
			}
			return comments.contains(tokenType)
		}
	}
	
	companion object {
		private val INDENT_HIGHLIGHTERS_IN_EDITOR_KEY = Key.create<MutableList<RangeHighlighter>>("_INDENT_HIGHLIGHTERS_IN_EDITOR_KEY_")
		private val INDENT_GUIDE_STYLE_KEY = Key.create<IndentGuideStyle>("_INDENT_GUIDE_STYLE_KEY_")
		private val ACTIVE_GUIDE_CACHE_KEY = Key.create<ActiveGuideCache>("_ACTIVE_INDENT_GUIDE_CACHE_KEY_")
		private val LAST_TIME_INDENTS_BUILT = Key.create<Long>("_LAST_TIME_INDENTS_BUILT_")
		
		private val RENDERER = RainbowIndentGuideRenderer()
		
		private data class ActiveGuideCache(
			val caretOffset: Int,
			val highlighters: List<RangeHighlighter>?,
			val active: RangeHighlighter?,
		)

		private fun findInnermostGuideAtCaret(editor: Editor): RangeHighlighter? {
			val caretOffset = editor.caretModel.offset
			return editor.getUserData(INDENT_HIGHLIGHTERS_IN_EDITOR_KEY)
				?.asSequence()
				?.filter { it.isValid && caretOffset in it.startOffset until it.endOffset }
				?.maxWithOrNull(compareBy<RangeHighlighter> { it.startOffset }.thenBy { -it.endOffset })
		}

		private fun activeGuide(editor: Editor): RangeHighlighter? {
			val caretOffset = editor.caretModel.offset
			val highlighters = editor.getUserData(INDENT_HIGHLIGHTERS_IN_EDITOR_KEY)
			val cached = editor.getUserData(ACTIVE_GUIDE_CACHE_KEY)
			if (cached != null && cached.caretOffset == caretOffset && cached.highlighters === highlighters) {
				return cached.active
			}

			return findInnermostGuideAtCaret(editor).also {
				editor.putUserData(ACTIVE_GUIDE_CACHE_KEY, ActiveGuideCache(caretOffset, highlighters, it))
			}
		}

		internal fun isInnermostGuideAtCaret(editor: Editor, highlighter: RangeHighlighter): Boolean {
			return activeGuide(editor) === highlighter
		}

		internal fun caretPositionChanged(editor: Editor) {
			val previous = editor.getUserData(ACTIVE_GUIDE_CACHE_KEY)?.active
			editor.putUserData(ACTIVE_GUIDE_CACHE_KEY, null)
			val current = activeGuide(editor)

			if (previous !== current) {
				editor.contentComponent.repaint()
			}
		}

		internal fun getStyle(highlighter: RangeHighlighter): IndentGuideStyle? =
			highlighter.getUserData(INDENT_GUIDE_STYLE_KEY)

		private fun isRainbowIndentGuidesShown(project: Project): Boolean {
			if (RainbowSettings.instance.disableRainbowIndentsInZenMode && isZenModeEnabled(project)) {
				return false
			}
			return RainbowSettings.instance.isRainbowEnabled && RainbowSettings.instance.isShowRainbowIndentGuides
		}
		
		private fun isZenModeEnabled(project: Project) =
			ToggleZenModeAction.isZenModeEnabled(project)
		
		private fun createHighlighter(mm: MarkupModel, range: TextRange, style: IndentGuideStyle?): RangeHighlighter {
			return mm.addRangeHighlighter(
				range.startOffset,
				range.endOffset,
				0,
				null,
				HighlighterTargetArea.EXACT_RANGE
			).apply {
				putUserData(INDENT_GUIDE_STYLE_KEY, style)
				customRenderer = RENDERER
			}
		}
		
		private fun compare(r: TextRange, h: RangeHighlighter): Int {
			val answer = r.startOffset - h.startOffset
			return if (answer != 0) answer else r.endOffset - h.endOffset
		}
	}

	private data class StyledRange(
		val range: TextRange,
		val style: IndentGuideStyle?,
	)
}
