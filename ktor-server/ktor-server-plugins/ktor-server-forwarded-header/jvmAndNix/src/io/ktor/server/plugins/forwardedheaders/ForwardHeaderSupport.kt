/*
 * Copyright 2014-2022 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.plugins.forwardedheaders

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.util.*

/**
 * `X-Forwarded-*` headers support
 * See http://ktor.io/servers/features/forward-headers.html for details
 */
public object XForwardedHeaderSupport :
    ApplicationPlugin<ApplicationCallPipeline, XForwardedHeaderSupport.Config, XForwardedHeaderSupport.Config> {

    /**
     * Values of X-Forward-* headers. Each property may contain multiple comma-separated values.
     */
    public data class XForwardedHeaderValues(
        /**
         * Comma-separated list of values for [Config.protoHeaders] header
         */
        public val protoHeader: String?,
        /**
         * Comma-separated list of values for [Config.forHeaders] header
         */
        public val forHeader: String?,
        /**
         * Comma-separated list of values for [Config.hostHeaders] header
         */
        public val hostHeader: String?,
        /**
         * Comma-separated list of values for [Config.httpsFlagHeaders] header
         */
        public val httpsFlagHeader: String?,
        /**
         * Comma-separated list of values for [Config.portHeaders] header
         */
        public val portHeader: String?
    )

    override val key: AttributeKey<Config> = AttributeKey("XForwardedHeaderSupport")

    override fun install(pipeline: ApplicationCallPipeline, configure: Config.() -> Unit): Config {
        val config = Config()
        configure(config)

        pipeline.intercept(ApplicationCallPipeline.Setup) {
            val strategy = config.xForwardedHeadersHandler
            val headers = XForwardedHeaderValues(
                protoHeader = config.protoHeaders.firstNotNullOfOrNull { call.request.headers[it] },
                forHeader = config.forHeaders.firstNotNullOfOrNull { call.request.headers[it] },
                hostHeader = config.hostHeaders.firstNotNullOfOrNull { call.request.headers[it] },
                httpsFlagHeader = config.httpsFlagHeaders.firstNotNullOfOrNull { call.request.headers[it] },
                portHeader = config.portHeaders.firstNotNullOfOrNull { call.request.headers[it] },
            )
            strategy.invoke(call.mutableOriginConnectionPoint, headers)
        }

        return config
    }

    /**
     * [XForwardedHeaderSupport] plugin's configuration
     */
    @Suppress("PublicApiImplicitType")
    public class Config {
        /**
         * Host name X-header names. Default are `X-Forwarded-Server` and `X-Forwarded-Host`
         */
        public val hostHeaders: ArrayList<String> =
            arrayListOf(HttpHeaders.XForwardedHost, HttpHeaders.XForwardedServer)

        /**
         * Protocol X-header names. Default are `X-Forwarded-Proto` and `X-Forwarded-Protocol`
         */
        public val protoHeaders: MutableList<String> =
            mutableListOf(HttpHeaders.XForwardedProto, "X-Forwarded-Protocol")

        /**
         * `X-Forwarded-For` header names
         */
        public val forHeaders: MutableList<String> = mutableListOf(HttpHeaders.XForwardedFor)

        /**
         * HTTPS/TLS flag header names. Default are `X-Forwarded-SSL` and `Front-End-Https`
         */
        public val httpsFlagHeaders: MutableList<String> = mutableListOf("X-Forwarded-SSL", "Front-End-Https")

        /**
         * Names of headers used to identify the destination port. The default is `X-Forwarded-Port`
         */
        public val portHeaders: MutableList<String> = mutableListOf("X-Forwarded-Port")

        internal var xForwardedHeadersHandler: (MutableOriginConnectionPoint, XForwardedHeaderValues) -> Unit =
            { _, _ -> }

        init {
            useFirstProxy()
        }

        /**
         * Custom logic to extract the value from the X-Forward-* headers when multiple values are present.
         * You need to modify [MutableOriginConnectionPoint] based on headers from [XForwardedHeaderValues]
         */
        public fun extractEdgeProxy(block: (MutableOriginConnectionPoint, XForwardedHeaderValues) -> Unit) {
            xForwardedHeadersHandler = block
        }

        /**
         * Takes the first value from the X-Forward-* headers when multiple values are present
         */
        public fun useFirstProxy() {
            extractEdgeProxy { connectionPoint, headers ->
                setValues(connectionPoint, headers) { it.firstOrNull()?.trim() }
            }
        }

        /**
         * Takes the last value from the X-Forward-* headers when multiple values are present
         */
        public fun useLastProxy() {
            extractEdgeProxy { connectionPoint, headers ->
                setValues(connectionPoint, headers) { it.lastOrNull()?.trim() }
            }
        }

        /**
         * Takes the [proxiesCount]-before-last value from the X-Forward-* headers when multiple values are present
         */
        public fun skipLastProxies(proxiesCount: Int) {
            extractEdgeProxy { connectionPoint, headers ->
                setValues(connectionPoint, headers) { values ->
                    values.getOrElse(values.size - proxiesCount - 1) { values.lastOrNull() }?.trim()
                }
            }
        }

        /**
         * Removes known [hosts] from the end of the list and takes the last value
         * from X-Forward-* headers when multiple values are present
         * */
        public fun skipKnownProxies(hosts: List<String>) {
            extractEdgeProxy { connectionPoint, headers ->
                val forValues = headers.forHeader?.split(',')

                var proxiesCount = 0
                while (
                    hosts.lastIndex >= proxiesCount &&
                    forValues != null &&
                    forValues.lastIndex >= proxiesCount &&
                    hosts[hosts.size - proxiesCount - 1].trim() == forValues[forValues.size - proxiesCount - 1].trim()
                ) {
                    proxiesCount++
                }
                setValues(connectionPoint, headers) { values ->
                    values.getOrElse(values.size - proxiesCount - 1) { values.lastOrNull() }?.trim()
                }
            }
        }

        private fun setValues(
            connectionPoint: MutableOriginConnectionPoint,
            headers: XForwardedHeaderValues,
            extractValue: (List<String>) -> String?
        ) {
            val protoValues = headers.protoHeader?.split(',')
            val httpsFlagValues = headers.httpsFlagHeader?.split(',')
            val hostValues = headers.hostHeader?.split(',')
            val portValues = headers.portHeader?.split(',')
            val forValues = headers.forHeader?.split(',')

            protoValues?.let { values ->
                val scheme = extractValue(values) ?: return@let
                connectionPoint.scheme = scheme
                URLProtocol.byName[scheme]?.let { connectionPoint.port = it.defaultPort }
            }

            httpsFlagValues?.let { values ->
                val useHttps = extractValue(values).toBoolean()
                if (!useHttps) return@let

                connectionPoint.let { route ->
                    route.scheme = "https"
                    route.port = URLProtocol.HTTPS.defaultPort
                }
            }

            hostValues?.let { values ->
                val hostAndPort = extractValue(values) ?: return@let
                val host = hostAndPort.substringBefore(':')
                val port = hostAndPort.substringAfter(':', "")

                connectionPoint.host = host
                port.toIntOrNull()?.let {
                    connectionPoint.port = it
                } ?: URLProtocol.byName[connectionPoint.scheme]?.let {
                    connectionPoint.port = it.defaultPort
                }
            }

            portValues?.let { values ->
                val port = extractValue(values) ?: return@let
                connectionPoint.port = port.toInt()
            }

            forValues?.let { values ->
                val remoteHost = extractValue(values) ?: return@let
                if (remoteHost.isNotBlank()) {
                    connectionPoint.remoteHost = remoteHost
                }
            }
        }

        private fun String?.toBoolean() = this == "yes" || this == "true" || this == "on"
    }
}

