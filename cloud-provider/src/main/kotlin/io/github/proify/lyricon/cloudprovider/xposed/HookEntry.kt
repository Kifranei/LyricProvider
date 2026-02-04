/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.cloudprovider.xposed

import com.highcapable.yukihookapi.YukiHookAPI
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit

@InjectYukiHookWithXposed(modulePackageName = "io.github.proify.lyricon.cloudprovider")
open class HookEntry : IYukiHookXposedInit {

    override fun onHook() {

        YukiHookAPI.encase {
            loadApp(isExcludeSelf = false, CloudProvider)
        }
    }

    override fun onInit() {
        super.onInit()
        YukiHookAPI.configs {
            debugLog {
                tag = "CloudProvider "
            }
        }
    }
}