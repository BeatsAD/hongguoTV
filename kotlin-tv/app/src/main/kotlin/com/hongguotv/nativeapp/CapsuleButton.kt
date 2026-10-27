// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
class CapsuleButton(context: Context,value: String,selected: Boolean=false,action: ()->Unit): BrowseButton(context,value,Role.CAPSULE,selected,action)
