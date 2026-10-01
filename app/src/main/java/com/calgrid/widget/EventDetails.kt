package com.calgrid.widget

private val locationWhitespace = Regex("[\\s\\p{Z}\\u0085]+")

internal fun eventDetails(time: String, location: String?): String {
    // Calendar locations can contain newlines. maxLines = 1 would ellipsize at the first
    // newline even when there is room for the rest of the address on the same line.
    val singleLineLocation = location?.replace(locationWhitespace, " ")?.trim().orEmpty()
    return if (singleLineLocation.isEmpty()) time else "$time · $singleLineLocation"
}
