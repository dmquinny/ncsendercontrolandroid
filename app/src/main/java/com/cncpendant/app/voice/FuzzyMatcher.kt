package com.cncpendant.app.voice

import kotlin.math.min

/**
 * Fuzzy string matching utilities for more forgiving voice command recognition.
 * Uses Levenshtein distance and other techniques to handle speech variations.
 */
object FuzzyMatcher {
    
    /**
     * Calculate Levenshtein distance between two strings
     */
    fun levenshteinDistance(s1: String, s2: String): Int {
        val len1 = s1.length
        val len2 = s2.length
        
        if (len1 == 0) return len2
        if (len2 == 0) return len1
        
        val dp = Array(len1 + 1) { IntArray(len2 + 1) }
        
        for (i in 0..len1) dp[i][0] = i
        for (j in 0..len2) dp[0][j] = j
        
        for (i in 1..len1) {
            for (j in 1..len2) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = min(
                    min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        
        return dp[len1][len2]
    }
    
    /**
     * Calculate similarity ratio (0.0 to 1.0) between two strings
     */
    fun similarity(s1: String, s2: String): Float {
        val maxLen = maxOf(s1.length, s2.length)
        if (maxLen == 0) return 1.0f
        val distance = levenshteinDistance(s1.lowercase(), s2.lowercase())
        return 1.0f - (distance.toFloat() / maxLen)
    }
    
    /**
     * Check if two strings are similar within a threshold
     * @param threshold Minimum similarity (0.0 to 1.0), default 0.7 (70% similar)
     */
    fun isSimilar(s1: String, s2: String, threshold: Float = 0.7f): Boolean {
        return similarity(s1, s2) >= threshold
    }
    
    /**
     * Find the best matching string from a list of candidates
     * @return Pair of (best match, similarity score) or null if no match above threshold
     */
    fun findBestMatch(input: String, candidates: List<String>, threshold: Float = 0.6f): Pair<String, Float>? {
        var bestMatch: String? = null
        var bestScore = 0f
        
        for (candidate in candidates) {
            val score = similarity(input, candidate)
            if (score > bestScore) {
                bestScore = score
                bestMatch = candidate
            }
        }
        
        return if (bestMatch != null && bestScore >= threshold) {
            Pair(bestMatch, bestScore)
        } else null
    }
    
    /**
     * Check if input contains any of the patterns (with fuzzy matching)
     * @return The matched pattern or null
     */
    fun fuzzyContains(input: String, patterns: List<String>, threshold: Float = 0.75f): String? {
        val words = input.split(" ")
        
        for (pattern in patterns) {
            // Direct contains check first (fastest)
            if (input.contains(pattern)) {
                return pattern
            }
            
            // Check each word for fuzzy match
            val patternWords = pattern.split(" ")
            if (patternWords.size == 1) {
                // Single word pattern - check against each input word
                for (word in words) {
                    if (isSimilar(word, pattern, threshold)) {
                        return pattern
                    }
                }
            } else {
                // Multi-word pattern - check sliding window
                for (i in 0..(words.size - patternWords.size)) {
                    val window = words.subList(i, i + patternWords.size).joinToString(" ")
                    if (isSimilar(window, pattern, threshold)) {
                        return pattern
                    }
                }
            }
        }
        
        return null
    }
    
    /**
     * Find all fuzzy matches in input for a list of patterns
     * @return List of (pattern, matchedText, similarity) tuples
     */
    fun findAllMatches(
        input: String, 
        patterns: List<String>, 
        threshold: Float = 0.7f
    ): List<Triple<String, String, Float>> {
        val matches = mutableListOf<Triple<String, String, Float>>()
        val words = input.split(" ")
        
        for (pattern in patterns) {
            val patternWords = pattern.split(" ")
            
            if (patternWords.size == 1) {
                for (word in words) {
                    val score = similarity(word, pattern)
                    if (score >= threshold) {
                        matches.add(Triple(pattern, word, score))
                    }
                }
            } else {
                for (i in 0..(words.size - patternWords.size)) {
                    val window = words.subList(i, i + patternWords.size).joinToString(" ")
                    val score = similarity(window, pattern)
                    if (score >= threshold) {
                        matches.add(Triple(pattern, window, score))
                    }
                }
            }
        }
        
        return matches.sortedByDescending { it.third }
    }
    
    /**
     * Phonetic similarity check using simplified Soundex-like algorithm
     * Good for handling accent variations
     */
    fun phoneticCode(s: String): String {
        if (s.isEmpty()) return ""
        
        val input = s.uppercase()
        val result = StringBuilder()
        result.append(input[0])
        
        val mapping = mapOf(
            'B' to '1', 'F' to '1', 'P' to '1', 'V' to '1',
            'C' to '2', 'G' to '2', 'J' to '2', 'K' to '2', 'Q' to '2', 'S' to '2', 'X' to '2', 'Z' to '2',
            'D' to '3', 'T' to '3',
            'L' to '4',
            'M' to '5', 'N' to '5',
            'R' to '6'
        )
        
        var lastCode = mapping[input[0]] ?: '0'
        
        for (i in 1 until input.length) {
            val code = mapping[input[i]] ?: '0'
            if (code != '0' && code != lastCode) {
                result.append(code)
                if (result.length >= 4) break
            }
            lastCode = code
        }
        
        while (result.length < 4) {
            result.append('0')
        }
        
        return result.toString()
    }
    
    /**
     * Check phonetic similarity between two strings
     */
    fun isPhoneticallySimilar(s1: String, s2: String): Boolean {
        return phoneticCode(s1) == phoneticCode(s2)
    }
}
