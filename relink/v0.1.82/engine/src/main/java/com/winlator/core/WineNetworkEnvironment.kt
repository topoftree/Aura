/* Aura 0.1.82 local added file; modification notice dated 2026-10-19. See MODIFICATIONS.tsv and NOTICE.md; original third-party licenses are retained. */
package com.winlator.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress

/** Supply the DNS list advertised by the bundled Wine dnsapi, using this app's default network. */
object WineNetworkEnvironment {
    const val DNS_VARIABLE = "ANDROID_RESOLV_DNS"
    const val NDIS_VARIABLE = "WINE_NEW_NDIS"

    enum class State { ACTIVE, NO_NETWORK, UNAVAILABLE }

    data class Snapshot(
        val state: State,
        val dnsServers: List<InetAddress> = emptyList(),
        val vpn: Boolean? = null,
        val privateDns: Boolean? = null,
    )

    @JvmStatic
    fun read(context: Context): Snapshot = try {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager?.activeNetwork
        when {
            manager == null -> Snapshot(State.UNAVAILABLE)
            network == null -> Snapshot(State.NO_NETWORK)
            else -> {
                val properties = manager.getLinkProperties(network)
                if (properties == null) Snapshot(State.UNAVAILABLE)
                else Snapshot(State.ACTIVE, properties.dnsServers.toList(),
                    manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                    properties.isPrivateDnsActive)
            }
        }
    } catch (_: SecurityException) {
        // An unavailable snapshot must not prevent an offline game or expose platform error text.
        Snapshot(State.UNAVAILABLE)
    }

    /** Existing per-container/per-launch overrides win. Never resolve a name or send a DNS query here. */
    @JvmStatic
    fun apply(env: EnvVars, snapshot: Snapshot): String {
        val candidates = if (snapshot.state == State.ACTIVE) snapshot.dnsServers.filter {
            it is Inet4Address && !it.isAnyLocalAddress && !it.isMulticastAddress
        }.distinct() else emptyList()
        val mode = when {
            env.has(DNS_VARIABLE) -> "CUSTOM"
            candidates.isNotEmpty() -> {
                // This Wine build accepts ONE numeric IPv4 address, not a list or IPv6 literal.
                // Its otherwise hard-coded 8.8.4.4 list can differ from Android's resolver.
                env.put(DNS_VARIABLE, candidates.first().hostAddress)
                "ANDROID_IPV4"
            }
            else -> "RUNTIME_DEFAULT"
        }
        val ndis = when {
            !env.has(NDIS_VARIABLE) -> "RUNTIME_DEFAULT"
            env.get(NDIS_VARIABLE) == "1" -> "GETIFADDRS"
            env.get(NDIS_VARIABLE) == "0" -> "IF_NAMEINDEX"
            else -> "CUSTOM"
        }
        // Do not record addresses, interface names, private-DNS hostnames, or exception messages.
        return "WINE_NETWORK_V1 state=${snapshot.state}; dns=$mode; ipv4_dns_count=${candidates.size}; " +
            "vpn=${snapshot.vpn ?: "?"}; private_dns=${snapshot.privateDns ?: "?"}; ndis=$ndis; snapshot=LAUNCH"
    }
}
