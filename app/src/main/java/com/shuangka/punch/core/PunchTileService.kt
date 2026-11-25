package com.shuangka.punch.core

import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.shuangka.punch.R

/**
 * 控制中心（快捷设置）磁贴：点一下就把常驻服务重新拉起来 + 重排闹钟。
 * 被杀之后不用找 App 图标，下拉开关面板点一下就回来了（GKD 也是这个思路）。
 */
class PunchTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        KeepAlive.beat(this, "磁贴点击")
        try {
            startService(
                Intent(this, PunchService::class.java)
                    .setAction(PunchService.ACTION_KEEPALIVE)
            )
        } catch (e: Exception) {
            // 被系统限制就在下面用调度兜底
        }
        PunchScheduler.scheduleAll(this)
        // 磁贴同时把常驻守护和自检挂上，等于一键「完整复活」
        KeepAlive.ensureService(this)
        KeepAlive.scheduleChecks(this)
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val alive = KeepAlive.serviceAlive(this) || KeepAlive.accessibilityAlive(this)
        tile.label = "自动打卡"
        tile.subtitle = KeepAlive.statusText(this)
        tile.state = if (alive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile)
        tile.updateTile()
    }
}
