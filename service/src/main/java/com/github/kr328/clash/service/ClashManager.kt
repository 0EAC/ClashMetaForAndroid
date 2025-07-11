package com.github.kr328.clash.service

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.*
import com.github.kr328.clash.service.data.Selection
import com.github.kr328.clash.service.data.SelectionDao
import com.github.kr328.clash.service.remote.IClashManager
import com.github.kr328.clash.service.remote.ILogObserver
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.sendOverrideChanged
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel

class ClashManager(private val context: Context) : IClashManager,
    CoroutineScope by CoroutineScope(Dispatchers.IO) {
    private val store = ServiceStore(context)
    private var logReceiver: ReceiveChannel<LogMessage>? = null

    override fun queryTunnelState(): TunnelState {
        return Clash.queryTunnelState()
    }

    override fun queryTrafficTotal(): Long {
        return Clash.queryTrafficTotal()
    }

    override fun queryProxyGroupNames(excludeNotSelectable: Boolean): List<String> {
        return Clash.queryGroupNames(excludeNotSelectable)
    }

    override fun queryProxyGroup(name: String, proxySort: ProxySort): ProxyGroup {
        return Clash.queryGroup(name, proxySort)
    }

    override fun queryConfiguration(): UiConfiguration {
        return Clash.queryConfiguration()
    }

    override fun queryProviders(): ProviderList {
        return ProviderList(Clash.queryProviders())
    }

    override fun queryOverride(slot: Clash.OverrideSlot): ConfigurationOverride {
        return Clash.queryOverride(slot)
    }

    override fun patchSelector(group: String, name: String): Boolean {
        return Clash.patchSelector(group, name).also {
            val current = store.activeProfile ?: return@also

            if (it) {
                SelectionDao().setSelected(Selection(current, group, name))
            } else {
                SelectionDao().removeSelected(current, group)
            }
        }
    }

    override fun patchOverride(slot: Clash.OverrideSlot, configuration: ConfigurationOverride) {
        Clash.patchOverride(slot, configuration)

        context.sendOverrideChanged()
    }

    override fun clearOverride(slot: Clash.OverrideSlot) {
        Clash.clearOverride(slot)
    }

    override suspend fun healthCheck(group: String) {
        return Clash.healthCheck(group).await()
    }

    override suspend fun updateProvider(type: Provider.Type, name: String) {
        return Clash.updateProvider(type, name).await()
    }

    override fun setLogObserver(observer: ILogObserver?) {
        synchronized(this) {
            logReceiver?.apply {
                cancel()

                Clash.forceGc()
            }

            if (observer != null) {
                logReceiver = Clash.subscribeLogcat().also { c ->
                    launch {
                        try {
                            while (isActive) {
                                observer.newItem(c.receive())
                            }
                        } catch (e: CancellationException) {
                            // intended behavior
                            // ignore
                        } catch (e: Exception) {
                            Log.w("UI crashed", e)
                        } finally {
                            withContext(NonCancellable) {
                                c.cancel()

                                Clash.forceGc()
                            }
                        }
                    }
                }
            }
        }
    }

    fun start() {
        val serviceMode = store.serviceMode
        if (serviceMode == TunnelState.Mode.Tun) {
            startRoot()
        } else {
            TunService.start(context)
        }
    }

    private fun startRoot() {
        if (!Root.exec(
                "iptables -w 100 -I FORWARD -o tun0 -j ACCEPT",
                "iptables -w 100 -I FORWARD -i tun0 -j ACCEPT"
            )
        ) {
            Log.e("Failed to set iptables forward rules")
            // ignore error
        }

        val config = ConfigurationOverride(
            httpPort = null,
            socksPort = null,
            redirectPort = null,
            tproxyPort = null,
            mixedPort = null,
            authentication = null,
            allowLan = null,
            bindAddress = null,
            mode = null,
            logLevel = null,
            ipv6 = null,
            hosts = null,
            dns = null,
            externalController = null,
            externalControllerTLS = null,
            secret = null,
            externalControllerCors = null,
            tun = TunConfiguration(
                enable = true,
                device = "tun0",
                stack = TunStack.GVisor,
                dnsHijack = emptyList(),
                autoRoute = true,
                autoDetectInterface = true
            )
        )

        Clash.patchOverride(Clash.OverrideSlot.Session, config)
    }

    fun stop() {
        if (store.serviceMode == TunnelState.Mode.Tun) {
            Root.exec(
                "iptables -w 100 -D FORWARD -o tun0 -j ACCEPT",
                "iptables -w 100 -D FORWARD -i tun0 -j ACCEPT"
            )
            Clash.clearOverride(Clash.OverrideSlot.Session)
        } else {
            TunService.stop(context)
        }
    }
}
