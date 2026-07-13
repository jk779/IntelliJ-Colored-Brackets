package com.chylex.intellij.coloredbrackets

import com.chylex.intellij.coloredbrackets.indents.provider.IndentGuideCandidate
import com.chylex.intellij.coloredbrackets.indents.provider.IndentGuideStyle
import com.chylex.intellij.coloredbrackets.indents.provider.IndentGuideStyleContext
import com.chylex.intellij.coloredbrackets.indents.provider.IndentGuideStyleProviders
import com.chylex.intellij.coloredbrackets.indents.provider.RubyIndentGuideStyleProvider
import com.chylex.intellij.coloredbrackets.indents.provider.YamlIndentGuideStyleProvider
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.IndentGuideDescriptor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import org.jetbrains.plugins.ruby.ruby.lang.RubyFileType
import org.jetbrains.yaml.YAMLFileType

class IndentGuideStyleProviderTest : LightJavaCodeInsightFixtureTestCase() {
	fun testRubyProviderPreservesNestedBlockLevelsAcrossCommentsAndBlankLines() {
		val code = """
			class Example
			  # before the nested block
			  if true
			    puts true
			  end
			
			end
		""".trimIndent()
		myFixture.configureByText(RubyFileType.RUBY, code)
		val document = committedDocument()
		val outer = candidate(document, indent = 2, startLine = 0, endLine = document.lineCount)
		val inner = candidate(document, indent = 4, startLine = 2, endLine = 5)

		val provider = IndentGuideStyleProviders.allForLanguage(myFixture.file.language).singleOrNull()
		assertTrue(provider is RubyIndentGuideStyleProvider)
		val styles = provider!!.getStyles(
			context(document, indentSize = 2),
			listOf(outer, inner),
		)

		styles[outer].shouldBeStyle(level = 0, overrideBracketColor = true)
		styles[inner].shouldBeStyle(level = 1, overrideBracketColor = true)
	}

	fun testYamlProviderUsesStructuralDepthWithUnevenIndentation() {
		val code = """
			root:
			  - child:
			        leaf: true
		""".trimIndent()
		myFixture.configureByText(YAMLFileType.YML, code)
		val document = committedDocument()
		val outer = candidate(document, indent = 2, startLine = 0, endLine = document.lineCount)
		val inner = candidate(document, indent = 8, startLine = 1, endLine = document.lineCount)

		val provider = IndentGuideStyleProviders.allForLanguage(myFixture.file.language).singleOrNull()
		assertTrue(provider is YamlIndentGuideStyleProvider)
		val styles = provider!!.getStyles(
			context(document, indentSize = 2),
			listOf(outer, inner),
		)

		styles[outer].shouldBeStyle(level = 0)
		styles[inner].shouldBeStyle(level = 1)
	}

	fun testYamlProviderIgnoresBlockScalarIndentation() {
		val code = """
			root:
			  message: |
			    first line
			      deliberately indented text
			  sibling:
			    value: true
		""".trimIndent()
		myFixture.configureByText(YAMLFileType.YML, code)
		val document = committedDocument()
		val outer = candidate(document, indent = 2, startLine = 0, endLine = document.lineCount)
		val scalar = candidate(document, indent = 4, startLine = 1, endLine = 4)
		val scalarIndent = candidate(document, indent = 6, startLine = 2, endLine = 4)
		val sibling = candidate(document, indent = 4, startLine = 4, endLine = document.lineCount)

		val provider = IndentGuideStyleProviders.allForLanguage(myFixture.file.language).singleOrNull()
		assertTrue(provider is YamlIndentGuideStyleProvider)
		val styles = provider!!.getStyles(
			context(document, indentSize = 2),
			listOf(outer, scalar, scalarIndent, sibling),
		)

		styles[outer].shouldBeStyle(level = 0)
		assertNull(styles[scalar])
		assertNull(styles[scalarIndent])
		styles[sibling].shouldBeStyle(level = 1)
	}

	private fun committedDocument(): Document {
		PsiDocumentManager.getInstance(project).commitAllDocuments()
		return myFixture.editor.document
	}

	private fun context(document: Document, indentSize: Int) = IndentGuideStyleContext(
		file = myFixture.file,
		document = document,
		indentSize = indentSize,
	)

	private fun candidate(
		document: Document,
		indent: Int,
		startLine: Int,
		endLine: Int,
	): IndentGuideCandidate {
		val endOffset = if (endLine < document.lineCount) {
			document.getLineStartOffset(endLine)
		}
		else {
			document.textLength
		}
		return IndentGuideCandidate(
			IndentGuideDescriptor(indent, startLine, endLine),
			TextRange(document.getLineStartOffset(startLine), endOffset),
		)
	}

	private fun IndentGuideStyle?.shouldBeStyle(
		level: Int,
		overrideBracketColor: Boolean = false,
	) {
		assertNotNull(this)
		assertEquals(level, this?.level)
		assertEquals(
			if (overrideBracketColor) IndentGuideStyle.Precedence.OVERRIDE_BRACKET_COLOR else IndentGuideStyle.Precedence.FALLBACK,
			this?.precedence,
		)
	}
}
