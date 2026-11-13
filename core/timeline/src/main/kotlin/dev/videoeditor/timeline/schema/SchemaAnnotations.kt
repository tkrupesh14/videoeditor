@file:OptIn(ExperimentalSerializationApi::class)

package dev.videoeditor.timeline.schema

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialInfo

/** Description of a tool / object (on a class) or of a property. Emitted as JSON Schema `description`. */
@SerialInfo
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
annotation class Desc(
    val text: String,
)

/** Inclusive lower bound of an integer or number property. Emitted as JSON Schema `minimum`. */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class Min(
    val value: Long,
)
