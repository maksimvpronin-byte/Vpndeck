// Adapted from sing-box-for-android (GPL-3.0-or-later),
// https://github.com/SagerNet/sing-box-for-android

package com.vpndeck.vpn

import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import android.system.ErrnoException
import androidx.annotation.RequiresApi
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.LocalDNSTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine

/** Resolves through the underlying network's DNS for the core's "local" DNS server. */
object LocalResolver : LocalDNSTransport {
    private const val RCODE_NXDOMAIN = 3

    override fun raw(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun exchange(ctx: ExchangeContext, message: ByteArray) {
        val network = NetworkMonitor.defaultNetwork ?: error("missing default interface")
        runBlocking {
            suspendCoroutine { cont ->
                val signal = CancellationSignal()
                ctx.onCancel {
                    signal.cancel()
                    cont.tryResumeWithException(CancellationException())
                }
                DnsResolver.getInstance().rawQuery(
                    network, message, DnsResolver.FLAG_NO_RETRY, Dispatchers.IO.asExecutor(), signal,
                    object : DnsResolver.Callback<ByteArray> {
                        override fun onAnswer(answer: ByteArray, rcode: Int) {
                            if (rcode == 0) ctx.rawSuccess(answer) else ctx.errorCode(rcode)
                            cont.tryResume(Unit)
                        }

                        override fun onError(error: DnsResolver.DnsException) {
                            val cause = error.cause
                            if (cause is ErrnoException) {
                                ctx.errnoCode(cause.errno)
                                cont.tryResume(Unit)
                            } else {
                                cont.tryResumeWithException(error)
                            }
                        }
                    },
                )
            }
        }
    }

    override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
        val underlying = NetworkMonitor.defaultNetwork ?: error("missing default interface")
        runBlocking {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                suspendCoroutine { cont ->
                    val signal = CancellationSignal()
                    ctx.onCancel {
                        signal.cancel()
                        cont.tryResumeWithException(CancellationException())
                    }
                    val callback = object : DnsResolver.Callback<Collection<InetAddress>> {
                        override fun onAnswer(answer: Collection<InetAddress>, rcode: Int) {
                            if (rcode == 0) {
                                ctx.success(answer.mapNotNull { it.hostAddress }.joinToString("\n"))
                            } else {
                                ctx.errorCode(rcode)
                            }
                            cont.tryResume(Unit)
                        }

                        override fun onError(error: DnsResolver.DnsException) {
                            val cause = error.cause
                            if (cause is ErrnoException) {
                                ctx.errnoCode(cause.errno)
                                cont.tryResume(Unit)
                            } else {
                                cont.tryResumeWithException(error)
                            }
                        }
                    }
                    val type = when {
                        network.endsWith("4") -> DnsResolver.TYPE_A
                        network.endsWith("6") -> DnsResolver.TYPE_AAAA
                        else -> null
                    }
                    if (type != null) {
                        DnsResolver.getInstance().query(
                            underlying, domain, type, DnsResolver.FLAG_NO_RETRY, Dispatchers.IO.asExecutor(), signal, callback,
                        )
                    } else {
                        DnsResolver.getInstance().query(
                            underlying, domain, DnsResolver.FLAG_NO_RETRY, Dispatchers.IO.asExecutor(), signal, callback,
                        )
                    }
                }
            } else {
                val answer = try {
                    underlying.getAllByName(domain)
                } catch (e: UnknownHostException) {
                    ctx.errorCode(RCODE_NXDOMAIN)
                    return@runBlocking
                }
                ctx.success(answer.mapNotNull { it.hostAddress }.joinToString("\n"))
            }
        }
    }

    private fun <T> Continuation<T>.tryResume(value: T) {
        try {
            resumeWith(Result.success(value))
        } catch (_: IllegalStateException) {
        }
    }

    private fun <T> Continuation<T>.tryResumeWithException(e: Throwable) {
        try {
            resumeWith(Result.failure(e))
        } catch (_: IllegalStateException) {
        }
    }
}
