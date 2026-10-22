package com.example.earthquack.testutil

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

/**
 * Shared test fixture helpers for generating cryptographic keys in unit tests.
 */
object TestKeyUtils {
    fun generateEc(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        return kpg.generateKeyPair()
    }

    fun freePort(): Int = try {
        java.net.ServerSocket(0).use { it.localPort }
    } catch (e: java.io.IOException) {
        (20000..40000).random()
    }
}
