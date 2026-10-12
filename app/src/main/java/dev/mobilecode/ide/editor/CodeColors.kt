package dev.mobilecode.ide.editor

import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/** Token IDs are shared with syntax.cpp; custom Sora colors start at 256. */
object SyntaxStyle {
    const val STRING=256
    const val NUMBER=257
    const val TYPE=258
    const val PREPROCESSOR=259
    const val QUALIFIER=260
    const val BRACKET=261
    fun color(token: Int): Int = when(token) {
        1 -> EditorColorScheme.KEYWORD
        2 -> STRING
        3 -> EditorColorScheme.COMMENT
        4 -> NUMBER
        5 -> TYPE
        6 -> EditorColorScheme.FUNCTION_NAME
        7 -> PREPROCESSOR
        8 -> QUALIFIER
        9 -> EditorColorScheme.IDENTIFIER_VAR
        10 -> EditorColorScheme.OPERATOR
        in 12..14 -> BRACKET+token-12
        else -> EditorColorScheme.TEXT_NORMAL
    }
}

class CodeColors(dark: Boolean): EditorColorScheme(dark) {
    init {
        fun color(id: Int,night: Long,day: Long) = setColor(id,(if(dark) night else day).toInt())
        color(WHOLE_BACKGROUND,0xFF111213,0xFFFAFAF8)
        color(LINE_NUMBER_BACKGROUND,0xFF111213,0xFFFAFAF8)
        color(TEXT_NORMAL,0xFFD9E2EE,0xFF263445)
        color(TEXT_SELECTED,0xFFEAF2FF,0xFF152B42)
        color(LINE_NUMBER,0xFF8290A1,0xFF737D89)
        color(LINE_NUMBER_CURRENT,0xFFD9E2EE,0xFF263445)
        color(CURRENT_LINE,0xFF1B1E22,0xFFEEF1F4)
        color(LINE_DIVIDER,0xFF2B3036,0xFFDDE2E7)
        color(BLOCK_LINE,0xFF35393F,0xFFD9DEE4)
        color(BLOCK_LINE_CURRENT,0xFF596575,0xFF9AA8B6)
        color(KEYWORD,0xFFFF574C,0xFFC02C27)
        color(SyntaxStyle.QUALIFIER,0xFF27AEFF,0xFF0068A8)
        color(SyntaxStyle.TYPE,0xFF00DFCE,0xFF007D73)
        color(IDENTIFIER_NAME,0xFF00DFCE,0xFF007D73)
        color(IDENTIFIER_VAR,0xFFD9E2EE,0xFF263445)
        color(FUNCTION_NAME,0xFFD69AFF,0xFF8240AD)
        color(SyntaxStyle.PREPROCESSOR,0xFFF081EF,0xFF9F287E)
        color(SyntaxStyle.STRING,0xFF82D6FF,0xFF076B9F)
        color(SyntaxStyle.NUMBER,0xFFFFCC68,0xFF985000)
        color(LITERAL,0xFFFFCC68,0xFF985000)
        color(COMMENT,0xFF849BB5,0xFF61758A)
        color(OPERATOR,0xFFCDD9EA,0xFF46576C)
        color(SyntaxStyle.BRACKET,0xFFFFDF00,0xFF806700)
        color(SyntaxStyle.BRACKET+1,0xFFFF70EE,0xFF9F287E)
        color(SyntaxStyle.BRACKET+2,0xFF2CCBFF,0xFF0068A8)
        color(SELECTION_INSERT,0xFF82D6FF,0xFF0068A8)
        color(SELECTION_HANDLE,0xFF82D6FF,0xFF0068A8)
        color(SELECTED_TEXT_BACKGROUND,0xFF293D56,0xFFD5E8F9)
        color(MATCHED_TEXT_BACKGROUND,0xFF3E3A20,0xFFFFEBA9)
        color(HIGHLIGHTED_DELIMITERS_FOREGROUND,0xFFFFDF00,0xFF806700)
        color(HIGHLIGHTED_DELIMITERS_BACKGROUND,0xFF333329,0xFFF0EACC)
        color(COMPLETION_WND_BACKGROUND,0xFF202429,0xFFF4F6F8)
        color(COMPLETION_WND_CORNER,0xFF414B57,0xFFBAC5D0)
        color(COMPLETION_WND_TEXT_PRIMARY,0xFFD9E2EE,0xFF263445)
        color(COMPLETION_WND_TEXT_SECONDARY,0xFF96A6B9,0xFF61758A)
        color(COMPLETION_WND_TEXT_MATCHED,0xFF82D6FF,0xFF0068A8)
        color(COMPLETION_WND_ITEM_CURRENT,0xFF2D3A49,0xFFDDEAF6)
        color(PROBLEM_ERROR,0xFFFF574C,0xFFC02C27)
        color(PROBLEM_WARNING,0xFFFFCC68,0xFF985000)
        color(DIAGNOSTIC_TOOLTIP_BACKGROUND,0xFF202429,0xFFF4F6F8)
        color(DIAGNOSTIC_TOOLTIP_BRIEF_MSG,0xFFD9E2EE,0xFF263445)
        color(DIAGNOSTIC_TOOLTIP_DETAILED_MSG,0xFF96A6B9,0xFF61758A)
        color(DIAGNOSTIC_TOOLTIP_ACTION,0xFF82D6FF,0xFF0068A8)
        color(TEXT_ACTION_WINDOW_BACKGROUND,0xFF202429,0xFFF4F6F8)
        color(TEXT_ACTION_WINDOW_ICON_COLOR,0xFFD9E2EE,0xFF263445)
    }
}
