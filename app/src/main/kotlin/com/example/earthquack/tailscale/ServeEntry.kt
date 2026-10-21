package com.example.earthquack.tailscale

data class ServeEntry(
    val localPort: Int,
    val tailnetPort: Int,
    val tls: Boolean = false,
    val funnel: Boolean = false,
)