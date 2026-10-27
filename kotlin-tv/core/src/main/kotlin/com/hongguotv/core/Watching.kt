// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONObject

object PlaybackQuality {
    val options=listOf(480,720,1080)
    fun normalize(value: Int)=value.takeIf { it in options } ?: 1080
}

/** One automatic recovery budget per episode; only an explicit user retry resets it. */
class RecoveryBudget {
    var attempts=0; private set
    val remaining get()=attempts<3
    val delayMillis get()=listOf(1000L,2500L,5000L).getOrNull(attempts)
    fun consume(): Boolean { if(!remaining) return false; attempts++; return true }
    fun reset() { attempts=0 }
}

data class ResumeTarget(val index: Int,val position: Long)
object ResumePlayback {
    fun refreshedIndex(episodes: List<String>,episodeId: String?,previousIndex: Int,playing: Boolean): Int? {
        require(episodes.isNotEmpty())
        val index=episodes.indexOf(episodeId)
        return if(index>=0) index else if(playing) null else previousIndex.coerceIn(0,episodes.lastIndex)
    }
    fun target(episodes: List<String>,episodeId: String?,position: Long,completed: Boolean): ResumeTarget {
        require(episodes.isNotEmpty())
        val index=episodes.indexOf(episodeId)
        if(index<0) return ResumeTarget(0,0)
        return if(completed) ResumeTarget((index+1).coerceAtMost(episodes.lastIndex),0)
            else ResumeTarget(index,position.coerceAtLeast(0))
    }
}

/** Seen count is a high-water mark, so temporary catalog shrinkage is not a new update. */
data class FavoriteUpdate(val total: Int,val seen: Int,val checkedAt: Long,val fingerprint: String="",val contentChanged: Boolean=false) {
    val added get()=(total-seen).coerceAtLeast(0)
    val hasChanges get()=added>0 || contentChanged
    fun observe(count: Int,now: Long,acknowledge: Boolean=false,fingerprint: String=this.fingerprint): FavoriteUpdate {
        require(count>0)
        if(now<checkedAt) return this
        val changed=!acknowledge && (contentChanged || (this.fingerprint.isNotEmpty() && fingerprint!=this.fingerprint && count<=total))
        return FavoriteUpdate(count,if(acknowledge) maxOf(seen,count) else seen,now,fingerprint,changed)
    }
    fun json()=JSONObject().put("total",total).put("seen",seen).put("checkedAt",checkedAt).put("fingerprint",fingerprint).put("contentChanged",contentChanged)
    companion object {
        fun first(count: Int,now: Long,fingerprint: String=""): FavoriteUpdate { require(count>0); return FavoriteUpdate(count,count,now,fingerprint) }
        fun fingerprint(episodes: List<String>) = java.security.MessageDigest.getInstance("SHA-256")
            .digest(episodes.joinToString("\n").toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        fun decode(value: JSONObject?)=runCatching {
            require(value!=null)
            val total=value.getInt("total"); val seen=value.getInt("seen"); val time=value.getLong("checkedAt")
            require(total>0 && seen>0 && time>=0)
            val fingerprint=value.optString("fingerprint","").takeIf { it.matches(Regex("[0-9a-f]{64}")) }.orEmpty()
            FavoriteUpdate(total,seen,time,fingerprint,value.optBoolean("contentChanged",false))
        }.getOrNull()
    }
}
