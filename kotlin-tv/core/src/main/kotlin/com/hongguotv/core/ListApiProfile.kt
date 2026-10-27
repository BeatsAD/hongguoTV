// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/** List protocol is deliberately independent of playback and search identities. */
internal class ListApiProfile(private val repository: ContentRepository) {
    private val signer=Signer()
    fun request(path: String,params: Map<String,String> = emptyMap(),body: String?=null): String {
        val values=linkedMapOf("app_name" to "novelread","aid" to "8662","version_code" to "73532","version_name" to "7.3.5.32","update_version_code" to "73532","manifest_version_code" to "73532","device_platform" to "android","os" to "android","os_api" to "36","os_version" to "16","ac" to "wifi","channel" to "update_64","ssmix" to "a","language" to "zh","device_brand" to "Redmi","device_type" to "25053RT47C","dpi" to "520","resolution" to "1280*2772")
        values.putAll(params)
        val signed=signer.sign("https://api5-normal-sinfonlinea.fqnovel.com$path",values,body?.toByteArray(Charsets.UTF_8))
        return repository.text(signed.url,signed,timeoutMillis=18_000)
    }
}
