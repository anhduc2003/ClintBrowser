package com.jhaiian.clint.comictranslate

import android.graphics.Rect

private const val MAX_VERTICAL_GAP_FACTOR = 0.9f
private const val MIN_HORIZONTAL_OVERLAP = 0.25f
private const val MIN_LETTERS_PER_BLOCK = 2

/**
 * Comic pages have no structure to lean on, so lines are stitched into speech bubbles by
 * geometry: a line joins a block when it sits right under the block and mostly shares its
 * horizontal span.
 */
fun groupComicLines(lines: List<ComicOcrLine>): List<ComicTextBlock> {
    val sorted = lines.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
    val groups = mutableListOf<MutableList<ComicOcrLine>>()
    for (line in sorted) {
        val height = line.bounds.height().coerceAtLeast(1)
        var target: MutableList<ComicOcrLine>? = null
        var smallestGap = Float.MAX_VALUE
        for (group in groups) {
            val last = group.last()
            val gap = (line.bounds.top - last.bounds.bottom).toFloat()
            val maxGap = MAX_VERTICAL_GAP_FACTOR * maxOf(height, last.bounds.height())
            if (gap > maxGap || gap < -height) continue
            if (horizontalOverlap(line.bounds, boundsOf(group)) < MIN_HORIZONTAL_OVERLAP) continue
            if (gap < smallestGap) {
                smallestGap = gap
                target = group
            }
        }
        if (target != null) target.add(line) else groups.add(mutableListOf(line))
    }
    return groups.mapNotNull { group ->
        val text = group.joinToString(" ") { it.text }.replace(Regex("\\s+"), " ").trim()
        if (text.count { it.isLetterOrDigit() } < MIN_LETTERS_PER_BLOCK) return@mapNotNull null
        ComicTextBlock(bounds = boundsOf(group), original = text)
    }
}

private fun boundsOf(group: List<ComicOcrLine>): Rect {
    val bounds = Rect(group.first().bounds)
    group.forEach { bounds.union(it.bounds) }
    return bounds
}

private fun horizontalOverlap(first: Rect, second: Rect): Float {
    val overlap = minOf(first.right, second.right) - maxOf(first.left, second.left)
    if (overlap <= 0) return 0f
    return overlap.toFloat() / minOf(first.width(), second.width()).coerceAtLeast(1)
}
