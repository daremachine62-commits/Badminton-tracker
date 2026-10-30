package com.example.badmintontracker

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

internal data class DetectedCourt(
    val corners: List<CourtPoint>,
    val confidence: Float,
)

internal fun detectCourt(
    luminance: IntArray,
    width: Int,
    height: Int,
    courtLineMask: BooleanArray? = null,
): DetectedCourt? {
    if (luminance.size != width * height ||
        (courtLineMask != null && courtLineMask.size != luminance.size) ||
        width < 32 || height < 24
    ) {
        return null
    }

    val diagonal = kotlin.math.hypot(width.toDouble(), height.toDouble()).roundToInt()
    val rhoCount = diagonal * 2 + 1
    val accumulator = IntArray(180 * rhoCount)
    val cosines = DoubleArray(180) { angle -> cos(angle * PI / 180.0) }
    val sines = DoubleArray(180) { angle -> sin(angle * PI / 180.0) }

    for (y in 0 until height) {
        for (x in 0 until width) {
            val pixelIndex = y * width + x
            if (y < height * 0.52 || luminance[pixelIndex] < 180 ||
                (courtLineMask != null && !courtLineMask[pixelIndex])
            ) {
                continue
            }
            for (angle in 0 until 180) {
                val rho = (x * cosines[angle] + y * sines[angle]).roundToInt() + diagonal
                accumulator[angle * rhoCount + rho]++
            }
        }
    }

    val minimumVotes = max(12, min(width, height) / 5)
    val lines = ArrayList<DetectionLine>()
    for (angle in 0 until 180) {
        for (rhoIndex in 1 until rhoCount - 1) {
            val votes = accumulator[angle * rhoCount + rhoIndex]
            if (votes < minimumVotes) continue
            var isPeak = true
            for (nearAngle in max(0, angle - 3)..min(179, angle + 3)) {
                for (nearRho in max(0, rhoIndex - 4)..min(rhoCount - 1, rhoIndex + 4)) {
                    if (nearAngle == angle && nearRho == rhoIndex) continue
                    val nearbyVotes = accumulator[nearAngle * rhoCount + nearRho]
                    if (nearbyVotes > votes ||
                        (nearbyVotes == votes &&
                            (nearAngle < angle || (nearAngle == angle && nearRho < rhoIndex)))
                    ) {
                        isPeak = false
                        break
                    }
                }
                if (!isPeak) break
            }
            if (isPeak) {
                lines.add(DetectionLine(angle, rhoIndex - diagonal, votes))
            }
        }
    }

    val candidates = lines.sortedByDescending { it.votes }.take(36)
    val parallelPairs = ArrayList<ParallelLinePair>()
    for (firstIndex in candidates.indices) {
        for (secondIndex in firstIndex + 1 until candidates.size) {
            val first = candidates[firstIndex]
            val second = candidates[secondIndex]
            val angleDelta = abs(first.angle - second.angle)
            if (min(angleDelta, 180 - angleDelta) > 8) continue
            if (abs(first.rho - second.rho) < min(width, height) * 0.12) continue
            parallelPairs.add(ParallelLinePair(first, second))
        }
    }

    var best: DetectedCourt? = null
    var bestScore = Double.NEGATIVE_INFINITY
    for (firstPairIndex in parallelPairs.indices) {
        val firstPair = parallelPairs[firstPairIndex]
        for (secondPairIndex in firstPairIndex + 1 until parallelPairs.size) {
            val secondPair = parallelPairs[secondPairIndex]
            val angleDelta = abs(firstPair.first.angle - secondPair.first.angle)
            val orthogonalDelta = min(angleDelta, 180 - angleDelta)
            if (orthogonalDelta !in 45..135) continue

            val intersections = listOf(
                intersection(firstPair.first, secondPair.first),
                intersection(firstPair.first, secondPair.second),
                intersection(firstPair.second, secondPair.second),
                intersection(firstPair.second, secondPair.first),
            )
            if (intersections.any { point ->
                    point == null ||
                        point.x < -width * 0.35 || point.x > width * 1.35 ||
                        point.y < -height * 0.35 || point.y > height * 1.35
                }
            ) {
                continue
            }
            val points = intersections.filterNotNull()
            if (points.sumOf { it.y.toDouble() } / points.size < height * 0.55) continue
            val area = polygonArea(points)
            val frameArea = width.toDouble() * height
            if (area < frameArea * 0.08) continue

            val votes = firstPair.first.votes + firstPair.second.votes +
                secondPair.first.votes + secondPair.second.votes
            val areaRatio = (area / frameArea).coerceIn(0.0, 1.5)
            val score = votes * (0.2 + areaRatio)
            if (score <= bestScore) continue

            bestScore = score
            val ordered = orderCourtCorners(points, width, height)
            val confidence = (
                votes.toDouble() / (minimumVotes * 4) *
                    min(1.0, areaRatio)
                ).toFloat().coerceIn(0f, 1f)
            best = DetectedCourt(ordered, confidence)
        }
    }
    return best?.takeIf { it.confidence >= 0.18f }
}

internal fun courtLineMaskFromRgb(pixels: IntArray, width: Int, height: Int): BooleanArray? {
    if (pixels.size != width * height) return null
    val greenSurface = BooleanArray(pixels.size)
    for (index in pixels.indices) {
        val color = pixels[index]
        val red = color shr 16 and 0xff
        val green = color shr 8 and 0xff
        val blue = color and 0xff
        greenSurface[index] = green > 70 && green - red > 20 && green - blue > 12
    }

    return BooleanArray(pixels.size) { index ->
        val color = pixels[index]
        val red = color shr 16 and 0xff
        val green = color shr 8 and 0xff
        val blue = color and 0xff
        val minimum = min(red, min(green, blue))
        val maximum = max(red, max(green, blue))
        if (minimum < 130 || maximum - minimum > 90) {
            false
        } else {
            val x = index % width
            val y = index / width
            var adjacentToGreen = false
            for (nearY in max(0, y - 2)..min(height - 1, y + 2)) {
                for (nearX in max(0, x - 2)..min(width - 1, x + 2)) {
                    if (greenSurface[nearY * width + nearX]) {
                        adjacentToGreen = true
                        break
                    }
                }
                if (adjacentToGreen) break
            }
            adjacentToGreen
        }
    }
}

private data class DetectionLine(val angle: Int, val rho: Int, val votes: Int)

private data class ParallelLinePair(
    val first: DetectionLine,
    val second: DetectionLine,
)

private fun intersection(first: DetectionLine, second: DetectionLine): CourtPoint? {
    val firstAngle = first.angle * PI / 180.0
    val secondAngle = second.angle * PI / 180.0
    val firstCosine = cos(firstAngle)
    val firstSine = sin(firstAngle)
    val secondCosine = cos(secondAngle)
    val secondSine = sin(secondAngle)
    val determinant = firstCosine * secondSine - secondCosine * firstSine
    if (abs(determinant) < 1e-6) return null
    val x = (first.rho * secondSine - second.rho * firstSine) / determinant
    val y = (firstCosine * second.rho - secondCosine * first.rho) / determinant
    return if (x.isFinite() && y.isFinite()) CourtPoint(x.toFloat(), y.toFloat()) else null
}

private fun polygonArea(points: List<CourtPoint>): Double {
    var twiceArea = 0.0
    points.indices.forEach { index ->
        val current = points[index]
        val next = points[(index + 1) % points.size]
        twiceArea += current.x * next.y - next.x * current.y
    }
    return abs(twiceArea) / 2.0
}

private fun orderCourtCorners(
    points: List<CourtPoint>,
    width: Int,
    height: Int,
): List<CourtPoint> {
    fun normalized(point: CourtPoint) = CourtPoint(point.x / width, point.y / height)

    return listOf(
        normalized(points.minBy { it.x + it.y }),
        normalized(points.maxBy { it.x - it.y }),
        normalized(points.maxBy { it.x + it.y }),
        normalized(points.minBy { it.x - it.y }),
    )
}
