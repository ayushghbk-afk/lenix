package com.lenix.installer

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

/**
 * Parses a strict-schema RootFS manifest.
 *
 * Unknown fields are ignored so older APKs can still read newer catalogs where it is
 * safe to do so, but required fields are validated hard and reported as a readable
 * [IllegalArgumentException] instead of a Jackson stacktrace.
 */
class RootfsManifestParser(
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false),
) {
    fun parse(json: String): RootfsManifest {
        val manifest = objectMapper.readValue<RootfsManifest>(json)
        validate(manifest)
        return manifest
    }

    private fun validate(manifest: RootfsManifest) {
        require(manifest.schemaVersion >= SUPPORTED_SCHEMA_VERSION) {
            "Unsupported manifest schema ${manifest.schemaVersion}; this APK supports $SUPPORTED_SCHEMA_VERSION+"
        }
        require(manifest.layers.isNotEmpty()) {
            "Manifest ${manifest.id} contains no layers."
        }
        require(manifest.signature.isNotBlank()) {
            "Manifest ${manifest.id} is missing its signature."
        }
        manifest.layers.forEach { layer ->
            require(layer.sizeBytes > 0L) { "Layer ${layer.id} has no size." }
            require(layer.uncompressedBytes > 0L) { "Layer ${layer.id} has no uncompressed size." }
            require(layer.sha256.isNotBlank()) { "Layer ${layer.id} has no sha256." }
        }
    }

    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1
    }
}
